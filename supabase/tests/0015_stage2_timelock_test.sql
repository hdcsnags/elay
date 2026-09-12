-- pgTAP: Stage 2 time-lock negotiation -- the full Sol §5 adversarial list EXCEPT the DST
-- suite (which lives in 0016_stage2_dst_test.sql per the seat grant).
--
-- Sol §5 mandatory items and where each is covered below:
--   anonymous denial                             -> T-ANON-*
--   non-participant denial                       -> T-NONPARTICIPANT-*
--   guessed proposal / cross-pair id denial       -> T-GUESS-*
--   former-member denial                         -> T-FORMER-*
--   direct writes                                 -> T-DIRECT-WRITE-*
--   invalid zone/deadline/candidate count/duration/index -> T-INVALID-*
--   immutable history                             -> T-IMMUTABLE-*
--   stale revision                                -> T-STALE-REVISION
--   revision monotonicity and the 20-revision bound -> T-REVISION-CAP
--   response by revision author                   -> T-AUTHOR-CANNOT-RESPOND
--   double-accept concurrency (one winner, two blocks, two commitments) -> T-DOUBLE-ACCEPT-*
--   accept after expiry/cancel/decline             -> T-ACCEPT-AFTER-*
--   cancel by non-creator                          -> T-CANCEL-NON-CREATOR
--   sweep idempotence                              -> T-SWEEP-*
--   every RPC's exact receipt replay + cross-action rejection -> T-REPLAY-*, T-CROSS-ACTION-*
--   conflict hints busy-only exact-key projection  -> T-CONFLICT-HINTS-*
--   get/list exact-key projections, peer commitment absence -> T-PROJECTION-*
--   complete-one vs complete-both independence      -> T-COMPLETE-*

begin;
create extension if not exists pgtap with schema extensions;
select plan(100);

-- ===================================================================================
-- structure
-- ===================================================================================
select has_table('public', 'time_lock_proposals', 'time_lock_proposals table exists');
select has_table('public', 'proposal_revisions', 'proposal_revisions table exists');
select has_table('public', 'proposal_responses', 'proposal_responses table exists');
select has_table('public', 'commitments', 'commitments table exists');

select ok((select relrowsecurity from pg_class where oid = 'public.time_lock_proposals'::regclass), 'RLS enabled on time_lock_proposals');
select ok((select relrowsecurity from pg_class where oid = 'public.proposal_revisions'::regclass), 'RLS enabled on proposal_revisions');
select ok((select relrowsecurity from pg_class where oid = 'public.proposal_responses'::regclass), 'RLS enabled on proposal_responses');
select ok((select relrowsecurity from pg_class where oid = 'public.commitments'::regclass), 'RLS enabled on commitments');

select has_function('public', 'rpc_create_proposal', array['uuid','text','text','timestamptz','jsonb'], 'rpc_create_proposal exists');
select has_function('public', 'rpc_respond_proposal', array['uuid','uuid','integer','text','integer','text','timestamptz','jsonb'], 'rpc_respond_proposal exists');
select has_function('public', 'rpc_cancel_proposal', array['uuid','uuid'], 'rpc_cancel_proposal exists');
select has_function('public', 'rpc_complete_lock', array['uuid','uuid'], 'rpc_complete_lock exists');
select has_function('public', 'rpc_proposal_conflict_hints', array['jsonb'], 'rpc_proposal_conflict_hints exists');
select has_function('public', 'rpc_list_proposals', array['text','text'], 'rpc_list_proposals exists');
select has_function('public', 'rpc_get_proposal', array['uuid'], 'rpc_get_proposal exists');
select has_function('public', 'fn_expire_proposals', array['integer'], 'fn_expire_proposals exists');

-- ===================================================================================
-- setup: pair1 = A/B (main flow), pair2 = C/D (non-participant / former-member flow), R unpaired.
-- ===================================================================================
insert into auth.users (id, email) values
    ('00000000-0000-0000-0000-0000000000a1', 'a1@test.local'),
    ('00000000-0000-0000-0000-0000000000b1', 'b1@test.local'),
    ('00000000-0000-0000-0000-0000000000c1', 'c1@test.local'),
    ('00000000-0000-0000-0000-0000000000d1', 'd1@test.local'),
    ('00000000-0000-0000-0000-0000000000f1', 'r1@test.local');

insert into public.profiles (user_id, display_name, home_tz) values
    ('00000000-0000-0000-0000-0000000000a1', 'User A', 'America/Toronto'),
    ('00000000-0000-0000-0000-0000000000b1', 'User B', 'America/Vancouver'),
    ('00000000-0000-0000-0000-0000000000c1', 'User C', 'America/Toronto'),
    ('00000000-0000-0000-0000-0000000000d1', 'User D', 'America/Vancouver'),
    ('00000000-0000-0000-0000-0000000000f1', 'User R', 'America/Toronto')
on conflict (user_id) do update set display_name = excluded.display_name, home_tz = excluded.home_tz;

create temporary table t_result (label text, result jsonb);
grant all on t_result to authenticated, anon;

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}';
insert into t_result (label, result) select 'a_invite', public.rpc_create_pair_invite('a0000000-0000-4000-8000-000000000001'::uuid, 60);

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000b1","role":"authenticated"}';
insert into t_result (label, result) select 'b_redeem', public.rpc_redeem_pair_invite(
    'a0000000-0000-4000-8000-000000000002'::uuid, (select result->'invite'->>'code' from t_result where label = 'a_invite'));
select is((select result->>'outcome' from t_result where label = 'b_redeem'), 'applied', 'setup: A/B paired');

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000c1","role":"authenticated"}';
insert into t_result (label, result) select 'c_invite', public.rpc_create_pair_invite('a0000000-0000-4000-8000-000000000003'::uuid, 60);

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000d1","role":"authenticated"}';
insert into t_result (label, result) select 'd_redeem', public.rpc_redeem_pair_invite(
    'a0000000-0000-4000-8000-000000000004'::uuid, (select result->'invite'->>'code' from t_result where label = 'c_invite'));
select is((select result->>'outcome' from t_result where label = 'd_redeem'), 'applied', 'setup: C/D paired');

-- ===================================================================================
-- T-ANON-*: anonymous denial
-- ===================================================================================
set local role anon;
set local request.jwt.claims to '{}';
select throws_ok('select 1 from public.time_lock_proposals', '42501', null, 'T-ANON-1: anon has no grant on time_lock_proposals');
select throws_ok('select 1 from public.proposal_revisions', '42501', null, 'T-ANON-2: anon has no grant on proposal_revisions');
select throws_ok('select 1 from public.proposal_responses', '42501', null, 'T-ANON-3: anon has no grant on proposal_responses');
select throws_ok('select 1 from public.commitments', '42501', null, 'T-ANON-4: anon has no grant on commitments');
select throws_ok(
    $$select public.rpc_create_proposal(gen_random_uuid(), 'x', 'America/Toronto', now() + interval '1 hour', '[{"candidate_idx":0,"starts_at_utc":"2026-09-13T19:00:00Z","ends_at_utc":"2026-09-13T20:00:00Z","duration_min":60}]'::jsonb)$$,
    '42501', null, 'T-ANON-5: anon cannot execute rpc_create_proposal'
);

-- ===================================================================================
-- A creates a proposal for A/B.
-- ===================================================================================
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}';

-- fn_expire_proposals must never be authenticated-callable (lead amendment 4). Run only now
-- that the session role is actually 'authenticated' (a run as the harness's own connecting
-- role/superuser, before this point, would bypass the revoke entirely and prove nothing).
select throws_ok(
    $$select * from public.fn_expire_proposals(10)$$,
    '42501',
    null,
    'T-SWEEP-NOT-AUTHENTICATED: fn_expire_proposals is not callable by authenticated (revoked explicitly, lead amendment 4)'
);

-- T-DIRECT-WRITE-*: no client INSERT/UPDATE/DELETE on any Stage-2 table.
select throws_ok(
    $$insert into public.time_lock_proposals (pair_id, creator_id, title, response_deadline, origin_tz)
      values (gen_random_uuid(), '00000000-0000-0000-0000-0000000000a1', 'x', now() + interval '1 hour', 'America/Toronto')$$,
    '42501', null, 'T-DIRECT-WRITE-1: authenticated cannot INSERT into time_lock_proposals directly'
);
select throws_ok(
    $$insert into public.proposal_revisions (proposal_id, revision_no, author_id, origin_tz, candidates)
      values (gen_random_uuid(), 1, '00000000-0000-0000-0000-0000000000a1', 'America/Toronto', '[]'::jsonb)$$,
    '42501', null, 'T-DIRECT-WRITE-2: authenticated cannot INSERT into proposal_revisions directly'
);
select throws_ok(
    $$insert into public.commitments (proposal_id, user_id, created_from_revision, candidate_idx, time_block_id)
      values (gen_random_uuid(), '00000000-0000-0000-0000-0000000000a1', 1, 0, gen_random_uuid())$$,
    '42501', null, 'T-DIRECT-WRITE-3: authenticated cannot INSERT into commitments directly'
);

-- T-INVALID-ZONE
select throws_ok(
    format($$select public.rpc_create_proposal(gen_random_uuid(), 'Dinner', 'Not/AZone', now() + interval '1 hour',
        '[{"candidate_idx":0,"starts_at_utc":"2026-09-13T19:00:00Z","ends_at_utc":"2026-09-13T20:00:00Z","duration_min":60}]'::jsonb)$$),
    '22023', null, 'T-INVALID-ZONE: an unrecognized IANA zone name is rejected'
);
-- T-INVALID-DEADLINE (too soon / too far)
select throws_ok(
    $$select public.rpc_create_proposal(gen_random_uuid(), 'Dinner', 'America/Toronto', now() + interval '1 minute',
        '[{"candidate_idx":0,"starts_at_utc":"2026-09-13T19:00:00Z","ends_at_utc":"2026-09-13T20:00:00Z","duration_min":60}]'::jsonb)$$,
    '22023', null, 'T-INVALID-DEADLINE-1: a deadline under 5 minutes ahead is rejected'
);
select throws_ok(
    $$select public.rpc_create_proposal(gen_random_uuid(), 'Dinner', 'America/Toronto', now() + interval '30 days',
        '[{"candidate_idx":0,"starts_at_utc":"2026-09-13T19:00:00Z","ends_at_utc":"2026-09-13T20:00:00Z","duration_min":60}]'::jsonb)$$,
    '22023', null, 'T-INVALID-DEADLINE-2: a deadline over 7 days ahead is rejected'
);
-- T-INVALID-CANDIDATE-COUNT (0 and 4)
select throws_ok(
    $$select public.rpc_create_proposal(gen_random_uuid(), 'Dinner', 'America/Toronto', now() + interval '1 hour', '[]'::jsonb)$$,
    '22023', null, 'T-INVALID-CANDIDATE-COUNT-1: zero candidates is rejected'
);
select throws_ok(
    $$select public.rpc_create_proposal(gen_random_uuid(), 'Dinner', 'America/Toronto', now() + interval '1 hour',
        '[{"candidate_idx":0,"starts_at_utc":"2026-09-13T19:00:00Z","ends_at_utc":"2026-09-13T20:00:00Z","duration_min":60},
          {"candidate_idx":1,"starts_at_utc":"2026-09-13T19:00:00Z","ends_at_utc":"2026-09-13T20:00:00Z","duration_min":60},
          {"candidate_idx":2,"starts_at_utc":"2026-09-13T19:00:00Z","ends_at_utc":"2026-09-13T20:00:00Z","duration_min":60},
          {"candidate_idx":3,"starts_at_utc":"2026-09-13T19:00:00Z","ends_at_utc":"2026-09-13T20:00:00Z","duration_min":60}]'::jsonb)$$,
    '22023', null, 'T-INVALID-CANDIDATE-COUNT-2: four candidates is rejected'
);
-- T-INVALID-DURATION (mismatch, and out of bounds)
select throws_ok(
    $$select public.rpc_create_proposal(gen_random_uuid(), 'Dinner', 'America/Toronto', now() + interval '1 hour',
        '[{"candidate_idx":0,"starts_at_utc":"2026-09-13T19:00:00Z","ends_at_utc":"2026-09-13T20:00:00Z","duration_min":45}]'::jsonb)$$,
    '22023', null, 'T-INVALID-DURATION-1: duration_min not matching the instant interval is rejected'
);
select throws_ok(
    $$select public.rpc_create_proposal(gen_random_uuid(), 'Dinner', 'America/Toronto', now() + interval '1 hour',
        '[{"candidate_idx":0,"starts_at_utc":"2026-09-13T19:00:00Z","ends_at_utc":"2026-09-14T20:01:00Z","duration_min":1441}]'::jsonb)$$,
    '22023', null, 'T-INVALID-DURATION-2: duration_min over 1440 minutes is rejected'
);
-- T-INVALID-INDEX (non-contiguous / non-zero-based)
select throws_ok(
    $$select public.rpc_create_proposal(gen_random_uuid(), 'Dinner', 'America/Toronto', now() + interval '1 hour',
        '[{"candidate_idx":1,"starts_at_utc":"2026-09-13T19:00:00Z","ends_at_utc":"2026-09-13T20:00:00Z","duration_min":60}]'::jsonb)$$,
    '22023', null, 'T-INVALID-INDEX-1: candidate_idx not starting at 0 is rejected'
);
select throws_ok(
    $$select public.rpc_create_proposal(gen_random_uuid(), 'Dinner', 'America/Toronto', now() + interval '1 hour',
        '[{"candidate_idx":0,"starts_at_utc":"2026-09-13T19:00:00Z","ends_at_utc":"2026-09-13T20:00:00Z","duration_min":60},
          {"candidate_idx":2,"starts_at_utc":"2026-09-14T19:00:00Z","ends_at_utc":"2026-09-14T20:00:00Z","duration_min":60}]'::jsonb)$$,
    '22023', null, 'T-INVALID-INDEX-2: a gap in candidate_idx (0, then 2) is rejected'
);

-- Happy-path create (2 candidates).
insert into t_result (label, result) select 'p1_create', public.rpc_create_proposal(
    'b0000000-0000-4000-8000-000000000001'::uuid, 'Date night', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-14T23:00:00Z","ends_at_utc":"2026-09-15T01:00:00Z","duration_min":120},
      {"candidate_idx":1,"starts_at_utc":"2026-09-15T23:00:00Z","ends_at_utc":"2026-09-16T01:00:00Z","duration_min":120}]'::jsonb
);
select is((select result->>'outcome' from t_result where label = 'p1_create'), 'applied', 'T-CREATE-HAPPY: A creates a 2-candidate proposal');
select is((select result->'proposal'->>'status' from t_result where label = 'p1_create'), 'proposed', 'new proposal status=proposed');
select is((select result->'proposal'->>'current_revision' from t_result where label = 'p1_create'), '1', 'new proposal current_revision=1');

-- T-REPLAY-CREATE
insert into t_result (label, result) select 'p1_create_replay', public.rpc_create_proposal(
    'b0000000-0000-4000-8000-000000000001'::uuid, 'DIFFERENT TITLE IGNORED', 'America/Toronto', now() + interval '3 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-20T19:00:00Z","ends_at_utc":"2026-09-20T20:00:00Z","duration_min":60}]'::jsonb
);
select is(
    (select result from t_result where label = 'p1_create_replay'),
    (select result from t_result where label = 'p1_create'),
    'T-REPLAY-CREATE: replay of the same operation_id returns the identical stored result, ignoring new args'
);

-- T-CROSS-ACTION-1: reuse the create operation_id on a different RPC/action.
select throws_ok(
    $$select public.rpc_cancel_proposal('b0000000-0000-4000-8000-000000000001'::uuid,
        (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create'))$$,
    '22023', null, 'T-CROSS-ACTION-1: create''s operation_id reused on rpc_cancel_proposal is rejected'
);

-- ===================================================================================
-- T-GUESS-*: a guessed / cross-pair proposal id.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000c1","role":"authenticated"}';
select is(
    public.rpc_get_proposal('deadbeef-0000-4000-8000-000000000000'::uuid), null,
    'T-GUESS-1: rpc_get_proposal returns null for a fabricated proposal id'
);
select is(
    public.rpc_get_proposal((select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create')), null,
    'T-NONPARTICIPANT-1: C (a different pair entirely) gets null from rpc_get_proposal for A/B''s real proposal id'
);
select is_empty(
    format('select 1 from public.time_lock_proposals where id = %L', (select result->'proposal'->>'id' from t_result where label = 'p1_create')),
    'T-NONPARTICIPANT-2: C cannot SELECT A/B''s proposal row directly (RLS)'
);
select throws_ok(
    format(
        $$select public.rpc_respond_proposal(gen_random_uuid(), %L, 1, 'decline')$$,
        (select result->'proposal'->>'id' from t_result where label = 'p1_create')
    ),
    '42501', null, 'T-NONPARTICIPANT-3: C cannot respond to A/B''s proposal (not an active member of its pair)'
);

-- ===================================================================================
-- T-CONFLICT-HINTS-*: busy-only exact-key projection. rpc_proposal_conflict_hints checks
-- the CALLER's PARTNER's blocks (contract §2: "derives the other active member"), so A
-- (the proposal's composer) is the caller here and B's calendar is what gets intersected --
-- the natural use case (A previewing whether candidates would conflict with B before
-- proposing/countering); a peer's OWN calendar has no need for this RPC since they can just
-- read their own time_blocks rows directly.
-- ===================================================================================
reset role;
-- Deliberately wider than candidate 0's own window on both ends, so the busy_windows
-- assertions below actually exercise clipping (contract §2: "clipped windows").
insert into public.time_blocks (owner_id, title, starts_at_utc, ends_at_utc, origin_tz, type, status) values
    ('00000000-0000-0000-0000-0000000000b1', 'B busy thing', '2026-09-14T22:00:00Z', '2026-09-15T02:00:00Z', 'America/Vancouver', 'personal', 'scheduled');
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}';

insert into t_result (label, result) select 'conflict_hints', public.rpc_proposal_conflict_hints(
    (select candidates from public.proposal_revisions where proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create') and revision_no = 1)
);
select is(jsonb_array_length((select result from t_result where label = 'conflict_hints')), 2, 'T-CONFLICT-HINTS-1: one hint object per candidate');
select is(
    (select array_agg(k order by k) from jsonb_object_keys((select result->0 from t_result where label = 'conflict_hints')) k),
    array['busy_windows','candidate_idx','certainty','has_conflict']::text[],
    'T-CONFLICT-HINTS-2: exact key set {candidate_idx, has_conflict, busy_windows, certainty} (certainty added by 20260912200000, Stage 4)'
);
select is((select result->0->>'has_conflict' from t_result where label = 'conflict_hints'), 'true', 'T-CONFLICT-HINTS-3: candidate 0 (overlaps B''s busy block) is flagged has_conflict');
select is((select result->1->>'has_conflict' from t_result where label = 'conflict_hints'), 'false', 'T-CONFLICT-HINTS-4: candidate 1 (no overlap) is not flagged');
select is(
    (select array_agg(k order by k) from jsonb_object_keys((select result->0->'busy_windows'->0 from t_result where label = 'conflict_hints')) k),
    array['ends_at_utc','starts_at_utc']::text[],
    'T-CONFLICT-HINTS-5: a busy_windows entry carries only {starts_at_utc, ends_at_utc} -- no id/title/type'
);
select is(
    (select result->0->'busy_windows'->0->>'starts_at_utc' from t_result where label = 'conflict_hints'),
    '2026-09-14T23:00:00Z',
    'T-CONFLICT-HINTS-6: the busy window is clipped to the candidate''s own start, not B''s block''s earlier true start'
);
select is(
    (select result->0->'busy_windows'->0->>'ends_at_utc' from t_result where label = 'conflict_hints'),
    '2026-09-15T01:00:00Z',
    'T-CONFLICT-HINTS-7: the busy window is clipped to the candidate''s own end, not B''s block''s later true end'
);

-- ===================================================================================
-- T-AUTHOR-CANNOT-RESPOND: A (author of revision 1) cannot respond to their own revision.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}';
select throws_ok(
    format($$select public.rpc_respond_proposal(gen_random_uuid(), %L, 1, 'decline')$$,
        (select result->'proposal'->>'id' from t_result where label = 'p1_create')),
    '42501', null, 'T-AUTHOR-CANNOT-RESPOND: A (the current revision''s author) cannot respond to it'
);

-- ===================================================================================
-- T-STALE-REVISION: B responds against the wrong expected_revision.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000b1","role":"authenticated"}';
insert into t_result (label, result) select 'b_stale', public.rpc_respond_proposal(
    gen_random_uuid(), (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create'), 99, 'decline'
);
select is((select result->>'outcome' from t_result where label = 'b_stale'), 'conflict', 'T-STALE-REVISION: wrong expected_revision returns a non-mutating conflict, not an exception');
select is((select result->>'current_revision' from t_result where label = 'b_stale'), '1', 'the conflict reports the true current_revision');
select is(
    (select status from public.time_lock_proposals where id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create')),
    'proposed',
    'T-STALE-REVISION: the stale call did not mutate proposal status'
);

-- ===================================================================================
-- B counters (candidate shape / deadline / cap enforcement first, then happy path).
-- ===================================================================================
select throws_ok(
    format($$select public.rpc_respond_proposal(gen_random_uuid(), %L, 1, 'counter', null, 'America/Vancouver', now() + interval '1 hour',
        '[{"candidate_idx":0,"starts_at_utc":"2026-09-17T19:00:00Z","ends_at_utc":"2026-09-17T19:30:00Z","duration_min":45}]'::jsonb)$$,
        (select result->'proposal'->>'id' from t_result where label = 'p1_create')),
    '22023', null, 'T-INVALID-DURATION-3 (counter path): the same duration_min validator applies to a counter''s candidates'
);

insert into t_result (label, result) select 'b_counter', public.rpc_respond_proposal(
    'b0000000-0000-4000-8000-000000000002'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create'),
    1, 'counter', null, 'America/Vancouver', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-17T19:00:00Z","ends_at_utc":"2026-09-17T20:00:00Z","duration_min":60}]'::jsonb
);
select is((select result->>'outcome' from t_result where label = 'b_counter'), 'applied', 'B counters: outcome=applied');
select is((select result->'proposal'->>'status' from t_result where label = 'b_counter'), 'countered', 'proposal status=countered after a counter');
select is((select result->'proposal'->>'current_revision' from t_result where label = 'b_counter'), '2', 'current_revision bumped to 2 by the counter');

-- T-REVISION-MONOTONICITY: proposal_revisions is append-only in ascending order; revision 1
-- is untouched and still the original A-authored candidates.
select is(
    (select author_id from public.proposal_revisions where proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create') and revision_no = 1),
    '00000000-0000-0000-0000-0000000000a1',
    'T-REVISION-MONOTONICITY: revision 1''s author is still A, unmodified by the counter'
);

-- T-IMMUTABLE-*: direct UPDATE/DELETE on proposal_revisions is rejected even bypassing RLS.
reset role;
select throws_ok(
    format($$update public.proposal_revisions set origin_tz = 'UTC' where proposal_id = %L and revision_no = 1$$,
        (select result->'proposal'->>'id' from t_result where label = 'p1_create')),
    '23514', null, 'T-IMMUTABLE-1: UPDATE on proposal_revisions is rejected'
);
select throws_ok(
    format($$delete from public.proposal_revisions where proposal_id = %L and revision_no = 1$$,
        (select result->'proposal'->>'id' from t_result where label = 'p1_create')),
    '23514', null, 'T-IMMUTABLE-2: DELETE on proposal_revisions is rejected'
);
set local role authenticated;

-- T-REVISION-CAP: force current_revision to 20 (test-only bypass), then one more counter
-- must be rejected.
reset role;
update public.time_lock_proposals set current_revision = 20, status = 'countered'
where id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create');
insert into public.proposal_revisions (proposal_id, revision_no, author_id, origin_tz, candidates)
select (result->'proposal'->>'id')::uuid, 20, '00000000-0000-0000-0000-0000000000a1', 'America/Toronto',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-19T19:00:00Z","ends_at_utc":"2026-09-19T20:00:00Z","duration_min":60}]'::jsonb
from t_result where label = 'p1_create';
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000b1","role":"authenticated"}';
select throws_ok(
    format($$select public.rpc_respond_proposal(gen_random_uuid(), %L, 20, 'counter', null, 'America/Vancouver', now() + interval '1 hour',
        '[{"candidate_idx":0,"starts_at_utc":"2026-09-20T19:00:00Z","ends_at_utc":"2026-09-20T20:00:00Z","duration_min":60}]'::jsonb)$$,
        (select result->'proposal'->>'id' from t_result where label = 'p1_create')),
    '22023', null, 'T-REVISION-CAP: a counter beyond revision 20 is rejected'
);
-- restore to revision 2 / countered so the rest of the suite continues from a known state.
-- The append-only trigger fires for EVERY role including the table owner (that is the whole
-- point of T-IMMUTABLE-*), so this test-only cleanup must disable it around the delete.
reset role;
alter table public.proposal_revisions disable trigger proposal_revisions_no_delete;
delete from public.proposal_revisions
where proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create') and revision_no = 20;
alter table public.proposal_revisions enable trigger proposal_revisions_no_delete;
update public.time_lock_proposals set current_revision = 2, status = 'countered'
where id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create');
set local role authenticated;

-- ===================================================================================
-- A accepts candidate 0 of revision 2. Double-accept race: a second, different
-- operation_id from the SAME responder (A) attempting the same accept again must
-- conflict, leaving exactly one winner / two blocks / two commitments.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}';

insert into t_result (label, result) select 'a_accept', public.rpc_respond_proposal(
    'b0000000-0000-4000-8000-000000000003'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create'),
    2, 'accept', 0
);
select is((select result->>'outcome' from t_result where label = 'a_accept'), 'applied', 'T-ACCEPT-HAPPY: A accepts candidate 0 of revision 2');
select is((select result->'proposal'->>'status' from t_result where label = 'a_accept'), 'accepted', 'proposal status=accepted');
select is((select result->'proposal'->>'accepted_revision' from t_result where label = 'a_accept'), '2', 'accepted_revision=2');
select is((select result->'proposal'->>'accepted_candidate_idx' from t_result where label = 'a_accept'), '0', 'accepted_candidate_idx=0');

insert into t_result (label, result) select 'a_accept_race', public.rpc_respond_proposal(
    'b0000000-0000-4000-8000-000000000004'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create'),
    2, 'accept', 0
);
select is((select result->>'outcome' from t_result where label = 'a_accept_race'), 'conflict', 'T-DOUBLE-ACCEPT-1: a second accept attempt (different operation_id, already-accepted proposal) conflicts, not a second winner');

reset role;
select is(
    (select count(*)::int from public.time_blocks where source_proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create')),
    2,
    'T-DOUBLE-ACCEPT-2: exactly two shared_lock time_blocks exist for this proposal (one per member)'
);
select is(
    (select count(*)::int from public.commitments where proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create')),
    2,
    'T-DOUBLE-ACCEPT-3: exactly two commitments exist for this proposal (one per member)'
);
select is(
    (select count(distinct owner_id)::int from public.time_blocks where source_proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create')),
    2,
    'T-DOUBLE-ACCEPT-4: the two blocks belong to two DIFFERENT owners (A and B), not the same person twice'
);
select is(
    (select count(distinct (starts_at_utc, ends_at_utc))::int from public.time_blocks where source_proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create')),
    1,
    'T-DOUBLE-ACCEPT-5: both blocks share the identical winning UTC instant'
);
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}';

-- T-ACCEPT-AFTER-ACCEPTED (decline/counter on an already-accepted proposal also conflicts).
select is(
    (select (public.rpc_respond_proposal(
        'b0000000-0000-4000-8000-000000000005'::uuid,
        (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create'),
        2, 'decline'))->>'outcome'),
    'conflict',
    'T-ACCEPT-AFTER-ACCEPTED: a decline attempt against an already-accepted proposal conflicts'
);

-- T-PROJECTION-*: rpc_get_proposal exact shape; B sees their own commitment only.
-- Capture A's real block id via a postgres-bypass read first: fetching it again as B below
-- (under B's owner-only RLS) would just return null, making the isnt() comparison vacuous.
reset role;
insert into t_result (label, result)
select 'a_block_id', to_jsonb(tb.id)
from public.time_blocks tb
where tb.owner_id = '00000000-0000-0000-0000-0000000000a1'
  and tb.source_proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create');
set local role authenticated;

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000b1","role":"authenticated"}';
insert into t_result (label, result) select 'b_get', public.rpc_get_proposal((select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create'));
select is(jsonb_array_length((select result->'revisions' from t_result where label = 'b_get')), 2, 'T-PROJECTION-1: rpc_get_proposal returns both revisions (1 and 2)');
select is(
    (select result->'my_commitment'->>'user_id' from t_result where label = 'b_get'),
    '00000000-0000-0000-0000-0000000000b1',
    'T-PROJECTION-2: my_commitment belongs to the caller (B), never A''s'
);
select isnt(
    (select result->'my_commitment'->>'time_block_id' from t_result where label = 'b_get'),
    (select (result #>> '{}') from t_result where label = 'a_block_id'),
    'T-PROJECTION-3: B''s my_commitment.time_block_id is not A''s private block id'
);
select is_empty(
    format('select 1 from public.commitments where user_id = ''00000000-0000-0000-0000-0000000000a1''
            and proposal_id = %L',
        (select result->'proposal'->>'id' from t_result where label = 'p1_create')),
    'T-PROJECTION-4: B cannot SELECT A''s commitment row directly (RLS, owner-only)'
);
select is_empty(
    format('select 1 from public.time_blocks where owner_id = ''00000000-0000-0000-0000-0000000000a1'' and id = (
        select time_block_id from public.commitments where user_id = ''00000000-0000-0000-0000-0000000000a1'' and proposal_id = %L
    )', (select result->'proposal'->>'id' from t_result where label = 'p1_create')),
    'T-PROJECTION-5: B cannot SELECT A''s private shared_lock time_block row directly (RLS, owner-only)'
);

-- ===================================================================================
-- T-COMPLETE-*: complete-one vs complete-both independence. Backdate the winning block
-- (test-only, bypassing RLS) so its interval has already begun.
-- ===================================================================================
reset role;
update public.time_blocks
set starts_at_utc = now() - interval '1 hour', ends_at_utc = now() - interval '30 minutes'
where source_proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create');
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000b1","role":"authenticated"}';

insert into t_result (label, result) select 'b_complete', public.rpc_complete_lock(
    'b0000000-0000-4000-8000-000000000006'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create')
);
select is((select result->>'outcome' from t_result where label = 'b_complete'), 'applied', 'B completes their own commitment');
select is((select result->>'proposal_status' from t_result where label = 'b_complete'), 'accepted', 'T-COMPLETE-1: proposal stays accepted after only ONE member completes (independence, ADR-010 §2)');

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}';
insert into t_result (label, result) select 'a_complete', public.rpc_complete_lock(
    'b0000000-0000-4000-8000-000000000007'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create')
);
select is((select result->>'proposal_status' from t_result where label = 'a_complete'), 'completed', 'T-COMPLETE-2: proposal transitions to completed once BOTH members have completed');

-- T-REPLAY-COMPLETE
insert into t_result (label, result) select 'a_complete_replay', public.rpc_complete_lock(
    'b0000000-0000-4000-8000-000000000007'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create')
);
select is(
    (select result from t_result where label = 'a_complete_replay'),
    (select result from t_result where label = 'a_complete'),
    'T-REPLAY-COMPLETE: replay of the same complete_lock operation_id is identical'
);

-- ===================================================================================
-- T-CANCEL-NON-CREATOR / T-ACCEPT-AFTER-CANCEL / T-ACCEPT-AFTER-DECLINE via a fresh
-- second proposal (p1's is already terminal/completed).
-- ===================================================================================
insert into t_result (label, result) select 'p2_create', public.rpc_create_proposal(
    'b0000000-0000-4000-8000-000000000008'::uuid, 'Second proposal', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-21T19:00:00Z","ends_at_utc":"2026-09-21T20:00:00Z","duration_min":60}]'::jsonb
);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000b1","role":"authenticated"}';
select throws_ok(
    format($$select public.rpc_cancel_proposal(gen_random_uuid(), %L)$$, (select result->'proposal'->>'id' from t_result where label = 'p2_create')),
    '42501', null, 'T-CANCEL-NON-CREATOR: B (not the creator) cannot cancel A''s proposal'
);

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}';
insert into t_result (label, result) select 'p2_cancel', public.rpc_cancel_proposal(
    'b0000000-0000-4000-8000-000000000009'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p2_create')
);
select is((select result->>'outcome' from t_result where label = 'p2_cancel'), 'applied', 'A (creator) cancels the second proposal');

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000b1","role":"authenticated"}';
select is(
    (select (public.rpc_respond_proposal(gen_random_uuid(), (select (result->'proposal'->>'id')::uuid from t_result where label = 'p2_create'), 1, 'accept', 0))->>'outcome'),
    'conflict',
    'T-ACCEPT-AFTER-CANCEL: accepting a cancelled proposal conflicts, does not raise, does not mutate'
);

-- Third proposal: decline it, then attempt to accept the declined proposal.
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}';
insert into t_result (label, result) select 'p3_create', public.rpc_create_proposal(
    'b0000000-0000-4000-8000-00000000000a'::uuid, 'Third proposal', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-22T19:00:00Z","ends_at_utc":"2026-09-22T20:00:00Z","duration_min":60}]'::jsonb
);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000b1","role":"authenticated"}';
insert into t_result (label, result) select 'p3_decline', public.rpc_respond_proposal(
    'b0000000-0000-4000-8000-00000000000b'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p3_create'), 1, 'decline'
);
select is((select result->>'outcome' from t_result where label = 'p3_decline'), 'applied', 'B declines the third proposal');
select is(
    (select (public.rpc_respond_proposal(gen_random_uuid(), (select (result->'proposal'->>'id')::uuid from t_result where label = 'p3_create'), 1, 'accept', 0))->>'outcome'),
    'conflict',
    'T-ACCEPT-AFTER-DECLINE: accepting a declined proposal conflicts, does not raise, does not mutate'
);

-- ===================================================================================
-- T-SWEEP-*: expiry. Fourth proposal, backdated deadline, swept by fn_expire_proposals.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}';
insert into t_result (label, result) select 'p4_create', public.rpc_create_proposal(
    'b0000000-0000-4000-8000-00000000000c'::uuid, 'Fourth proposal', 'America/Toronto', now() + interval '5 minutes',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-23T19:00:00Z","ends_at_utc":"2026-09-23T20:00:00Z","duration_min":60}]'::jsonb
);
reset role;
-- Backdate BOTH columns (not just the deadline): time_lock_proposals_deadline_after_created_
-- check requires response_deadline > created_at always, so simulating "the deadline has
-- already passed" needs created_at pushed back too, not just response_deadline.
update public.time_lock_proposals set created_at = now() - interval '10 minutes', response_deadline = now() - interval '1 minute'
where id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p4_create');

select is(
    (select count(*)::int from public.fn_expire_proposals(500) f where f.proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p4_create')),
    1,
    'T-SWEEP-1: fn_expire_proposals expires the overdue proposal and returns its id'
);
select is(
    (select status from public.time_lock_proposals where id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p4_create')),
    'expired',
    'T-SWEEP-2: the proposal is now expired'
);
select is(
    (select count(*)::int from public.fn_expire_proposals(500) f where f.proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p4_create')),
    0,
    'T-SWEEP-3 (idempotence): a second sweep call touches it again 0 times -- already-expired rows are not selected twice'
);
set local role authenticated;

-- T-ACCEPT-AFTER-EXPIRY: a fifth proposal, deadline passed WITHOUT a sweep run,
-- responded-to directly -- the RPC's own expire-before-mutate must catch it inline.
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000a1","role":"authenticated"}';
insert into t_result (label, result) select 'p5_create', public.rpc_create_proposal(
    'b0000000-0000-4000-8000-00000000000d'::uuid, 'Fifth proposal', 'America/Toronto', now() + interval '5 minutes',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-24T19:00:00Z","ends_at_utc":"2026-09-24T20:00:00Z","duration_min":60}]'::jsonb
);
reset role;
update public.time_lock_proposals set created_at = now() - interval '10 minutes', response_deadline = now() - interval '1 minute'
where id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p5_create');
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000b1","role":"authenticated"}';
insert into t_result (label, result) select 'p5_accept_after_expiry', public.rpc_respond_proposal(
    gen_random_uuid(), (select (result->'proposal'->>'id')::uuid from t_result where label = 'p5_create'), 1, 'accept', 0
);
select is((select result->>'outcome' from t_result where label = 'p5_accept_after_expiry'), 'conflict', 'T-ACCEPT-AFTER-EXPIRY: accept-before-sweep-but-past-deadline auto-expires inline then conflicts, not applies');
select is(
    (select status from public.time_lock_proposals where id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p5_create')),
    'expired',
    'T-ACCEPT-AFTER-EXPIRY-2: the inline expire-before-mutate check itself flipped the proposal to expired'
);

-- ===================================================================================
-- T-FORMER-*: D leaves pair2 (C/D); D loses all access to C's proposals.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000c1","role":"authenticated"}';
insert into t_result (label, result) select 'p6_create', public.rpc_create_proposal(
    'b0000000-0000-4000-8000-00000000000e'::uuid, 'Sixth proposal (C/D)', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-25T19:00:00Z","ends_at_utc":"2026-09-25T20:00:00Z","duration_min":60}]'::jsonb
);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000d1","role":"authenticated"}';
insert into t_result (label, result) select 'd_leave', public.rpc_leave_pair('b0000000-0000-4000-8000-00000000000f'::uuid);
select is((select result->>'outcome' from t_result where label = 'd_leave'), 'applied', 'setup: D leaves the C/D pair');

select is(
    public.rpc_get_proposal((select (result->'proposal'->>'id')::uuid from t_result where label = 'p6_create')), null,
    'T-FORMER-1: D (departed) gets null from rpc_get_proposal for C''s proposal'
);
select is_empty(
    format('select 1 from public.time_lock_proposals where id = %L', (select result->'proposal'->>'id' from t_result where label = 'p6_create')),
    'T-FORMER-2: D cannot SELECT C''s proposal row directly (RLS) after leaving'
);
select throws_ok(
    format($$select public.rpc_respond_proposal(gen_random_uuid(), %L, 1, 'decline')$$,
        (select result->'proposal'->>'id' from t_result where label = 'p6_create')),
    '42501', null, 'T-FORMER-3: D cannot respond to C''s proposal after leaving'
);

-- ===================================================================================
-- rpc_create_proposal requires an active TWO-member pair (R is unpaired).
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000000f1","role":"authenticated"}';
select throws_ok(
    $$select public.rpc_create_proposal(gen_random_uuid(), 'x', 'America/Toronto', now() + interval '1 hour',
        '[{"candidate_idx":0,"starts_at_utc":"2026-09-13T19:00:00Z","ends_at_utc":"2026-09-13T20:00:00Z","duration_min":60}]'::jsonb)$$,
    '42501', null, 'T-NO-PAIR: an unpaired caller cannot create a proposal'
);
select throws_ok(
    $$select public.rpc_proposal_conflict_hints('[{"candidate_idx":0,"starts_at_utc":"2026-09-13T19:00:00Z","ends_at_utc":"2026-09-13T20:00:00Z","duration_min":60}]'::jsonb)$$,
    '42501', null, 'T-NO-PAIR-2: an unpaired caller gets no conflict hints (no partner to intersect against)'
);
select is(
    (select (public.rpc_list_proposals('active', null))->'items'), '[]'::jsonb,
    'T-NO-PAIR-3: an unpaired caller''s active list is empty'
);

select * from finish();
rollback;
