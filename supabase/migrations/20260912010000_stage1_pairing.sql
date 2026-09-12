-- Stage 1: recurring-pair membership + realtime (contracts/stage1-pairing.md,
-- council/stage1-pairing-contract-sol.md §1-§3). One atomic migration:
--   1. pairs / pair_members / pair_invites schema, RLS, grants, triggers.
--   2. household_id -> pair_id rename on goals/tasks/time_blocks, guard-drop, new
--      visibility/pair_id CHECKs, pair FK, owner WITH CHECK amendments.
--   3. Peer-read projection RPCs (exact-key jsonb per visibility tier).
--   4. Pairing RPCs (create/redeem invite, leave) with advisory+row locking,
--      action-tagged receipts (reusing mutation_receipts from
--      20260911050500_mutation_receipts.sql -- no schema change to that table;
--      "action" is a top-level key inside the jsonb this migration's RPCs store/return),
--      and channel_generation rotation.
--   5. Realtime: realtime.messages SELECT policy for private Broadcast, and
--      membership-event broadcast triggers.
--
-- Realtime API note (lead amendment 2 / brief): the local stack's realtime schema was
-- inspected directly (read-only) to confirm the exact function to call is
-- `realtime.send(payload jsonb, event text, topic text, private boolean default true)`
-- -- not `realtime.broadcast`, which does not exist in this Supabase version (realtime
-- v2.130.0). `realtime.topic()` reads the `realtime.topic` GUC the Realtime server sets
-- per-connection. Both confirmed against the running local stack; see this seat's report.

-- =====================================================================================
-- 0. Private HMAC signing key for invite codes (no-grant table; generated here, never
--    committed as a literal). A replayed rpc_create_pair_invite reconstructs the same
--    code from (key, invite_id) without persisting the plaintext code anywhere.
-- =====================================================================================

create table public.pair_invite_hmac_keys (
    id  boolean primary key default true,
    key bytea not null,

    constraint pair_invite_hmac_keys_singleton_check check (id)
);

comment on table public.pair_invite_hmac_keys is
    'Singleton private key for deriving invite codes via HMAC-SHA256 (contract §1). No grants to any role; readable only from inside SECURITY DEFINER functions owned by the migration role.';

insert into public.pair_invite_hmac_keys (id, key) values (true, extensions.gen_random_bytes(32));

alter table public.pair_invite_hmac_keys enable row level security;
revoke all on table public.pair_invite_hmac_keys from public, anon, authenticated;
-- no policies: authenticated/anon get zero rows even if a future migration grants SELECT.

-- =====================================================================================
-- 1. Schema: pairs, pair_members, pair_invites (contract §1).
-- =====================================================================================

create table public.pairs (
    id                  uuid primary key default gen_random_uuid(),
    created_by          uuid not null references auth.users (id),
    status              text not null default 'active',
    channel_generation  uuid not null default gen_random_uuid(),
    version             bigint not null default 1,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    ended_at            timestamptz null,

    constraint pairs_status_check check (status in ('active', 'ended')),
    constraint pairs_version_check check (version > 0),
    constraint pairs_ended_at_check check ((status = 'ended') = (ended_at is not null))
);

comment on table public.pairs is 'A recurring pair (exactly two, eventually one-then-two, people). created_by is audit provenance only, not permanent authority (contract §1).';
comment on column public.pairs.channel_generation is 'Rotated on every membership change; load-bearing for realtime.messages authorization (contract §3).';

create trigger pairs_set_updated_at
    before update on public.pairs
    for each row
    execute function public.set_updated_at();

create table public.pair_members (
    id         uuid primary key default gen_random_uuid(),
    pair_id    uuid not null references public.pairs (id) on delete cascade,
    user_id    uuid not null references auth.users (id) on delete cascade,
    joined_at  timestamptz not null default now(),
    left_at    timestamptz null,

    constraint pair_members_left_after_joined_check check (left_at is null or left_at >= joined_at)
);

comment on table public.pair_members is 'Membership rows for a pair; a user has at most one active (left_at is null) row across all pairs (contract §1).';

-- One active pair per user, and no duplicate active membership in the same pair.
-- Backstop for the RPC-level advisory + row locking, not the primary concurrency mechanism.
create unique index pair_members_one_active_per_pair_user
    on public.pair_members (pair_id, user_id)
    where left_at is null;

create unique index pair_members_one_active_pair_per_user
    on public.pair_members (user_id)
    where left_at is null;

-- Defense-in-depth: rejects a third active member even against privileged/manual writes
-- that bypass the RPC's own row locking (contract §1).
create or replace function public.pair_members_enforce_cap()
returns trigger
language plpgsql
as $$
declare
    v_active_count int;
begin
    select count(*) into v_active_count
    from public.pair_members
    where pair_id = new.pair_id and left_at is null;

    if v_active_count >= 2 then
        raise exception 'a pair may have at most two active members' using errcode = '23514';
    end if;

    return new;
end;
$$;

create trigger pair_members_enforce_cap_trigger
    before insert on public.pair_members
    for each row
    execute function public.pair_members_enforce_cap();

create table public.pair_invites (
    id            uuid primary key default gen_random_uuid(),
    pair_id       uuid not null references public.pairs (id) on delete cascade,
    inviter_id    uuid not null references auth.users (id),
    code_hash     bytea not null,
    created_at    timestamptz not null default now(),
    expires_at    timestamptz not null,
    redeemed_at   timestamptz null,
    redeemed_by   uuid null references auth.users (id),
    revoked_at    timestamptz null,

    constraint pair_invites_code_hash_length_check check (octet_length(code_hash) = 32),
    constraint pair_invites_expiry_after_created_check check (expires_at > created_at),
    constraint pair_invites_redeemed_by_iff_redeemed_at_check check (
        (redeemed_by is not null) = (redeemed_at is not null)
    ),
    constraint pair_invites_redeemed_before_expiry_check check (
        redeemed_at is null or redeemed_at <= expires_at
    ),
    constraint pair_invites_redeem_revoke_exclusive_check check (
        redeemed_at is null or revoked_at is null
    )
);

comment on table public.pair_invites is 'Expiring, single-use, code-based invites. code_hash is SHA-256 of the normalized code; the plaintext code is never stored (contract §1).';

-- Only one *live* (unredeemed, unrevoked) invite per pair, and code_hash is unique among
-- live invites. Both are additive backstops -- rpc_create_pair_invite already revokes the
-- pair's prior live invite before creating a new one.
create unique index pair_invites_one_live_code_hash
    on public.pair_invites (code_hash)
    where redeemed_at is null and revoked_at is null;

create unique index pair_invites_one_live_per_pair
    on public.pair_invites (pair_id)
    where redeemed_at is null and revoked_at is null;

-- =====================================================================================
-- RLS + grants (contract §1): no client INSERT/UPDATE/DELETE on any pairing table;
-- mutations occur only through the RPCs below (SECURITY DEFINER, bypass RLS as the
-- migration owner). pair_invites gets no policies and no grants at all -- not even SELECT
-- -- so a code/hash never appears via a direct table read, only via the RPC's own return
-- value / receipt.
-- =====================================================================================

-- Non-recursive membership check, used by the pairs/pair_members SELECT policies below so
-- neither policy has to reference the other's RLS-protected table under RLS itself.
create or replace function public.is_active_pair_member(p_pair_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.pair_members pm
        where pm.pair_id = p_pair_id
          and pm.user_id = (select auth.uid())
          and pm.left_at is null
    );
$$;

revoke all on function public.is_active_pair_member(uuid) from public;
grant execute on function public.is_active_pair_member(uuid) to authenticated;

alter table public.pairs enable row level security;
revoke all on table public.pairs from anon, authenticated;
grant select on table public.pairs to authenticated;

create policy pairs_select_active_member on public.pairs
    for select to authenticated
    using (public.is_active_pair_member(id));

alter table public.pair_members enable row level security;
revoke all on table public.pair_members from anon, authenticated;
grant select on table public.pair_members to authenticated;

create policy pair_members_select_via_active_membership on public.pair_members
    for select to authenticated
    using (public.is_active_pair_member(pair_id));

alter table public.pair_invites enable row level security;
revoke all on table public.pair_invites from anon, authenticated;
-- no grants, no policies: pair_invites is reachable only from SECURITY DEFINER RPCs.

-- =====================================================================================
-- 2. household_id -> pair_id rename + guard-drop + pair FK + WITH CHECK amendments
--    (contract §1). Applies to the three shared planner tables. milestones derives
--    access from goals and captures is owner-only -- neither carries a Phase 1 guard.
-- =====================================================================================

-- ---- goals -------------------------------------------------------------------------

alter table public.goals rename column household_id to pair_id;

alter table public.goals drop constraint goals_phase1_private_only;

alter table public.goals
    add constraint goals_visibility_pair_check check (
        (visibility = 'private' and pair_id is null) or
        (visibility <> 'private' and pair_id is not null)
    ),
    add constraint goals_no_busy_only_check check (visibility <> 'busy_only'),
    add constraint goals_pair_id_fk foreign key (pair_id) references public.pairs (id);

comment on constraint goals_visibility_pair_check on public.goals is 'Stage 1 guard-drop replacement: pair_id is null iff visibility=''private'' (contract §1).';
comment on constraint goals_no_busy_only_check on public.goals is 'A goal cannot be busy_only (contract §1 / Sol projection rules): it has no single time window to reduce to.';

create or replace function public.goal_to_jsonb(g public.goals)
returns jsonb
language sql
stable
as $$
    select jsonb_build_object(
        'id', g.id,
        'owner_id', g.owner_id,
        'pair_id', g.pair_id,
        'visibility', g.visibility,
        'title', g.title,
        'notes', g.notes,
        'target_date', to_char(g.target_date, 'YYYY-MM-DD'),
        'status', g.status,
        'version', g.version,
        'created_at', public.iso_utc(g.created_at),
        'updated_at', public.iso_utc(g.updated_at)
    );
$$;

drop policy goals_insert_own on public.goals;
drop policy goals_update_own on public.goals;

-- owner WITH CHECK now also binds a non-private row to the owner's own active pair
-- (contract §1: "amend owner INSERT/UPDATE WITH CHECK so a non-private row can name only
-- the owner's active pair"). Base-table SELECT stays owner-only, unchanged.
create policy goals_insert_own on public.goals
    for insert to authenticated
    with check (
        owner_id = (select auth.uid())
        and (
            pair_id is null
            or pair_id in (
                select pm.pair_id from public.pair_members pm
                where pm.user_id = (select auth.uid()) and pm.left_at is null
            )
        )
    );

create policy goals_update_own on public.goals
    for update to authenticated
    using (owner_id = (select auth.uid()))
    with check (
        owner_id = (select auth.uid())
        and (
            pair_id is null
            or pair_id in (
                select pm.pair_id from public.pair_members pm
                where pm.user_id = (select auth.uid()) and pm.left_at is null
            )
        )
    );

-- ---- tasks -------------------------------------------------------------------------

alter table public.tasks rename column household_id to pair_id;

alter table public.tasks drop constraint tasks_phase1_private_only;

alter table public.tasks
    add constraint tasks_visibility_pair_check check (
        (visibility = 'private' and pair_id is null) or
        (visibility <> 'private' and pair_id is not null)
    ),
    add constraint tasks_busy_only_requires_due_range_check check (
        visibility <> 'busy_only' or (due_start_utc is not null and due_end_utc is not null)
    ),
    add constraint tasks_pair_id_fk foreign key (pair_id) references public.pairs (id);

comment on constraint tasks_visibility_pair_check on public.tasks is 'Stage 1 guard-drop replacement: pair_id is null iff visibility=''private'' (contract §1).';
comment on constraint tasks_busy_only_requires_due_range_check on public.tasks is 'A busy-only task requires a complete due range so the busy_only projection always has both instants (contract §1).';

create or replace function public.task_to_jsonb(t public.tasks)
returns jsonb
language sql
stable
as $$
    select jsonb_build_object(
        'id', t.id,
        'owner_id', t.owner_id,
        'pair_id', t.pair_id,
        'visibility', t.visibility,
        'goal_id', t.goal_id,
        'milestone_id', t.milestone_id,
        'title', t.title,
        'notes', t.notes,
        'status', t.status,
        'priority', t.priority,
        'effort', t.effort,
        'estimate_min', t.estimate_min,
        'due_start_utc', public.iso_utc(t.due_start_utc),
        'due_end_utc', public.iso_utc(t.due_end_utc),
        'recurrence_rule', t.recurrence_rule,
        'tags', to_jsonb(t.tags),
        'version', t.version,
        'created_at', public.iso_utc(t.created_at),
        'updated_at', public.iso_utc(t.updated_at)
    );
$$;

drop policy tasks_insert_own on public.tasks;
drop policy tasks_update_own on public.tasks;

create policy tasks_insert_own on public.tasks
    for insert to authenticated
    with check (
        owner_id = (select auth.uid())
        and (
            pair_id is null
            or pair_id in (
                select pm.pair_id from public.pair_members pm
                where pm.user_id = (select auth.uid()) and pm.left_at is null
            )
        )
    );

create policy tasks_update_own on public.tasks
    for update to authenticated
    using (owner_id = (select auth.uid()))
    with check (
        owner_id = (select auth.uid())
        and (
            pair_id is null
            or pair_id in (
                select pm.pair_id from public.pair_members pm
                where pm.user_id = (select auth.uid()) and pm.left_at is null
            )
        )
    );

-- ---- time_blocks ---------------------------------------------------------------------

alter table public.time_blocks rename column household_id to pair_id;

alter table public.time_blocks drop constraint time_blocks_phase1_private_only;

alter table public.time_blocks
    add constraint time_blocks_visibility_pair_check check (
        (visibility = 'private' and pair_id is null) or
        (visibility <> 'private' and pair_id is not null)
    ),
    add constraint time_blocks_pair_id_fk foreign key (pair_id) references public.pairs (id);

comment on constraint time_blocks_visibility_pair_check on public.time_blocks is 'Stage 1 guard-drop replacement: pair_id is null iff visibility=''private'' (contract §1). No busy_only-specific CHECK needed: starts_at_utc/ends_at_utc are already NOT NULL.';

create or replace function public.time_block_to_jsonb(b public.time_blocks)
returns jsonb
language sql
stable
as $$
    select jsonb_build_object(
        'id', b.id,
        'owner_id', b.owner_id,
        'pair_id', b.pair_id,
        'visibility', b.visibility,
        'task_id', b.task_id,
        'title', b.title,
        'starts_at_utc', public.iso_utc(b.starts_at_utc),
        'ends_at_utc', public.iso_utc(b.ends_at_utc),
        'origin_tz', b.origin_tz,
        'type', b.type,
        'status', b.status,
        'recurrence_rule', b.recurrence_rule,
        'all_day', b.all_day,
        'version', b.version,
        'created_at', public.iso_utc(b.created_at),
        'updated_at', public.iso_utc(b.updated_at)
    );
$$;

drop policy time_blocks_insert_own on public.time_blocks;
drop policy time_blocks_update_own on public.time_blocks;

create policy time_blocks_insert_own on public.time_blocks
    for insert to authenticated
    with check (
        owner_id = (select auth.uid())
        and (
            pair_id is null
            or pair_id in (
                select pm.pair_id from public.pair_members pm
                where pm.user_id = (select auth.uid()) and pm.left_at is null
            )
        )
    );

create policy time_blocks_update_own on public.time_blocks
    for update to authenticated
    using (owner_id = (select auth.uid()))
    with check (
        owner_id = (select auth.uid())
        and (
            pair_id is null
            or pair_id in (
                select pm.pair_id from public.pair_members pm
                where pm.user_id = (select auth.uid()) and pm.left_at is null
            )
        )
    );

-- =====================================================================================
-- 3. Peer-read projection RPCs (contract §1): SECURITY DEFINER, set search_path='',
--    exact-key jsonb per visibility tier, built with jsonb_strip_nulls. Milestones appear
--    only beneath a full goal. Captures never appear (no projection RPC for them at all).
--
--    Tier key sets (this seat's reading of Sol §1's prose -- no literal JSON schema was
--    frozen in the contract text; lead/B3/C2 should sanity-check against the DTOs they
--    build):
--      goals        title_only -> {id, title, target_date}
--                   full       -> the full goal_to_jsonb() key set + "milestones" (array)
--                   busy_only  -> unreachable (goals_no_busy_only_check)
--      tasks        busy_only  -> {due_start_utc, due_end_utc}
--                   title_only -> {id, title, due_start_utc, due_end_utc}
--                   full       -> the full task_to_jsonb() key set
--      time_blocks  busy_only  -> {starts_at_utc, ends_at_utc}
--                   title_only -> {id, title, starts_at_utc, ends_at_utc}
--                   full       -> the full time_block_to_jsonb() key set
-- =====================================================================================

create or replace function public.rpc_list_pair_shared_goals()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller  uuid := auth.uid();
    v_pair_id uuid;
    v_result  jsonb;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    select pm.pair_id into v_pair_id
    from public.pair_members pm
    where pm.user_id = v_caller and pm.left_at is null;

    if v_pair_id is null then
        return '[]'::jsonb;
    end if;

    select coalesce(
        (select jsonb_agg(proj order by sort_id)
         from (
             select
                 g.id as sort_id,
                 case g.visibility
                     when 'title_only' then jsonb_strip_nulls(jsonb_build_object(
                         'id', g.id,
                         'title', g.title,
                         'target_date', to_char(g.target_date, 'YYYY-MM-DD')
                     ))
                     when 'full' then jsonb_strip_nulls(public.goal_to_jsonb(g)) || jsonb_build_object(
                         'milestones', coalesce((
                             select jsonb_agg(public.milestone_to_jsonb(m) order by m.sort_order, m.id)
                             from public.milestones m
                             where m.goal_id = g.id
                         ), '[]'::jsonb)
                     )
                     else null
                 end as proj
             from public.goals g
             where g.pair_id = v_pair_id
               and g.owner_id <> v_caller
               and g.visibility in ('title_only', 'full')
         ) rows
         where proj is not null),
        '[]'::jsonb
    ) into v_result;

    return v_result;
end;
$$;

create or replace function public.rpc_list_pair_shared_tasks()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller  uuid := auth.uid();
    v_pair_id uuid;
    v_result  jsonb;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    select pm.pair_id into v_pair_id
    from public.pair_members pm
    where pm.user_id = v_caller and pm.left_at is null;

    if v_pair_id is null then
        return '[]'::jsonb;
    end if;

    select coalesce(
        (select jsonb_agg(proj order by sort_id)
         from (
             select
                 t.id as sort_id,
                 case t.visibility
                     when 'busy_only' then jsonb_strip_nulls(jsonb_build_object(
                         'due_start_utc', public.iso_utc(t.due_start_utc),
                         'due_end_utc', public.iso_utc(t.due_end_utc)
                     ))
                     when 'title_only' then jsonb_strip_nulls(jsonb_build_object(
                         'id', t.id,
                         'title', t.title,
                         'due_start_utc', public.iso_utc(t.due_start_utc),
                         'due_end_utc', public.iso_utc(t.due_end_utc)
                     ))
                     when 'full' then jsonb_strip_nulls(public.task_to_jsonb(t))
                     else null
                 end as proj
             from public.tasks t
             where t.pair_id = v_pair_id
               and t.owner_id <> v_caller
               and t.visibility in ('busy_only', 'title_only', 'full')
         ) rows
         where proj is not null),
        '[]'::jsonb
    ) into v_result;

    return v_result;
end;
$$;

create or replace function public.rpc_list_pair_shared_time_blocks()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller  uuid := auth.uid();
    v_pair_id uuid;
    v_result  jsonb;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    select pm.pair_id into v_pair_id
    from public.pair_members pm
    where pm.user_id = v_caller and pm.left_at is null;

    if v_pair_id is null then
        return '[]'::jsonb;
    end if;

    select coalesce(
        (select jsonb_agg(proj order by sort_id)
         from (
             select
                 b.id as sort_id,
                 case b.visibility
                     when 'busy_only' then jsonb_strip_nulls(jsonb_build_object(
                         'starts_at_utc', public.iso_utc(b.starts_at_utc),
                         'ends_at_utc', public.iso_utc(b.ends_at_utc)
                     ))
                     when 'title_only' then jsonb_strip_nulls(jsonb_build_object(
                         'id', b.id,
                         'title', b.title,
                         'starts_at_utc', public.iso_utc(b.starts_at_utc),
                         'ends_at_utc', public.iso_utc(b.ends_at_utc)
                     ))
                     when 'full' then jsonb_strip_nulls(public.time_block_to_jsonb(b))
                     else null
                 end as proj
             from public.time_blocks b
             where b.pair_id = v_pair_id
               and b.owner_id <> v_caller
               and b.visibility in ('busy_only', 'title_only', 'full')
         ) rows
         where proj is not null),
        '[]'::jsonb
    ) into v_result;

    return v_result;
end;
$$;

revoke all on function public.rpc_list_pair_shared_goals() from public;
grant execute on function public.rpc_list_pair_shared_goals() to authenticated;

revoke all on function public.rpc_list_pair_shared_tasks() from public;
grant execute on function public.rpc_list_pair_shared_tasks() to authenticated;

revoke all on function public.rpc_list_pair_shared_time_blocks() from public;
grant execute on function public.rpc_list_pair_shared_time_blocks() to authenticated;

-- =====================================================================================
-- 4. Pairing RPCs (contract §2): SECURITY DEFINER, set search_path='', explicit
--    auth.uid() checks, PUBLIC execute revoked / authenticated granted, mutation_receipts
--    idempotency with an "action" tag inside the stored/returned jsonb so a replayed
--    operation_id whose recorded action differs is rejected instead of silently returning
--    a different RPC's cached shape (cross-RPC replay rejection).
-- =====================================================================================

-- Deterministic Crockford Base32 code (10 chars, grouped XXXXX-XXXXX, ~50 bits) derived
-- from HMAC-SHA256(invite_id) using the migration-generated private key. Never granted to
-- any role -- reachable only from inside the SECURITY DEFINER RPCs below, so a client can
-- never recompute another invite's code even if it learns the invite_id.
create or replace function public.pair_invite_code(p_invite_id uuid)
returns text
language plpgsql
stable
set search_path = ''
as $$
declare
    v_key      bytea;
    v_mac      bytea;
    v_alphabet text := '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
    v_num      bigint := 0;
    v_num50    bigint;
    v_chars    text := '';
    v_idx      int;
    i          int;
begin
    select key into v_key from public.pair_invite_hmac_keys where id;
    if v_key is null then
        raise exception 'pair invite signing key is not configured' using errcode = 'XX000';
    end if;

    v_mac := extensions.hmac(convert_to(p_invite_id::text, 'UTF8'), v_key, 'sha256');

    for i in 0..6 loop
        v_num := (v_num << 8) | get_byte(v_mac, i);
    end loop;

    -- v_num holds 56 bits (7 bytes); keep the top 50 bits (10 groups of 5 bits).
    v_num50 := v_num >> 6;

    for i in reverse 9..0 loop
        v_idx := ((v_num50 >> (i * 5)) & 31)::int;
        v_chars := v_chars || substr(v_alphabet, v_idx + 1, 1);
    end loop;

    return substr(v_chars, 1, 5) || '-' || substr(v_chars, 6, 5);
end;
$$;

revoke all on function public.pair_invite_code(uuid) from public;

-- Case-insensitive, hyphen-insensitive normalization; maps the common look-alike
-- confusions (I/L -> 1, O -> 0) per Crockford Base32 convention (contract §1).
create or replace function public.pair_invite_normalize_code(p_code text)
returns text
language sql
immutable
set search_path = ''
as $$
    select translate(
        upper(regexp_replace(coalesce(p_code, ''), '[^0-9A-Za-z]', '', 'g')),
        'ILO',
        '110'
    );
$$;

revoke all on function public.pair_invite_normalize_code(text) from public;

-- Builds the frozen PairSnapshotDto shape (contract §4): pair_id, status, version,
-- channel_topic, members[], active_invite_expires_at. Not itself grant-reachable --
-- called only from inside the SECURITY DEFINER RPCs below (their bypass-RLS context is
-- what lets it read a peer's profiles row).
create or replace function public.pair_to_jsonb(p public.pairs)
returns jsonb
language sql
stable
set search_path = ''
as $$
    select jsonb_build_object(
        'pair_id', p.id,
        'status', p.status,
        'version', p.version,
        'channel_topic', 'pair:' || p.id::text || ':' || p.channel_generation::text,
        'members', coalesce((
            select jsonb_agg(jsonb_build_object(
                'user_id', pm.user_id,
                'display_name', pr.display_name,
                'home_tz', pr.home_tz,
                'joined_at', public.iso_utc(pm.joined_at)
            ) order by pm.joined_at)
            from public.pair_members pm
            join public.profiles pr on pr.user_id = pm.user_id
            where pm.pair_id = p.id and pm.left_at is null
        ), '[]'::jsonb),
        'active_invite_expires_at', (
            select public.iso_utc(pi.expires_at)
            from public.pair_invites pi
            where pi.pair_id = p.id
              and pi.redeemed_at is null
              and pi.revoked_at is null
              and pi.expires_at > now()
            order by pi.created_at desc
            limit 1
        )
    );
$$;

revoke all on function public.pair_to_jsonb(public.pairs) from public;

-- rpc_get_pair: the caller's active pair snapshot, or SQL null when unpaired.
create or replace function public.rpc_get_pair()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller uuid := auth.uid();
    v_pair   public.pairs;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    select p.* into v_pair
    from public.pairs p
    join public.pair_members pm on pm.pair_id = p.id
    where pm.user_id = v_caller and pm.left_at is null and p.status = 'active';

    if not found then
        return null;
    end if;

    return public.pair_to_jsonb(v_pair);
end;
$$;

revoke all on function public.rpc_get_pair() from public;
grant execute on function public.rpc_get_pair() to authenticated;

-- rpc_create_pair_invite(p_operation_id, p_ttl_minutes default 1440): reuses the caller's
-- singleton pair or creates a one-member pair atomically; rejects if two active members
-- already exist; revokes the pair's prior live invite; creates one invite; bumps version.
create or replace function public.rpc_create_pair_invite(
    p_operation_id uuid,
    p_ttl_minutes int default 1440
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller       uuid := auth.uid();
    v_receipt      public.mutation_receipts;
    v_pair_id      uuid;
    v_pair         public.pairs;
    v_active_count int;
    v_invite_id    uuid;
    v_expires_at   timestamptz;
    v_code         text;
    v_code_hash    bytea;
    v_result       jsonb;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_operation_id is null then
        raise exception 'p_operation_id is required' using errcode = '22004';
    end if;
    if p_ttl_minutes is null or p_ttl_minutes < 5 or p_ttl_minutes > 10080 then
        raise exception 'p_ttl_minutes must be between 5 and 10080' using errcode = '22023';
    end if;

    select * into v_receipt
    from public.mutation_receipts
    where owner_id = v_caller and operation_id = p_operation_id;

    if found then
        if (v_receipt.result->>'action') is distinct from 'create_pair_invite' then
            raise exception 'operation_id already used for a different action' using errcode = '22023';
        end if;
        return v_receipt.result;
    end if;

    perform pg_advisory_xact_lock(hashtext('elay:pair_ops')::int, hashtext(v_caller::text)::int);

    select pm.pair_id into v_pair_id
    from public.pair_members pm
    where pm.user_id = v_caller and pm.left_at is null;

    if v_pair_id is not null then
        select * into v_pair from public.pairs where id = v_pair_id for update;

        select count(*) into v_active_count
        from public.pair_members
        where pair_id = v_pair_id and left_at is null;

        if v_active_count >= 2 then
            raise exception 'pair already has two active members' using errcode = '42501';
        end if;
    else
        insert into public.pairs (created_by) values (v_caller) returning * into v_pair;
        -- Defensive: clear any stale stash from an earlier pairing RPC call in the same
        -- transaction (see the ordering note on pair_members_broadcast()) so this brand
        -- new pair's creator-join always falls back to its own (only) generation.
        perform set_config('elay.pair_broadcast_topic', null, true);
        insert into public.pair_members (pair_id, user_id) values (v_pair.id, v_caller);
        v_pair_id := v_pair.id;
    end if;

    update public.pair_invites
    set revoked_at = now()
    where pair_id = v_pair_id and redeemed_at is null and revoked_at is null;

    v_invite_id := gen_random_uuid();
    v_expires_at := now() + make_interval(mins => p_ttl_minutes);
    v_code := public.pair_invite_code(v_invite_id);
    v_code_hash := extensions.digest(
        convert_to(public.pair_invite_normalize_code(v_code), 'UTF8'),
        'sha256'
    );

    insert into public.pair_invites (id, pair_id, inviter_id, code_hash, expires_at)
    values (v_invite_id, v_pair_id, v_caller, v_code_hash, v_expires_at);

    update public.pairs
    set version = version + 1, updated_at = now()
    where id = v_pair_id
    returning * into v_pair;

    v_result := jsonb_build_object(
        'outcome', 'applied',
        'action', 'create_pair_invite',
        'pair', public.pair_to_jsonb(v_pair),
        'invite', jsonb_build_object(
            'invite_id', v_invite_id,
            'code', v_code,
            'expires_at', public.iso_utc(v_expires_at)
        )
    );

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_pair.version, v_result);

    return v_result;
end;
$$;

revoke all on function public.rpc_create_pair_invite(uuid, int) from public;
grant execute on function public.rpc_create_pair_invite(uuid, int) to authenticated;

-- Per-account rate limiting for redemption attempts (contract §1). Logged unconditionally
-- (even for a receipt replay short-circuit that never reaches this point again, and even
-- for attempts that turn out invalid) so a brute-force loop cannot dodge the limiter by
-- varying operation_id/code. No client access; only used from rpc_redeem_pair_invite.
create table public.pair_invite_redemption_attempts (
    user_id      uuid not null references auth.users (id) on delete cascade,
    attempted_at timestamptz not null default now()
);

create index pair_invite_redemption_attempts_user_time
    on public.pair_invite_redemption_attempts (user_id, attempted_at);

alter table public.pair_invite_redemption_attempts enable row level security;
revoke all on table public.pair_invite_redemption_attempts from public, anon, authenticated;

-- rpc_redeem_pair_invite(p_operation_id, p_code): atomic validate + join. All
-- unavailable-code cases (wrong code, expired, revoked, already redeemed, self-redeem,
-- pair already full, caller already paired) return the same {"outcome":"invalid_or_unavailable"}
-- so a client cannot distinguish why a code did not work.
create or replace function public.rpc_redeem_pair_invite(
    p_operation_id uuid,
    p_code text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller      uuid := auth.uid();
    v_receipt     public.mutation_receipts;
    v_attempts    int;
    v_normalized  text;
    v_hash        bytea;
    v_invite      public.pair_invites;
    v_pair        public.pairs;
    v_active_count int;
    v_old_topic   text;
    v_result      jsonb;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_operation_id is null then
        raise exception 'p_operation_id is required' using errcode = '22004';
    end if;
    if p_code is null or length(trim(p_code)) = 0 then
        raise exception 'p_code is required' using errcode = '22004';
    end if;

    select * into v_receipt
    from public.mutation_receipts
    where owner_id = v_caller and operation_id = p_operation_id;

    if found then
        if (v_receipt.result->>'action') is distinct from 'redeem_pair_invite' then
            raise exception 'operation_id already used for a different action' using errcode = '22023';
        end if;
        return v_receipt.result;
    end if;

    insert into public.pair_invite_redemption_attempts (user_id) values (v_caller);

    select count(*) into v_attempts
    from public.pair_invite_redemption_attempts
    where user_id = v_caller and attempted_at > now() - interval '15 minutes';

    if v_attempts > 20 then
        return jsonb_build_object('outcome', 'invalid_or_unavailable', 'action', 'redeem_pair_invite');
    end if;

    perform pg_advisory_xact_lock(hashtext('elay:pair_ops')::int, hashtext(v_caller::text)::int);

    v_normalized := public.pair_invite_normalize_code(p_code);
    v_hash := extensions.digest(convert_to(v_normalized, 'UTF8'), 'sha256');

    select * into v_invite from public.pair_invites where code_hash = v_hash for update;

    if not found
       or v_invite.revoked_at is not null
       or v_invite.redeemed_at is not null
       or v_invite.expires_at <= now()
       or v_invite.inviter_id = v_caller
    then
        v_result := jsonb_build_object('outcome', 'invalid_or_unavailable', 'action', 'redeem_pair_invite');
        insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
        values (v_caller, p_operation_id, 0, v_result);
        return v_result;
    end if;

    if exists (select 1 from public.pair_members where user_id = v_caller and left_at is null) then
        v_result := jsonb_build_object('outcome', 'invalid_or_unavailable', 'action', 'redeem_pair_invite');
        insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
        values (v_caller, p_operation_id, 0, v_result);
        return v_result;
    end if;

    select * into v_pair from public.pairs where id = v_invite.pair_id for update;

    select count(*) into v_active_count
    from public.pair_members
    where pair_id = v_pair.id and left_at is null;

    if v_pair.status <> 'active' or v_active_count <> 1 then
        v_result := jsonb_build_object('outcome', 'invalid_or_unavailable', 'action', 'redeem_pair_invite');
        insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
        values (v_caller, p_operation_id, 0, v_result);
        return v_result;
    end if;

    -- Stash the pre-rotation topic for the membership-broadcast trigger fired below, then
    -- rotate the pair (version + generation) *before* the membership row changes so the
    -- trigger's own read of public.pairs already reflects the new version/generation while
    -- the event still goes out on the topic subscribers are currently listening on
    -- (contract §3: "the old topic receives one sanitized membership event").
    v_old_topic := 'pair:' || v_pair.id::text || ':' || v_pair.channel_generation::text;
    perform set_config('elay.pair_broadcast_topic', v_old_topic, true);

    update public.pairs
    set version = version + 1, channel_generation = gen_random_uuid(), updated_at = now()
    where id = v_pair.id
    returning * into v_pair;

    update public.pair_invites set redeemed_at = now(), redeemed_by = v_caller where id = v_invite.id;
    update public.pair_invites
    set revoked_at = now()
    where pair_id = v_pair.id and id <> v_invite.id and redeemed_at is null and revoked_at is null;

    insert into public.pair_members (pair_id, user_id) values (v_pair.id, v_caller);
    -- Clear the stash immediately: set_config(..., true) is transaction-local, not
    -- statement-local, so a stale value here would otherwise leak into whatever the next
    -- pair_members mutation in the same transaction turns out to be.
    perform set_config('elay.pair_broadcast_topic', null, true);

    v_result := jsonb_build_object(
        'outcome', 'applied',
        'action', 'redeem_pair_invite',
        'pair', public.pair_to_jsonb(v_pair)
    );

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_pair.version, v_result);

    return v_result;
end;
$$;

revoke all on function public.rpc_redeem_pair_invite(uuid, text) from public;
grant execute on function public.rpc_redeem_pair_invite(uuid, text) to authenticated;

-- rpc_leave_pair(p_operation_id): ADR-009 revocation. Ends the pair only when no active
-- members remain; otherwise rotates the channel so a departed member's already-authorized
-- realtime socket cannot keep listening.
create or replace function public.rpc_leave_pair(p_operation_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller       uuid := auth.uid();
    v_receipt      public.mutation_receipts;
    v_member       public.pair_members;
    v_pair         public.pairs;
    v_remaining    int;
    v_old_topic    text;
    v_result       jsonb;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_operation_id is null then
        raise exception 'p_operation_id is required' using errcode = '22004';
    end if;

    select * into v_receipt
    from public.mutation_receipts
    where owner_id = v_caller and operation_id = p_operation_id;

    if found then
        if (v_receipt.result->>'action') is distinct from 'leave_pair' then
            raise exception 'operation_id already used for a different action' using errcode = '22023';
        end if;
        return v_receipt.result;
    end if;

    perform pg_advisory_xact_lock(hashtext('elay:pair_ops')::int, hashtext(v_caller::text)::int);

    select * into v_member from public.pair_members where user_id = v_caller and left_at is null for update;

    if not found then
        v_result := jsonb_build_object('outcome', 'not_member', 'action', 'leave_pair');
        insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
        values (v_caller, p_operation_id, 0, v_result);
        return v_result;
    end if;

    select * into v_pair from public.pairs where id = v_member.pair_id for update;

    select count(*) into v_remaining
    from public.pair_members
    where pair_id = v_pair.id and left_at is null and id <> v_member.id;

    update public.pair_invites
    set revoked_at = now()
    where pair_id = v_pair.id and redeemed_at is null and revoked_at is null;

    v_old_topic := 'pair:' || v_pair.id::text || ':' || v_pair.channel_generation::text;
    perform set_config('elay.pair_broadcast_topic', v_old_topic, true);

    update public.pairs
    set version = version + 1,
        channel_generation = gen_random_uuid(),
        updated_at = now(),
        status = case when v_remaining = 0 then 'ended' else status end,
        ended_at = case when v_remaining = 0 then now() else ended_at end
    where id = v_pair.id
    returning * into v_pair;

    update public.pair_members set left_at = now() where id = v_member.id;
    -- Clear the stash immediately (see the matching comment in rpc_redeem_pair_invite).
    perform set_config('elay.pair_broadcast_topic', null, true);

    v_result := jsonb_build_object(
        'outcome', 'applied',
        'action', 'leave_pair',
        'left_pair_id', v_pair.id
    );

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_pair.version, v_result);

    return v_result;
end;
$$;

revoke all on function public.rpc_leave_pair(uuid) from public;
grant execute on function public.rpc_leave_pair(uuid) to authenticated;

-- =====================================================================================
-- 5. Realtime (contract §3): private Broadcast only, topic pair:<pair_uuid>:<generation>.
--    Trigger-published events only; there is no authenticated INSERT policy on
--    realtime.messages (only realtime.send(), called from these SECURITY DEFINER
--    triggers running as the migration owner, ever writes a row).
-- =====================================================================================

-- Caller's current topic string, or null when unpaired. Used both by the realtime.messages
-- SELECT policy below and (indirectly, via rpc_get_pair -> pair_to_jsonb) by the client.
create or replace function public.caller_active_pair_topic()
returns text
language sql
stable
security definer
set search_path = ''
as $$
    select 'pair:' || p.id::text || ':' || p.channel_generation::text
    from public.pairs p
    join public.pair_members pm on pm.pair_id = p.id
    where pm.user_id = (select auth.uid())
      and pm.left_at is null
      and p.status = 'active'
    limit 1;
$$;

revoke all on function public.caller_active_pair_topic() from public;
grant execute on function public.caller_active_pair_topic() to authenticated;

-- realtime.messages already ships with RLS enabled and no policies (verified against the
-- local stack; see this seat's report). This policy is additive: a private broadcast row
-- is visible only when the topic the caller's realtime client is asking about
-- (realtime.topic(), set per-connection by the Realtime server) matches the topic derived
-- from the caller's own current active membership -- never from anything the row itself
-- or the caller supplies, so there is no way to see another pair's topic by guessing it.
create policy pair_broadcast_select on realtime.messages
    for select
    to authenticated
    using (
        extension = 'broadcast'
        and private is true
        and realtime.topic() = public.caller_active_pair_topic()
    );

-- Membership-event broadcast trigger (contract §3: pair.member_joined.v1 /
-- pair.member_left.v1; pair.proposal_created.v1 / pair.proposal_updated.v1 /
-- pair.commitment_changed.v1 are reserved for Stage 2, not emitted here).
--
-- Ordering convention: every pairing RPC above rotates public.pairs (version + generation)
-- *before* touching public.pair_members, and stashes the pre-rotation topic in the
-- transaction-local `elay.pair_broadcast_topic` GUC. That means when this AFTER trigger
-- fires, public.pairs already reflects the new version/generation (used in the payload),
-- while the event itself still goes out on the *old* topic (contract §3: "the old topic
-- receives one sanitized membership event, while every subsequent event uses the new
-- topic"). If the GUC was not set (e.g. a manual/test INSERT into pair_members outside the
-- RPCs), this falls back to the pair's current generation.
create or replace function public.pair_members_broadcast()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_pair    public.pairs;
    v_event   text;
    v_topic   text;
    v_payload jsonb;
begin
    if tg_op = 'INSERT' then
        v_event := 'pair.member_joined.v1';
    elsif tg_op = 'UPDATE' and new.left_at is not null and old.left_at is null then
        v_event := 'pair.member_left.v1';
    else
        return new;
    end if;

    select * into v_pair from public.pairs where id = new.pair_id;
    if not found then
        return new;
    end if;

    v_topic := current_setting('elay.pair_broadcast_topic', true);
    if v_topic is null or v_topic = '' then
        v_topic := 'pair:' || v_pair.id::text || ':' || v_pair.channel_generation::text;
    end if;

    if v_event = 'pair.member_joined.v1' then
        v_payload := jsonb_build_object(
            'pair_id', v_pair.id,
            'member', (
                select jsonb_build_object(
                    'user_id', pr.user_id,
                    'display_name', pr.display_name,
                    'home_tz', pr.home_tz,
                    'joined_at', public.iso_utc(new.joined_at)
                )
                from public.profiles pr where pr.user_id = new.user_id
            ),
            'version', v_pair.version,
            'occurred_at', public.iso_utc(now())
        );
    else
        v_payload := jsonb_build_object(
            'pair_id', v_pair.id,
            'user_id', new.user_id,
            'version', v_pair.version,
            'occurred_at', public.iso_utc(now())
        );
    end if;

    perform realtime.send(v_payload, v_event, v_topic, true);

    return new;
end;
$$;

create trigger pair_members_broadcast_trigger
    after insert or update on public.pair_members
    for each row
    execute function public.pair_members_broadcast();
