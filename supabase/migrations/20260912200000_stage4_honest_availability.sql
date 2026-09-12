-- Stage 4: honest availability (contracts/stage4-honest-availability.md -- FROZEN;
-- council/stage4-availability-opus.md sA + council/stage4-availability-gemini.md sB
-- restated there -- binding full text). Seat A6 (SQL): this migration only.
--
-- Scope (contract Adopted bullets + lead amendments):
--   1. external_busy (never a time_blocks type) + availability_sources tables -- owner-only
--      SELECT, ZERO write grants, partial-unique dedupe, freshness/window bookkeeping.
--   2. rpc_upsert_external_busy / rpc_delete_external_busy (authenticated, manual only,
--      source_tag hard-coded server-side, Stage-2/3-style action-tagged receipts).
--   3. fn_sync_external_busy (service_role only): atomic window replace + availability_
--      sources upsert -- the deferred Google adapter's landing spot; nothing here requires
--      it to exist to build/test.
--   4. rpc_my_availability_sources(): the caller's own staleness-copy source of truth.
--   5. rpc_proposal_conflict_hints extended ADDITIVELY: union the partner's external_busy
--      into the same clipped busy_windows, coalesce overlapping/adjacent intervals, add
--      exactly one new key `certainty` (four-value ladder, busy-always-wins). Shares a new
--      Stage-3-style 60/15min-per-caller attempts table/limiter with the new
--      rpc_self_conflict_hints (identical shape for the caller's own calendar).
--
-- UNVERIFIED / judgment-call flags (also called out inline at point of use; lead/verifier
-- should re-check these against the freeze):
--   1. "Zero sources" (free_per_elay) vs "has a source" is keyed off availability_sources
--      row EXISTENCE for the target owner, not source_tag='google' specifically -- a user
--      who has only ever used the manual sheet already has ONE row (source_tag='manual'),
--      so their subsequent candidates read free_per_calendar/unknown (per manual's window/
--      freshness), never free_per_elay. This seat's reading: "no sources" means "we know
--      NOTHING about this person's off-ELAY calendar", which becomes false the first time
--      they tell ELAY about anything by hand.
--   2. rpc_upsert_external_busy / rpc_delete_external_busy re-stamp availability_sources
--      for source_tag='manual' with the SAME rolling window the contract fixes for sync,
--      [now-1d, now+35d] -- touching the manual list re-asserts "as far as I know this is
--      complete" for that window (see stamp_availability_source's comment). Not literally
--      specified in the contract text; flagged for lead review.
--   3. A synced (non-manual) external_busy row's origin_tz is a best-effort fallback to the
--      owner's own profiles.home_tz (falling back further to 'Etc/UTC') -- a third-party
--      free-busy interval carries no wall-clock intent of its own the way a proposal
--      candidate does, so there is no principled origin_tz to record; this satisfies the
--      NOT NULL / IANA-validity trigger without inventing false provenance.
--   4. mutation_receipts.result_version has no natural meaning for external_busy (no version
--      column on that table); rpc_upsert_external_busy/rpc_delete_external_busy store the
--      literal constant 1.
--   5. The 60/15min hints rate limit is shared by CALLER across BOTH rpc_proposal_
--      conflict_hints and rpc_self_conflict_hints (contract says "Hints RPC" singular; this
--      seat reads "per caller", not "per RPC", as the tighter and more defensible reading).
--   6. certainty=free_per_calendar requires only ONE of the target's availability_sources
--      rows to be fresh AND window-covering (not all of them) -- a stale manual entry
--      alongside a fresh, covering google sync should not downgrade an otherwise-confident
--      answer.
--
-- PL/pgSQL quirks avoided (recorded in STATE / prior seats' reports, re-checked here): no
-- multi-column composite INTO trap (every INTO target below is a single row/scalar); no
-- left(bytea, n) (Postgres has none -- not needed here, but noted since Stage 3 hit it);
-- house grant discipline (contract lead amendment 4): every new object below gets an
-- EXPLICIT revoke from public/anon[/authenticated] regardless of whether it is a pure
-- read-projector -- stricter than some earlier stages' _to_jsonb helpers, per this stage's
-- own contract instruction ("Explicit revokes for EVERY new object").

-- =====================================================================================
-- 0. Per-source_tag freshness TTL defaults (contract: manual=43200min/30d, google=360min/6h).
-- =====================================================================================

create or replace function public.default_freshness_ttl_minutes(p_source_tag text)
returns int
language sql
immutable
set search_path = ''
as $$
    select case p_source_tag
        when 'manual' then 43200
        when 'google' then 360
        else null
    end;
$$;

comment on function public.default_freshness_ttl_minutes(text) is 'Per-source_tag freshness TTL default (contract: manual=43200min/30d, google=360min/6h). Not grant-reachable -- used only from inside the SECURITY DEFINER functions below when they stamp availability_sources.';

revoke all on function public.default_freshness_ttl_minutes(text) from public, anon, authenticated;

-- =====================================================================================
-- 1. external_busy (contract): a SEPARATE table from time_blocks. Owner-only SELECT, ZERO
--    write grants -- every write goes through rpc_upsert_external_busy/rpc_delete_
--    external_busy (manual) or fn_sync_external_busy (service_role, any source_tag).
-- =====================================================================================

create table public.external_busy (
    id                uuid primary key default gen_random_uuid(),
    owner_id          uuid not null references auth.users (id) on delete cascade,
    source_tag        text not null,
    external_ref_hash bytea null,
    starts_at_utc     timestamptz not null,
    ends_at_utc       timestamptz not null,
    origin_tz         text not null,
    label             text null,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),

    constraint external_busy_source_tag_check check (source_tag in ('manual', 'google')),
    constraint external_busy_end_after_start_check check (ends_at_utc > starts_at_utc),
    constraint external_busy_duration_sanity_check check (ends_at_utc - starts_at_utc <= interval '60 days'),
    constraint external_busy_label_length_check check (label is null or char_length(label) between 1 and 200)
);

comment on table public.external_busy is 'Contract: a separate table from time_blocks (never that type) -- busy intervals sourced from off-ELAY calendars (source_tag=google, deferred adapter) or the manual "I''m busy then" UI (source_tag=manual, hard-coded server-side by rpc_upsert_external_busy). Owner-only SELECT, ZERO write grants. external_ref_hash is the sync dedupe key and is never returned by any RPC.';
comment on column public.external_busy.external_ref_hash is 'sha256 of the source provider''s own stable event identifier (google sync only) -- null for manual rows, which have no external identity to dedupe against. Never returned by any RPC (contract).';
comment on column public.external_busy.origin_tz is 'IANA zone name; validated by the reused validate_origin_tz trigger (same function/pattern as time_blocks). For a synced (non-manual) interval this is a best-effort fallback to the owner''s own home_tz -- see fn_sync_external_busy and UNVERIFIED #3 above.';
comment on column public.external_busy.label is 'Optional free-text note for a manual entry (Gemini sB''s "I''m busy then" sheet); never set by fn_sync_external_busy beyond passing through whatever the provider itself supplies.';

create index external_busy_owner_window_idx
    on public.external_busy (owner_id, starts_at_utc, ends_at_utc);

-- Contract: "partial unique on (owner_id, source_tag, external_ref_hash)". Excludes NULL
-- (manual rows carry no external_ref_hash and never dedupe against each other or anything
-- else).
create unique index external_busy_dedupe_unique
    on public.external_busy (owner_id, source_tag, external_ref_hash)
    where external_ref_hash is not null;

comment on index public.external_busy_dedupe_unique is 'Contract: partial unique on (owner_id, source_tag, external_ref_hash) -- dedupes a synced provider''s re-reported event across syncs.';

create trigger external_busy_set_updated_at
    before update on public.external_busy
    for each row
    execute function public.set_updated_at();

-- Reuses the house's validate_origin_tz trigger function VERBATIM (it is generic: a
-- plpgsql trigger that reads NEW.origin_tz, with no table-specific dependency), exactly as
-- the contract's grant instructs ("reuse validate_origin_tz if it's a reusable function").
create trigger external_busy_validate_origin_tz
    before insert or update on public.external_busy
    for each row
    execute function public.validate_origin_tz();

alter table public.external_busy enable row level security;
revoke all on table public.external_busy from anon, authenticated;
grant select on table public.external_busy to authenticated;
-- No insert/update/delete grant at all (contract: "ZERO write grants") -- every write goes
-- through a SECURITY DEFINER RPC/function below.

create policy external_busy_select_own on public.external_busy
    for select to authenticated
    using (owner_id = (select auth.uid()));

-- =====================================================================================
-- 2. availability_sources (contract): per-owner, per-source_tag freshness/window
--    bookkeeping backing the certainty ladder. Owner-only SELECT, ZERO write grants.
-- =====================================================================================

create table public.availability_sources (
    owner_id              uuid not null references auth.users (id) on delete cascade,
    source_tag            text not null,
    status                text not null default 'active',
    last_synced_at        timestamptz not null,
    freshness_ttl_minutes int not null,
    window_start_utc      timestamptz not null,
    window_end_utc        timestamptz not null,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),

    constraint availability_sources_pkey primary key (owner_id, source_tag),
    constraint availability_sources_source_tag_check check (source_tag in ('manual', 'google')),
    constraint availability_sources_status_check check (status in ('active', 'disconnected')),
    constraint availability_sources_ttl_positive_check check (freshness_ttl_minutes > 0),
    constraint availability_sources_window_check check (window_end_utc > window_start_utc)
);

comment on table public.availability_sources is 'Contract: per-owner/source_tag freshness+window bookkeeping. certainty=free_per_calendar requires a row here whose window COVERS the candidate AND last_synced_at + freshness_ttl_minutes has not elapsed; certainty=free_per_elay means the owner has ZERO rows here at all; staleness/non-coverage otherwise downgrades to unknown -- never erases a busy. Owner-only SELECT, ZERO write grants -- writes only via rpc_upsert_external_busy/rpc_delete_external_busy (source_tag=manual) or fn_sync_external_busy (service_role, any source_tag).';
comment on column public.availability_sources.status is 'Reserved for the deferred Google adapter''s revocation state (''disconnected''); no RPC in THIS migration ever sets anything but ''active'' -- manual and every fn_sync_external_busy call stamp ''active''.';

create trigger availability_sources_set_updated_at
    before update on public.availability_sources
    for each row
    execute function public.set_updated_at();

alter table public.availability_sources enable row level security;
revoke all on table public.availability_sources from anon, authenticated;
grant select on table public.availability_sources to authenticated;

create policy availability_sources_select_own on public.availability_sources
    for select to authenticated
    using (owner_id = (select auth.uid()));

-- =====================================================================================
-- 3. Wire-format serializers (contract sA "external_ref_hash ... never returned by any
--    RPC" -- the projector below simply never selects it).
-- =====================================================================================

create or replace function public.external_busy_to_jsonb(b public.external_busy)
returns jsonb
language sql
stable
set search_path = ''
as $$
    select jsonb_build_object(
        'id', b.id,
        'owner_id', b.owner_id,
        'source_tag', b.source_tag,
        'starts_at_utc', public.iso_utc(b.starts_at_utc),
        'ends_at_utc', public.iso_utc(b.ends_at_utc),
        'origin_tz', b.origin_tz,
        'label', b.label,
        'created_at', public.iso_utc(b.created_at),
        'updated_at', public.iso_utc(b.updated_at)
    );
$$;

comment on function public.external_busy_to_jsonb(public.external_busy) is 'Wire projector for one external_busy row. Deliberately excludes external_ref_hash (contract: never returned by any RPC).';

revoke all on function public.external_busy_to_jsonb(public.external_busy) from public, anon, authenticated;

create or replace function public.availability_source_to_jsonb(s public.availability_sources)
returns jsonb
language sql
stable
set search_path = ''
as $$
    select jsonb_build_object(
        'source_tag', s.source_tag,
        'status', s.status,
        'last_synced_at', public.iso_utc(s.last_synced_at),
        'freshness_ttl_minutes', s.freshness_ttl_minutes,
        'window_start_utc', public.iso_utc(s.window_start_utc),
        'window_end_utc', public.iso_utc(s.window_end_utc)
    );
$$;

revoke all on function public.availability_source_to_jsonb(public.availability_sources) from public, anon, authenticated;

-- Shared upsert of one (owner, source_tag) availability_sources row. Reused by
-- rpc_upsert_external_busy/rpc_delete_external_busy (source_tag='manual', the standard
-- rolling window) and fn_sync_external_busy (any source_tag, caller-supplied window).
create or replace function public.stamp_availability_source(
    p_owner          uuid,
    p_source_tag     text,
    p_last_synced_at timestamptz,
    p_window_start   timestamptz,
    p_window_end     timestamptz
)
returns void
language plpgsql
set search_path = ''
as $$
begin
    insert into public.availability_sources (
        owner_id, source_tag, status, last_synced_at, freshness_ttl_minutes,
        window_start_utc, window_end_utc
    ) values (
        p_owner, p_source_tag, 'active', p_last_synced_at,
        public.default_freshness_ttl_minutes(p_source_tag), p_window_start, p_window_end
    )
    on conflict (owner_id, source_tag) do update
    set status = 'active',
        last_synced_at = excluded.last_synced_at,
        freshness_ttl_minutes = excluded.freshness_ttl_minutes,
        window_start_utc = excluded.window_start_utc,
        window_end_utc = excluded.window_end_utc;
end;
$$;

comment on function public.stamp_availability_source(uuid, text, timestamptz, timestamptz, timestamptz) is 'Shared availability_sources upsert (contract: fn_sync_external_busy "stamping last_synced_at"). Not grant-reachable.';

revoke all on function public.stamp_availability_source(uuid, text, timestamptz, timestamptz, timestamptz) from public, anon, authenticated;

-- =====================================================================================
-- 4. rpc_upsert_external_busy / rpc_delete_external_busy (contract): authenticated;
--    source_tag='manual' hard-coded server-side -- there is NO p_source_tag parameter on
--    either function, so a client has no entry point to fabricate provenance. Stage-2/3-
--    style action-tagged mutation_receipts (cross-action 22023).
-- =====================================================================================

create or replace function public.rpc_upsert_external_busy(
    p_operation_id  uuid,
    p_id            uuid default null,
    p_starts_at_utc timestamptz default null,
    p_ends_at_utc   timestamptz default null,
    p_origin_tz     text default null,
    p_label         text default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller       uuid := auth.uid();
    v_receipt      public.mutation_receipts;
    v_current      public.external_busy;
    v_row          public.external_busy;
    v_window_start timestamptz;
    v_window_end   timestamptz;
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
        if (v_receipt.result ->> 'action') is distinct from 'upsert_external_busy' then
            raise exception 'operation_id already used for a different action' using errcode = '22023';
        end if;
        return v_receipt.result;
    end if;

    if p_starts_at_utc is null or p_ends_at_utc is null then
        raise exception 'p_starts_at_utc and p_ends_at_utc are required' using errcode = '22004';
    end if;
    if p_ends_at_utc <= p_starts_at_utc then
        raise exception 'p_ends_at_utc must be after p_starts_at_utc' using errcode = '22023';
    end if;
    if p_origin_tz is null then
        raise exception 'p_origin_tz is required' using errcode = '22004';
    end if;
    if p_label is not null and char_length(p_label) not between 1 and 200 then
        raise exception 'p_label must be between 1 and 200 characters' using errcode = '22023';
    end if;

    if p_id is not null then
        -- source_tag = 'manual' filter here (not just owner_id): a synced (google) row can
        -- never be edited through this path, only ever wholesale-replaced by
        -- fn_sync_external_busy. Folding "not mine" and "not manual" into the same not-found
        -- avoids disclosing which reason applied (house pattern: same shape either way).
        select * into v_current
        from public.external_busy
        where id = p_id and owner_id = v_caller and source_tag = 'manual'
        for update;

        if not found then
            -- TRUE upsert (lead fix at merge): the house convention (rpc_upsert_time_block,
            -- B7's client) supplies a CLIENT-generated id on create -- an unknown p_id
            -- inserts rather than erroring. A guessed id belonging to someone else (or to a
            -- synced row) also lands here and simply creates the caller's own new manual
            -- row under that id -- if the id exists elsewhere, the pk violation surfaces as
            -- an error without disclosing whose row it was.
            insert into public.external_busy (
                id, owner_id, source_tag, external_ref_hash, starts_at_utc, ends_at_utc, origin_tz, label
            ) values (
                p_id, v_caller, 'manual', null, p_starts_at_utc, p_ends_at_utc, p_origin_tz, p_label
            )
            returning * into v_row;
        else
            update public.external_busy
            set starts_at_utc = p_starts_at_utc,
                ends_at_utc   = p_ends_at_utc,
                origin_tz     = p_origin_tz,
                label         = p_label
            where id = p_id
            returning * into v_row;
        end if;
    else
        insert into public.external_busy (
            owner_id, source_tag, external_ref_hash, starts_at_utc, ends_at_utc, origin_tz, label
        ) values (
            v_caller, 'manual', null, p_starts_at_utc, p_ends_at_utc, p_origin_tz, p_label
        )
        returning * into v_row;
    end if;

    -- Touching the manual list re-stamps the standard rolling snapshot window as the manual
    -- source's own coverage bookkeeping (UNVERIFIED #2 above).
    v_window_start := now() - interval '1 day';
    v_window_end   := now() + interval '35 days';
    perform public.stamp_availability_source(v_caller, 'manual', now(), v_window_start, v_window_end);

    v_result := jsonb_build_object(
        'outcome', 'applied',
        'action', 'upsert_external_busy',
        'external_busy', public.external_busy_to_jsonb(v_row)
    );

    -- result_version has no meaning for external_busy (no version column, UNVERIFIED #4) --
    -- stored as the constant 1 to satisfy mutation_receipts' NOT NULL column.
    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, 1, v_result);

    return v_result;
end;
$$;

comment on function public.rpc_upsert_external_busy(uuid, uuid, timestamptz, timestamptz, text, text) is 'Contract: the manual "I''m busy then" provider (Gemini sB). source_tag is ALWAYS ''manual'' -- there is no p_source_tag parameter, so a client has no way to fabricate the provenance free_per_calendar trusts. p_id null inserts; p_id set updates the caller''s own existing MANUAL row (never a synced one). Action-tagged receipt (cross-action 22023 against rpc_delete_external_busy).';

revoke all on function public.rpc_upsert_external_busy(uuid, uuid, timestamptz, timestamptz, text, text) from public, anon;
grant execute on function public.rpc_upsert_external_busy(uuid, uuid, timestamptz, timestamptz, text, text) to authenticated;

create or replace function public.rpc_delete_external_busy(
    p_operation_id uuid,
    p_id           uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller       uuid := auth.uid();
    v_receipt      public.mutation_receipts;
    v_current      public.external_busy;
    v_window_start timestamptz;
    v_window_end   timestamptz;
    v_result       jsonb;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_operation_id is null then
        raise exception 'p_operation_id is required' using errcode = '22004';
    end if;
    if p_id is null then
        raise exception 'p_id is required' using errcode = '22004';
    end if;

    select * into v_receipt
    from public.mutation_receipts
    where owner_id = v_caller and operation_id = p_operation_id;

    if found then
        if (v_receipt.result ->> 'action') is distinct from 'delete_external_busy' then
            raise exception 'operation_id already used for a different action' using errcode = '22023';
        end if;
        return v_receipt.result;
    end if;

    select * into v_current
    from public.external_busy
    where id = p_id and owner_id = v_caller and source_tag = 'manual'
    for update;

    if not found then
        raise exception 'external busy entry not found' using errcode = 'P0002';
    end if;

    delete from public.external_busy where id = p_id;

    v_window_start := now() - interval '1 day';
    v_window_end   := now() + interval '35 days';
    perform public.stamp_availability_source(v_caller, 'manual', now(), v_window_start, v_window_end);

    v_result := jsonb_build_object(
        'outcome', 'applied',
        'action', 'delete_external_busy',
        'id', p_id
    );

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, 1, v_result);

    return v_result;
end;
$$;

comment on function public.rpc_delete_external_busy(uuid, uuid) is 'Contract: deletes the caller''s own MANUAL external_busy row (never a synced one -- source_tag=''manual'' filter, folded into the same not-found as "not mine"). Action-tagged receipt (cross-action 22023 against rpc_upsert_external_busy).';

revoke all on function public.rpc_delete_external_busy(uuid, uuid) from public, anon;
grant execute on function public.rpc_delete_external_busy(uuid, uuid) to authenticated;

-- =====================================================================================
-- 5. fn_sync_external_busy (contract): service_role only. Atomic window replace + the
--    deferred Google adapter's landing spot -- nothing here requires it to exist to build
--    or test.
-- =====================================================================================

-- Structural validator for p_intervals: array of {starts_at_utc, ends_at_utc, external_ref?,
-- label?} objects, end after start. Mirrors proposal_candidates_valid's role (contract sA
-- style) but looser (no candidate_idx/duration_min -- a sync batch is not the same shape as
-- a proposal's candidate list).
create or replace function public.external_busy_intervals_valid(p_intervals jsonb)
returns boolean
language plpgsql
immutable
as $$
declare
    v_len   int;
    v_elem  jsonb;
    v_start timestamptz;
    v_end   timestamptz;
    i       int;
begin
    if p_intervals is null or jsonb_typeof(p_intervals) is distinct from 'array' then
        return false;
    end if;

    v_len := jsonb_array_length(p_intervals);
    for i in 0 .. v_len - 1 loop
        v_elem := p_intervals -> i;
        if v_elem is null or jsonb_typeof(v_elem) is distinct from 'object' then
            return false;
        end if;
        if not (v_elem ? 'starts_at_utc') or not (v_elem ? 'ends_at_utc') then
            return false;
        end if;

        begin
            v_start := (v_elem ->> 'starts_at_utc')::timestamptz;
            v_end   := (v_elem ->> 'ends_at_utc')::timestamptz;
        exception when others then
            return false;
        end;
        if v_start is null or v_end is null or v_end <= v_start then
            return false;
        end if;
    end loop;

    return true;
end;
$$;

comment on function public.external_busy_intervals_valid(jsonb) is 'Structural validator for fn_sync_external_busy''s p_intervals array. Not grant-reachable.';

revoke all on function public.external_busy_intervals_valid(jsonb) from public, anon, authenticated;

create or replace function public.fn_sync_external_busy(
    p_owner        uuid,
    p_source_tag   text,
    p_window_start timestamptz,
    p_window_end   timestamptz,
    p_intervals    jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_owner_tz text;
    v_count    int;
begin
    -- Pre-gate F12: the sync path must never touch hand-entered rows -- an edge-function
    -- bug could otherwise wipe a user's manual entries AND re-stamp the source as
    -- fresh+covering (a confident free_per_calendar over an emptied calendar).
    if p_source_tag = 'manual' then
        raise exception 'fn_sync_external_busy may not sync the manual source' using errcode = '22023';
    end if;
    if p_owner is null then
        raise exception 'p_owner is required' using errcode = '22004';
    end if;
    if p_source_tag is null or p_source_tag not in ('manual', 'google') then
        raise exception 'p_source_tag must be manual or google' using errcode = '22023';
    end if;
    if p_window_start is null or p_window_end is null or p_window_end <= p_window_start then
        raise exception 'p_window_start/p_window_end must be a valid, non-empty range' using errcode = '22023';
    end if;
    if not public.external_busy_intervals_valid(coalesce(p_intervals, '[]'::jsonb)) then
        raise exception 'p_intervals must be an array of {starts_at_utc,ends_at_utc,...} objects with end after start' using errcode = '22023';
    end if;

    -- Atomic window replace (contract): everything this (owner, source_tag) previously
    -- reported that overlaps the given window is discarded and replaced with exactly
    -- p_intervals, inside this single function-body transaction.
    delete from public.external_busy
    where owner_id = p_owner
      and source_tag = p_source_tag
      and starts_at_utc < p_window_end
      and ends_at_utc > p_window_start;

    -- UNVERIFIED #3: best-effort origin_tz fallback for a synced interval (no wall-clock
    -- intent of its own to record).
    select coalesce(pr.home_tz, 'Etc/UTC') into v_owner_tz
    from public.profiles pr where pr.user_id = p_owner;
    v_owner_tz := coalesce(v_owner_tz, 'Etc/UTC');

    insert into public.external_busy (owner_id, source_tag, external_ref_hash, starts_at_utc, ends_at_utc, origin_tz, label)
    select
        p_owner,
        p_source_tag,
        case when iv ->> 'external_ref' is not null
             then extensions.digest(convert_to(iv ->> 'external_ref', 'UTF8'), 'sha256')
             else null end,
        (iv ->> 'starts_at_utc')::timestamptz,
        (iv ->> 'ends_at_utc')::timestamptz,
        v_owner_tz,
        iv ->> 'label'
    from jsonb_array_elements(coalesce(p_intervals, '[]'::jsonb)) iv
    on conflict (owner_id, source_tag, external_ref_hash) where external_ref_hash is not null do nothing;

    get diagnostics v_count = row_count;

    perform public.stamp_availability_source(p_owner, p_source_tag, now(), p_window_start, p_window_end);

    return jsonb_build_object(
        'outcome', 'applied',
        'owner_id', p_owner,
        'source_tag', p_source_tag,
        'window_start_utc', public.iso_utc(p_window_start),
        'window_end_utc', public.iso_utc(p_window_end),
        'count', v_count
    );
end;
$$;

comment on function public.fn_sync_external_busy(uuid, text, timestamptz, timestamptz, jsonb) is 'service_role-only atomic window replace for a synced provider (contract). Deletes every external_busy row for (p_owner, p_source_tag) overlapping [p_window_start, p_window_end], re-inserts p_intervals ({starts_at_utc, ends_at_utc, external_ref?, label?} -- external_ref becomes external_ref_hash via sha256, never stored/returned in plaintext), then stamps availability_sources. Not a per-user idempotent mutation (no operation_id/mutation_receipts row) -- delete-then-insert makes re-running it for the same window naturally safe. The deferred Google adapter''s landing spot; nothing in this migration requires it to be called.';

revoke all on function public.fn_sync_external_busy(uuid, text, timestamptz, timestamptz, jsonb) from public, anon, authenticated;
grant execute on function public.fn_sync_external_busy(uuid, text, timestamptz, timestamptz, jsonb) to service_role;

-- =====================================================================================
-- 6. rpc_my_availability_sources (contract): the caller's OWN rows -- the staleness-copy
--    source of truth for the owner's own UI. Never exposed to a peer.
-- =====================================================================================

create or replace function public.rpc_my_availability_sources()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller uuid := auth.uid();
    v_result jsonb;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    select coalesce(jsonb_agg(public.availability_source_to_jsonb(s) order by s.source_tag), '[]'::jsonb)
    into v_result
    from public.availability_sources s
    where s.owner_id = v_caller;

    return v_result;
end;
$$;

comment on function public.rpc_my_availability_sources() is 'Contract: the caller''s OWN availability_sources rows {source_tag, status, last_synced_at, freshness_ttl_minutes, window_start_utc, window_end_utc} -- never disclosed to a peer. rpc_proposal_conflict_hints reads the same table server-side for the TARGET (partner or self) but returns only the derived certainty label, never these raw fields, when the target is a peer.';

revoke all on function public.rpc_my_availability_sources() from public, anon;
grant execute on function public.rpc_my_availability_sources() to authenticated;

-- =====================================================================================
-- 7. Hints rate limiting (contract: "Hints RPC gains the Stage-3 attempts pattern"; see
--    UNVERIFIED #5 -- shared per CALLER across both hint RPCs below).
-- =====================================================================================

create table public.conflict_hints_attempts (
    caller_id    uuid not null references auth.users (id) on delete cascade,
    attempted_at timestamptz not null default now()
);

comment on table public.conflict_hints_attempts is 'Unconditional per-attempt log backing the 60/15min per-caller limiter shared by rpc_proposal_conflict_hints and rpc_self_conflict_hints. No client access.';

create index conflict_hints_attempts_caller_time_idx
    on public.conflict_hints_attempts (caller_id, attempted_at);

alter table public.conflict_hints_attempts enable row level security;
revoke all on table public.conflict_hints_attempts from public, anon, authenticated;
-- no policies: written/read only from inside the SECURITY DEFINER RPCs below.

create or replace function public.hints_rate_limited(p_caller uuid)
returns boolean
language plpgsql
set search_path = ''
as $$
declare
    v_count int;
begin
    -- Opportunistic retention (pre-gate F8): drop attempt rows older than a day so the
    -- table cannot grow unbounded; cheap because of the (caller, attempted_at) index.
    delete from public.conflict_hints_attempts where attempted_at < now() - interval '1 day';
    insert into public.conflict_hints_attempts (caller_id) values (p_caller);

    select count(*) into v_count
    from public.conflict_hints_attempts
    where caller_id = p_caller and attempted_at > now() - interval '15 minutes';

    return v_count > 60;
end;
$$;

comment on function public.hints_rate_limited(uuid) is 'Logs the attempt unconditionally, then reports whether the caller has exceeded 60/15min. Shared by rpc_proposal_conflict_hints and rpc_self_conflict_hints (UNVERIFIED #5: per CALLER, not per RPC). Not grant-reachable.';

revoke all on function public.hints_rate_limited(uuid) from public, anon, authenticated;

-- Over-limit response: the SAME exact key set as a normal hints response, so a client never
-- has to special-case rate limiting -- just an opaque, no-data-disclosed certainty=unknown
-- per candidate (contract: "over-limit returns the opaque certainty='unknown' shape").
create or replace function public.hints_opaque_shape(p_candidates jsonb)
returns jsonb
language sql
immutable
set search_path = ''
as $$
    select coalesce(jsonb_agg(
        jsonb_build_object(
            'candidate_idx', (c ->> 'candidate_idx')::int,
            'has_conflict', false,
            'busy_windows', '[]'::jsonb,
            'certainty', 'unknown'
        )
        order by (c ->> 'candidate_idx')::int
    ), '[]'::jsonb)
    from jsonb_array_elements(p_candidates) c;
$$;

revoke all on function public.hints_opaque_shape(jsonb) from public, anon, authenticated;

-- =====================================================================================
-- 8. Busy-window union + coalescing + certainty (contract): shared computation core for
--    BOTH rpc_proposal_conflict_hints (target = partner) and rpc_self_conflict_hints
--    (target = caller).
-- =====================================================================================

-- Gaps-and-islands merge: an owner's scheduled ELAY time_blocks UNIONED with their
-- external_busy rows, clipped to [p_clip_start, p_clip_end], collapsed into non-overlapping,
-- non-adjacent windows (contract: "coalescing of overlapping/adjacent intervals ... before
-- return" -- the union itself is what masks provenance, per the contract's ADR-007 reading).
create or replace function public.coalesced_busy_windows(
    p_owner       uuid,
    p_clip_start  timestamptz,
    p_clip_end    timestamptz
)
returns jsonb
language sql
stable
set search_path = ''
as $$
    with raw_intervals as (
        select greatest(b.starts_at_utc, p_clip_start) as s,
               least(b.ends_at_utc, p_clip_end) as e
        from public.time_blocks b
        where b.owner_id = p_owner
          and b.status = 'scheduled'
          and b.starts_at_utc < p_clip_end
          and b.ends_at_utc > p_clip_start
        union all
        select greatest(eb.starts_at_utc, p_clip_start) as s,
               least(eb.ends_at_utc, p_clip_end) as e
        from public.external_busy eb
        where eb.owner_id = p_owner
          and eb.starts_at_utc < p_clip_end
          and eb.ends_at_utc > p_clip_start
    ),
    with_prev as (
        select s, e,
               max(e) over (order by s, e rows between unbounded preceding and 1 preceding) as prev_max_e
        from raw_intervals
    ),
    grouped as (
        select s, e,
               sum(case when prev_max_e is null or s > prev_max_e then 1 else 0 end)
                   over (order by s, e) as grp
        from with_prev
    ),
    merged as (
        select min(s) as starts_at_utc, max(e) as ends_at_utc
        from grouped
        group by grp
    )
    select coalesce(jsonb_agg(jsonb_build_object(
        'starts_at_utc', public.iso_utc(starts_at_utc),
        'ends_at_utc', public.iso_utc(ends_at_utc)
    ) order by starts_at_utc), '[]'::jsonb)
    from merged;
$$;

comment on function public.coalesced_busy_windows(uuid, timestamptz, timestamptz) is 'Contract: union an owner''s scheduled ELAY time_blocks with their external_busy rows, clip to [p_clip_start, p_clip_end], then merge overlapping/ADJACENT (touching) intervals into single windows -- the 19:00-20:00 + 19:30-20:30 -> 19:00-20:30 case. Not grant-reachable.';

revoke all on function public.coalesced_busy_windows(uuid, timestamptz, timestamptz) from public, anon, authenticated;

-- Per-candidate {candidate_idx, has_conflict, busy_windows, certainty} for an arbitrary
-- target owner (partner or self). certainty ladder (contract, busy always wins):
--   busy_windows non-empty              -> 'busy'
--   target has zero availability_sources rows -> 'free_per_elay'
--   target has a fresh AND window-covering source (any one) -> 'free_per_calendar'
--   otherwise (stale or non-covering)   -> 'unknown'
create or replace function public.candidate_busy_hints(p_owner uuid, p_candidates jsonb)
returns jsonb
language sql
stable
set search_path = ''
as $$
    select coalesce(jsonb_agg(
        jsonb_build_object(
            'candidate_idx', (c ->> 'candidate_idx')::int,
            'has_conflict', jsonb_array_length(w.bw) > 0,
            'busy_windows', w.bw,
            'certainty',
                case
                    when jsonb_array_length(w.bw) > 0 then 'busy'
                    when not exists (
                        select 1 from public.availability_sources s where s.owner_id = p_owner
                    ) then 'free_per_elay'
                    when exists (
                        select 1 from public.availability_sources s
                        where s.owner_id = p_owner
                          and s.window_start_utc <= (c ->> 'starts_at_utc')::timestamptz
                          and s.window_end_utc   >= (c ->> 'ends_at_utc')::timestamptz
                          and s.last_synced_at + make_interval(mins => s.freshness_ttl_minutes) > now()
                    ) then 'free_per_calendar'
                    else 'unknown'
                end
        )
        order by (c ->> 'candidate_idx')::int
    ), '[]'::jsonb)
    from jsonb_array_elements(p_candidates) c
    cross join lateral (
        select public.coalesced_busy_windows(
            p_owner,
            (c ->> 'starts_at_utc')::timestamptz,
            (c ->> 'ends_at_utc')::timestamptz
        ) as bw
    ) w;
$$;

comment on function public.candidate_busy_hints(uuid, jsonb) is 'Shared core for rpc_proposal_conflict_hints (p_owner=partner) and rpc_self_conflict_hints (p_owner=caller): exact keys {candidate_idx,has_conflict,busy_windows,certainty}. Not grant-reachable -- called only from inside those two SECURITY DEFINER RPCs.';

revoke all on function public.candidate_busy_hints(uuid, jsonb) from public, anon, authenticated;

-- =====================================================================================
-- 9. rpc_proposal_conflict_hints (contract: extended ADDITIVELY over the Stage-2 shipped
--    function -- same signature, same shipped keys, plus exactly one new key `certainty`).
-- =====================================================================================

create or replace function public.rpc_proposal_conflict_hints(p_candidates jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller  uuid := auth.uid();
    v_partner uuid;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if not public.proposal_candidates_valid(p_candidates) then
        raise exception 'p_candidates must be 1-3 valid, contiguously-indexed candidates' using errcode = '22023';
    end if;

    if public.hints_rate_limited(v_caller) then
        return public.hints_opaque_shape(p_candidates);
    end if;

    select pm2.user_id into v_partner
    from public.pair_members pm1
    join public.pair_members pm2
        on pm2.pair_id = pm1.pair_id and pm2.user_id <> pm1.user_id and pm2.left_at is null
    where pm1.user_id = v_caller and pm1.left_at is null
    limit 1;

    if v_partner is null then
        raise exception 'caller has no active pair partner' using errcode = '42501';
    end if;

    return public.candidate_busy_hints(v_partner, p_candidates);
end;
$$;

comment on function public.rpc_proposal_conflict_hints(jsonb) is 'ADR-007 busy-only projection, extended ADDITIVELY (Stage 4 contract): exact keys {candidate_idx,has_conflict,busy_windows:[{starts_at_utc,ends_at_utc}],certainty}. busy_windows now unions the partner''s scheduled ELAY blocks with their external_busy rows, coalesced (overlapping/adjacent merged) before return -- provenance masked by the union. certainty is computed from the partner''s OWN availability_sources but the sync timestamps/source tags themselves are never disclosed here (peer-directed; contract: "staleness copy reads the caller''s OWN rpc_my_availability_sources()"). Rate-limited 60/15min per caller, shared with rpc_self_conflict_hints; over limit returns the same exact-key shape with certainty=unknown and no data.';

revoke all on function public.rpc_proposal_conflict_hints(jsonb) from public, anon;
grant execute on function public.rpc_proposal_conflict_hints(jsonb) to authenticated;

-- =====================================================================================
-- 10. rpc_self_conflict_hints (contract, new): identical shape for the caller's OWN
--     calendar. No pair/partner requirement.
-- =====================================================================================

create or replace function public.rpc_self_conflict_hints(p_candidates jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller uuid := auth.uid();
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if not public.proposal_candidates_valid(p_candidates) then
        raise exception 'p_candidates must be 1-3 valid, contiguously-indexed candidates' using errcode = '22023';
    end if;

    if public.hints_rate_limited(v_caller) then
        return public.hints_opaque_shape(p_candidates);
    end if;

    return public.candidate_busy_hints(v_caller, p_candidates);
end;
$$;

comment on function public.rpc_self_conflict_hints(jsonb) is 'Stage 4 contract: identical shape to rpc_proposal_conflict_hints (exact keys {candidate_idx,has_conflict,busy_windows,certainty}) but computed against the CALLER''S OWN scheduled ELAY blocks + external_busy + availability_sources -- no pair/partner requirement. Shares the same 60/15min per-caller rate limiter (public.hints_rate_limited).';

revoke all on function public.rpc_self_conflict_hints(jsonb) from public, anon;
grant execute on function public.rpc_self_conflict_hints(jsonb) to authenticated;
