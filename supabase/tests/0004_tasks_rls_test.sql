-- pgTAP: tasks RLS allow/deny (owner, peer, anonymous) + CHECK bounds + composite-FK
-- cross-owner rejection (contract §1).
begin;
create extension if not exists pgtap with schema extensions;
select plan(19);

select has_table('public', 'tasks', 'tasks table exists');
select ok(
    (select relrowsecurity from pg_class where oid = 'public.tasks'::regclass),
    'RLS is enabled on tasks'
);

-- seed two users, one goal each (owner_id needed for the composite FK cross-owner test)
insert into auth.users (id, email)
values ('00000000-0000-0000-0000-000000000001', 'a@test.local'),
       ('00000000-0000-0000-0000-000000000002', 'b@test.local');

insert into public.goals (id, owner_id, title)
values ('a0000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000001', 'A''s goal'),
       ('b0000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000002', 'B''s goal');

insert into public.tasks (id, owner_id, title)
values ('a2000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000001', 'A''s task'),
       ('b2000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000002', 'B''s task');

-- act as user A (authenticated)
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000000001","role":"authenticated"}';

select results_eq(
    'select id from public.tasks',
    $$values ('a2000000-0000-4000-8000-000000000001'::uuid)$$,
    'A sees exactly one task: their own'
);

select lives_ok(
    $$update public.tasks set title = 'A''s task (renamed)'
      where id = 'a2000000-0000-4000-8000-000000000001'$$,
    'A can update their own task'
);

select is_empty(
    $$update public.tasks set title = 'pwned'
      where id = 'b2000000-0000-4000-8000-000000000002' returning 1$$,
    'A cannot update B''s task by guessed id (0 rows affected)'
);

select is_empty(
    $$select 1 from public.tasks where id = 'b2000000-0000-4000-8000-000000000002'$$,
    'A cannot read B''s task even by guessed id'
);

select is_empty(
    $$delete from public.tasks where id = 'b2000000-0000-4000-8000-000000000002' returning 1$$,
    'A cannot delete B''s task by guessed id (0 rows affected)'
);

-- one reject case per CHECK bound
select throws_ok(
    $$insert into public.tasks (owner_id, title, household_id, visibility)
      values ('00000000-0000-0000-0000-000000000001', 'sharing not allowed yet',
              '99999999-0000-4000-8000-000000000099', 'private')$$,
    '23514',
    'new row for relation "tasks" violates check constraint "tasks_phase1_private_only"',
    'Phase 1 guard rejects a non-null household_id'
);

select throws_ok(
    $$insert into public.tasks (owner_id, title, visibility)
      values ('00000000-0000-0000-0000-000000000001', 'bad visibility', 'full')$$,
    '23514',
    'new row for relation "tasks" violates check constraint "tasks_phase1_private_only"',
    'Phase 1 guard rejects a non-private visibility even with household_id null'
);

select throws_ok(
    $$insert into public.tasks (owner_id, title, visibility)
      values ('00000000-0000-0000-0000-000000000001', 'bad enum', 'shared-with-everyone')$$,
    '23514',
    'new row for relation "tasks" violates check constraint "tasks_phase1_private_only"',
    'non-private visibility is unreachable in Phase 1: the phase1 guard rejects it first (enum CHECK becomes testable when Phase 2 drops the guard)'
);

select throws_ok(
    $$insert into public.tasks (owner_id, title)
      values ('00000000-0000-0000-0000-000000000001', repeat('x', 201))$$,
    '23514',
    'new row for relation "tasks" violates check constraint "tasks_title_length_check"',
    'title CHECK rejects a title over 200 characters'
);

select throws_ok(
    $$insert into public.tasks (owner_id, title)
      values ('00000000-0000-0000-0000-000000000001', '')$$,
    '23514',
    'new row for relation "tasks" violates check constraint "tasks_title_length_check"',
    'title CHECK rejects an empty title'
);

select throws_ok(
    $$insert into public.tasks (owner_id, title, status)
      values ('00000000-0000-0000-0000-000000000001', 'bad status', 'done')$$,
    '23514',
    'new row for relation "tasks" violates check constraint "tasks_status_check"',
    'status CHECK rejects an enum value outside the allowed set'
);

select throws_ok(
    $$insert into public.tasks (owner_id, title, priority)
      values ('00000000-0000-0000-0000-000000000001', 'bad priority', 4)$$,
    '23514',
    'new row for relation "tasks" violates check constraint "tasks_priority_check"',
    'priority CHECK rejects a value outside 0..3'
);

select throws_ok(
    $$insert into public.tasks (owner_id, title, effort)
      values ('00000000-0000-0000-0000-000000000001', 'bad effort', 0)$$,
    '23514',
    'new row for relation "tasks" violates check constraint "tasks_effort_check"',
    'effort CHECK rejects a value outside 1..5'
);

select throws_ok(
    $$insert into public.tasks (owner_id, title, estimate_min)
      values ('00000000-0000-0000-0000-000000000001', 'bad estimate', 1441)$$,
    '23514',
    'new row for relation "tasks" violates check constraint "tasks_estimate_min_check"',
    'estimate_min CHECK rejects a value outside 1..1440'
);

select throws_ok(
    $$insert into public.tasks (owner_id, title, due_start_utc, due_end_utc)
      values ('00000000-0000-0000-0000-000000000001', 'bad due range',
              '2026-09-11T20:00:00Z', '2026-09-11T19:00:00Z')$$,
    '23514',
    'new row for relation "tasks" violates check constraint "tasks_due_range_check"',
    'due_end_utc CHECK rejects an end before the start'
);

-- composite-FK cross-owner rejection: goal_id must belong to the same owner_id
select throws_ok(
    $$insert into public.tasks (owner_id, title, goal_id)
      values ('00000000-0000-0000-0000-000000000001', 'stolen goal link',
              'b0000000-0000-4000-8000-000000000002')$$,
    '23503',
    'insert or update on table "tasks" violates foreign key constraint "tasks_goal_owner_fk"',
    'composite FK rejects a goal_id owned by a different owner_id'
);

-- act as anonymous
set local role anon;
set local request.jwt.claims to '{}';

select throws_ok(
    'select id from public.tasks',
    '42501',
    'permission denied for table tasks',
    'anon has no grant on tasks at all'
);

select * from finish();
rollback;
