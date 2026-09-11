-- rpc_upsert_milestone / rpc_delete_milestone (contract §2).
-- Milestones carry no owner_id/visibility of their own, so this is the RPC the contract
-- calls out explicitly: "milestone RPC validates goal ownership". Every lookup below joins
-- through goals to establish ownership; RLS (the ALL policy on milestones) backs this up
-- independently.

create or replace function public.rpc_upsert_milestone(
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
    v_goal_id    uuid;
    v_receipt    public.mutation_receipts;
    v_current    public.milestones;
    v_row        public.milestones;
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

    v_goal_id := nullif(p_row->>'goal_id', '')::uuid;
    if v_goal_id is null then
        raise exception 'p_row.goal_id is required' using errcode = '22004';
    end if;

    -- contract §2: "milestone RPC validates goal ownership".
    if not exists (
        select 1 from public.goals where id = v_goal_id and owner_id = v_caller
    ) then
        raise exception 'goal_id does not belong to the caller' using errcode = '42501';
    end if;

    select m.* into v_current
    from public.milestones m
    join public.goals g on g.id = m.goal_id
    where m.id = v_id and g.owner_id = v_caller;

    if p_expected_version = 0 then
        if found then
            return jsonb_build_object('outcome', 'conflict', 'current', public.milestone_to_jsonb(v_current));
        end if;

        insert into public.milestones (id, goal_id, title, target_date, sort_order, status)
        values (
            v_id, v_goal_id,
            p_row->>'title',
            nullif(p_row->>'target_date', '')::date,
            coalesce((p_row->>'sort_order')::int, 0),
            coalesce(p_row->>'status', 'pending')
        )
        returning * into v_row;
        v_new_version := v_row.version;
    else
        if not found or v_current.version <> p_expected_version then
            return jsonb_build_object(
                'outcome', 'conflict',
                'current', case when found then public.milestone_to_jsonb(v_current) else null end
            );
        end if;

        update public.milestones set
            goal_id = v_goal_id,
            title = p_row->>'title',
            target_date = nullif(p_row->>'target_date', '')::date,
            sort_order = coalesce((p_row->>'sort_order')::int, v_current.sort_order),
            status = coalesce(p_row->>'status', v_current.status),
            version = v_current.version + 1
        where id = v_id
        returning * into v_row;
        v_new_version := v_row.version;
    end if;

    v_result := jsonb_build_object('outcome', 'applied', 'row', public.milestone_to_jsonb(v_row));

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_new_version, v_result);

    return v_result;
end;
$$;

create or replace function public.rpc_delete_milestone(
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
    v_current    public.milestones;
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

    select m.* into v_current
    from public.milestones m
    join public.goals g on g.id = m.goal_id
    where m.id = p_id and g.owner_id = v_caller;

    if not found or v_current.version <> p_expected_version then
        return jsonb_build_object(
            'outcome', 'conflict',
            'current', case when found then public.milestone_to_jsonb(v_current) else null end
        );
    end if;

    delete from public.milestones where id = p_id;

    v_new_version := v_current.version + 1;
    v_result := jsonb_build_object('outcome', 'applied', 'id', p_id, 'version', v_new_version);

    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_new_version, v_result);

    return v_result;
end;
$$;

revoke all on function public.rpc_upsert_milestone(uuid, bigint, jsonb) from public;
grant execute on function public.rpc_upsert_milestone(uuid, bigint, jsonb) to authenticated;

revoke all on function public.rpc_delete_milestone(uuid, bigint, uuid) from public;
grant execute on function public.rpc_delete_milestone(uuid, bigint, uuid) to authenticated;
