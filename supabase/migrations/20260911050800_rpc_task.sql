-- rpc_upsert_task / rpc_delete_task (contract §2).
-- goal_id ownership is already enforced by the composite FK tasks_goal_owner_fk (owner_id is
-- forced to the caller below, so a cross-owner goal_id can never satisfy the FK). It is
-- re-checked explicitly anyway so callers get a clean 42501 instead of a raw 23503 from the
-- constraint. milestone_id has no such DB-level backstop (milestones carry no owner_id), so
-- its ownership check here is load-bearing, not just cosmetic -- this is the
-- "same pattern for milestone_id via milestones->goal ownership (validate in RPC)" the
-- contract calls for.

create or replace function public.rpc_upsert_task(
    p_operation_id uuid,
    p_expected_version bigint,
    p_row jsonb
)
returns jsonb
language plpgsql
security invoker
as $$
declare
    v_caller       uuid := auth.uid();
    v_id           uuid;
    v_goal_id      uuid;
    v_milestone_id uuid;
    v_tags         text[];
    v_receipt      public.mutation_receipts;
    v_current      public.tasks;
    v_row          public.tasks;
    v_new_version  bigint;
    v_result       jsonb;
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

    v_goal_id := nullif(p_row->>'goal_id', '')::uuid;
    v_milestone_id := nullif(p_row->>'milestone_id', '')::uuid;

    if v_goal_id is not null and not exists (
        select 1 from public.goals where id = v_goal_id and owner_id = v_caller
    ) then
        raise exception 'goal_id does not belong to the caller' using errcode = '42501';
    end if;

    if v_milestone_id is not null and not exists (
        select 1 from public.milestones m
        join public.goals g on g.id = m.goal_id
        where m.id = v_milestone_id and g.owner_id = v_caller
    ) then
        raise exception 'milestone_id does not belong to a goal owned by the caller' using errcode = '42501';
    end if;

    select coalesce(array_agg(elem), '{}'::text[]) into v_tags
    from jsonb_array_elements_text(coalesce(p_row->'tags', '[]'::jsonb)) as elem;

    select * into v_current from public.tasks where id = v_id and owner_id = v_caller;

    if p_expected_version = 0 then
        if found then
            return jsonb_build_object('outcome', 'conflict', 'current', public.task_to_jsonb(v_current));
        end if;

        insert into public.tasks (
            id, owner_id, goal_id, milestone_id, title, notes, status, priority, effort,
            estimate_min, due_start_utc, due_end_utc, recurrence_rule, tags
        ) values (
            v_id, v_caller, v_goal_id, v_milestone_id,
            p_row->>'title',
            p_row->>'notes',
            coalesce(p_row->>'status', 'todo'),
            coalesce(nullif(p_row->>'priority', '')::smallint, 1),
            nullif(p_row->>'effort', '')::smallint,
            nullif(p_row->>'estimate_min', '')::int,
            nullif(p_row->>'due_start_utc', '')::timestamptz,
            nullif(p_row->>'due_end_utc', '')::timestamptz,
            p_row->>'recurrence_rule',
            v_tags
        )
        returning * into v_row;
        v_new_version := v_row.version;
    else
        if not found or v_current.version <> p_expected_version then
            return jsonb_build_object(
                'outcome', 'conflict',
                'current', case when found then public.task_to_jsonb(v_current) else null end
            );
        end if;

        update public.tasks set
            goal_id = v_goal_id,
            milestone_id = v_milestone_id,
            title = p_row->>'title',
            notes = p_row->>'notes',
            status = coalesce(p_row->>'status', v_current.status),
            priority = coalesce(nullif(p_row->>'priority', '')::smallint, v_current.priority),
            effort = nullif(p_row->>'effort', '')::smallint,
            estimate_min = nullif(p_row->>'estimate_min', '')::int,
            due_start_utc = nullif(p_row->>'due_start_utc', '')::timestamptz,
            due_end_utc = nullif(p_row->>'due_end_utc', '')::timestamptz,
            recurrence_rule = p_row->>'recurrence_rule',
            tags = v_tags,
            version = v_current.version + 1
        where id = v_id and owner_id = v_caller
        returning * into v_row;
        v_new_version := v_row.version;
    end if;

    v_result := jsonb_build_object('outcome', 'applied', 'row', public.task_to_jsonb(v_row));

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_new_version, v_result);

    return v_result;
end;
$$;

create or replace function public.rpc_delete_task(
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
    v_current    public.tasks;
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

    select * into v_current from public.tasks where id = p_id and owner_id = v_caller;

    if not found or v_current.version <> p_expected_version then
        return jsonb_build_object(
            'outcome', 'conflict',
            'current', case when found then public.task_to_jsonb(v_current) else null end
        );
    end if;

    delete from public.tasks where id = p_id and owner_id = v_caller;

    v_new_version := v_current.version + 1;
    v_result := jsonb_build_object('outcome', 'applied', 'id', p_id, 'version', v_new_version);

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_new_version, v_result);

    return v_result;
end;
$$;

revoke all on function public.rpc_upsert_task(uuid, bigint, jsonb) from public;
grant execute on function public.rpc_upsert_task(uuid, bigint, jsonb) to authenticated;

revoke all on function public.rpc_delete_task(uuid, bigint, uuid) from public;
grant execute on function public.rpc_delete_task(uuid, bigint, uuid) to authenticated;
