-- pgTAP: rpc_upsert_goal / rpc_delete_goal -- happy path, idempotent replay, version
-- conflict, delete tombstone + replay + conflict-on-gone-row (contract §2).
-- Results are staged in a temp table rather than psql \gset, since the harness may run
-- these files through pg_prove (no client-side meta-commands available).
begin;
create extension if not exists pgtap with schema extensions;
select plan(17);

select has_function('public', 'rpc_upsert_goal', array['uuid', 'bigint', 'jsonb'], 'rpc_upsert_goal exists');
select has_function('public', 'rpc_delete_goal', array['uuid', 'bigint', 'uuid'], 'rpc_delete_goal exists');

insert into auth.users (id, email) values ('00000000-0000-0000-0000-000000000001', 'a@test.local');

create temporary table t_result (label text, result jsonb);
-- role switches below: the temp table must stay writable to client roles
grant all on t_result to authenticated, anon;

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000000001","role":"authenticated"}';

-- happy path: fresh insert (p_expected_version = 0)
insert into t_result (label, result)
select 'insert', public.rpc_upsert_goal(
    'a0000001-0000-4000-8000-000000000001'::uuid,
    0,
    jsonb_build_object('id', 'c0000000-0000-4000-8000-000000000001', 'title', 'Test Goal', 'status', 'active')
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
    (select version from public.goals where id = 'c0000000-0000-4000-8000-000000000001'),
    1::bigint,
    'stored row version is 1 after the insert'
);
select is(
    (select count(*) from public.mutation_receipts
     where owner_id = '00000000-0000-0000-0000-000000000001'
       and operation_id = 'a0000001-0000-4000-8000-000000000001')::int,
    1,
    'a receipt was written for the insert operation_id'
);

-- idempotent replay: same operation_id, even with a different (ignored) payload
insert into t_result (label, result)
select 'replay', public.rpc_upsert_goal(
    'a0000001-0000-4000-8000-000000000001'::uuid,
    0,
    jsonb_build_object('id', 'c0000000-0000-4000-8000-000000000001', 'title', 'Different Title', 'status', 'active')
);

select is(
    (select result from t_result where label = 'replay'),
    (select result from t_result where label = 'insert'),
    'replay of the same operation_id returns the exact stored result'
);
select is(
    (select version from public.goals where id = 'c0000000-0000-4000-8000-000000000001'),
    1::bigint,
    'replay does not double-apply: version is still 1'
);
select is(
    (select title from public.goals where id = 'c0000000-0000-4000-8000-000000000001'),
    'Test Goal',
    'replay did not pick up the different (ignored) payload'
);

-- proper update at the correct expected_version
insert into t_result (label, result)
select 'update', public.rpc_upsert_goal(
    'a0000002-0000-4000-8000-000000000002'::uuid,
    1,
    jsonb_build_object('id', 'c0000000-0000-4000-8000-000000000001', 'title', 'Renamed Goal', 'status', 'paused')
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
select 'stale', public.rpc_upsert_goal(
    'a0000003-0000-4000-8000-000000000003'::uuid,
    1,
    jsonb_build_object('id', 'c0000000-0000-4000-8000-000000000001', 'title', 'Should not apply', 'status', 'archived')
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
    (select title from public.goals where id = 'c0000000-0000-4000-8000-000000000001'),
    'Renamed Goal',
    'conflict left the row untouched (title unchanged)'
);
select is(
    (select count(*) from public.mutation_receipts
     where owner_id = '00000000-0000-0000-0000-000000000001'
       and operation_id = 'a0000003-0000-4000-8000-000000000003')::int,
    0,
    'conflict does not write a receipt'
);

-- delete tombstone at the correct expected_version
insert into t_result (label, result)
select 'delete', public.rpc_delete_goal(
    'a0000004-0000-4000-8000-000000000004'::uuid,
    2,
    'c0000000-0000-4000-8000-000000000001'
);

select is(
    (select result->>'outcome' from t_result where label = 'delete'),
    'applied',
    'delete at the correct expected_version returns outcome=applied'
);
select is(
    (select count(*) from public.goals where id = 'c0000000-0000-4000-8000-000000000001')::int,
    0,
    'the row is actually gone after delete'
);

select * from finish();
rollback;
