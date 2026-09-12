-- pgTAP: time_blocks RLS allow/deny (owner, peer, anonymous) + CHECK bounds + origin_tz
-- trigger + composite-FK cross-owner rejection (contract §1, ADR-006).
begin;
create extension if not exists pgtap with schema extensions;
select plan(20);

select has_table('public', 'time_blocks', 'time_blocks table exists');
select ok(
    (select relrowsecurity from pg_class where oid = 'public.time_blocks'::regclass),
    'RLS is enabled on time_blocks'
);

insert into auth.users (id, email)
values ('00000000-0000-0000-0000-000000000001', 'a@test.local'),
       ('00000000-0000-0000-0000-000000000002', 'b@test.local');

insert into public.tasks (id, owner_id, title)
values ('a2000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000001', 'A''s task'),
       ('b2000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000002', 'B''s task');

insert into public.time_blocks (id, owner_id, starts_at_utc, ends_at_utc, origin_tz, title)
values (
    'a4000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000001',
    '2026-09-11T19:00:00Z', '2026-09-11T20:00:00Z', 'America/Toronto', 'A''s block'
), (
    'b4000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000002',
    '2026-09-11T19:00:00Z', '2026-09-11T20:00:00Z', 'America/Vancouver', 'B''s block'
);

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000000001","role":"authenticated"}';

select results_eq(
    'select id from public.time_blocks',
    $$values ('a4000000-0000-4000-8000-000000000001'::uuid)$$,
    'A sees exactly one time block: their own'
);

select lives_ok(
    $$update public.time_blocks set title = 'A''s block (renamed)'
      where id = 'a4000000-0000-4000-8000-000000000001'$$,
    'A can update their own time block'
);

select is_empty(
    $$update public.time_blocks set title = 'pwned'
      where id = 'b4000000-0000-4000-8000-000000000002' returning 1$$,
    'A cannot update B''s time block by guessed id (0 rows affected)'
);

select is_empty(
    $$select 1 from public.time_blocks where id = 'b4000000-0000-4000-8000-000000000002'$$,
    'A cannot read B''s time block even by guessed id'
);

select is_empty(
    $$delete from public.time_blocks where id = 'b4000000-0000-4000-8000-000000000002' returning 1$$,
    'A cannot delete B''s time block by guessed id (0 rows affected)'
);

-- one reject case per CHECK bound
select throws_ok(
    $$insert into public.time_blocks (owner_id, starts_at_utc, ends_at_utc, origin_tz, pair_id, visibility)
      values ('00000000-0000-0000-0000-000000000001', '2026-09-11T19:00:00Z', '2026-09-11T20:00:00Z',
              'UTC', '99999999-0000-4000-8000-000000000099', 'private')$$,
    '42501',
    'new row violates row-level security policy for table "time_blocks"',
    'RLS WITH CHECK rejects naming a pair the owner is not an active member of (fires before the CHECK)'
);

select throws_ok(
    $$insert into public.time_blocks (owner_id, starts_at_utc, ends_at_utc, origin_tz, visibility)
      values ('00000000-0000-0000-0000-000000000001', '2026-09-11T19:00:00Z', '2026-09-11T20:00:00Z',
              'UTC', 'full')$$,
    '23514',
    'new row for relation "time_blocks" violates check constraint "time_blocks_visibility_pair_check"',
    'a non-private row must name a pair (Stage 1 rule)'
);

select throws_ok(
    $$insert into public.time_blocks (owner_id, starts_at_utc, ends_at_utc, origin_tz, visibility)
      values ('00000000-0000-0000-0000-000000000001', '2026-09-11T19:00:00Z', '2026-09-11T20:00:00Z',
              'UTC', 'shared-with-everyone')$$,
    '23514',
    'new row for relation "time_blocks" violates check constraint "time_blocks_visibility_check"',
    'the visibility enum CHECK is reachable again after the Stage 1 guard drop'
);

select throws_ok(
    $$insert into public.time_blocks (owner_id, starts_at_utc, ends_at_utc, origin_tz, title)
      values ('00000000-0000-0000-0000-000000000001', '2026-09-11T19:00:00Z', '2026-09-11T20:00:00Z',
              'UTC', repeat('x', 201))$$,
    '23514',
    'new row for relation "time_blocks" violates check constraint "time_blocks_title_length_check"',
    'title CHECK rejects a title over 200 characters'
);

select throws_ok(
    $$insert into public.time_blocks (owner_id, starts_at_utc, ends_at_utc, origin_tz, title)
      values ('00000000-0000-0000-0000-000000000001', '2026-09-11T19:00:00Z', '2026-09-11T20:00:00Z',
              'UTC', '')$$,
    '23514',
    'new row for relation "time_blocks" violates check constraint "time_blocks_title_length_check"',
    'title CHECK rejects an empty (but non-null) title'
);

select throws_ok(
    $$insert into public.time_blocks (owner_id, starts_at_utc, ends_at_utc, origin_tz)
      values ('00000000-0000-0000-0000-000000000001', '2026-09-11T20:00:00Z', '2026-09-11T19:00:00Z', 'UTC')$$,
    '23514',
    'new row for relation "time_blocks" violates check constraint "time_blocks_duration_bounds_check"',
    'end<=start is rejected (also violates duration bounds, which fires first deterministically)'
);

select throws_ok(
    $$insert into public.time_blocks (owner_id, starts_at_utc, ends_at_utc, origin_tz)
      values ('00000000-0000-0000-0000-000000000001', '2026-09-11T19:00:00Z', '2026-09-11T19:00:30Z', 'UTC')$$,
    '23514',
    'new row for relation "time_blocks" violates check constraint "time_blocks_duration_bounds_check"',
    'duration CHECK rejects a block shorter than 1 minute'
);

select throws_ok(
    $$insert into public.time_blocks (owner_id, starts_at_utc, ends_at_utc, origin_tz)
      values ('00000000-0000-0000-0000-000000000001', '2026-09-11T19:00:00Z', '2026-09-13T19:00:01Z', 'UTC')$$,
    '23514',
    'new row for relation "time_blocks" violates check constraint "time_blocks_duration_bounds_check"',
    'duration CHECK rejects a block longer than 24 hours'
);

select throws_ok(
    $$insert into public.time_blocks (owner_id, starts_at_utc, ends_at_utc, origin_tz, type)
      values ('00000000-0000-0000-0000-000000000001', '2026-09-11T19:00:00Z', '2026-09-11T20:00:00Z',
              'UTC', 'imaginary')$$,
    '23514',
    'new row for relation "time_blocks" violates check constraint "time_blocks_type_check"',
    'type CHECK rejects an enum value outside the allowed set'
);

select throws_ok(
    $$insert into public.time_blocks (owner_id, starts_at_utc, ends_at_utc, origin_tz, status)
      values ('00000000-0000-0000-0000-000000000001', '2026-09-11T19:00:00Z', '2026-09-11T20:00:00Z',
              'UTC', 'archived')$$,
    '23514',
    'new row for relation "time_blocks" violates check constraint "time_blocks_status_check"',
    'status CHECK rejects an enum value outside the allowed set'
);

-- origin_tz trigger: must be a real IANA zone name (ADR-006), not a CHECK constraint
select throws_ok(
    $$insert into public.time_blocks (owner_id, starts_at_utc, ends_at_utc, origin_tz)
      values ('00000000-0000-0000-0000-000000000001', '2026-09-11T19:00:00Z', '2026-09-11T20:00:00Z',
              'Mars/Olympus_Mons')$$,
    '22023',
    'origin_tz "Mars/Olympus_Mons" is not a recognized IANA timezone name',
    'origin_tz trigger rejects a zone name not in pg_timezone_names'
);

-- composite-FK cross-owner rejection: task_id must belong to the same owner_id
select throws_ok(
    $$insert into public.time_blocks (owner_id, starts_at_utc, ends_at_utc, origin_tz, task_id)
      values ('00000000-0000-0000-0000-000000000001', '2026-09-11T19:00:00Z', '2026-09-11T20:00:00Z',
              'UTC', 'b2000000-0000-4000-8000-000000000002')$$,
    '23503',
    'insert or update on table "time_blocks" violates foreign key constraint "time_blocks_task_owner_fk"',
    'composite FK rejects a task_id owned by a different owner_id'
);

set local role anon;
set local request.jwt.claims to '{}';

select throws_ok(
    'select id from public.time_blocks',
    '42501',
    'permission denied for table time_blocks',
    'anon has no grant on time_blocks at all'
);

select * from finish();
rollback;
