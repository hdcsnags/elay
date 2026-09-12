-- Stage 5: retention + hardening -- SQL lane (contracts/stage5-retention-hardening.md -- FROZEN;
-- council/stage5-hardening-opus.md sA + council/stage5-retention-gemini.md sB restated there --
-- binding full text). Seat A7 (SQL): this migration + supabase/tests/0023_stage5_retention_test.sql
-- + contracts/fixtures/session-outcome-*.json only.
--
-- Scope (contract Adopted bullets + lead amendments):
--   1. session_outcomes (never columns on commitments/time_lock_proposals -- failure mode 1):
--      owner-only RLS all four verbs, outcome CHECK, actual_minutes null-iff + 1..1440 bounds,
--      one outcome per (owner, block) re-recordable with version bump, composite owner-aware FK
--      into time_blocks (requires the additive time_blocks unique(id, owner_id) below),
--      shared_with_pair inert-by-CHECK forward column (Phase-1 private-only pattern, matching
--      goals/tasks/time_blocks). PRIVATE, NOT SHARED (spec S7 "do not infer sharing";
--      completion-fact sharing already exists via proposal status).
--   2. rpc_record_session_outcome (elapsed-only: a future block -> 22023; action-tagged
--      receipts; cross-action guard) and rpc_next_time_suggestion (server-side median of the
--      LAST FIVE matching actual_minutes, emitted only at sample_size >= 2, else null -- the
--      honesty threshold this seat pins in pgTAP).
--   3. Failure modes (brief-10 SCOPE RULING + contract lead amendment 2, binding as written):
--      - commitments-columns forbidden: session_outcomes is a SEPARATE table; the existing
--        commitment_to_jsonb / time_lock_proposal_to_jsonb projectors are untouched (0023
--        re-pins their exact key sets, 0017-style, and grep-proofs their function bodies).
--      - no new realtime event or auto-proposal from `rescheduled`: rpc_record_session_outcome
--        below calls realtime.send NOWHERE and never inserts into time_lock_proposals --
--        recording a `rescheduled` outcome is purely a fact-of-record, not an action (0023
--        grep-proofs the function body for both).
--      - printlns replaced never deleted: not applicable to this SQL-only seat (D2's grant).
--
-- UNVERIFIED / judgment-call flags (also called out inline at point of use; lead/verifier
-- should re-check these against the freeze -- the full sA/sB lane prose was delivered
-- in-session and is not re-derivable from the repo alone):
--   1. actual_minutes null-iff rule: this seat reads it as "not null iff outcome measures a
--      real elapsed duration" -- i.e. required for ran_long/finished_early (something was
--      actually timed), forbidden for didnt_happen (nothing happened, no duration to record)
--      and rescheduled (the session never ran in this slot; a future re-proposal is a NEW
--      time_lock_proposal, never inferred here per the failure-mode guard above).
--   2. rpc_record_session_outcome takes NO p_expected_version parameter (the contract's
--      4-parameter signature has none) -- re-recording is a plain upsert-with-version-bump,
--      not an optimistic-concurrency check from the client. There is therefore no {"outcome":
--      "conflict",...} branch for this RPC (matching the Stage-2 idempotency-receipt policy:
--      only 'applied' is ever stored/returned).
--   3. "block must be caller-owned else 42501" is read to also cover "block does not exist at
--      all" -- folded into the SAME 42501 (house pattern: fn_sync_external_busy /
--      rpc_upsert_external_busy fold "not mine" and "not found" into one shape, stage4
--      migration comment). A caller cannot distinguish "not yours" from "never existed".
--   4. rpc_next_time_suggestion's "LAST FIVE matching outcomes" orders by the matching
--      time_blocks.starts_at_utc DESC (the most recent real sessions), not by
--      session_outcomes.created_at (when it was logged) -- reads truer to "next time" framing
--      (council/stage5-retention-gemini.md sB 2.2: "Last time ran 20m long"), with
--      updated_at DESC as a deterministic tiebreak.
--   5. The median is rounded to the nearest integer minute (round(::numeric), half rounds
--      away from zero) since actual_minutes/suggested_minutes are both integers on the wire;
--      the contract does not specify a rounding rule.
--   6. p_task_id takes precedence over p_title_key when both are supplied (contract text:
--      "...by task_id when given else by lower(title)=lower(p_title_key)").
--   7. session_outcomes gets full owner-only CRUD table grants (contract: "owner-only RLS all
--      four verbs") -- like goals/tasks/time_blocks, NOT the stage4 external_busy "zero write
--      grants, RPC-only" pattern. rpc_record_session_outcome remains the officially supported
--      client path (elapsed check, upsert-with-version-bump, receipt), but direct table access
--      is not blocked, matching every other planner table's established convention.
--
-- PL/pgSQL quirks avoided (recorded in STATE / prior seats' reports, re-checked here): no
-- multi-column composite INTO trap (every INTO target below is a single row/scalar -- the two-
-- column `into v_sample_size, v_median` is two independent scalars, not a composite); no
-- left(bytea, n) (no bytea column in this migration at all); every throws_ok in the companion
-- test file is called with the full 4-arg form (sql, sqlstate, message-or-null, description) --
-- never the 3-arg form that silently reinterprets the description as an expected message; the
-- companion test's auth.users inserts rely on handle_new_user firing (profile_bootstrap
-- migration) and then explicitly upsert public.profiles with on conflict do update, matching
-- 0017/0022's pattern, rather than assuming the trigger's fallback display_name is good enough.
-- House grant discipline (20260912200000's discipline, the latest before this migration):
-- EVERY new object below gets an EXPLICIT revoke from public/anon[/authenticated] regardless of
-- whether it is a pure read-projector -- and service_role is NEVER claimed to get zero rows
-- anywhere (it bypasses RLS entirely; no revoke/grant statement here mentions it).

-- =====================================================================================
-- 0. time_blocks additive alter (contract): a composite owner-aware FK from session_outcomes
--    needs a unique target on (id, owner_id) -- 20260911050400_time_blocks.sql shipped without
--    one (only a bare PK on id). Purely additive; does not touch any existing column/policy.
-- =====================================================================================

alter table public.time_blocks
    add constraint time_blocks_id_owner_id_unique unique (id, owner_id);

comment on constraint time_blocks_id_owner_id_unique on public.time_blocks is 'Stage 5 addition: lets session_outcomes carry a composite owner-aware FK (time_block_id, owner_id) -> time_blocks (id, owner_id), the same pattern goals/tasks already expose for their own composite FKs.';

-- =====================================================================================
-- 1. session_outcomes (contract): owner-only, private, one row per (owner, block),
--    re-recordable with a version bump. NEVER columns on commitments/time_lock_proposals
--    (failure mode 1).
-- =====================================================================================

create table public.session_outcomes (
    id               uuid primary key default gen_random_uuid(),
    owner_id         uuid not null references auth.users (id) on delete cascade,
    time_block_id    uuid not null,
    outcome          text not null,
    actual_minutes   int null,
    shared_with_pair boolean not null default false,
    version          bigint not null default 1,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),

    constraint session_outcomes_outcome_check check (
        outcome in ('ran_long', 'finished_early', 'didnt_happen', 'rescheduled')
    ),
    -- UNVERIFIED #1: not-null iff a real duration was measured (ran_long/finished_early);
    -- null for didnt_happen/rescheduled (nothing to time).
    constraint session_outcomes_actual_minutes_null_iff_check check (
        (outcome in ('ran_long', 'finished_early')) = (actual_minutes is not null)
    ),
    constraint session_outcomes_actual_minutes_bounds_check check (
        actual_minutes is null or actual_minutes between 1 and 1440
    ),
    -- One outcome per (owner, block); rpc_record_session_outcome upserts on this key,
    -- bumping version on re-record instead of erroring.
    constraint session_outcomes_owner_block_unique unique (owner_id, time_block_id),
    -- Phase-1 forward column (contract): sharing is inert until a later stage drops this pin.
    constraint session_outcomes_shared_with_pair_check check (shared_with_pair = false),
    -- Composite owner-aware FK (contract): a time_block_id can only reference a time_block this
    -- same owner_id already owns -- cross-owner linkage is impossible even before RLS runs.
    -- on delete cascade (not set null): the same reasoning as tasks.goal_id/time_blocks.task_id
    -- -- set null would also null the NOT NULL owner_id column and raise on every block delete
    -- that still has a recorded outcome.
    constraint session_outcomes_block_owner_fk foreign key (time_block_id, owner_id)
        references public.time_blocks (id, owner_id) on delete cascade
);

comment on table public.session_outcomes is 'Contract: plan-vs-actual retention loop, owner-only and PRIVATE (spec S7 "do not infer sharing" -- completion-fact sharing already exists via proposal status, so this table never joins into a pair projection). One row per (owner_id, time_block_id), re-recordable via rpc_record_session_outcome''s upsert (version bump on re-record). Never columns on commitments or time_lock_proposals (failure mode 1) -- a wholly separate table so no pair-facing projector can accidentally pick up an outcome key.';
comment on column public.session_outcomes.outcome is 'Contract-fixed enum: ran_long | finished_early | didnt_happen | rescheduled. Recording ''rescheduled'' here is purely a fact-of-record -- it creates no new time_lock_proposals row and sends no realtime event (failure mode 2).';
comment on column public.session_outcomes.actual_minutes is 'Null-iff outcome in (ran_long, finished_early) -- see session_outcomes_actual_minutes_null_iff_check (UNVERIFIED #1) -- and, when present, 1..1440 (session_outcomes_actual_minutes_bounds_check), matching time_blocks'' own 1min..24h duration bound.';
comment on column public.session_outcomes.shared_with_pair is 'Forward column for a possible future pair-sharing phase; pinned false in Phase 1 by session_outcomes_shared_with_pair_check (contract: private, not shared -- spec S7 "do not infer sharing"). Inert until a later migration drops the pin, matching the goals/tasks/time_blocks visibility/household_id Phase-1 pattern.';
comment on constraint session_outcomes_shared_with_pair_check on public.session_outcomes is 'Phase 1 guard: shared_with_pair stays false until a later stage''s migration drops this pin (forward column, contract).';

create trigger session_outcomes_set_updated_at
    before update on public.session_outcomes
    for each row
    execute function public.set_updated_at();

alter table public.session_outcomes enable row level security;

-- Contract: owner-only RLS, ALL FOUR verbs directly grant-reachable (matching goals/tasks/
-- time_blocks, not stage4's external_busy "zero write grants" pattern -- UNVERIFIED #7).
revoke all on table public.session_outcomes from anon, authenticated;
grant select, insert, update, delete on table public.session_outcomes to authenticated;

create policy session_outcomes_select_own on public.session_outcomes
    for select to authenticated
    using (owner_id = (select auth.uid()));

create policy session_outcomes_insert_own on public.session_outcomes
    for insert to authenticated
    with check (owner_id = (select auth.uid()));

create policy session_outcomes_update_own on public.session_outcomes
    for update to authenticated
    using (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

create policy session_outcomes_delete_own on public.session_outcomes
    for delete to authenticated
    using (owner_id = (select auth.uid()));

-- =====================================================================================
-- 2. Wire-format serializer (contract: "a projector that includes planned/actual/
--    delta_minutes derived"). Takes the matching time_blocks row alongside the outcome row
--    since planned_minutes/delta_minutes are derived from the block's own duration, which
--    session_outcomes itself does not store.
-- =====================================================================================

create or replace function public.session_outcome_to_jsonb(o public.session_outcomes, b public.time_blocks)
returns jsonb
language sql
stable
set search_path = ''
as $$
    select jsonb_build_object(
        'id', o.id,
        'owner_id', o.owner_id,
        'time_block_id', o.time_block_id,
        'outcome', o.outcome,
        'planned_minutes', round((extract(epoch from (b.ends_at_utc - b.starts_at_utc)) / 60)::numeric)::int,
        'actual_minutes', o.actual_minutes,
        'delta_minutes', case
            when o.actual_minutes is not null
                then o.actual_minutes - round((extract(epoch from (b.ends_at_utc - b.starts_at_utc)) / 60)::numeric)::int
            else null
        end,
        'shared_with_pair', o.shared_with_pair,
        'version', o.version,
        'created_at', public.iso_utc(o.created_at),
        'updated_at', public.iso_utc(o.updated_at)
    );
$$;

comment on function public.session_outcome_to_jsonb(public.session_outcomes, public.time_blocks) is 'Wire projector for one session_outcomes row (contract: "a projector that includes planned/actual/delta_minutes derived"). planned_minutes comes from the paired time_blocks row''s own duration; delta_minutes = actual_minutes - planned_minutes, null whenever actual_minutes is null (didnt_happen/rescheduled). Not grant-reachable -- called only from inside rpc_record_session_outcome.';

revoke all on function public.session_outcome_to_jsonb(public.session_outcomes, public.time_blocks) from public, anon, authenticated;

-- =====================================================================================
-- 3. rpc_record_session_outcome (contract): authenticated; caller from auth.uid(); block
--    must be caller-owned else 42501; elapsed-only (ends_at_utc > now() -> 22023 'session
--    has not elapsed'); upsert semantics on re-record with version bump; action-tagged
--    receipt (cross-action 22023). NO realtime.send, NO time_lock_proposals write anywhere
--    in this function body (failure mode 2 -- grep-proofed in 0023).
-- =====================================================================================

create or replace function public.rpc_record_session_outcome(
    p_operation_id   uuid,
    p_time_block_id  uuid,
    p_outcome        text,
    p_actual_minutes int default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller  uuid := auth.uid();
    v_receipt public.mutation_receipts;
    v_block   public.time_blocks;
    v_row     public.session_outcomes;
    v_result  jsonb;
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
        if (v_receipt.result ->> 'action') is distinct from 'record_session_outcome' then
            raise exception 'operation_id already used for a different action' using errcode = '22023';
        end if;
        return v_receipt.result;
    end if;

    if p_time_block_id is null then
        raise exception 'p_time_block_id is required' using errcode = '22004';
    end if;
    if p_outcome is null or p_outcome not in ('ran_long', 'finished_early', 'didnt_happen', 'rescheduled') then
        raise exception 'p_outcome must be one of ran_long, finished_early, didnt_happen, rescheduled' using errcode = '22023';
    end if;
    -- Mirrors session_outcomes_actual_minutes_null_iff_check up front, so a bad combination
    -- surfaces as a clean 22023 instead of a raw constraint-violation errcode.
    if (p_outcome in ('ran_long', 'finished_early')) is distinct from (p_actual_minutes is not null) then
        raise exception 'p_actual_minutes is required for ran_long/finished_early and forbidden for didnt_happen/rescheduled' using errcode = '22023';
    end if;
    if p_actual_minutes is not null and p_actual_minutes not between 1 and 1440 then
        raise exception 'p_actual_minutes must be between 1 and 1440' using errcode = '22023';
    end if;

    -- UNVERIFIED #3: folds "block does not exist" and "block belongs to someone else" into
    -- the same 42501 (house non-disclosure pattern).
    select * into v_block
    from public.time_blocks
    where id = p_time_block_id and owner_id = v_caller
    for update;

    if not found then
        raise exception 'time block is not caller-owned' using errcode = '42501';
    end if;

    -- Elapsed-only (contract): a session cannot be recorded before its winning interval ends.
    if v_block.ends_at_utc > now() then
        raise exception 'session has not elapsed' using errcode = '22023';
    end if;

    -- Upsert semantics on re-record (contract): the SAME (owner_id, time_block_id) can be
    -- recorded again (the user changes their mind, or corrects a mistap) -- version bumps,
    -- nothing else about the row's identity changes. `session_outcomes.version` (unqualified
    -- by EXCLUDED) refers to the pre-existing row, not the proposed insert.
    insert into public.session_outcomes (owner_id, time_block_id, outcome, actual_minutes)
    values (v_caller, p_time_block_id, p_outcome, p_actual_minutes)
    on conflict (owner_id, time_block_id) do update
    set outcome        = excluded.outcome,
        actual_minutes = excluded.actual_minutes,
        version        = public.session_outcomes.version + 1
    returning * into v_row;

    v_result := jsonb_build_object(
        'outcome', 'applied',
        'action', 'record_session_outcome',
        'session_outcome', public.session_outcome_to_jsonb(v_row, v_block)
    );

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_row.version, v_result);

    return v_result;
end;
$$;

comment on function public.rpc_record_session_outcome(uuid, uuid, text, int) is 'Contract: records (or re-records, with a version bump) the caller''s own outcome for their own, already-elapsed time_blocks row. errcodes: 28000 unauthenticated, 22004 missing operation_id/time_block_id, 22023 bad outcome/actual_minutes combination or bounds or "session has not elapsed" (ends_at_utc > now()), 42501 block not caller-owned (also covers "does not exist", UNVERIFIED #3). No p_expected_version (UNVERIFIED #2) -- always upsert-with-version-bump, never {"outcome":"conflict"}. Emits no realtime event and writes no time_lock_proposals row under any outcome, including rescheduled (failure mode 2).';

revoke all on function public.rpc_record_session_outcome(uuid, uuid, text, int) from public, anon;
grant execute on function public.rpc_record_session_outcome(uuid, uuid, text, int) to authenticated;

-- =====================================================================================
-- 4. rpc_next_time_suggestion (contract): authenticated; median of the LAST FIVE matching
--    actual_minutes (task_id when given, else lower(title)=lower(p_title_key)); null
--    suggestion when sample_size < 2. Pure read -- no operation_id, no mutation_receipts row
--    (matches rpc_my_availability_sources' shape: a derived view, not a mutation).
-- =====================================================================================

create or replace function public.rpc_next_time_suggestion(
    p_task_id   uuid default null,
    p_title_key text default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller      uuid := auth.uid();
    v_basis       text;
    v_sample_size int;
    v_median      double precision;
    v_suggested   int;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    -- UNVERIFIED #6: task_id wins over title_key when both are supplied.
    v_basis := case
        when p_task_id is not null then 'task'
        when p_title_key is not null then 'title'
        else null
    end;

    -- UNVERIFIED #4: "last five" ordered by the matching block's own starts_at_utc (the most
    -- recent real sessions), updated_at as a deterministic tiebreak; only rows with a real
    -- measured actual_minutes (ran_long/finished_early) ever enter the pool.
    with matched as (
        select so.actual_minutes
        from public.session_outcomes so
        join public.time_blocks b
            on b.id = so.time_block_id and b.owner_id = so.owner_id
        where so.owner_id = v_caller
          and so.actual_minutes is not null
          and (
              (p_task_id is not null and b.task_id = p_task_id)
              or (p_task_id is null and p_title_key is not null and lower(btrim(b.title)) = lower(btrim(p_title_key)))
          )
        order by b.starts_at_utc desc, so.updated_at desc, so.id desc
        limit 5
    )
    select count(*), percentile_cont(0.5) within group (order by actual_minutes)
    into v_sample_size, v_median
    from matched;

    -- UNVERIFIED #5: rounded to the nearest whole minute (numeric round, half away from zero).
    v_suggested := case when v_sample_size >= 2 then round(v_median::numeric)::int else null end;

    return jsonb_build_object(
        'suggested_minutes', v_suggested,
        'sample_size', v_sample_size,
        'basis', v_basis
    );
end;
$$;

comment on function public.rpc_next_time_suggestion(uuid, text) is 'Contract: server-side median of the caller''s own LAST FIVE matching outcomes'' actual_minutes (task_id when given, else lower(title)=lower(p_title_key), UNVERIFIED #6), null suggestion below the sample_size>=2 honesty threshold. Caller-scoped by construction (so.owner_id = auth.uid()) -- a peer''s outcomes, however numerous, can never enter the pool.';

revoke all on function public.rpc_next_time_suggestion(uuid, text) from public, anon;
grant execute on function public.rpc_next_time_suggestion(uuid, text) to authenticated;
