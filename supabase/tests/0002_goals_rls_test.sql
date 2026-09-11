-- pgTAP: goals RLS allow/deny (owner, peer, anonymous) + CHECK bounds (contract §1).
begin;
create extension if not exists pgtap with schema extensions;
select plan(14);

-- structure
select has_table('public', 'goals', 'goals table exists');
select ok(
    (select relrowsecurity from pg_class where oid = 'public.goals'::regclass),
    'RLS is enabled on goals'
);

-- seed two users and one goal each (postgres role bypasses RLS here, by design)
insert into auth.users (id, email)
values ('00000000-0000-0000-0000-000000000001', 'a@test.local'),
       ('00000000-0000-0000-0000-000000000002', 'b@test.local');

insert into public.goals (id, owner_id, title)
values ('a0000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000001', 'A''s goal'),
       ('b0000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000002', 'B''s goal');

-- act as user A (authenticated)
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000000001","role":"authenticated"}';

select results_eq(
    'select id from public.goals',
    $$values ('a0000000-0000-4000-8000-000000000001'::uuid)$$,
    'A sees exactly one row: their own'
);

select lives_ok(
    $$update public.goals set title = 'A''s goal (renamed)'
      where id = 'a0000000-0000-4000-8000-000000000001'$$,
    'A can update their own goal'
);

select is_empty(
    $$update public.goals set title = 'pwned'
      where id = 'b0000000-0000-4000-8000-000000000002' returning 1$$,
    'A cannot update B''s goal by guessed id (0 rows affected)'
);

select is_empty(
    $$select 1 from public.goals where id = 'b0000000-0000-4000-8000-000000000002'$$,
    'A cannot read B''s goal even by guessed id'
);

select is_empty(
    $$delete from public.goals where id = 'b0000000-0000-4000-8000-000000000002' returning 1$$,
    'A cannot delete B''s goal by guessed id (0 rows affected)'
);

-- one reject case per CHECK bound
select throws_ok(
    $$insert into public.goals (owner_id, title, household_id, visibility)
      values ('00000000-0000-0000-0000-000000000001', 'sharing not allowed yet',
              '99999999-0000-4000-8000-000000000099', 'private')$$,
    '23514',
    'new row for relation "goals" violates check constraint "goals_phase1_private_only"',
    'Phase 1 guard rejects a non-null household_id'
);

select throws_ok(
    $$insert into public.goals (owner_id, title, visibility)
      values ('00000000-0000-0000-0000-000000000001', 'bad visibility', 'full')$$,
    '23514',
    'new row for relation "goals" violates check constraint "goals_phase1_private_only"',
    'Phase 1 guard rejects a non-private visibility even with household_id null'
);

select throws_ok(
    $$insert into public.goals (owner_id, title, visibility)
      values ('00000000-0000-0000-0000-000000000001', 'bad enum', 'shared-with-everyone')$$,
    '23514',
    'new row for relation "goals" violates check constraint "goals_phase1_private_only"',
    'non-private visibility is unreachable in Phase 1: the phase1 guard rejects it first (enum CHECK becomes testable when Phase 2 drops the guard)'
);

select throws_ok(
    $$insert into public.goals (owner_id, title, status)
      values ('00000000-0000-0000-0000-000000000001', 'bad status', 'done')$$,
    '23514',
    'new row for relation "goals" violates check constraint "goals_status_check"',
    'status CHECK rejects an enum value outside the allowed set'
);

select throws_ok(
    $$insert into public.goals (owner_id, title)
      values ('00000000-0000-0000-0000-000000000001', repeat('x', 201))$$,
    '23514',
    'new row for relation "goals" violates check constraint "goals_title_length_check"',
    'title CHECK rejects a title over 200 characters'
);

select throws_ok(
    $$insert into public.goals (owner_id, title)
      values ('00000000-0000-0000-0000-000000000001', '')$$,
    '23514',
    'new row for relation "goals" violates check constraint "goals_title_length_check"',
    'title CHECK rejects an empty title'
);

-- act as anonymous
set local role anon;
set local request.jwt.claims to '{}';

select throws_ok(
    'select id from public.goals',
    '42501',
    'permission denied for table goals',
    'anon has no grant on goals at all'
);

select * from finish();
rollback;
