-- Verifier residual F9 (Stage 2 closure, tracked in STATE.md's closing session entry):
-- rpc_delete_time_block on a shared_lock block raised a raw 23503 foreign-key violation
-- instead of a clean domain outcome. commitments.time_block_id is `not null unique
-- references public.time_blocks (id)` with no `on delete` action (stage2_timelock.sql), so
-- deleting a time_blocks row a commitment still points at aborts the whole call with a
-- Postgres-level FK error the client can't render as calm copy.
--
-- A shared_lock block's lifecycle belongs to the proposal RPCs (rpc_respond_proposal's
-- accept path creates it; rpc_cancel_proposal/withdraw and rpc_complete_lock are the only
-- writers that touch it afterward) -- rpc_delete_time_block must never silently cascade a
-- delete through that machinery. Fix: when the target block is `type = 'shared_lock'`, or a
-- commitments row still references it (belt-and-braces -- covers a hypothetical future block
-- whose type changed but whose commitment link didn't), return
-- `{"outcome":"conflict","reason":"shared_lock"}` without deleting, leaving the block in
-- place. A plain personal/focus/routine block with no commitment is unaffected and still
-- deletes exactly as before.
--
-- Whole function replaced per house style; signature, `security invoker` (unchanged from the
-- original -- no `set search_path` there either, so none added here), and every other branch
-- are identical to 20260911051000_rpc_time_block.sql except the new guard. Grants restated
-- explicitly (house rule).

create or replace function public.rpc_delete_time_block(
    p_operation_id uuid,
    p_expected_version bigint,
    p_id uuid
)
returns jsonb
language plpgsql
security invoker
as $$
declare
    v_caller     uuid := auth.uid();
    v_receipt    public.mutation_receipts;
    v_current    public.time_blocks;
    v_new_version bigint;
    v_result     jsonb;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_operation_id is null then
        raise exception 'p_operation_id is required' using errcode = '22004';
    end if;
    if p_expected_version is null or p_expected_version < 1 then
        raise exception 'p_expected_version must be >= 1 for delete' using errcode = '22023';
    end if;
    if p_id is null then
        raise exception 'p_id is required' using errcode = '22004';
    end if;

    select * into v_receipt
    from public.mutation_receipts
    where owner_id = v_caller and operation_id = p_operation_id;

    if found then
        return v_receipt.result;
    end if;

    select * into v_current from public.time_blocks where id = p_id and owner_id = v_caller;

    if not found or v_current.version <> p_expected_version then
        return jsonb_build_object(
            'outcome', 'conflict',
            'current', case when found then public.time_block_to_jsonb(v_current) else null end
        );
    end if;

    -- F9 guard: a shared_lock block's lifecycle belongs to the proposal RPCs -- never cascade
    -- a delete through it here. Checks the block's own type first (the common case) and falls
    -- back to a live commitments reference (belt-and-braces), so either signal alone is enough
    -- to refuse the delete cleanly instead of hitting the commitments FK.
    if v_current.type = 'shared_lock' or exists (
        select 1 from public.commitments where time_block_id = p_id
    ) then
        return jsonb_build_object('outcome', 'conflict', 'reason', 'shared_lock');
    end if;

    delete from public.time_blocks where id = p_id and owner_id = v_caller;

    v_new_version := v_current.version + 1;
    v_result := jsonb_build_object('outcome', 'applied', 'id', p_id, 'version', v_new_version);

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_new_version, v_result);

    return v_result;
end;
$$;

revoke all on function public.rpc_delete_time_block(uuid, bigint, uuid) from public;
grant execute on function public.rpc_delete_time_block(uuid, bigint, uuid) to authenticated;
