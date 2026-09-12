-- Stage 2: time-lock negotiation -- schema (contracts/stage2-timelock.md,
-- council/stage2-timelock-sol.md §1). Four tables (time_lock_proposals,
-- proposal_revisions, proposal_responses, commitments), the time_blocks amendment
-- (type gains 'shared_lock'; nullable source_proposal_id/source_revision; unique
-- (source_proposal_id, owner_id)), RLS + grants, and the append-only/candidate-shape
-- triggers. RPCs, fn_expire_proposals, and Realtime publishing live in the companion
-- migration 20260912140000_rpc_stage2_timelock.sql (same seat, split for reviewability).
--
-- Block model (Sol §1): acceptance creates one time_blocks row PER MEMBER --
-- visibility='private', pair_id=null, type='shared_lock' -- linked back to the winning
-- proposal/revision via source_proposal_id/source_revision. These rows ride the existing
-- owner-only base RLS and the existing Today/Plan read pipeline; the proposal remains the
-- shared object precisely so no new sharing surface is needed on time_blocks itself.

-- =====================================================================================
-- 0. time_blocks amendment (contract §1 / lead amendment 1): type CHECK gains
--    'shared_lock'; nullable source_proposal_id/source_revision link a shared_lock row
--    back to the proposal that produced it; unique (source_proposal_id, owner_id)
--    prevents a double-accept race from producing two blocks for the same owner.
-- =====================================================================================

alter table public.time_blocks
    add column source_proposal_id uuid null,
    add column source_revision    int  null;

comment on column public.time_blocks.source_proposal_id is 'Stage 2: the time_lock_proposals row this shared_lock block was created from (null for every other block type).';
comment on column public.time_blocks.source_revision is 'Stage 2: the winning proposal_revisions.revision_no this shared_lock block was created from.';

alter table public.time_blocks drop constraint time_blocks_type_check;
alter table public.time_blocks
    add constraint time_blocks_type_check check (type in ('personal', 'focus', 'routine', 'shared_lock'));

comment on constraint time_blocks_type_check on public.time_blocks is 'Stage 2 amendment: shared_lock added for accepted time-lock proposals (contract §1).';

-- Both columns null together iff the block did not come from a proposal; both set
-- together iff it did. Backstops the RPC-level invariant that every shared_lock row
-- carries its provenance and no other row ever does.
alter table public.time_blocks
    add constraint time_blocks_source_proposal_iff_revision_check check (
        (source_proposal_id is null) = (source_revision is null)
    );

-- Referential integrity into the specific winning revision (added after proposal_revisions
-- exists, below in section 2).

comment on table public.time_blocks is 'Owner-scoped calendar events; Phase 1 is private-only (ADR-007). origin_tz per ADR-006. Stage 2: type=shared_lock rows are the per-member locks an accepted time_lock_proposal produces (contract §1).';

-- =====================================================================================
-- 1. time_lock_proposals (contract §1).
-- =====================================================================================

create table public.time_lock_proposals (
    id                     uuid primary key default gen_random_uuid(),
    pair_id                uuid not null references public.pairs (id),
    creator_id             uuid not null references auth.users (id),
    title                  text not null check (char_length(title) between 1 and 200),
    status                 text not null default 'proposed',
    response_deadline      timestamptz not null,
    origin_tz              text not null,
    current_revision       int not null default 1 check (current_revision between 1 and 20),
    accepted_revision      int null,
    accepted_candidate_idx smallint null,
    version                bigint not null default 1 check (version > 0),
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now(),
    accepted_at            timestamptz null,
    declined_at            timestamptz null,
    expired_at             timestamptz null,
    cancelled_at           timestamptz null,
    completed_at           timestamptz null,

    constraint time_lock_proposals_status_check check (
        status in ('draft', 'proposed', 'accepted', 'countered', 'declined', 'expired', 'cancelled', 'completed')
    ),
    -- Exactly the accepted/completed statuses carry a winner; every other status (incl.
    -- declined/expired/cancelled) carries neither field (contract §1).
    constraint time_lock_proposals_winner_iff_accepted_check check (
        (status in ('accepted', 'completed')) = (accepted_revision is not null and accepted_candidate_idx is not null)
    ),
    constraint time_lock_proposals_deadline_after_created_check check (response_deadline > created_at)
);

comment on table public.time_lock_proposals is 'A time-lock negotiation between two pair members; the shared object acceptance turns into per-member time_blocks + commitments (contract §1). ''draft'' is retained for the status enum (spec §4) but no Stage-2 RPC creates or reaches it -- rpc_create_proposal sends immediately as ''proposed''.';
comment on column public.time_lock_proposals.origin_tz is 'IANA zone name of the proposal''s current revision author at authoring time; validated against pg_timezone_names by trigger (ADR-006).';
comment on column public.time_lock_proposals.current_revision is 'The revision number responders must reference (expected_revision) to respond; bumped only by a counter.';

create trigger time_lock_proposals_set_updated_at
    before update on public.time_lock_proposals
    for each row
    execute function public.set_updated_at();

create trigger time_lock_proposals_validate_origin_tz
    before insert or update on public.time_lock_proposals
    for each row
    execute function public.validate_origin_tz();

create index time_lock_proposals_pair_status_deadline_idx
    on public.time_lock_proposals (pair_id, status, response_deadline);

create index time_lock_proposals_pair_updated_idx
    on public.time_lock_proposals (pair_id, updated_at desc);

-- =====================================================================================
-- 2. proposal_revisions (contract §1): append-only (1-3 candidates per revision,
--    validated by the immutable public.proposal_candidates_valid() function used in a
--    CHECK constraint), UPDATE/DELETE-rejecting trigger, revision cap 20.
-- =====================================================================================

-- Immutable validator (usable in a CHECK constraint, unlike validate_origin_tz which must
-- query pg_timezone_names and so cannot be immutable): candidates is a jsonb array of 1-3
-- objects, each with EXACTLY the four keys {candidate_idx, starts_at_utc, ends_at_utc,
-- duration_min}; candidate_idx values are contiguous zero-based (0, 1, 2, ... in array
-- order); both instants parse; ends_at_utc > starts_at_utc; duration_min is an integer
-- between 1 and 1440; and duration_min exactly equals the instant interval in minutes
-- (contract §1 / §5). RPCs additionally call this directly (raising a friendly 22023) so a
-- client gets a clean error instead of a raw constraint-violation message; the CHECK is the
-- backstop against any other insertion path.
create or replace function public.proposal_candidates_valid(p_candidates jsonb)
returns boolean
language plpgsql
immutable
as $$
declare
    v_len          int;
    v_elem         jsonb;
    v_key_count    int;
    v_idx          int;
    v_start        timestamptz;
    v_end          timestamptz;
    v_duration     int;
    v_actual_mins  numeric;
    i              int;
begin
    if p_candidates is null or jsonb_typeof(p_candidates) is distinct from 'array' then
        return false;
    end if;

    v_len := jsonb_array_length(p_candidates);
    if v_len < 1 or v_len > 3 then
        return false;
    end if;

    for i in 0 .. v_len - 1 loop
        v_elem := p_candidates -> i;

        if v_elem is null or jsonb_typeof(v_elem) is distinct from 'object' then
            return false;
        end if;

        select count(*) into v_key_count from jsonb_object_keys(v_elem);
        if v_key_count <> 4
           or not (v_elem ? 'candidate_idx')
           or not (v_elem ? 'starts_at_utc')
           or not (v_elem ? 'ends_at_utc')
           or not (v_elem ? 'duration_min')
        then
            return false;
        end if;

        begin
            v_idx := (v_elem ->> 'candidate_idx')::int;
        exception when others then
            return false;
        end;
        if v_idx is distinct from i then
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

        begin
            v_duration := (v_elem ->> 'duration_min')::int;
        exception when others then
            return false;
        end;
        if v_duration is null or v_duration < 1 or v_duration > 1440 then
            return false;
        end if;

        v_actual_mins := extract(epoch from (v_end - v_start)) / 60.0;
        if v_actual_mins <> v_duration then
            return false;
        end if;
    end loop;

    return true;
end;
$$;

comment on function public.proposal_candidates_valid(jsonb) is 'Immutable structural validator for a proposal_revisions.candidates array (contract §1/§5): 1-3 exact-key objects, contiguous zero-based candidate_idx, end > start, duration_min in [1,1440] and exactly equal to the instant interval. Used both as a CHECK constraint and directly by the mutation RPCs.';

create table public.proposal_revisions (
    proposal_id uuid not null references public.time_lock_proposals (id),
    revision_no int not null check (revision_no between 1 and 20),
    author_id   uuid not null references auth.users (id),
    origin_tz   text not null,
    candidates  jsonb not null,
    created_at  timestamptz not null default now(),

    constraint proposal_revisions_pkey primary key (proposal_id, revision_no),
    constraint proposal_revisions_candidates_valid_check check (public.proposal_candidates_valid(candidates))
);

comment on table public.proposal_revisions is 'Append-only candidate history for a proposal; a counter inserts revision_no = current_revision + 1 and never touches an earlier row (contract §1).';

create trigger proposal_revisions_validate_origin_tz
    before insert or update on public.proposal_revisions
    for each row
    execute function public.validate_origin_tz();

-- Append-only: reject every UPDATE and DELETE, including by table owner/service_role, so
-- "no seat can accidentally overwrite history" holds even under a bug elsewhere (contract §1).
create or replace function public.proposal_revisions_immutable()
returns trigger
language plpgsql
as $$
begin
    raise exception 'proposal_revisions rows are append-only and cannot be updated or deleted'
        using errcode = '23514';
end;
$$;

comment on function public.proposal_revisions_immutable() is 'BEFORE UPDATE/DELETE trigger enforcing append-only proposal_revisions (contract §1).';

create trigger proposal_revisions_no_update
    before update on public.proposal_revisions
    for each row
    execute function public.proposal_revisions_immutable();

create trigger proposal_revisions_no_delete
    before delete on public.proposal_revisions
    for each row
    execute function public.proposal_revisions_immutable();

-- Now that proposal_revisions exists: link the winning revision from the proposal itself,
-- and the provenance pair from a shared_lock time_block, into a real revision row.
alter table public.time_lock_proposals
    add constraint time_lock_proposals_accepted_revision_fk
        foreign key (id, accepted_revision) references public.proposal_revisions (proposal_id, revision_no);

alter table public.time_blocks
    add constraint time_blocks_source_revision_fk
        foreign key (source_proposal_id, source_revision) references public.proposal_revisions (proposal_id, revision_no);

-- Scheduled-block overlap index (contract §1): backs both the conflict-hints RPC's
-- busy-window scan and any future planner query shaped owner+window.
create index time_blocks_owner_window_idx
    on public.time_blocks (owner_id, starts_at_utc, ends_at_utc);

-- Defense-in-depth backstop for the accept RPC's per-owner uniqueness: at most one
-- shared_lock block per (source_proposal_id, owner_id), i.e. a double-accept race can never
-- leave two blocks for the same person from the same proposal.
create unique index time_blocks_source_proposal_owner_unique
    on public.time_blocks (source_proposal_id, owner_id)
    where source_proposal_id is not null;

comment on index public.time_blocks_source_proposal_owner_unique is 'Backstop for accept-race safety (contract §1): at most one shared_lock block per proposal per owner, even if the RPC''s row lock were somehow bypassed.';

-- =====================================================================================
-- 3. proposal_responses (contract §1): one response per (proposal, revision, user);
--    accept carries only candidate_idx, decline carries neither pointer, counter carries
--    only counter_revision = revision_no + 1.
-- =====================================================================================

create table public.proposal_responses (
    id               uuid primary key default gen_random_uuid(),
    proposal_id      uuid not null,
    revision_no      int not null,
    user_id          uuid not null references auth.users (id),
    response         text not null,
    candidate_idx    smallint null,
    counter_revision int null,
    responded_at     timestamptz not null default now(),

    constraint proposal_responses_response_check check (response in ('accept', 'decline', 'counter')),
    constraint proposal_responses_shape_check check (
        (response = 'accept'  and candidate_idx is not null and counter_revision is null) or
        (response = 'decline' and candidate_idx is null     and counter_revision is null) or
        (response = 'counter' and candidate_idx is null     and counter_revision = revision_no + 1)
    ),
    constraint proposal_responses_revision_fk
        foreign key (proposal_id, revision_no) references public.proposal_revisions (proposal_id, revision_no),
    constraint proposal_responses_unique_per_user_revision unique (proposal_id, revision_no, user_id)
);

comment on table public.proposal_responses is 'One row per (proposal, revision, user) response; a counter response and its new revision are inserted in the same RPC transaction (contract §1).';

-- "Response lookup" index (contract §1), distinct from the per-(proposal,revision,user)
-- uniqueness index above: a per-user response history / audit scan.
create index proposal_responses_user_idx
    on public.proposal_responses (user_id, responded_at desc);

-- =====================================================================================
-- 4. commitments (contract §1 / ADR-010): one row per accepted participant per proposal;
--    each links to exactly one owner-private shared_lock time_block.
-- =====================================================================================

create table public.commitments (
    id                  uuid primary key default gen_random_uuid(),
    proposal_id         uuid not null references public.time_lock_proposals (id),
    user_id             uuid not null references auth.users (id),
    state               text not null default 'active',
    created_from_revision int not null,
    candidate_idx       smallint not null,
    time_block_id       uuid not null unique references public.time_blocks (id),
    version             bigint not null default 1 check (version > 0),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    withdrawn_at        timestamptz null,
    completed_at        timestamptz null,

    constraint commitments_state_check check (state in ('active', 'withdrawn', 'completed')),
    constraint commitments_withdrawn_at_iff_check check ((state = 'withdrawn') = (withdrawn_at is not null)),
    constraint commitments_completed_at_iff_check check ((state = 'completed') = (completed_at is not null)),
    constraint commitments_unique_per_proposal_user unique (proposal_id, user_id),
    constraint commitments_revision_fk
        foreign key (proposal_id, created_from_revision) references public.proposal_revisions (proposal_id, revision_no)
);

comment on table public.commitments is 'Per-participant stake in an accepted proposal (ADR-010 §1-2): independent state, one commitment <-> one owner-private shared_lock time_block.';

create trigger commitments_set_updated_at
    before update on public.commitments
    for each row
    execute function public.set_updated_at();

create index commitments_user_state_idx
    on public.commitments (user_id, state);

-- =====================================================================================
-- 5. RLS + grants (contract §1): no direct client writes on any Stage-2 table; active
--    pair members may SELECT proposals/revisions/responses for their own pair (reusing
--    public.is_active_pair_member from Stage 1); commitments (and time_blocks, unchanged)
--    stay owner-only. Former members lose all four immediately -- is_active_pair_member
--    already re-evaluates membership on every call, so there is nothing Stage-2-specific
--    to add for revocation.
-- =====================================================================================

alter table public.time_lock_proposals enable row level security;
revoke all on table public.time_lock_proposals from anon, authenticated;
grant select on table public.time_lock_proposals to authenticated;

create policy time_lock_proposals_select_active_member on public.time_lock_proposals
    for select to authenticated
    using (public.is_active_pair_member(pair_id));

alter table public.proposal_revisions enable row level security;
revoke all on table public.proposal_revisions from anon, authenticated;
grant select on table public.proposal_revisions to authenticated;

create policy proposal_revisions_select_active_member on public.proposal_revisions
    for select to authenticated
    using (
        exists (
            select 1 from public.time_lock_proposals p
            where p.id = proposal_revisions.proposal_id
              and public.is_active_pair_member(p.pair_id)
        )
    );

alter table public.proposal_responses enable row level security;
revoke all on table public.proposal_responses from anon, authenticated;
grant select on table public.proposal_responses to authenticated;

create policy proposal_responses_select_active_member on public.proposal_responses
    for select to authenticated
    using (
        exists (
            select 1 from public.time_lock_proposals p
            where p.id = proposal_responses.proposal_id
              and public.is_active_pair_member(p.pair_id)
        )
    );

alter table public.commitments enable row level security;
revoke all on table public.commitments from anon, authenticated;
grant select on table public.commitments to authenticated;

create policy commitments_select_own on public.commitments
    for select to authenticated
    using (user_id = (select auth.uid()));

comment on policy commitments_select_own on public.commitments is 'Commitments stay owner-only (contract §1): a peer''s commitment/time_block is never read directly, only via proposal projections (which surface no private block content).';
