-- pgTAP: rpc_upsert_task / rpc_delete_task -- goal_id and milestone_id ownership
-- validation, happy path (incl. tags array round-trip), idempotent replay, version
-- conflict (contract §2).
begin;
create extension if not exists pgtap with schema extensions;
select plan(18);

select has_function('public', 'rpc_upsert_task', array['uuid', 'bigint', 'jsonb'], 'rpc_upsert_task exists');
select has_function('public', 'rpc_delete_task', array['uuid', 'bigint', 'uuid'], 'rpc_delete_task exists');

insert into auth.users (id, email)
values ('00000000-0000-0000-0000-000000000001', 'a@test.local'),
       ('00000000-0000-0000-0000-000000000002', 'b@test.local');

insert into public.goals (id, owner_id, title)
values ('a0000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000001', 'A''s goal'),
       ('b0000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000002', 'B''s goal');

insert into public.milestones (id, goal_id, title)
values ('a1000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000001', 'A''s milestone'),
       ('b1000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000002', 'B''s milestone');

create temporary table t_result (label text, result jsonb);
-- role switches below: the temp table must stay writable to client roles
grant all on t_result to authenticated, anon;

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000000001","role":"authenticated"}';

-- goal_id ownership validation
select throws_ok(
    $$select public.rpc_upsert_task(
        'a2000001-0000-4000-8000-000000000001'::uuid,
        0,
        jsonb_build_object('id', 'e0000000-0000-4000-8000-000000000001', 'goal_id',
                            'b0000000-0000-4000-8000-000000000002', 'title', 'stolen goal link')
    )$$,
    '42501',
    'goal_id does not belong to the caller',
    'rpc_upsert_task rejects a goal_id owned by a different caller'
);

-- milestone_id ownership validation (no DB-level composite FK backstop for this column)
select throws_ok(
    $$select public.rpc_upsert_task(
        'a2000002-0000-4000-8000-000000000002'::uuid,
        0,
        jsonb_build_object('id', 'e0000000-0000-4000-8000-000000000002', 'milestone_id',
                            'b1000000-0000-4000-8000-000000000002', 'title', 'stolen milestone link')
    )$$,
    '42501',
    'milestone_id does not belong to a goal owned by the caller',
    'rpc_upsert_task rejects a milestone_id whose goal is owned by a different caller'
);

-- happy path: fresh insert with a real goal_id/milestone_id and a tags array
insert into t_result (label, result)
select 'insert', public.rpc_upsert_task(
    'a2000003-0000-4000-8000-000000000003'::uuid,
    0,
    jsonb_build_object(
        'id', 'e0000000-0000-4000-8000-000000000003',
        'goal_id', 'a0000000-0000-4000-8000-000000000001',
        'milestone_id', 'a1000000-0000-4000-8000-000000000001',
        'title', 'Test Task',
        'tags', jsonb_build_array('schema', 'phase1')
    )
);

select is(
    (select result->>'outcome' from t_result where label = 'insert'),
    'applied',
    'fresh insert returns outcome=applied'
);
select is(
    (select (result->'row'->>'version')::int from t_result where label = 'insert'),
    1,
    'fresh insert bumps version to 1'
);
select is(
    (select result->'row'->'tags' from t_result where label = 'insert'),
    '["schema", "phase1"]'::jsonb,
    'tags array round-trips as a JSON array'
);
select is(
    (select count(*) from public.mutation_receipts
     where owner_id = '00000000-0000-0000-0000-000000000001'
       and operation_id = 'a2000003-0000-4000-8000-000000000003')::int,
    1,
    'a receipt was written for the insert operation_id'
);

-- idempotent replay
insert into t_result (label, result)
select 'replay', public.rpc_upsert_task(
    'a2000003-0000-4000-8000-000000000003'::uuid,
    0,
    jsonb_build_object('id', 'e0000000-0000-4000-8000-000000000003', 'title', 'Different Title')
);

select is(
    (select result from t_result where label = 'replay'),
    (select result from t_result where label = 'insert'),
    'replay of the same operation_id returns the exact stored result'
);
select is(
    (select version from public.tasks where id = 'e0000000-0000-4000-8000-000000000003'),
    1::bigint,
    'replay does not double-apply: version is still 1'
);

-- proper update at the correct expected_version
insert into t_result (label, result)
select 'update', public.rpc_upsert_task(
    'a2000004-0000-4000-8000-000000000004'::uuid,
    1,
    jsonb_build_object(
        'id', 'e0000000-0000-4000-8000-000000000003',
        'goal_id', 'a0000000-0000-4000-8000-000000000001',
        'title', 'Renamed Task',
        'status', 'in_progress'
    )
);

select is(
    (select result->>'outcome' from t_result where label = 'update'),
    'applied',
    'update at the correct expected_version returns outcome=applied'
);
select is(
    (select (result->'row'->>'version')::int from t_result where label = 'update'),
    2,
    'update bumps version to 2'
);

-- stale expected_version: conflict, no mutation
insert into t_result (label, result)
select 'stale', public.rpc_upsert_task(
    'a2000005-0000-4000-8000-000000000005'::uuid,
    1,
    jsonb_build_object('id', 'e0000000-0000-4000-8000-000000000003', 'title', 'Should not apply')
);

select is(
    (select result->>'outcome' from t_result where label = 'stale'),
    'conflict',
    'stale expected_version returns outcome=conflict'
);
select is(
    (select (result->'current'->>'version')::int from t_result where label = 'stale'),
    2,
    'conflict payload carries the true current version'
);
select is(
    (select status from public.tasks where id = 'e0000000-0000-4000-8000-000000000003'),
    'in_progress',
    'conflict left the row untouched (status unchanged)'
);
select is(
    (select count(*) from public.mutation_receipts
     where owner_id = '00000000-0000-0000-0000-000000000001'
       and operation_id = 'a2000005-0000-4000-8000-000000000005')::int,
    0,
    'conflict does not write a receipt'
);

-- delete tombstone at the correct expected_version
insert into t_result (label, result)
select 'delete', public.rpc_delete_task(
    'a2000006-0000-4000-8000-000000000006'::uuid,
    2,
    'e0000000-0000-4000-8000-000000000003'
);

select is(
    (select result->>'outcome' from t_result where label = 'delete'),
    'applied',
    'delete at the correct expected_version returns outcome=applied'
);
select is(
    (select count(*) from public.tasks where id = 'e0000000-0000-4000-8000-000000000003')::int,
    0,
    'the row is actually gone after delete'
);

select * from finish();
rollback;
