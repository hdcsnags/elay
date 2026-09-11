-- pgTAP: captures RLS allow/deny (owner, peer, anonymous) + CHECK bounds (contract §1).
-- Note: captures.clarified_task_id is a plain FK (see supabase/migrations/*_captures.sql for
-- why it isn't a composite owner-aware FK), so there is no DB-level composite-FK cross-owner
-- case to test here; ownership of clarified_task_id is enforced by rpc_upsert_capture and
-- covered in supabase/tests/0010_rpc_capture_test.sql instead.
begin;
create extension if not exists pgtap with schema extensions;
select plan(12);

select has_table('public', 'captures', 'captures table exists');
select ok(
    (select relrowsecurity from pg_class where oid = 'public.captures'::regclass),
    'RLS is enabled on captures'
);

insert into auth.users (id, email)
values ('00000000-0000-0000-0000-000000000001', 'a@test.local'),
       ('00000000-0000-0000-0000-000000000002', 'b@test.local');

insert into public.captures (id, owner_id, body)
values ('a3000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000001', 'A''s capture'),
       ('b3000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000002', 'B''s capture');

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000000001","role":"authenticated"}';

select results_eq(
    'select id from public.captures',
    $$values ('a3000000-0000-4000-8000-000000000001'::uuid)$$,
    'A sees exactly one capture: their own'
);

select lives_ok(
    $$update public.captures set body = 'A''s capture (edited)'
      where id = 'a3000000-0000-4000-8000-000000000001'$$,
    'A can update their own capture'
);

select is_empty(
    $$update public.captures set body = 'pwned'
      where id = 'b3000000-0000-4000-8000-000000000002' returning 1$$,
    'A cannot update B''s capture by guessed id (0 rows affected)'
);

select is_empty(
    $$select 1 from public.captures where id = 'b3000000-0000-4000-8000-000000000002'$$,
    'A cannot read B''s capture even by guessed id'
);

select is_empty(
    $$delete from public.captures where id = 'b3000000-0000-4000-8000-000000000002' returning 1$$,
    'A cannot delete B''s capture by guessed id (0 rows affected)'
);

select throws_ok(
    $$insert into public.captures (owner_id, body)
      values ('00000000-0000-0000-0000-000000000001', repeat('x', 10001))$$,
    '23514',
    'new row for relation "captures" violates check constraint "captures_body_length_check"',
    'body CHECK rejects a body over 10000 characters'
);

select throws_ok(
    $$insert into public.captures (owner_id, body)
      values ('00000000-0000-0000-0000-000000000001', '')$$,
    '23514',
    'new row for relation "captures" violates check constraint "captures_body_length_check"',
    'body CHECK rejects an empty body'
);

select throws_ok(
    $$insert into public.captures (owner_id, body, source)
      values ('00000000-0000-0000-0000-000000000001', 'bad source', 'carrier-pigeon')$$,
    '23514',
    'new row for relation "captures" violates check constraint "captures_source_check"',
    'source CHECK rejects an enum value outside the allowed set'
);

select throws_ok(
    $$insert into public.captures (owner_id, body, ai_parse_status)
      values ('00000000-0000-0000-0000-000000000001', 'bad status', 'confused')$$,
    '23514',
    'new row for relation "captures" violates check constraint "captures_ai_parse_status_check"',
    'ai_parse_status CHECK rejects an enum value outside the allowed set'
);

set local role anon;
set local request.jwt.claims to '{}';

select throws_ok(
    'select id from public.captures',
    '42501',
    'permission denied for table captures',
    'anon has no grant on captures at all'
);

select * from finish();
rollback;
