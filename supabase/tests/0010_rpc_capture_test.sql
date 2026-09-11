-- pgTAP: rpc_upsert_capture / rpc_delete_capture -- clarified_task_id ownership validation
-- (the enforcement point for the plain, non-composite FK -- see the captures migration for
-- why), happy path, idempotent replay, version conflict (contract §2).
begin;
create extension if not exists pgtap with schema extensions;
select plan(16);

select has_function('public', 'rpc_upsert_capture', array['uuid', 'bigint', 'jsonb'], 'rpc_upsert_capture exists');
select has_function('public', 'rpc_delete_capture', array['uuid', 'bigint', 'uuid'], 'rpc_delete_capture exists');

insert into auth.users (id, email)
values ('00000000-0000-0000-0000-000000000001', 'a@test.local'),
       ('00000000-0000-0000-0000-000000000002', 'b@test.local');

insert into public.tasks (id, owner_id, title)
values ('a2000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000001', 'A''s task'),
       ('b2000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000002', 'B''s task');

create temporary table t_result (label text, result jsonb);
-- role switches below: the temp table must stay writable to client roles
grant all on t_result to authenticated, anon;

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000000001","role":"authenticated"}';

-- clarified_task_id ownership validation
select throws_ok(
    $$select public.rpc_upsert_capture(
        'a3000001-0000-4000-8000-000000000001'::uuid,
        0,
        jsonb_build_object('id', 'f0000000-0000-4000-8000-000000000001', 'body', 'stolen task link',
                            'clarified_task_id', 'b2000000-0000-4000-8000-000000000002')
    )$$,
    '42501',
    'clarified_task_id does not belong to the caller',
    'rpc_upsert_capture rejects a clarified_task_id owned by a different caller'
);

-- happy path
insert into t_result (label, result)
select 'insert', public.rpc_upsert_capture(
    'a3000002-0000-4000-8000-000000000002'::uuid,
    0,
    jsonb_build_object('id', 'f0000000-0000-4000-8000-000000000002', 'body', 'Call the dentist',
                        'clarified_task_id', 'a2000000-0000-4000-8000-000000000001')
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
       and operation_id = 'a3000002-0000-4000-8000-000000000002')::int,
    1,
    'a receipt was written for the insert operation_id'
);

-- idempotent replay
insert into t_result (label, result)
select 'replay', public.rpc_upsert_capture(
    'a3000002-0000-4000-8000-000000000002'::uuid,
    0,
    jsonb_build_object('id', 'f0000000-0000-4000-8000-000000000002', 'body', 'Different body')
);

select is(
    (select result from t_result where label = 'replay'),
    (select result from t_result where label = 'insert'),
    'replay of the same operation_id returns the exact stored result'
);
select is(
    (select version from public.captures where id = 'f0000000-0000-4000-8000-000000000002'),
    1::bigint,
    'replay does not double-apply: version is still 1'
);

-- proper update at the correct expected_version
insert into t_result (label, result)
select 'update', public.rpc_upsert_capture(
    'a3000003-0000-4000-8000-000000000003'::uuid,
    1,
    jsonb_build_object('id', 'f0000000-0000-4000-8000-000000000002', 'body', 'Call the dentist tomorrow')
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
select 'stale', public.rpc_upsert_capture(
    'a3000004-0000-4000-8000-000000000004'::uuid,
    1,
    jsonb_build_object('id', 'f0000000-0000-4000-8000-000000000002', 'body', 'Should not apply')
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
    (select body from public.captures where id = 'f0000000-0000-4000-8000-000000000002'),
    'Call the dentist tomorrow',
    'conflict left the row untouched (body unchanged)'
);
select is(
    (select count(*) from public.mutation_receipts
     where owner_id = '00000000-0000-0000-0000-000000000001'
       and operation_id = 'a3000004-0000-4000-8000-000000000004')::int,
    0,
    'conflict does not write a receipt'
);

-- delete tombstone at the correct expected_version
insert into t_result (label, result)
select 'delete', public.rpc_delete_capture(
    'a3000005-0000-4000-8000-000000000005'::uuid,
    2,
    'f0000000-0000-4000-8000-000000000002'
);

select is(
    (select result->>'outcome' from t_result where label = 'delete'),
    'applied',
    'delete at the correct expected_version returns outcome=applied'
);
select is(
    (select count(*) from public.captures where id = 'f0000000-0000-4000-8000-000000000002')::int,
    0,
    'the row is actually gone after delete'
);

select * from finish();
rollback;
