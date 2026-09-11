-- pgTAP: profiles RLS allow/deny (owner, peer, anonymous) — the Gate 0b harness proof.
begin;
create extension if not exists pgtap with schema extensions;
select plan(8);

-- structure
select has_table('public', 'profiles', 'profiles table exists');
select ok(
    (select relrowsecurity from pg_class where oid = 'public.profiles'::regclass),
    'RLS is enabled on profiles'
);

-- seed two users (postgres role bypasses RLS here, by design)
insert into auth.users (id, email)
values ('00000000-0000-0000-0000-000000000001', 'a@test.local'),
       ('00000000-0000-0000-0000-000000000002', 'b@test.local');
insert into public.profiles (user_id, display_name, home_tz)
values ('00000000-0000-0000-0000-000000000001', 'User A', 'America/Toronto'),
       ('00000000-0000-0000-0000-000000000002', 'User B', 'America/Vancouver');

-- act as user A (authenticated)
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000000001","role":"authenticated"}';

select results_eq(
    'select user_id from public.profiles',
    $$values ('00000000-0000-0000-0000-000000000001'::uuid)$$,
    'A sees exactly one row: their own'
);
select lives_ok(
    $$update public.profiles set display_name = 'User A2'
      where user_id = '00000000-0000-0000-0000-000000000001'$$,
    'A can update their own profile'
);
select is_empty(
    $$update public.profiles set display_name = 'pwned'
      where user_id = '00000000-0000-0000-0000-000000000002' returning 1$$,
    'A cannot update B (0 rows affected)'
);
select is_empty(
    $$select 1 from public.profiles
      where user_id = '00000000-0000-0000-0000-000000000002'$$,
    'A cannot read B even by guessed id'
);
select throws_ok(
    $$insert into public.profiles (user_id, display_name)
      values ('00000000-0000-0000-0000-000000000002', 'spoof')$$,
    '42501',
    'new row violates row-level security policy for table "profiles"',
    'A cannot insert a profile for B'
);

-- act as anonymous
set local role anon;
set local request.jwt.claims to '{}';
select throws_ok(
    'select user_id from public.profiles',
    '42501',
    'permission denied for table profiles',
    'anon has no grant on profiles at all'
);

select * from finish();
rollback;
