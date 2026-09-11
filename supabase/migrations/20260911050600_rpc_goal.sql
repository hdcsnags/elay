-- rpc_upsert_goal / rpc_delete_goal (contract §2).
-- security invoker: RLS on public.goals does the authorization; these functions add
-- idempotent replay (via mutation_receipts) and optimistic-concurrency conflict handling
-- on top. Conflicts are returned as data, never raised (contract §2: "Never raise for
-- conflicts -- raise only for authz/validation").
--
-- Design note: goals has no owner-aware parent FK to validate, so this is the simplest of
-- the five upsert RPCs and a good template for the others.

create or replace function public.rpc_upsert_goal(
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
    v_receipt    public.mutation_receipts;
    v_current    public.goals;
    v_row        public.goals;
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

    select * into v_current from public.goals where id = v_id and owner_id = v_caller;

    if p_expected_version = 0 then
        if found then
            return jsonb_build_object('outcome', 'conflict', 'current', public.goal_to_jsonb(v_current));
        end if;

        insert into public.goals (id, owner_id, title, notes, target_date, status)
        values (
            v_id,
            v_caller,
            p_row->>'title',
            p_row->>'notes',
            nullif(p_row->>'target_date', '')::date,
            coalesce(p_row->>'status', 'active')
        )
        returning * into v_row;
        v_new_version := v_row.version;
    else
        if not found or v_current.version <> p_expected_version then
            return jsonb_build_object(
                'outcome', 'conflict',
                'current', case when found then public.goal_to_jsonb(v_current) else null end
            );
        end if;

        update public.goals set
            title = p_row->>'title',
            notes = p_row->>'notes',
            target_date = nullif(p_row->>'target_date', '')::date,
            status = coalesce(p_row->>'status', v_current.status),
            version = v_current.version + 1
        where id = v_id and owner_id = v_caller
        returning * into v_row;
        v_new_version := v_row.version;
    end if;

    v_result := jsonb_build_object('outcome', 'applied', 'row', public.goal_to_jsonb(v_row));

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_new_version, v_result);

    return v_result;
end;
$$;

create or replace function public.rpc_delete_goal(
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
    v_current    public.goals;
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

    select * into v_current from public.goals where id = p_id and owner_id = v_caller;

    if not found or v_current.version <> p_expected_version then
        return jsonb_build_object(
            'outcome', 'conflict',
            'current', case when found then public.goal_to_jsonb(v_current) else null end
        );
    end if;

    delete from public.goals where id = p_id and owner_id = v_caller;

    v_new_version := v_current.version + 1;
    v_result := jsonb_build_object('outcome', 'applied', 'id', p_id, 'version', v_new_version);

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_new_version, v_result);

    return v_result;
end;
$$;

revoke all on function public.rpc_upsert_goal(uuid, bigint, jsonb) from public;
grant execute on function public.rpc_upsert_goal(uuid, bigint, jsonb) to authenticated;

revoke all on function public.rpc_delete_goal(uuid, bigint, uuid) from public;
grant execute on function public.rpc_delete_goal(uuid, bigint, uuid) to authenticated;
