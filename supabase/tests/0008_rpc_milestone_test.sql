-- pgTAP: rpc_upsert_milestone / rpc_delete_milestone -- goal-ownership validation, happy
-- path, idempotent replay, version conflict (contract §2: "milestone RPC validates goal
-- ownership").
begin;
create extension if not exists pgtap with schema extensions;
select plan(16);

select has_function('public', 'rpc_upsert_milestone', array['uuid', 'bigint', 'jsonb'], 'rpc_upsert_milestone exists');
select has_function('public', 'rpc_delete_milestone', array['uuid', 'bigint', 'uuid'], 'rpc_delete_milestone exists');

insert into auth.users (id, email)
values ('00000000-0000-0000-0000-000000000001', 'a@test.local'),
       ('00000000-0000-0000-0000-000000000002', 'b@test.local');

insert into public.goals (id, owner_id, title)
values ('a0000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000001', 'A''s goal'),
       ('b0000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000002', 'B''s goal');

create temporary table t_result (label text, result jsonb);
-- role switches below: the temp table must stay writable to client roles
grant all on t_result to authenticated, anon;

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000000001","role":"authenticated"}';

-- goal-ownership validation: A cannot attach a milestone to B's goal
select throws_ok(
    $$select public.rpc_upsert_milestone(
        'a1000001-0000-4000-8000-000000000001'::uuid,
        0,
        jsonb_build_object('id', 'd0000000-0000-4000-8000-000000000001', 'goal_id',
                            'b0000000-0000-4000-8000-000000000002', 'title', 'stolen')
    )$$,
    '42501',
    'goal_id does not belong to the caller',
    'rpc_upsert_milestone rejects a goal_id owned by a different caller'
);

-- happy path: fresh insert under A's own goal
insert into t_result (label, result)
select 'insert', public.rpc_upsert_milestone(
    'a1000002-0000-4000-8000-000000000002'::uuid,
    0,
    jsonb_build_object('id', 'd0000000-0000-4000-8000-000000000002', 'goal_id',
                        'a0000000-0000-4000-8000-000000000001', 'title', 'Test Milestone')
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
    (select count(*) from public.mutation_receipts
     where owner_id = '00000000-0000-0000-0000-000000000001'
       and operation_id = 'a1000002-0000-4000-8000-000000000002')::int,
    1,
    'a receipt was written for the insert operation_id'
);

-- idempotent replay
insert into t_result (label, result)
select 'replay', public.rpc_upsert_milestone(
    'a1000002-0000-4000-8000-000000000002'::uuid,
    0,
    jsonb_build_object('id', 'd0000000-0000-4000-8000-000000000002', 'goal_id',
                        'a0000000-0000-4000-8000-000000000001', 'title', 'Different Title')
);

select is(
    (select result from t_result where label = 'replay'),
    (select result from t_result where label = 'insert'),
    'replay of the same operation_id returns the exact stored result'
);
select is(
    (select version from public.milestones where id = 'd0000000-0000-4000-8000-000000000002'),
    1::bigint,
    'replay does not double-apply: version is still 1'
);

-- proper update at the correct expected_version
insert into t_result (label, result)
select 'update', public.rpc_upsert_milestone(
    'a1000003-0000-4000-8000-000000000003'::uuid,
    1,
    jsonb_build_object('id', 'd0000000-0000-4000-8000-000000000002', 'goal_id',
                        'a0000000-0000-4000-8000-000000000001', 'title', 'Renamed Milestone')
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
select 'stale', public.rpc_upsert_milestone(
    'a1000004-0000-4000-8000-000000000004'::uuid,
    1,
    jsonb_build_object('id', 'd0000000-0000-4000-8000-000000000002', 'goal_id',
                        'a0000000-0000-4000-8000-000000000001', 'title', 'Should not apply')
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
    (select title from public.milestones where id = 'd0000000-0000-4000-8000-000000000002'),
    'Renamed Milestone',
    'conflict left the row untouched (title unchanged)'
);
select is(
    (select count(*) from public.mutation_receipts
     where owner_id = '00000000-0000-0000-0000-000000000001'
       and operation_id = 'a1000004-0000-4000-8000-000000000004')::int,
    0,
    'conflict does not write a receipt'
);

-- delete tombstone at the correct expected_version
insert into t_result (label, result)
select 'delete', public.rpc_delete_milestone(
    'a1000005-0000-4000-8000-000000000005'::uuid,
    2,
    'd0000000-0000-4000-8000-000000000002'
);

select is(
    (select result->>'outcome' from t_result where label = 'delete'),
    'applied',
    'delete at the correct expected_version returns outcome=applied'
);
select is(
    (select count(*) from public.milestones where id = 'd0000000-0000-4000-8000-000000000002')::int,
    0,
    'the row is actually gone after delete'
);

select * from finish();
rollback;
