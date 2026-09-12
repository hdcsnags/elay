-- pgTAP: Stage 1 pairing lifecycle + the full §6 adversarial suite from
-- council/stage1-pairing-contract-sol.md (contracts/stage1-pairing.md).
--
-- Sol §6 mandatory list, and where each is covered below:
--   anonymous denial                          -> T-ANON-*
--   direct-table write denial                 -> T-DIRECT-WRITE-*
--   guessed pair/topic denial                 -> T-GUESS-PAIR (topic guessing lives in 0013)
--   private-row absence                       -> T-DIRECT-SELECT-INVITES, T-FORMER-*
--   tampered pair ID                           -> T-TAMPER-PAIR-ID-*
--   invite replay                              -> T-REPLAY-CREATE
--   expiry boundary                            -> T-EXPIRY
--   revoked code                               -> T-REVOKED
--   wrong code                                 -> T-WRONG-CODE
--   self-redeem                                -> T-SELF-REDEEM
--   concurrent double-redeem                   -> T-DOUBLE-REDEEM
--   third-member race                          -> T-THIRD-MEMBER-RACE (+ T-CAP-TRIGGER direct)
--   second-pair race                           -> T-SECOND-PAIR-RACE
--   operation ID reused across RPCs            -> T-CROSS-RPC-REPLAY
--   leave replay                               -> T-LEAVE-REPLAY
--   former-member API/projection denial        -> T-FORMER-*
--   old-topic acceptance / new-event absence   -> T-TOPIC-ROTATION-*
--   remaining member's successful resubscribe  -> T-RESUBSCRIBE
--   no code/hash via tables/receipts/realtime  -> T-NO-LEAK-*
-- (exact-key projection assertions live in 0013, per the seat split.)

begin;
create extension if not exists pgtap with schema extensions;
select plan(58);

-- ===================================================================================
-- structure
-- ===================================================================================
select has_table('public', 'pairs', 'pairs table exists');
select has_table('public', 'pair_members', 'pair_members table exists');
select has_table('public', 'pair_invites', 'pair_invites table exists');

select ok((select relrowsecurity from pg_class where oid = 'public.pairs'::regclass), 'RLS is enabled on pairs');
select ok((select relrowsecurity from pg_class where oid = 'public.pair_members'::regclass), 'RLS is enabled on pair_members');
select ok((select relrowsecurity from pg_class where oid = 'public.pair_invites'::regclass), 'RLS is enabled on pair_invites');

select has_function('public', 'rpc_get_pair', array[]::name[], 'rpc_get_pair exists');
select has_function('public', 'rpc_create_pair_invite', array['uuid', 'integer'], 'rpc_create_pair_invite exists');
select has_function('public', 'rpc_redeem_pair_invite', array['uuid', 'text'], 'rpc_redeem_pair_invite exists');
select has_function('public', 'rpc_leave_pair', array['uuid'], 'rpc_leave_pair exists');

-- seed six users + profiles (postgres role bypasses RLS here, by design)
insert into auth.users (id, email) values
    ('00000000-0000-0000-0000-00000000000a', 'a@test.local'),
    ('00000000-0000-0000-0000-00000000000b', 'b@test.local'),
    ('00000000-0000-0000-0000-00000000000c', 'c@test.local'),
    ('00000000-0000-0000-0000-00000000000d', 'd@test.local'),
    ('00000000-0000-0000-0000-00000000000e', 'e@test.local'),
    ('00000000-0000-0000-0000-00000000000f', 'f@test.local');

insert into public.profiles (user_id, display_name, home_tz) values
    ('00000000-0000-0000-0000-00000000000a', 'User A', 'America/Toronto'),
    ('00000000-0000-0000-0000-00000000000b', 'User B', 'America/Vancouver'),
    ('00000000-0000-0000-0000-00000000000c', 'User C', 'America/Toronto'),
    ('00000000-0000-0000-0000-00000000000d', 'User D', 'America/Toronto'),
    ('00000000-0000-0000-0000-00000000000e', 'User E', 'America/Toronto'),
    ('00000000-0000-0000-0000-00000000000f', 'User F', 'America/Toronto')
on conflict (user_id) do update set display_name = excluded.display_name, home_tz = excluded.home_tz;

create temporary table t_result (label text, result jsonb);
grant all on t_result to authenticated, anon;

-- ===================================================================================
-- anonymous denial (T-ANON-*)
-- ===================================================================================
set local role anon;
set local request.jwt.claims to '{}';

select throws_ok('select 1 from public.pairs', '42501', 'permission denied for table pairs', 'T-ANON-1: anon has no grant on pairs');
select throws_ok('select 1 from public.pair_members', '42501', 'permission denied for table pair_members', 'T-ANON-2: anon has no grant on pair_members');
select throws_ok('select 1 from public.pair_invites', '42501', 'permission denied for table pair_invites', 'T-ANON-3: anon has no grant on pair_invites');

-- ===================================================================================
-- act as A: create a pair + invite (happy path)
-- ===================================================================================
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000000a","role":"authenticated"}';

-- T-DIRECT-WRITE: no client INSERT/UPDATE/DELETE on any pairing table (contract §1).
select throws_ok(
    $$insert into public.pairs (created_by) values ('00000000-0000-0000-0000-00000000000a')$$,
    '42501', 'permission denied for table pairs',
    'T-DIRECT-WRITE-1: authenticated cannot INSERT into pairs directly'
);
select throws_ok(
    $$insert into public.pair_members (pair_id, user_id)
      values (gen_random_uuid(), '00000000-0000-0000-0000-00000000000a')$$,
    '42501', 'permission denied for table pair_members',
    'T-DIRECT-WRITE-2: authenticated cannot INSERT into pair_members directly'
);
select throws_ok(
    'select 1 from public.pair_invites',
    '42501', 'permission denied for table pair_invites',
    'T-DIRECT-SELECT-INVITES: authenticated has no grant on pair_invites at all, not even SELECT of their own'
);

insert into t_result (label, result)
select 'a_create_1', public.rpc_create_pair_invite('10000000-0000-4000-8000-00000000000a'::uuid, 60);

select is((select result->>'outcome' from t_result where label = 'a_create_1'), 'applied', 'A creates a pair+invite: outcome=applied');
select is((select result->>'action' from t_result where label = 'a_create_1'), 'create_pair_invite', 'result carries action=create_pair_invite');
select is((select result->'pair'->>'status' from t_result where label = 'a_create_1'), 'active', 'new pair status=active');
select is(jsonb_array_length((select result->'pair'->'members' from t_result where label = 'a_create_1')), 1, 'new pair has exactly one member (A)');
select ok(
    (select result->>'code' from (select result->'invite' as result from t_result where label = 'a_create_1') s) ~ '^[0-9A-Z]{5}-[0-9A-Z]{5}$',
    'invite code matches XXXXX-XXXXX Crockford Base32 shape'
);
select ok(
    (select result->>'code' from (select result->'invite' as result from t_result where label = 'a_create_1') s) !~ '[ILOU]',
    'invite code excludes ambiguous characters I, L, O, U'
);

-- T-REPLAY-CREATE: idempotent replay of the same operation_id
insert into t_result (label, result)
select 'a_create_1_replay', public.rpc_create_pair_invite('10000000-0000-4000-8000-00000000000a'::uuid, 999);

select is(
    (select result from t_result where label = 'a_create_1_replay'),
    (select result from t_result where label = 'a_create_1'),
    'T-REPLAY-CREATE: replay of the same operation_id returns the identical stored result'
);
reset role;
select is(
    (select count(*)::int from public.pair_invites i
     where i.pair_id = (select (result->'pair'->>'pair_id')::uuid
                        from t_result where label = 'a_create_1')),
    1,
    'T-REPLAY-CREATE: replay did not create a second invite row (scoped to the test pair - a live DB may hold others)'
);
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000000a","role":"authenticated"}';

-- T-CROSS-RPC-REPLAY: the same operation_id reused by a different pairing RPC is rejected.
select throws_ok(
    $$select public.rpc_leave_pair('10000000-0000-4000-8000-00000000000a'::uuid)$$,
    '22023', 'operation_id already used for a different action',
    'T-CROSS-RPC-REPLAY: reusing A''s create-invite operation_id on rpc_leave_pair is rejected'
);

-- T-GUESS-PAIR: an unrelated user cannot see the pair by guessing its id.
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000000c","role":"authenticated"}';
select is_empty(
    format('select 1 from public.pairs where id = %L', (select result->'pair'->>'pair_id' from t_result where label = 'a_create_1')),
    'T-GUESS-PAIR: an unrelated user cannot read the pair by guessed id'
);

-- ===================================================================================
-- T-WRONG-CODE / T-SELF-REDEEM (as B and A respectively)
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000000b","role":"authenticated"}';

insert into t_result (label, result)
select 'b_wrong_code', public.rpc_redeem_pair_invite('20000000-0000-4000-8000-00000000000b'::uuid, 'ZZZZZ-ZZZZZ');

select is((select result->>'outcome' from t_result where label = 'b_wrong_code'), 'invalid_or_unavailable', 'T-WRONG-CODE: a garbage code is rejected uniformly');

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000000a","role":"authenticated"}';

insert into t_result (label, result)
select 'a_self_redeem', public.rpc_redeem_pair_invite(
    '20000000-0000-4000-8000-00000000000c'::uuid,
    (select result->'invite'->>'code' from t_result where label = 'a_create_1')
);

select is((select result->>'outcome' from t_result where label = 'a_self_redeem'), 'invalid_or_unavailable', 'T-SELF-REDEEM: the inviter cannot redeem their own code');

-- ===================================================================================
-- T-THIRD-MEMBER-RACE guard rehearsal / T-REVOKED: A creates a second invite, which
-- revokes the first.
-- ===================================================================================
insert into t_result (label, result)
select 'a_create_2', public.rpc_create_pair_invite('10000000-0000-4000-8000-00000000000d'::uuid, 60);

select is((select result->>'outcome' from t_result where label = 'a_create_2'), 'applied', 'A creates a second invite (revokes the first)');
select isnt(
    (select result->'invite'->>'code' from t_result where label = 'a_create_2'),
    (select result->'invite'->>'code' from t_result where label = 'a_create_1'),
    'the second invite has a different code from the first'
);

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000000b","role":"authenticated"}';

insert into t_result (label, result)
select 'b_redeem_revoked', public.rpc_redeem_pair_invite(
    '20000000-0000-4000-8000-00000000000e'::uuid,
    (select result->'invite'->>'code' from t_result where label = 'a_create_1')
);

select is((select result->>'outcome' from t_result where label = 'b_redeem_revoked'), 'invalid_or_unavailable', 'T-REVOKED: the first (now-revoked) code no longer works');

-- ===================================================================================
-- T-SECOND-PAIR-RACE (via C's separate pair, before A/B actually pair up)
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000000c","role":"authenticated"}';

insert into t_result (label, result)
select 'c_create_1', public.rpc_create_pair_invite('10000000-0000-4000-8000-00000000000f'::uuid, 60);

select is((select result->>'outcome' from t_result where label = 'c_create_1'), 'applied', 'C creates their own separate pair+invite');

-- B is not yet paired; A is not yet paired either (A only ever created invites for
-- their own single-member pair so far). Use A to probe "already has an active pair"
-- is NOT yet true, then redeem into B/A pair first, then prove second-pair-race using A.
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000000b","role":"authenticated"}';

insert into t_result (label, result)
select 'b_redeem_ok', public.rpc_redeem_pair_invite(
    '20000000-0000-4000-8000-000000000010'::uuid,
    (select result->'invite'->>'code' from t_result where label = 'a_create_2')
);

select is((select result->>'outcome' from t_result where label = 'b_redeem_ok'), 'applied', 'B successfully redeems A''s live invite');
select is(jsonb_array_length((select result->'pair'->'members' from t_result where label = 'b_redeem_ok')), 2, 'the pair now has two members');
select isnt(
    (select result->'pair'->>'version' from t_result where label = 'b_redeem_ok'),
    (select result->'pair'->>'version' from t_result where label = 'a_create_2'),
    'pair version was bumped by the redemption'
);

-- T-DOUBLE-REDEEM: a second, different caller/operation attempting the same
-- already-consumed code is rejected.
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000000c","role":"authenticated"}';
insert into t_result (label, result)
select 'c_double_redeem', public.rpc_redeem_pair_invite(
    '20000000-0000-4000-8000-000000000011'::uuid,
    (select result->'invite'->>'code' from t_result where label = 'a_create_2')
);
select is((select result->>'outcome' from t_result where label = 'c_double_redeem'), 'invalid_or_unavailable', 'T-DOUBLE-REDEEM: the already-redeemed code is rejected for a different caller/operation');

-- Now T-SECOND-PAIR-RACE proper: A (already actively paired with B) tries to redeem
-- C''s still-live invite.
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000000a","role":"authenticated"}';
insert into t_result (label, result)
select 'a_second_pair_race', public.rpc_redeem_pair_invite(
    '20000000-0000-4000-8000-000000000012'::uuid,
    (select result->'invite'->>'code' from t_result where label = 'c_create_1')
);
select is((select result->>'outcome' from t_result where label = 'a_second_pair_race'), 'invalid_or_unavailable', 'T-SECOND-PAIR-RACE: an already-paired caller cannot redeem into a second pair');

-- T-THIRD-MEMBER-RACE: A/B pair is now full; A tries to create yet another invite.
select throws_ok(
    $$select public.rpc_create_pair_invite('10000000-0000-4000-8000-000000000010'::uuid, 60)$$,
    '42501', 'pair already has two active members',
    'T-THIRD-MEMBER-RACE: create_invite is rejected once the pair already has two members'
);

-- T-EXPIRY: expire C's still-live invite directly (as postgres, bypassing RLS), then D
-- attempts to redeem it.
reset role;
update public.pair_invites set created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour'
where pair_id = (select result->'pair'->>'pair_id' from t_result where label = 'c_create_1')::uuid
  and redeemed_at is null and revoked_at is null;

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000000d","role":"authenticated"}';
insert into t_result (label, result)
select 'd_expired_redeem', public.rpc_redeem_pair_invite(
    '20000000-0000-4000-8000-000000000013'::uuid,
    (select result->'invite'->>'code' from t_result where label = 'c_create_1')
);
select is((select result->>'outcome' from t_result where label = 'd_expired_redeem'), 'invalid_or_unavailable', 'T-EXPIRY: an expired code is rejected');

-- ===================================================================================
-- member-cap trigger + one-active-pair-per-user index, exercised directly
-- (defense-in-depth backstop behind the RPC locking; contract §1).
-- ===================================================================================
reset role;
-- C's pair currently has only C as an active member, so this legitimately succeeds
-- (count goes 1 -> 2) and sets up the cap trigger probe below.
insert into public.pair_members (pair_id, user_id)
values ((select result->'pair'->>'pair_id' from t_result where label = 'c_create_1')::uuid, '00000000-0000-0000-0000-00000000000e');

select throws_ok(
    format(
        $$insert into public.pair_members (pair_id, user_id) values (%L, '00000000-0000-0000-0000-00000000000f')$$,
        (select result->'pair'->>'pair_id' from t_result where label = 'c_create_1')
    ),
    '23514', 'a pair may have at most two active members',
    'T-CAP-TRIGGER: direct insert of a third active member is rejected by the trigger'
);

-- A fresh, otherwise-empty pair (explicit id, no client-side meta-commands -- the harness
-- may run this through pg_prove) isolates the one-active-pair-per-user partial unique
-- index from the cap trigger: A already has an active membership elsewhere (their pair
-- with B, now solo), so inserting A here must violate 23505, not 23514.
insert into public.pairs (id, created_by)
values ('40000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-00000000000f');

select throws_ok(
    $$insert into public.pair_members (pair_id, user_id)
      values ('40000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-00000000000a')$$,
    '23505', null,
    'T-ONE-ACTIVE-PAIR-PER-USER: direct insert giving A a second active pair violates the partial unique index'
);

-- ===================================================================================
-- T-NO-LEAK-RECEIPTS: another user cannot read A's receipt (which itself legitimately
-- carries the plaintext code, only ever to the creator).
-- ===================================================================================
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000000b","role":"authenticated"}';
select is_empty(
    $$select 1 from public.mutation_receipts where owner_id = '00000000-0000-0000-0000-00000000000a'$$,
    'T-NO-LEAK-RECEIPTS: B cannot read A''s mutation_receipts rows'
);

-- ===================================================================================
-- T-TAMPER-PAIR-ID: WITH CHECK binds a non-private row to the owner's own active pair.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000000a","role":"authenticated"}';

select throws_ok(
    format(
        $$insert into public.goals (owner_id, title, visibility, pair_id)
          values ('00000000-0000-0000-0000-00000000000a', 'tampered', 'full', %L)$$,
        (select result->'pair'->>'pair_id' from t_result where label = 'c_create_1')
    ),
    '42501', null,
    'T-TAMPER-PAIR-ID-1: A cannot insert a shared goal naming a pair_id A does not belong to'
);
select lives_ok(
    format(
        $$insert into public.goals (owner_id, title, visibility, pair_id)
          values ('00000000-0000-0000-0000-00000000000a', 'shared with B', 'full', %L)$$,
        (select result->'pair'->>'pair_id' from t_result where label = 'b_redeem_ok')
    ),
    'T-TAMPER-PAIR-ID-2 (positive control): A can insert a shared goal naming A''s own active pair'
);

-- ===================================================================================
-- leave: B leaves the A/B pair.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000000b","role":"authenticated"}';

insert into t_result (label, result)
select 'b_leave', public.rpc_leave_pair('30000000-0000-4000-8000-00000000000a'::uuid);

select is((select result->>'outcome' from t_result where label = 'b_leave'), 'applied', 'B leaves the pair: outcome=applied');
select is(
    (select result->>'left_pair_id' from t_result where label = 'b_leave'),
    (select result->'pair'->>'pair_id' from t_result where label = 'b_redeem_ok'),
    'left_pair_id matches the A/B pair'
);

-- T-LEAVE-REPLAY
insert into t_result (label, result)
select 'b_leave_replay', public.rpc_leave_pair('30000000-0000-4000-8000-00000000000a'::uuid);
select is(
    (select result from t_result where label = 'b_leave_replay'),
    (select result from t_result where label = 'b_leave'),
    'T-LEAVE-REPLAY: replay of the same leave operation_id is identical'
);

-- leave when never a member (T- "fresh operation when already absent")
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000000f","role":"authenticated"}';
insert into t_result (label, result)
select 'f_leave_not_member', public.rpc_leave_pair('30000000-0000-4000-8000-00000000000b'::uuid);
select is((select result->>'outcome' from t_result where label = 'f_leave_not_member'), 'not_member', 'a fresh leave operation for a non-member returns not_member');

-- pair stays active (A remains) -- checked via A below.
reset role;
select is(
    (select status from public.pairs where id = (select result->'pair'->>'pair_id' from t_result where label = 'b_redeem_ok')::uuid),
    'active',
    'the pair remains active after only one of two members leaves'
);

-- ===================================================================================
-- T-FORMER-MEMBER: B can no longer see the pair, and rpc_get_pair/projections are empty.
-- ===================================================================================
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000000b","role":"authenticated"}';

select is_empty(
    format('select 1 from public.pairs where id = %L', (select result->'pair'->>'pair_id' from t_result where label = 'b_redeem_ok')),
    'T-FORMER-MEMBER-1: B (departed) can no longer select the pair row'
);
select is(public.rpc_get_pair(), null, 'T-FORMER-MEMBER-2: rpc_get_pair returns null for the departed member');
select is(public.rpc_list_pair_shared_goals(), '[]'::jsonb, 'T-FORMER-MEMBER-3: rpc_list_pair_shared_goals is empty for the departed member');

-- ===================================================================================
-- T-RESUBSCRIBE: A (remaining member) gets a new channel_topic after B's departure.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000000a","role":"authenticated"}';

select isnt(
    (select public.rpc_get_pair()->>'channel_topic'),
    (select result->'pair'->>'channel_topic' from t_result where label = 'b_redeem_ok'),
    'T-RESUBSCRIBE: the remaining member''s channel_topic changed after the departure'
);
select is(
    (select public.rpc_get_pair()->>'channel_topic'),
    public.caller_active_pair_topic(),
    'T-RESUBSCRIBE: caller_active_pair_topic() agrees with rpc_get_pair''s channel_topic'
);

-- ===================================================================================
-- T-TOPIC-ROTATION / T-NO-LEAK-REALTIME: inspect realtime.messages directly
-- (as postgres, bypassing RLS -- this is the server-side proof; 0013 proves the
-- client-facing RLS policy itself).
-- ===================================================================================
reset role;

-- Two member_joined events land on this original topic: A's own creator-join (fired when
-- A's brand new solo pair was created) and B's join (create_pair_invite never rotates the
-- generation, only redeem/leave do, so both memberships share the pre-redemption topic).
select is(
    (select count(*)::int from realtime.messages
     where topic = (select result->'pair'->>'channel_topic' from t_result where label = 'a_create_1')
       and event = 'pair.member_joined.v1'),
    2,
    'T-TOPIC-ROTATION-1: both the creator-join and the peer-join were published on the pre-redemption (old) topic'
);
select is(
    (select count(*)::int from realtime.messages
     where topic = (select result->'pair'->>'channel_topic' from t_result where label = 'a_create_1')
       and event = 'pair.member_left.v1'),
    0,
    'T-TOPIC-ROTATION-2: no later event was ever published on that same original old topic'
);
select is(
    (select count(*)::int from realtime.messages
     where topic = (select result->'pair'->>'channel_topic' from t_result where label = 'b_redeem_ok')
       and event = 'pair.member_left.v1'),
    1,
    'T-TOPIC-ROTATION-3: the leave event was published on the topic that was current at redemption time'
);
select is(
    (select payload ? 'code' or payload ? 'code_hash' from realtime.messages
     where event = 'pair.member_joined.v1'
     limit 1),
    false,
    'T-NO-LEAK-REALTIME: the member_joined broadcast payload carries no code or code_hash key'
);

select * from finish();
rollback;
