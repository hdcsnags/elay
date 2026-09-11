-- rpc_upsert_time_block / rpc_delete_time_block (contract §2).
-- task_id ownership is already enforced by the composite FK time_blocks_task_owner_fk
-- (owner_id is forced to the caller below); re-checked explicitly so callers get a clean
-- 42501 instead of a raw 23503 from the constraint. origin_tz validity (IANA zone name) is
-- enforced by the time_blocks_validate_origin_tz trigger, not here.

create or replace function public.rpc_upsert_time_block(
    p_operation_id uuid,
    p_expected_version bigint,
    p_row jsonb
)
returns jsonb
language plpgsql
security invoker
as $$
declare
    v_caller     uuid := auth.uid();
    v_id         uuid;
    v_task_id    uuid;
    v_receipt    public.mutation_receipts;
    v_current    public.time_blocks;
    v_row        public.time_blocks;
    v_new_version bigint;
    v_result     jsonb;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_operation_id is null then
        raise exception 'p_operation_id is required' using errcode = '22004';
    end if;
    if p_expected_version is null or p_expected_version < 0 then
        raise exception 'p_expected_version must be >= 0' using errcode = '22023';
    end if;

    select * into v_receipt
    from public.mutation_receipts
    where owner_id = v_caller and operation_id = p_operation_id;

    if found then
        return v_receipt.result;
    end if;

    v_id := nullif(p_row->>'id', '')::uuid;
    if v_id is null then
        raise exception 'p_row.id is required' using errcode = '22004';
    end if;

    v_task_id := nullif(p_row->>'task_id', '')::uuid;

    if v_task_id is not null and not exists (
        select 1 from public.tasks where id = v_task_id and owner_id = v_caller
    ) then
        raise exception 'task_id does not belong to the caller' using errcode = '42501';
    end if;

    select * into v_current from public.time_blocks where id = v_id and owner_id = v_caller;

    if p_expected_version = 0 then
        if found then
            return jsonb_build_object('outcome', 'conflict', 'current', public.time_block_to_jsonb(v_current));
        end if;

        insert into public.time_blocks (
            id, owner_id, task_id, title, starts_at_utc, ends_at_utc, origin_tz, type,
            status, recurrence_rule, all_day
        ) values (
            v_id, v_caller, v_task_id,
            p_row->>'title',
            (p_row->>'starts_at_utc')::timestamptz,
            (p_row->>'ends_at_utc')::timestamptz,
            p_row->>'origin_tz',
            coalesce(p_row->>'type', 'personal'),
            coalesce(p_row->>'status', 'scheduled'),
            p_row->>'recurrence_rule',
            coalesce(nullif(p_row->>'all_day', '')::boolean, false)
        )
        returning * into v_row;
        v_new_version := v_row.version;
    else
        if not found or v_current.version <> p_expected_version then
            return jsonb_build_object(
                'outcome', 'conflict',
                'current', case when found then public.time_block_to_jsonb(v_current) else null end
            );
        end if;

        update public.time_blocks set
            task_id = v_task_id,
            title = p_row->>'title',
            starts_at_utc = (p_row->>'starts_at_utc')::timestamptz,
            ends_at_utc = (p_row->>'ends_at_utc')::timestamptz,
            origin_tz = p_row->>'origin_tz',
            type = coalesce(p_row->>'type', v_current.type),
            status = coalesce(p_row->>'status', v_current.status),
            recurrence_rule = p_row->>'recurrence_rule',
            all_day = coalesce(nullif(p_row->>'all_day', '')::boolean, v_current.all_day),
            version = v_current.version + 1
        where id = v_id and owner_id = v_caller
        returning * into v_row;
        v_new_version := v_row.version;
    end if;

    v_result := jsonb_build_object('outcome', 'applied', 'row', public.time_block_to_jsonb(v_row));

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_new_version, v_result);

    return v_result;
end;
$$;

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

    delete from public.time_blocks where id = p_id and owner_id = v_caller;

    v_new_version := v_current.version + 1;
    v_result := jsonb_build_object('outcome', 'applied', 'id', p_id, 'version', v_new_version);

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_new_version, v_result);

    return v_result;
end;
$$;

revoke all on function public.rpc_upsert_time_block(uuid, bigint, jsonb) from public;
grant execute on function public.rpc_upsert_time_block(uuid, bigint, jsonb) to authenticated;

revoke all on function public.rpc_delete_time_block(uuid, bigint, uuid) from public;
grant execute on function public.rpc_delete_time_block(uuid, bigint, uuid) to authenticated;
