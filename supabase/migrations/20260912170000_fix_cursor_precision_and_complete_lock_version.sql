-- Stage 2 pre-gate fixes (lead, from the verification round 2026-09-12 — findings F2 and
-- F8, both proven with repros by the verifier):
--
-- F2 — rpc_list_proposals cursor loses rows on tied timestamps: the cursor serialized
-- updated_at through public.iso_utc() (whole-second precision) but compared it against
-- microsecond-precision updated_at, so the reparsed cursor sat strictly EARLIER than the
-- real boundary and every row sharing that second was skipped (25 tied rows -> page1: 20,
-- page2: 0, next_cursor null, 5 rows permanently unreachable — exactly the shape
-- fn_expire_proposals produces, since now() is transaction-fixed across a sweep batch).
-- Fix: serialize the cursor at microsecond precision. Wire shape unchanged
-- ("<iso timestamp>,<uuid>"); item projection unchanged from 20260912150000.
--
-- F8 — rpc_complete_lock set time_blocks.status='completed' WITHOUT bumping
-- time_blocks.version, so a client holding the pre-completion version could
-- rpc_upsert_time_block(expected_version=<stale>) and get outcome=applied, silently
-- reverting a completed lock to 'scheduled' while commitments.state stayed 'completed'.
-- Fix: bump version + updated_at in the same statement, closing the optimistic-lock hole.

-- ============================== F2: cursor precision =================================

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
        jsonb_agg(
            public.time_lock_proposal_to_jsonb(page) || jsonb_build_object(
                'revisions', coalesce((
                    select jsonb_agg(public.proposal_revision_to_jsonb(r) order by r.revision_no)
                    from public.proposal_revisions r
                    where r.proposal_id = page.id
                ), '[]'::jsonb),
                'responses', coalesce((
                    select jsonb_agg(public.proposal_response_to_jsonb(resp) order by resp.responded_at)
                    from public.proposal_responses resp
                    where resp.proposal_id = page.id
                ), '[]'::jsonb),
                'my_commitment', (
                    select public.commitment_to_jsonb(c)
                    from public.commitments c
                    where c.proposal_id = page.id and c.user_id = v_caller
                )
            )
            order by page.updated_at desc, page.id desc
        ),
        count(*),
        (array_agg(page.updated_at order by page.updated_at desc, page.id desc))[v_page_size],
        (array_agg(page.id order by page.updated_at desc, page.id desc))[v_page_size]
    into v_items, v_count, v_last_updated_at, v_last_id
    from page;

    if v_count = v_page_size then
        -- Microsecond precision (F2): to_char '…SS.US' round-trips ::timestamptz exactly,
        -- unlike iso_utc()'s whole-second form. The cursor is opaque to clients.
        v_next_cursor := to_char(v_last_updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
            || ',' || v_last_id::text;
    end if;

    return jsonb_build_object('items', coalesce(v_items, '[]'::jsonb), 'next_cursor', v_next_cursor);
end;
$$;

revoke all on function public.rpc_list_proposals(text, text) from public, anon;
grant execute on function public.rpc_list_proposals(text, text) to authenticated;

-- ============================ F8: complete_lock version bump =========================

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

    -- F8: bump the block's optimistic-lock version too, so a stale
    -- rpc_upsert_time_block(expected_version=<pre-completion>) conflicts instead of
    -- silently reverting the completed lock.
    update public.time_blocks
    set status = 'completed', version = version + 1, updated_at = now()
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
