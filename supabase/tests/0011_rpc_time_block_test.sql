-- pgTAP: rpc_upsert_time_block / rpc_delete_time_block -- task_id ownership validation,
-- origin_tz trigger firing through the RPC path too, happy path, idempotent replay, version
-- conflict (contract §2, ADR-006).
begin;
create extension if not exists pgtap with schema extensions;
select plan(17);

select has_function('public', 'rpc_upsert_time_block', array['uuid', 'bigint', 'jsonb'], 'rpc_upsert_time_block exists');
select has_function('public', 'rpc_delete_time_block', array['uuid', 'bigint', 'uuid'], 'rpc_delete_time_block exists');

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

-- task_id ownership validation
select throws_ok(
    $$select public.rpc_upsert_time_block(
        'a4000001-0000-4000-8000-000000000001'::uuid,
        0,
        jsonb_build_object('id', '60000000-0000-4000-8000-000000000001', 'task_id',
                            'b2000000-0000-4000-8000-000000000002', 'starts_at_utc', '2026-09-11T19:00:00Z',
                            'ends_at_utc', '2026-09-11T20:00:00Z', 'origin_tz', 'UTC')
    )$$,
    '42501',
    'task_id does not belong to the caller',
    'rpc_upsert_time_block rejects a task_id owned by a different caller'
);

-- origin_tz trigger still fires on the RPC insert path
select throws_ok(
    $$select public.rpc_upsert_time_block(
        'a4000002-0000-4000-8000-000000000002'::uuid,
        0,
        jsonb_build_object('id', '60000000-0000-4000-8000-000000000002', 'starts_at_utc',
                            '2026-09-11T19:00:00Z', 'ends_at_utc', '2026-09-11T20:00:00Z',
                            'origin_tz', 'Mars/Olympus_Mons')
    )$$,
    '22023',
    'origin_tz "Mars/Olympus_Mons" is not a recognized IANA timezone name',
    'rpc_upsert_time_block still runs the origin_tz IANA-name trigger'
);

-- happy path
insert into t_result (label, result)
select 'insert', public.rpc_upsert_time_block(
    'a4000003-0000-4000-8000-000000000003'::uuid,
    0,
    jsonb_build_object(
        'id', '60000000-0000-4000-8000-000000000003',
        'task_id', 'a2000000-0000-4000-8000-000000000001',
        'title', 'Focus block',
        'starts_at_utc', '2026-09-11T19:00:00Z',
        'ends_at_utc', '2026-09-11T20:30:00Z',
        'origin_tz', 'America/Toronto',
        'type', 'focus'
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
    (select count(*) from public.mutation_receipts
     where owner_id = '00000000-0000-0000-0000-000000000001'
       and operation_id = 'a4000003-0000-4000-8000-000000000003')::int,
    1,
    'a receipt was written for the insert operation_id'
);

-- idempotent replay
insert into t_result (label, result)
select 'replay', public.rpc_upsert_time_block(
    'a4000003-0000-4000-8000-000000000003'::uuid,
    0,
    jsonb_build_object('id', '60000000-0000-4000-8000-000000000003', 'title', 'Different Title')
);

select is(
    (select result from t_result where label = 'replay'),
    (select result from t_result where label = 'insert'),
    'replay of the same operation_id returns the exact stored result'
);
select is(
    (select version from public.time_blocks where id = '60000000-0000-4000-8000-000000000003'),
    1::bigint,
    'replay does not double-apply: version is still 1'
);

-- proper update at the correct expected_version
insert into t_result (label, result)
select 'update', public.rpc_upsert_time_block(
    'a4000004-0000-4000-8000-000000000004'::uuid,
    1,
    jsonb_build_object(
        'id', '60000000-0000-4000-8000-000000000003',
        'task_id', 'a2000000-0000-4000-8000-000000000001',
        'title', 'Renamed focus block',
        'starts_at_utc', '2026-09-11T19:00:00Z',
        'ends_at_utc', '2026-09-11T20:30:00Z',
        'origin_tz', 'America/Toronto',
        'status', 'completed'
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
select 'stale', public.rpc_upsert_time_block(
    'a4000005-0000-4000-8000-000000000005'::uuid,
    1,
    jsonb_build_object(
        'id', '60000000-0000-4000-8000-000000000003',
        'starts_at_utc', '2026-09-11T19:00:00Z',
        'ends_at_utc', '2026-09-11T20:30:00Z',
        'origin_tz', 'America/Toronto',
        'title', 'Should not apply'
    )
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
    (select status from public.time_blocks where id = '60000000-0000-4000-8000-000000000003'),
    'completed',
    'conflict left the row untouched (status unchanged)'
);
select is(
    (select count(*) from public.mutation_receipts
     where owner_id = '00000000-0000-0000-0000-000000000001'
       and operation_id = 'a4000005-0000-4000-8000-000000000005')::int,
    0,
    'conflict does not write a receipt'
);

-- delete tombstone at the correct expected_version
insert into t_result (label, result)
select 'delete', public.rpc_delete_time_block(
    'a4000006-0000-4000-8000-000000000006'::uuid,
    2,
    '60000000-0000-4000-8000-000000000003'
);

select is(
    (select result->>'outcome' from t_result where label = 'delete'),
    'applied',
    'delete at the correct expected_version returns outcome=applied'
);
select is(
    (select count(*) from public.time_blocks where id = '60000000-0000-4000-8000-000000000003')::int,
    0,
    'the row is actually gone after delete'
);

select * from finish();
rollback;
