-- pgTAP: F9 fix (20260912180000_delete_shared_lock_guard.sql) -- rpc_delete_time_block on a
-- shared_lock block must return a clean domain conflict, never the raw commitments FK
-- violation (23503) the unguarded version raised. Pairs A/B via the real pairing RPCs,
-- negotiates+accepts a real proposal to mint the shared_lock blocks the same way live
-- traffic does (no test-only row fabrication), then exercises the guard from the owning
-- side, and separately confirms a plain personal block is entirely unaffected.
--
-- LEAD-VERIFIED REQUIRED: this seat cannot run pgTAP/supabase locally (constraints). Every
-- assertion below is written and reviewed but UNVERIFIED until the lead runs it against the
-- live harness.

begin;
create extension if not exists pgtap with schema extensions;
select plan(14);

select has_function(
    'public', 'rpc_delete_time_block', array['uuid', 'bigint', 'uuid'],
    'rpc_delete_time_block keeps its exact signature after the F9 fix'
);
select ok(
    not (select prosecdef from pg_proc where oid = 'public.rpc_delete_time_block(uuid, bigint, uuid)'::regprocedure),
    'rpc_delete_time_block remains security invoker, unchanged from the original (house style: match the original except the guard)'
);

-- ===================================================================================
-- setup: pair A/B via the real pairing RPCs, then negotiate+accept a real proposal so the
-- shared_lock blocks are minted exactly the way live traffic mints them.
-- ===================================================================================
insert into auth.users (id, email) values
    ('00000000-0000-0000-0000-0000000000a1', 'a1@test.local'),
    ('00000000-0000-0000-0000-0000000000b1', 'b1@test.local');

insert into public.profiles (user_id, display_name, home_tz) values
    ('00000000-0000-0000-0000-0000000000a1', 'User A', 'America/Toronto'),
    ('00000000-0000-0000-0000-0000000000b1', 'User B', 'America/Vancouver')
on conflict (user_id) do update set display_name = excluded.display_name, home_tz = excluded.home_tz;

create temporary table t_result (label text, result jsonb);
grant all on t_result to authenticated, anon;

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}';
insert into t_result (label, result) select 'a_invite', public.rpc_create_pair_invite('c0000000-0000-4000-8000-000000000001'::uuid, 60);

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000b1","role":"authenticated"}';
insert into t_result (label, result) select 'b_redeem', public.rpc_redeem_pair_invite(
    'c0000000-0000-4000-8000-000000000002'::uuid, (select result->'invite'->>'code' from t_result where label = 'a_invite'));
select is((select result->>'outcome' from t_result where label = 'b_redeem'), 'applied', 'setup: A/B paired');

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}';
insert into t_result (label, result) select 'p1_create', public.rpc_create_proposal(
    'c0000000-0000-4000-8000-000000000003'::uuid, 'Study session', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-20T23:00:00Z","ends_at_utc":"2026-09-21T00:00:00Z","duration_min":60}]'::jsonb
);
select is((select result->>'outcome' from t_result where label = 'p1_create'), 'applied', 'setup: A creates a proposal');

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000b1","role":"authenticated"}';
insert into t_result (label, result) select 'b_accept', public.rpc_respond_proposal(
    'c0000000-0000-4000-8000-000000000004'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create'),
    1, 'accept', 0
);
select is((select result->>'outcome' from t_result where label = 'b_accept'), 'applied', 'setup: B accepts candidate 0 -> mints shared_lock blocks');
select is((select result->'proposal'->>'status' from t_result where label = 'b_accept'), 'accepted', 'setup: proposal is now accepted');

reset role;
select is(
    (select count(*)::int from public.time_blocks
     where source_proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create')
       and type = 'shared_lock'),
    2,
    'setup: exactly two shared_lock blocks exist for this proposal, one per member'
);

-- Capture A's own shared_lock block id + current version (bypassing RLS, same pattern as
-- 0015's T-PROJECTION setup) -- A reads it again as themself below under normal RLS.
insert into t_result (label, result)
select 'a_block', jsonb_build_object('id', tb.id, 'version', tb.version)
from public.time_blocks tb
where tb.owner_id = '00000000-0000-0000-0000-0000000000a1'
  and tb.source_proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create');

-- ===================================================================================
-- the guard: A (the owning member) attempts to delete their own shared_lock block.
-- ===================================================================================
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}';

insert into t_result (label, result) select 'delete_shared_lock', public.rpc_delete_time_block(
    'c0000000-0000-4000-8000-000000000005'::uuid,
    (select (result->>'version')::bigint from t_result where label = 'a_block'),
    (select (result->>'id')::uuid from t_result where label = 'a_block')
);
select is(
    (select result->>'outcome' from t_result where label = 'delete_shared_lock'),
    'conflict',
    'F9: deleting a shared_lock block returns a clean conflict, never raises the raw commitments FK violation'
);
select is(
    (select result->>'reason' from t_result where label = 'delete_shared_lock'),
    'shared_lock',
    'F9: the conflict carries reason=shared_lock'
);
select is(
    (select count(*)::int from public.time_blocks
     where id = (select (result->>'id')::uuid from t_result where label = 'a_block')),
    1,
    'F9: the shared_lock block is still present -- the guard refused the delete, it did not cascade it'
);
select is(
    (select count(*)::int from public.mutation_receipts
     where owner_id = '00000000-0000-0000-0000-0000000000a1'
       and operation_id = 'c0000000-0000-4000-8000-000000000005'),
    0,
    'F9: the guarded conflict does not write a mutation_receipts row (matches the plain version-conflict branch)'
);

-- ===================================================================================
-- unaffected path: a plain personal block (no commitment, type=personal) still deletes fine.
-- ===================================================================================
insert into t_result (label, result) select 'personal_create', public.rpc_upsert_time_block(
    'c0000000-0000-4000-8000-000000000006'::uuid,
    0,
    jsonb_build_object(
        'id', 'd0000000-0000-4000-8000-000000000001',
        'title', 'Just a personal block',
        'starts_at_utc', '2026-09-22T19:00:00Z',
        'ends_at_utc', '2026-09-22T20:00:00Z',
        'origin_tz', 'America/Toronto'
    )
);
select is((select result->>'outcome' from t_result where label = 'personal_create'), 'applied', 'setup: A creates a plain personal block');

insert into t_result (label, result) select 'personal_delete', public.rpc_delete_time_block(
    'c0000000-0000-4000-8000-000000000007'::uuid,
    1,
    'd0000000-0000-4000-8000-000000000001'
);
select is(
    (select result->>'outcome' from t_result where label = 'personal_delete'),
    'applied',
    'F9 regression check: a plain personal block (no commitment) still deletes normally'
);
select is(
    (select count(*)::int from public.time_blocks where id = 'd0000000-0000-4000-8000-000000000001'),
    0,
    'the personal block is actually gone after delete'
);

select * from finish();
rollback;
