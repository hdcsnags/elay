-- pgTAP: milestones RLS allow/deny (owner via goal, peer, anonymous) + CHECK bounds (contract §1).
begin;
create extension if not exists pgtap with schema extensions;
select plan(13);

select has_table('public', 'milestones', 'milestones table exists');
select ok(
    (select relrowsecurity from pg_class where oid = 'public.milestones'::regclass),
    'RLS is enabled on milestones'
);

-- seed two users, one goal each, one milestone each (postgres role bypasses RLS here)
insert into auth.users (id, email)
values ('00000000-0000-0000-0000-000000000001', 'a@test.local'),
       ('00000000-0000-0000-0000-000000000002', 'b@test.local');

insert into public.goals (id, owner_id, title)
values ('a0000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000001', 'A''s goal'),
       ('b0000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000002', 'B''s goal');

insert into public.milestones (id, goal_id, title)
values ('a1000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000001', 'A''s milestone'),
       ('b1000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000002', 'B''s milestone');

-- act as user A (authenticated)
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000000001","role":"authenticated"}';

select results_eq(
    'select id from public.milestones',
    $$values ('a1000000-0000-4000-8000-000000000001'::uuid)$$,
    'A sees exactly one milestone: the one under their own goal'
);

select lives_ok(
    $$update public.milestones set title = 'A''s milestone (renamed)'
      where id = 'a1000000-0000-4000-8000-000000000001'$$,
    'A can update a milestone under their own goal'
);

select is_empty(
    $$update public.milestones set title = 'pwned'
      where id = 'b1000000-0000-4000-8000-000000000002' returning 1$$,
    'A cannot update B''s milestone by guessed id (0 rows affected)'
);

select is_empty(
    $$select 1 from public.milestones where id = 'b1000000-0000-4000-8000-000000000002'$$,
    'A cannot read B''s milestone even by guessed id'
);

select is_empty(
    $$delete from public.milestones where id = 'b1000000-0000-4000-8000-000000000002' returning 1$$,
    'A cannot delete B''s milestone by guessed id (0 rows affected)'
);

select throws_ok(
    $$insert into public.milestones (goal_id, title)
      values ('b0000000-0000-4000-8000-000000000002', 'planted under B''s goal')$$,
    '42501',
    'new row violates row-level security policy for table "milestones"',
    'A cannot create a milestone under a goal owned by B'
);

select throws_ok(
    $$insert into public.milestones (goal_id, title)
      values ('a0000000-0000-4000-8000-000000000001', repeat('x', 201))$$,
    '23514',
    'new row for relation "milestones" violates check constraint "milestones_title_length_check"',
    'title CHECK rejects a title over 200 characters'
);

select throws_ok(
    $$insert into public.milestones (goal_id, title)
      values ('a0000000-0000-4000-8000-000000000001', '')$$,
    '23514',
    'new row for relation "milestones" violates check constraint "milestones_title_length_check"',
    'title CHECK rejects an empty title'
);

select throws_ok(
    $$insert into public.milestones (goal_id, title, sort_order)
      values ('a0000000-0000-4000-8000-000000000001', 'negative sort', -1)$$,
    '23514',
    'new row for relation "milestones" violates check constraint "milestones_sort_order_check"',
    'sort_order CHECK rejects a negative value'
);

select throws_ok(
    $$insert into public.milestones (goal_id, title, status)
      values ('a0000000-0000-4000-8000-000000000001', 'bad status', 'done')$$,
    '23514',
    'new row for relation "milestones" violates check constraint "milestones_status_check"',
    'status CHECK rejects an enum value outside the allowed set'
);

-- act as anonymous
set local role anon;
set local request.jwt.claims to '{}';

select throws_ok(
    'select id from public.milestones',
    '42501',
    'permission denied for table milestones',
    'anon has no grant on milestones at all'
);

select * from finish();
rollback;
