-- Stage 2: time-lock negotiation -- RPCs, sweep, and Realtime (contracts/stage2-timelock.md,
-- council/stage2-timelock-sol.md §2-§3). Depends on 20260912130000_stage2_timelock.sql.
--
-- House conventions reused throughout (Stage 1 lesson, contract §1 preamble): every
-- mutation RPC is SECURITY DEFINER, `set search_path = ''`, checks auth.uid() explicitly,
-- is REVOKE ALL FROM PUBLIC / GRANT EXECUTE TO authenticated, locks the proposal row FOR
-- UPDATE before touching it, and uses the shared mutation_receipts ledger keyed by
-- (owner_id, operation_id) with an "action" tag inside the stored/returned jsonb so a
-- replayed operation_id against a *different* action raises 22023 (cross-action replay
-- rejection) instead of silently returning a mismatched cached shape. Realtime uses
-- `realtime.send(payload, event, topic, private)` on the pair's EXISTING topic (Stage 1
-- reserves and rotates it) -- Stage 2 adds no new topic or channel machinery.
--
-- Idempotency-receipt policy (matches rpc_upsert_capture / rpc_delete_capture / rpc_upsert_
-- time_block, NOT the pairing RPCs' invalid_or_unavailable-is-also-a-receipt choice): only
-- an 'applied' outcome is stored as a receipt. A non-mutating {"outcome":"conflict",...}
-- (stale revision, wrong status, expired-out-from-under-you) is a normal, expected,
-- retriable race -- the client is expected to refetch and retry with a FRESH operation_id,
-- so there is nothing to protect by persisting it, and every call with the same operation_id
-- against unchanged server state returns the identical conflict anyway.

-- =====================================================================================
-- 0. Shared helpers: wire-format serializers and the pair-id -> topic lookup RPC bodies
--    need (contract §3's topic is `pair:<pair_uuid>:<channel_generation>`, identical to
--    Stage 1's `caller_active_pair_topic()` but addressed by an arbitrary pair_id since
--    these functions act on the proposal's pair, not necessarily reached through the
--    caller's own live membership row at call time -- e.g. the sweep has no caller).
-- =====================================================================================

create or replace function public.pair_topic(p_pair_id uuid)
returns text
language sql
stable
set search_path = ''
as $$
    select 'pair:' || p.id::text || ':' || p.channel_generation::text
    from public.pairs p
    where p.id = p_pair_id;
$$;

comment on function public.pair_topic(uuid) is 'Stage 2 helper: the current Broadcast topic for an arbitrary pair_id. Not grant-reachable -- called only from inside SECURITY DEFINER RPC/trigger bodies below (their bypass-RLS context is what lets them read public.pairs).';

revoke all on function public.pair_topic(uuid) from public, anon, authenticated;

create or replace function public.proposal_revision_to_jsonb(r public.proposal_revisions)
returns jsonb
language sql
stable
as $$
    select jsonb_build_object(
        'revision_no', r.revision_no,
        'author_id', r.author_id,
        'origin_tz', r.origin_tz,
        'candidates', r.candidates,
        'created_at', public.iso_utc(r.created_at)
    );
$$;

create or replace function public.proposal_response_to_jsonb(resp public.proposal_responses)
returns jsonb
language sql
stable
as $$
    select jsonb_build_object(
        'id', resp.id,
        'revision_no', resp.revision_no,
        'user_id', resp.user_id,
        'response', resp.response,
        'candidate_idx', resp.candidate_idx,
        'counter_revision', resp.counter_revision,
        'responded_at', public.iso_utc(resp.responded_at)
    );
$$;

create or replace function public.commitment_to_jsonb(c public.commitments)
returns jsonb
language sql
stable
as $$
    select jsonb_build_object(
        'id', c.id,
        'proposal_id', c.proposal_id,
        'user_id', c.user_id,
        'state', c.state,
        'created_from_revision', c.created_from_revision,
        'candidate_idx', c.candidate_idx,
        'time_block_id', c.time_block_id,
        'version', c.version,
        'created_at', public.iso_utc(c.created_at),
        'updated_at', public.iso_utc(c.updated_at),
        'withdrawn_at', public.iso_utc(c.withdrawn_at),
        'completed_at', public.iso_utc(c.completed_at)
    );
$$;

-- Core proposal shape shared by every RPC's response (contract §2/§4): every key always
-- present (null where inapplicable) so the frozen Kotlin ProposalState surface has a
-- stable shape to deserialize regardless of status.
create or replace function public.time_lock_proposal_to_jsonb(p public.time_lock_proposals)
returns jsonb
language sql
stable
as $$
    select jsonb_build_object(
        'id', p.id,
        'pair_id', p.pair_id,
        'creator_id', p.creator_id,
        'title', p.title,
        'status', p.status,
        'response_deadline', public.iso_utc(p.response_deadline),
        'origin_tz', p.origin_tz,
        'current_revision', p.current_revision,
        'accepted_revision', p.accepted_revision,
        'accepted_candidate_idx', p.accepted_candidate_idx,
        'version', p.version,
        'created_at', public.iso_utc(p.created_at),
        'updated_at', public.iso_utc(p.updated_at),
        'accepted_at', public.iso_utc(p.accepted_at),
        'declined_at', public.iso_utc(p.declined_at),
        'expired_at', public.iso_utc(p.expired_at),
        'cancelled_at', public.iso_utc(p.cancelled_at),
        'completed_at', public.iso_utc(p.completed_at)
    );
$$;

-- Sanitized invalidation-hint payload shared by every event this migration publishes
-- (contract §3: pair.proposal_created.v1 / pair.proposal_updated.v1 -- exact keys, no
-- title, candidate instant, conflict, response detail, or block content).
create or replace function public.proposal_broadcast_payload(p public.time_lock_proposals)
returns jsonb
language sql
stable
as $$
    select jsonb_build_object(
        'pair_id', p.pair_id,
        'proposal_id', p.id,
        'status', p.status,
        'current_revision', p.current_revision,
        'version', p.version
    );
$$;

-- =====================================================================================
-- 1. rpc_create_proposal (contract §2).
-- =====================================================================================

create or replace function public.rpc_create_proposal(
    p_operation_id      uuid,
    p_title             text,
    p_origin_tz         text,
    p_response_deadline timestamptz,
    p_candidates        jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller  uuid := auth.uid();
    v_receipt public.mutation_receipts;
    v_pair_id uuid;
    v_active_count int;
    v_proposal public.time_lock_proposals;
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
        if (v_receipt.result ->> 'action') is distinct from 'create_proposal' then
            raise exception 'operation_id already used for a different action' using errcode = '22023';
        end if;
        return v_receipt.result;
    end if;

    if p_title is null or char_length(p_title) not between 1 and 200 then
        raise exception 'p_title must be between 1 and 200 characters' using errcode = '22023';
    end if;
    if p_response_deadline is null
       or p_response_deadline < now() + interval '5 minutes'
       or p_response_deadline > now() + interval '7 days'
    then
        raise exception 'p_response_deadline must be between 5 minutes and 7 days from now' using errcode = '22023';
    end if;
    if not public.proposal_candidates_valid(p_candidates) then
        raise exception 'p_candidates must be 1-3 valid, contiguously-indexed candidates' using errcode = '22023';
    end if;

    -- Derive the caller's active, two-member pair (contract §2: create requires a partner).
    select pm.pair_id into v_pair_id
    from public.pair_members pm
    where pm.user_id = v_caller and pm.left_at is null;

    if v_pair_id is null then
        raise exception 'caller has no active pair' using errcode = '42501';
    end if;

    select count(*) into v_active_count
    from public.pair_members
    where pair_id = v_pair_id and left_at is null;

    if v_active_count <> 2 then
        raise exception 'caller''s pair does not yet have two active members' using errcode = '42501';
    end if;

    insert into public.time_lock_proposals (pair_id, creator_id, title, status, response_deadline, origin_tz)
    values (v_pair_id, v_caller, p_title, 'proposed', p_response_deadline, p_origin_tz)
    returning * into v_proposal;

    insert into public.proposal_revisions (proposal_id, revision_no, author_id, origin_tz, candidates)
    values (v_proposal.id, 1, v_caller, p_origin_tz, p_candidates);

    v_result := jsonb_build_object(
        'outcome', 'applied',
        'action', 'create_proposal',
        'proposal', public.time_lock_proposal_to_jsonb(v_proposal),
        'revision', (select public.proposal_revision_to_jsonb(r) from public.proposal_revisions r
                     where r.proposal_id = v_proposal.id and r.revision_no = 1)
    );

    perform realtime.send(
        public.proposal_broadcast_payload(v_proposal),
        'pair.proposal_created.v1',
        public.pair_topic(v_proposal.pair_id),
        true
    );

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_proposal.version, v_result);

    return v_result;
end;
$$;

revoke all on function public.rpc_create_proposal(uuid, text, text, timestamptz, jsonb) from public, anon;
grant execute on function public.rpc_create_proposal(uuid, text, text, timestamptz, jsonb) to authenticated;

-- =====================================================================================
-- 2. rpc_respond_proposal (contract §2): accept / decline / counter in one RPC, keyed by
--    p_response. Action tags are response-specific (accept_proposal / decline_proposal /
--    counter_proposal) so the cross-action replay guard also catches, e.g., an
--    operation_id first used to decline and then replayed against a counter call.
-- =====================================================================================

create or replace function public.rpc_respond_proposal(
    p_operation_id      uuid,
    p_proposal_id       uuid,
    p_expected_revision int,
    p_response          text,
    p_candidate_idx     int default null,
    p_new_origin_tz     text default null,
    p_new_deadline      timestamptz default null,
    p_new_candidates    jsonb default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller    uuid := auth.uid();
    v_action    text;
    v_receipt   public.mutation_receipts;
    v_proposal  public.time_lock_proposals;
    v_partner   uuid;
    v_candidate jsonb;
    v_new_revision int;
    v_block_caller_id  uuid;
    v_block_partner_id uuid;
    v_commitment_caller_id  uuid;
    v_commitment_partner_id uuid;
    v_result    jsonb;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_operation_id is null then
        raise exception 'p_operation_id is required' using errcode = '22004';
    end if;
    if p_response not in ('accept', 'decline', 'counter') then
        raise exception 'p_response must be accept, decline, or counter' using errcode = '22023';
    end if;

    v_action := p_response || '_proposal';

    select * into v_receipt
    from public.mutation_receipts
    where owner_id = v_caller and operation_id = p_operation_id;

    if found then
        if (v_receipt.result ->> 'action') is distinct from v_action then
            raise exception 'operation_id already used for a different action' using errcode = '22023';
        end if;
        return v_receipt.result;
    end if;

    select * into v_proposal from public.time_lock_proposals where id = p_proposal_id for update;
    if not found then
        raise exception 'proposal not found' using errcode = 'P0002';
    end if;

    if not exists (
        select 1 from public.pair_members pm
        where pm.pair_id = v_proposal.pair_id and pm.user_id = v_caller and pm.left_at is null
    ) then
        raise exception 'caller is not an active member of this proposal''s pair' using errcode = '42501';
    end if;

    -- Expire-before-mutate (contract §2): every mutation RPC repeats this check so sweep
    -- scheduling is never a correctness dependency.
    if v_proposal.status in ('proposed', 'countered') and v_proposal.response_deadline <= now() then
        update public.time_lock_proposals
        set status = 'expired', expired_at = now(), version = version + 1, updated_at = now()
        where id = v_proposal.id
        returning * into v_proposal;

        perform realtime.send(
            public.proposal_broadcast_payload(v_proposal),
            'pair.proposal_updated.v1',
            public.pair_topic(v_proposal.pair_id),
            true
        );
    end if;

    -- Only the non-author of the CURRENT revision may respond to it (contract §2).
    if exists (
        select 1 from public.proposal_revisions r
        where r.proposal_id = v_proposal.id
          and r.revision_no = v_proposal.current_revision
          and r.author_id = v_caller
    ) then
        raise exception 'the author of the current revision cannot respond to it' using errcode = '42501';
    end if;

    -- Stale revision / wrong status: non-mutating conflict, not an exception (contract §2).
    if p_expected_revision is distinct from v_proposal.current_revision
       or v_proposal.status not in ('proposed', 'countered')
    then
        return jsonb_build_object(
            'outcome', 'conflict',
            'current_revision', v_proposal.current_revision,
            'status', v_proposal.status
        );
    end if;

    if p_response = 'decline' then
        insert into public.proposal_responses (proposal_id, revision_no, user_id, response)
        values (v_proposal.id, v_proposal.current_revision, v_caller, 'decline');

        update public.time_lock_proposals
        set status = 'declined', declined_at = now(), version = version + 1, updated_at = now()
        where id = v_proposal.id
        returning * into v_proposal;

        v_result := jsonb_build_object(
            'outcome', 'applied',
            'action', v_action,
            'proposal', public.time_lock_proposal_to_jsonb(v_proposal)
        );

        perform realtime.send(
            public.proposal_broadcast_payload(v_proposal),
            'pair.proposal_updated.v1',
            public.pair_topic(v_proposal.pair_id),
            true
        );

    elsif p_response = 'accept' then
        if p_candidate_idx is null then
            raise exception 'p_candidate_idx is required to accept' using errcode = '22004';
        end if;

        select r.candidates -> p_candidate_idx into v_candidate
        from public.proposal_revisions r
        where r.proposal_id = v_proposal.id and r.revision_no = v_proposal.current_revision;

        if v_candidate is null then
            raise exception 'p_candidate_idx is out of range for the current revision' using errcode = '22023';
        end if;

        select pm.user_id into v_partner
        from public.pair_members pm
        where pm.pair_id = v_proposal.pair_id and pm.user_id <> v_caller and pm.left_at is null;

        if v_partner is null then
            raise exception 'proposal''s pair no longer has an active partner' using errcode = '42501';
        end if;

        insert into public.proposal_responses (proposal_id, revision_no, user_id, response, candidate_idx)
        values (v_proposal.id, v_proposal.current_revision, v_caller, 'accept', p_candidate_idx);

        update public.time_lock_proposals
        set status = 'accepted',
            accepted_revision = v_proposal.current_revision,
            accepted_candidate_idx = p_candidate_idx,
            accepted_at = now(),
            version = version + 1,
            updated_at = now()
        where id = v_proposal.id
        returning * into v_proposal;

        -- Two owner-private shared_lock blocks, one per active member, identical winning
        -- UTC instants + origin_tz, linked back to the winning proposal/revision
        -- (contract §1: "Block model: per-member rows"). visibility='private'/pair_id=null
        -- so these ride the existing owner-only base RLS and Today/Plan pipeline.
        insert into public.time_blocks (
            owner_id, visibility, pair_id, title, starts_at_utc, ends_at_utc, origin_tz,
            type, status, source_proposal_id, source_revision
        ) values (
            v_caller, 'private', null, v_proposal.title,
            (v_candidate ->> 'starts_at_utc')::timestamptz, (v_candidate ->> 'ends_at_utc')::timestamptz,
            v_proposal.origin_tz, 'shared_lock', 'scheduled', v_proposal.id, v_proposal.accepted_revision
        )
        returning id into v_block_caller_id;

        insert into public.time_blocks (
            owner_id, visibility, pair_id, title, starts_at_utc, ends_at_utc, origin_tz,
            type, status, source_proposal_id, source_revision
        ) values (
            v_partner, 'private', null, v_proposal.title,
            (v_candidate ->> 'starts_at_utc')::timestamptz, (v_candidate ->> 'ends_at_utc')::timestamptz,
            v_proposal.origin_tz, 'shared_lock', 'scheduled', v_proposal.id, v_proposal.accepted_revision
        )
        returning id into v_block_partner_id;

        insert into public.commitments (proposal_id, user_id, state, created_from_revision, candidate_idx, time_block_id)
        values (v_proposal.id, v_caller, 'active', v_proposal.accepted_revision, p_candidate_idx, v_block_caller_id)
        returning id into v_commitment_caller_id;

        insert into public.commitments (proposal_id, user_id, state, created_from_revision, candidate_idx, time_block_id)
        values (v_proposal.id, v_partner, 'active', v_proposal.accepted_revision, p_candidate_idx, v_block_partner_id)
        returning id into v_commitment_partner_id;

        v_result := jsonb_build_object(
            'outcome', 'applied',
            'action', v_action,
            'proposal', public.time_lock_proposal_to_jsonb(v_proposal),
            'commitment_id', v_commitment_caller_id,
            'time_block_id', v_block_caller_id
        );

        perform realtime.send(
            public.proposal_broadcast_payload(v_proposal),
            'pair.proposal_updated.v1',
            public.pair_topic(v_proposal.pair_id),
            true
        );
        perform realtime.send(
            jsonb_build_object(
                'pair_id', v_proposal.pair_id, 'proposal_id', v_proposal.id,
                'commitment_id', v_commitment_caller_id, 'user_id', v_caller,
                'state', 'active', 'version', v_proposal.version
            ),
            'pair.commitment_changed.v1',
            public.pair_topic(v_proposal.pair_id),
            true
        );
        perform realtime.send(
            jsonb_build_object(
                'pair_id', v_proposal.pair_id, 'proposal_id', v_proposal.id,
                'commitment_id', v_commitment_partner_id, 'user_id', v_partner,
                'state', 'active', 'version', v_proposal.version
            ),
            'pair.commitment_changed.v1',
            public.pair_topic(v_proposal.pair_id),
            true
        );

    else -- counter
        if p_new_candidates is null or p_new_origin_tz is null or p_new_deadline is null then
            raise exception 'counter requires p_new_origin_tz, p_new_deadline, and p_new_candidates' using errcode = '22004';
        end if;
        if v_proposal.current_revision >= 20 then
            raise exception 'the 20-revision cap has been reached' using errcode = '22023';
        end if;
        if not public.proposal_candidates_valid(p_new_candidates) then
            raise exception 'p_new_candidates must be 1-3 valid, contiguously-indexed candidates' using errcode = '22023';
        end if;
        if p_new_deadline < now() + interval '5 minutes' or p_new_deadline > now() + interval '7 days' then
            raise exception 'p_new_deadline must be between 5 minutes and 7 days from now' using errcode = '22023';
        end if;
        if not exists (select 1 from pg_timezone_names where name = p_new_origin_tz) then
            raise exception 'p_new_origin_tz "%" is not a recognized IANA timezone name', p_new_origin_tz
                using errcode = '22023';
        end if;

        v_new_revision := v_proposal.current_revision + 1;

        insert into public.proposal_revisions (proposal_id, revision_no, author_id, origin_tz, candidates)
        values (v_proposal.id, v_new_revision, v_caller, p_new_origin_tz, p_new_candidates);

        insert into public.proposal_responses (proposal_id, revision_no, user_id, response, counter_revision)
        values (v_proposal.id, v_proposal.current_revision, v_caller, 'counter', v_new_revision);

        update public.time_lock_proposals
        set current_revision = v_new_revision,
            status = 'countered',
            response_deadline = p_new_deadline,
            version = version + 1,
            updated_at = now()
        where id = v_proposal.id
        returning * into v_proposal;

        v_result := jsonb_build_object(
            'outcome', 'applied',
            'action', v_action,
            'proposal', public.time_lock_proposal_to_jsonb(v_proposal),
            'revision', (select public.proposal_revision_to_jsonb(r) from public.proposal_revisions r
                         where r.proposal_id = v_proposal.id and r.revision_no = v_new_revision)
        );

        perform realtime.send(
            public.proposal_broadcast_payload(v_proposal),
            'pair.proposal_updated.v1',
            public.pair_topic(v_proposal.pair_id),
            true
        );
    end if;

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_proposal.version, v_result);

    return v_result;
end;
$$;

revoke all on function public.rpc_respond_proposal(uuid, uuid, int, text, int, text, timestamptz, jsonb) from public, anon;
grant execute on function public.rpc_respond_proposal(uuid, uuid, int, text, int, text, timestamptz, jsonb) to authenticated;

-- =====================================================================================
-- 3. rpc_cancel_proposal (contract §2): creator-only, proposed|countered only. Does not
--    retroactively cancel an already-accepted lock.
-- =====================================================================================

create or replace function public.rpc_cancel_proposal(
    p_operation_id uuid,
    p_proposal_id  uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller   uuid := auth.uid();
    v_receipt  public.mutation_receipts;
    v_proposal public.time_lock_proposals;
    v_result   jsonb;
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
        if (v_receipt.result ->> 'action') is distinct from 'cancel_proposal' then
            raise exception 'operation_id already used for a different action' using errcode = '22023';
        end if;
        return v_receipt.result;
    end if;

    select * into v_proposal from public.time_lock_proposals where id = p_proposal_id for update;
    if not found then
        raise exception 'proposal not found' using errcode = 'P0002';
    end if;

    if v_proposal.creator_id <> v_caller then
        raise exception 'only the proposal''s creator may cancel it' using errcode = '42501';
    end if;

    if v_proposal.status in ('proposed', 'countered') and v_proposal.response_deadline <= now() then
        update public.time_lock_proposals
        set status = 'expired', expired_at = now(), version = version + 1, updated_at = now()
        where id = v_proposal.id
        returning * into v_proposal;

        perform realtime.send(
            public.proposal_broadcast_payload(v_proposal),
            'pair.proposal_updated.v1',
            public.pair_topic(v_proposal.pair_id),
            true
        );
    end if;

    if v_proposal.status not in ('proposed', 'countered') then
        return jsonb_build_object(
            'outcome', 'conflict',
            'current_revision', v_proposal.current_revision,
            'status', v_proposal.status
        );
    end if;

    update public.time_lock_proposals
    set status = 'cancelled', cancelled_at = now(), version = version + 1, updated_at = now()
    where id = v_proposal.id
    returning * into v_proposal;

    v_result := jsonb_build_object(
        'outcome', 'applied',
        'action', 'cancel_proposal',
        'proposal', public.time_lock_proposal_to_jsonb(v_proposal)
    );

    perform realtime.send(
        public.proposal_broadcast_payload(v_proposal),
        'pair.proposal_updated.v1',
        public.pair_topic(v_proposal.pair_id),
        true
    );

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_proposal.version, v_result);

    return v_result;
end;
$$;

revoke all on function public.rpc_cancel_proposal(uuid, uuid) from public, anon;
grant execute on function public.rpc_cancel_proposal(uuid, uuid) to authenticated;

-- =====================================================================================
-- 4. rpc_complete_lock (contract §2): an owner of an active commitment completes only
--    THEIR OWN commitment + block, once the winning interval has begun. When both
--    commitments for the proposal are completed, the proposal transitions too -- one
--    person's state stays independent (ADR-010 §2) until that point.
-- =====================================================================================

create or replace function public.rpc_complete_lock(
    p_operation_id uuid,
    p_proposal_id  uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller     uuid := auth.uid();
    v_receipt    public.mutation_receipts;
    v_proposal   public.time_lock_proposals;
    v_commitment public.commitments;
    v_block      public.time_blocks;
    v_other_state text;
    v_result     jsonb;
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
        if (v_receipt.result ->> 'action') is distinct from 'complete_lock' then
            raise exception 'operation_id already used for a different action' using errcode = '22023';
        end if;
        return v_receipt.result;
    end if;

    select * into v_proposal from public.time_lock_proposals where id = p_proposal_id for update;
    if not found then
        raise exception 'proposal not found' using errcode = 'P0002';
    end if;

    select * into v_commitment
    from public.commitments
    where proposal_id = v_proposal.id and user_id = v_caller
    for update;

    if not found then
        raise exception 'caller has no commitment on this proposal' using errcode = '42501';
    end if;

    if v_commitment.state <> 'active' then
        return jsonb_build_object(
            'outcome', 'conflict',
            'commitment_state', v_commitment.state
        );
    end if;

    select * into v_block from public.time_blocks where id = v_commitment.time_block_id;

    if v_block.starts_at_utc > now() then
        raise exception 'the winning interval has not begun yet' using errcode = '22023';
    end if;

    update public.commitments
    set state = 'completed', completed_at = now(), version = version + 1, updated_at = now()
    where id = v_commitment.id
    returning * into v_commitment;

    update public.time_blocks
    set status = 'completed'
    where id = v_commitment.time_block_id;

    select c.state into v_other_state
    from public.commitments c
    where c.proposal_id = v_proposal.id and c.user_id <> v_caller;

    if v_other_state = 'completed' then
        update public.time_lock_proposals
        set status = 'completed', completed_at = now(), version = version + 1, updated_at = now()
        where id = v_proposal.id
        returning * into v_proposal;
    end if;

    v_result := jsonb_build_object(
        'outcome', 'applied',
        'action', 'complete_lock',
        'commitment', public.commitment_to_jsonb(v_commitment),
        'proposal_status', v_proposal.status
    );

    perform realtime.send(
        jsonb_build_object(
            'pair_id', v_proposal.pair_id, 'proposal_id', v_proposal.id,
            'commitment_id', v_commitment.id, 'user_id', v_caller,
            'state', 'completed', 'version', v_proposal.version
        ),
        'pair.commitment_changed.v1',
        public.pair_topic(v_proposal.pair_id),
        true
    );
    if v_other_state = 'completed' then
        perform realtime.send(
            public.proposal_broadcast_payload(v_proposal),
            'pair.proposal_updated.v1',
            public.pair_topic(v_proposal.pair_id),
            true
        );
    end if;

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_proposal.version, v_result);

    return v_result;
end;
$$;

revoke all on function public.rpc_complete_lock(uuid, uuid) from public, anon;
grant execute on function public.rpc_complete_lock(uuid, uuid) to authenticated;

-- =====================================================================================
-- 5. rpc_proposal_conflict_hints (contract §2 / ADR-007): busy-only, clipped windows,
--    exact keys, responder's own ELAY blocks only. No title/id/type/task/provenance ever
--    leaves this function.
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
    v_result  jsonb;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if not public.proposal_candidates_valid(p_candidates) then
        raise exception 'p_candidates must be 1-3 valid, contiguously-indexed candidates' using errcode = '22023';
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

    select coalesce(jsonb_agg(hint order by (hint ->> 'candidate_idx')::int), '[]'::jsonb)
    into v_result
    from (
        select jsonb_build_object(
            'candidate_idx', (c ->> 'candidate_idx')::int,
            'has_conflict', exists (
                select 1 from public.time_blocks b
                where b.owner_id = v_partner
                  and b.status = 'scheduled'
                  and b.starts_at_utc < (c ->> 'ends_at_utc')::timestamptz
                  and b.ends_at_utc > (c ->> 'starts_at_utc')::timestamptz
            ),
            'busy_windows', coalesce((
                select jsonb_agg(
                    jsonb_build_object(
                        'starts_at_utc', public.iso_utc(greatest(b.starts_at_utc, (c ->> 'starts_at_utc')::timestamptz)),
                        'ends_at_utc', public.iso_utc(least(b.ends_at_utc, (c ->> 'ends_at_utc')::timestamptz))
                    )
                    order by b.starts_at_utc
                )
                from public.time_blocks b
                where b.owner_id = v_partner
                  and b.status = 'scheduled'
                  and b.starts_at_utc < (c ->> 'ends_at_utc')::timestamptz
                  and b.ends_at_utc > (c ->> 'starts_at_utc')::timestamptz
            ), '[]'::jsonb)
        ) as hint
        from jsonb_array_elements(p_candidates) c
    ) hints;

    return v_result;
end;
$$;

comment on function public.rpc_proposal_conflict_hints(jsonb) is 'ADR-007 busy-only projection: exact keys {candidate_idx,has_conflict,busy_windows:[{starts_at_utc,ends_at_utc}]}, clipped to the candidate window, from the responder''s own scheduled ELAY blocks only -- no id/title/type/task/provenance.';

revoke all on function public.rpc_proposal_conflict_hints(jsonb) from public, anon;
grant execute on function public.rpc_proposal_conflict_hints(jsonb) to authenticated;

-- =====================================================================================
-- 6. Read projections (contract §2): rpc_get_proposal / rpc_list_proposals. Participant-
--    safe: revisions + responses (both members authored some of these; visible to both),
--    and the CALLER'S OWN commitment only (never the peer's commitment/block).
-- =====================================================================================

create or replace function public.rpc_get_proposal(p_proposal_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller   uuid := auth.uid();
    v_proposal public.time_lock_proposals;
    v_result   jsonb;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    select * into v_proposal from public.time_lock_proposals where id = p_proposal_id;

    if not found or not exists (
        select 1 from public.pair_members pm
        where pm.pair_id = v_proposal.pair_id and pm.user_id = v_caller and pm.left_at is null
    ) then
        return null;
    end if;

    select public.time_lock_proposal_to_jsonb(v_proposal) || jsonb_build_object(
        'revisions', coalesce((
            select jsonb_agg(public.proposal_revision_to_jsonb(r) order by r.revision_no)
            from public.proposal_revisions r
            where r.proposal_id = v_proposal.id
        ), '[]'::jsonb),
        'responses', coalesce((
            select jsonb_agg(public.proposal_response_to_jsonb(resp) order by resp.responded_at)
            from public.proposal_responses resp
            where resp.proposal_id = v_proposal.id
        ), '[]'::jsonb),
        'my_commitment', (
            select public.commitment_to_jsonb(c)
            from public.commitments c
            where c.proposal_id = v_proposal.id and c.user_id = v_caller
        )
    )
    into v_result;

    return v_result;
end;
$$;

revoke all on function public.rpc_get_proposal(uuid) from public, anon;
grant execute on function public.rpc_get_proposal(uuid) to authenticated;

-- rpc_list_proposals(scope, cursor): scope='active' -> proposed|countered|accepted;
-- scope='history' -> declined|expired|cancelled|completed. Keyset-paginated on
-- (updated_at desc, id desc); p_cursor is "<iso_utc updated_at>,<id>" (the exact string a
-- page's last item's own updated_at/id would encode) as emitted in next_cursor, or null for
-- the first page. Fixed page size (20) -- no literal wire schema for this shape was frozen
-- in the contract text; this seat's reading, matching the Stage-1 lead-amendment lesson
-- ("lead/B4 seat should sanity-check against the DTOs they build").
create or replace function public.rpc_list_proposals(
    p_scope  text default 'active',
    p_cursor text default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller  uuid := auth.uid();
    v_pair_id uuid;
    v_statuses text[];
    v_cursor_updated_at timestamptz := null;
    v_cursor_id uuid := null;
    v_page_size constant int := 20;
    v_items jsonb;
    v_next_cursor text := null;
    v_last_updated_at timestamptz;
    v_last_id uuid;
    v_count int;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_scope not in ('active', 'history') then
        raise exception 'p_scope must be active or history' using errcode = '22023';
    end if;

    v_statuses := case p_scope
        when 'active' then array['proposed', 'countered', 'accepted']
        else array['declined', 'expired', 'cancelled', 'completed']
    end;

    select pm.pair_id into v_pair_id
    from public.pair_members pm
    where pm.user_id = v_caller and pm.left_at is null;

    if v_pair_id is null then
        return jsonb_build_object('items', '[]'::jsonb, 'next_cursor', null);
    end if;

    if p_cursor is not null then
        v_cursor_updated_at := split_part(p_cursor, ',', 1)::timestamptz;
        v_cursor_id := split_part(p_cursor, ',', 2)::uuid;
    end if;

    with page as (
        select * from public.time_lock_proposals p
        where p.pair_id = v_pair_id
          and p.status = any (v_statuses)
          and (
              p_cursor is null
              or (p.updated_at, p.id) < (v_cursor_updated_at, v_cursor_id)
          )
        order by p.updated_at desc, p.id desc
        limit v_page_size
    )
    select
        jsonb_agg(public.time_lock_proposal_to_jsonb(page) order by page.updated_at desc, page.id desc),
        count(*),
        (array_agg(page.updated_at order by page.updated_at desc, page.id desc))[v_page_size],
        (array_agg(page.id order by page.updated_at desc, page.id desc))[v_page_size]
    into v_items, v_count, v_last_updated_at, v_last_id
    from page;

    if v_count = v_page_size then
        v_next_cursor := public.iso_utc(v_last_updated_at) || ',' || v_last_id::text;
    end if;

    return jsonb_build_object('items', coalesce(v_items, '[]'::jsonb), 'next_cursor', v_next_cursor);
end;
$$;

revoke all on function public.rpc_list_proposals(text, text) from public, anon;
grant execute on function public.rpc_list_proposals(text, text) to authenticated;

-- =====================================================================================
-- 7. fn_expire_proposals (contract §2 / lead amendment 4): service/test-only sweep, NOT
--    callable by authenticated -- every mutation RPC above repeats the same deadline check
--    inline, so this sweep is never a correctness dependency, only a UX nicety for a
--    negotiation nobody touches again before its deadline.
-- =====================================================================================

create or replace function public.fn_expire_proposals(p_batch_size int default 500)
returns table (proposal_id uuid)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_row public.time_lock_proposals;
begin
    for v_row in
        select p.* from public.time_lock_proposals p
        where p.status in ('proposed', 'countered')
          and p.response_deadline <= now()
        order by p.response_deadline
        limit p_batch_size
        for update skip locked
    loop
        update public.time_lock_proposals
        set status = 'expired', expired_at = now(), version = version + 1, updated_at = now()
        where id = v_row.id
        returning * into v_row;

        perform realtime.send(
            public.proposal_broadcast_payload(v_row),
            'pair.proposal_updated.v1',
            public.pair_topic(v_row.pair_id),
            true
        );

        proposal_id := v_row.id;
        return next;
    end loop;

    return;
end;
$$;

comment on function public.fn_expire_proposals(int) is 'Service/test-only sweep (lead amendment 4): SKIP LOCKED batch expiry of overdue proposed|countered proposals. Not a correctness dependency -- every mutation RPC also expire-checks inline.';

-- Explicit revoke from authenticated (lead amendment 4) in addition to the implicit
-- REVOKE ALL FROM PUBLIC every new function gets by default -- authenticated must never be
-- able to call this directly, only service_role (tests call it via the postgres/service
-- role the pgTAP harness runs as).
revoke all on function public.fn_expire_proposals(int) from public, anon, authenticated;
grant execute on function public.fn_expire_proposals(int) to service_role;
