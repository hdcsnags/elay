-- rpc_upsert_capture / rpc_delete_capture (contract §2).
-- clarified_task_id is a plain (non-composite) FK on the table (see captures.sql for why),
-- so its ownership is validated here explicitly -- the same "validate FK ownership" duty the
-- contract assigns generally to RPC step (3), applied to the one owner-aware reference this
-- table has.

create or replace function public.rpc_upsert_capture(
    p_operation_id uuid,
    p_expected_version bigint,
    p_row jsonb
)
returns jsonb
language plpgsql
security invoker
as $$
declare
    v_caller            uuid := auth.uid();
    v_id                uuid;
    v_clarified_task_id uuid;
    v_receipt           public.mutation_receipts;
    v_current           public.captures;
    v_row               public.captures;
    v_new_version       bigint;
    v_result            jsonb;
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

    v_clarified_task_id := nullif(p_row->>'clarified_task_id', '')::uuid;

    if v_clarified_task_id is not null and not exists (
        select 1 from public.tasks where id = v_clarified_task_id and owner_id = v_caller
    ) then
        raise exception 'clarified_task_id does not belong to the caller' using errcode = '42501';
    end if;

    select * into v_current from public.captures where id = v_id and owner_id = v_caller;

    if p_expected_version = 0 then
        if found then
            return jsonb_build_object('outcome', 'conflict', 'current', public.capture_to_jsonb(v_current));
        end if;

        insert into public.captures (
            id, owner_id, body, source, ai_parse_status, captured_at, clarified_task_id
        ) values (
            v_id, v_caller,
            p_row->>'body',
            coalesce(p_row->>'source', 'quick'),
            coalesce(p_row->>'ai_parse_status', 'unparsed'),
            coalesce(nullif(p_row->>'captured_at', '')::timestamptz, now()),
            v_clarified_task_id
        )
        returning * into v_row;
        v_new_version := v_row.version;
    else
        if not found or v_current.version <> p_expected_version then
            return jsonb_build_object(
                'outcome', 'conflict',
                'current', case when found then public.capture_to_jsonb(v_current) else null end
            );
        end if;

        update public.captures set
            body = p_row->>'body',
            source = coalesce(p_row->>'source', v_current.source),
            ai_parse_status = coalesce(p_row->>'ai_parse_status', v_current.ai_parse_status),
            captured_at = coalesce(nullif(p_row->>'captured_at', '')::timestamptz, v_current.captured_at),
            clarified_task_id = v_clarified_task_id,
            version = v_current.version + 1
        where id = v_id and owner_id = v_caller
        returning * into v_row;
        v_new_version := v_row.version;
    end if;

    v_result := jsonb_build_object('outcome', 'applied', 'row', public.capture_to_jsonb(v_row));

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_new_version, v_result);

    return v_result;
end;
$$;

create or replace function public.rpc_delete_capture(
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
    v_current    public.captures;
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

    select * into v_current from public.captures where id = p_id and owner_id = v_caller;

    if not found or v_current.version <> p_expected_version then
        return jsonb_build_object(
            'outcome', 'conflict',
            'current', case when found then public.capture_to_jsonb(v_current) else null end
        );
    end if;

    delete from public.captures where id = p_id and owner_id = v_caller;

    v_new_version := v_current.version + 1;
    v_result := jsonb_build_object('outcome', 'applied', 'id', p_id, 'version', v_new_version);

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_new_version, v_result);

    return v_result;
end;
$$;

revoke all on function public.rpc_upsert_capture(uuid, bigint, jsonb) from public;
grant execute on function public.rpc_upsert_capture(uuid, bigint, jsonb) to authenticated;

revoke all on function public.rpc_delete_capture(uuid, bigint, uuid) from public;
grant execute on function public.rpc_delete_capture(uuid, bigint, uuid) to authenticated;
