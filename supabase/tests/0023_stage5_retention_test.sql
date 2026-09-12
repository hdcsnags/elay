-- pgTAP: Stage 5 retention + hardening, SQL lane (migration 20260912210000_stage5_retention_
-- hardening.sql, contracts/stage5-retention-hardening.md -- FROZEN). Covers the adversarial
-- list this seat reconstructed from the contract's Adopted bullets + lead amendments (the full
-- sA lane prose was delivered in-session and is not itself in the repo):
--   structure / RLS / grants / the additive time_blocks unique                -> T-STRUCT-*
--   anon denied direct table access + both RPCs                              -> T-DENY-*
--   private helper not executable by anon/authenticated                      -> F-HELPER-*
--   record happy path (ran_long/finished_early/didnt_happen/rescheduled),
--     planned/actual/delta projector, exact key set, upsert-with-version-bump,
--     receipt replay                                                          -> T-CRUD-*
--   outcome/actual_minutes validation (enum, null-iff, bounds)                -> T-VALID-*
--   elapsed-only guard                                                        -> T-ELAPSED-*
--   caller-owned-block guard (missing and not-mine folded the same)           -> T-OWNER-*
--   cross-action replay guard (shared mutation_receipts ledger)               -> T-CROSS-*
--   DB-level unique + composite FK backstops (bypassing the RPC)             -> T-DBCONS-*
--   owner-only RLS isolation on the base table                               -> T-ISO-*
--   all four verbs are directly grant-reachable for the owner (contract)     -> T-DIRECT-*
--   failure mode 1 (no commitments/time_lock_proposals columns) grep-proofs  -> T-GREP-*
--   failure mode 2 (no realtime, no auto-proposal) grep-proofs               -> T-GREP-*
--   0017-style key-set re-pin over rpc_get_proposal + rpc_list_proposals,
--     proving no outcome key ever enters a pair projection                    -> T-REPIN-*
--   rpc_next_time_suggestion: task/title basis, precedence, last-five
--     ordering + null-actual exclusion, honesty threshold, exact key set      -> T-SUGGEST-*
--   peer-isolation: a peer's 50 outcomes leave the caller's suggestion
--     byte-identical                                                          -> T-PEER-ISO-*
begin;
create extension if not exists pgtap with schema extensions;
select plan(84);

-- ===================================================================================
-- structure
-- ===================================================================================
select has_table('public', 'session_outcomes', 'T-STRUCT-1: session_outcomes table exists');
select ok((select relrowsecurity from pg_class where oid = 'public.session_outcomes'::regclass), 'T-STRUCT-2: RLS enabled on session_outcomes');
select has_function('public', 'rpc_record_session_outcome', array['uuid','uuid','text','int'], 'T-STRUCT-3: rpc_record_session_outcome exists with the exact 4-arg signature');
select has_function('public', 'rpc_next_time_suggestion', array['uuid','text'], 'T-STRUCT-4: rpc_next_time_suggestion exists with the exact 2-arg signature');
select ok(
    exists (select 1 from pg_constraint where conname = 'time_blocks_id_owner_id_unique' and conrelid = 'public.time_blocks'::regclass),
    'T-STRUCT-5: the additive time_blocks unique(id, owner_id) constraint exists'
);

-- ===================================================================================
-- setup: four solo users (alice/priya/sam/nora, no pair needed -- session_outcomes is
-- private) + one pair (kay/leo) reserved for the 0017-style re-pin section.
-- ===================================================================================
insert into auth.users (id, email) values
    ('00000000-0000-0000-0000-000000023a01', 'alice@outcome.local'),
    ('00000000-0000-0000-0000-000000023b01', 'priya@outcome.local'),
    ('00000000-0000-0000-0000-000000023c01', 'sam@outcome.local'),
    ('00000000-0000-0000-0000-000000023d01', 'nora@outcome.local'),
    ('00000000-0000-0000-0000-000000023e01', 'kay@outcome.local'),
    ('00000000-0000-0000-0000-000000023f01', 'leo@outcome.local');

insert into public.profiles (user_id, display_name, home_tz) values
    ('00000000-0000-0000-0000-000000023a01', 'Outcome Alice', 'America/Toronto'),
    ('00000000-0000-0000-0000-000000023b01', 'Outcome Priya', 'America/Toronto'),
    ('00000000-0000-0000-0000-000000023c01', 'Outcome Sam', 'America/Toronto'),
    ('00000000-0000-0000-0000-000000023d01', 'Outcome Nora', 'America/Toronto'),
    ('00000000-0000-0000-0000-000000023e01', 'Outcome Kay', 'America/Toronto'),
    ('00000000-0000-0000-0000-000000023f01', 'Outcome Leo', 'America/Toronto')
on conflict (user_id) do update set display_name = excluded.display_name, home_tz = excluded.home_tz;

create temporary table t_result (label text, result jsonb);
grant all on t_result to authenticated, anon;

-- Alice's/Priya's time_blocks, inserted directly (service role) so their timestamps are exact.
reset role;
insert into public.time_blocks (id, owner_id, visibility, title, starts_at_utc, ends_at_utc, origin_tz, type, status) values
    ('b2300000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000023a01', 'private', 'Alice block 1 (60min)', '2020-01-01T19:00:00Z', '2020-01-01T20:00:00Z', 'America/Toronto', 'focus', 'completed'),
    ('b2300000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000023a01', 'private', 'Alice block 2 (30min)', '2020-01-02T10:00:00Z', '2020-01-02T10:30:00Z', 'America/Toronto', 'focus', 'completed'),
    ('b2300000-0000-4000-8000-000000000003', '00000000-0000-0000-0000-000000023a01', 'private', 'Alice future block', '2099-01-01T00:00:00Z', '2099-01-01T01:00:00Z', 'America/Toronto', 'focus', 'scheduled'),
    ('b2300000-0000-4000-8000-000000000004', '00000000-0000-0000-0000-000000023b01', 'private', 'Priya block', '2020-01-01T19:00:00Z', '2020-01-01T20:00:00Z', 'America/Toronto', 'focus', 'completed'),
    ('b2300000-0000-4000-8000-000000000005', '00000000-0000-0000-0000-000000023a01', 'private', 'Alice block 4 (45min)', '2020-01-03T08:00:00Z', '2020-01-03T08:45:00Z', 'America/Toronto', 'focus', 'completed'),
    ('b2300000-0000-4000-8000-000000000006', '00000000-0000-0000-0000-000000023a01', 'private', 'Alice direct-crud block', '2020-01-04T08:00:00Z', '2020-01-04T08:20:00Z', 'America/Toronto', 'focus', 'completed');
set local role authenticated;

-- ===================================================================================
-- T-DENY-*: anon denied direct table access and both RPCs.
-- ===================================================================================
set local role anon;
set local request.jwt.claims to '{}';
select throws_ok('select 1 from public.session_outcomes', '42501', null, 'T-DENY-1: anon denied direct select on session_outcomes');
select throws_ok(
    $$insert into public.session_outcomes (owner_id, time_block_id, outcome, actual_minutes) values (gen_random_uuid(), gen_random_uuid(), 'ran_long', 10)$$,
    '42501', null, 'T-DENY-2: anon denied direct insert on session_outcomes'
);
select throws_ok(
    $$select public.rpc_record_session_outcome(gen_random_uuid(), gen_random_uuid(), 'ran_long', 10)$$,
    '42501', null, 'T-DENY-3: anon cannot execute rpc_record_session_outcome'
);
select throws_ok(
    $$select public.rpc_next_time_suggestion(null, null)$$,
    '42501', null, 'T-DENY-4: anon cannot execute rpc_next_time_suggestion'
);

set local role authenticated;

-- ===================================================================================
-- F-HELPER-*: the wire projector is not grant-reachable by anon or authenticated.
-- ===================================================================================
select ok(not has_function_privilege('anon', 'public.session_outcome_to_jsonb(public.session_outcomes, public.time_blocks)', 'execute'), 'F-HELPER-1a: anon cannot execute session_outcome_to_jsonb');
select ok(not has_function_privilege('authenticated', 'public.session_outcome_to_jsonb(public.session_outcomes, public.time_blocks)', 'execute'), 'F-HELPER-1b: authenticated cannot execute session_outcome_to_jsonb');

-- ===================================================================================
-- T-VALID-0*: required-parameter checks.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000023a01","role":"authenticated"}';
select throws_ok(
    $$select public.rpc_record_session_outcome(null, 'b2300000-0000-4000-8000-000000000001'::uuid, 'ran_long', 30)$$,
    '22004', null, 'T-VALID-0a: p_operation_id is required'
);
select throws_ok(
    $$select public.rpc_record_session_outcome('a230000a-0000-4000-8000-000000000006'::uuid, null, 'ran_long', 30)$$,
    '22004', null, 'T-VALID-0b: p_time_block_id is required'
);

-- ===================================================================================
-- T-CRUD-*: Alice records, re-records (upsert + version bump), and replays.
-- ===================================================================================

insert into t_result (label, result) select 'a_record1', public.rpc_record_session_outcome(
    'a2300000-0000-4000-8000-000000000001'::uuid, 'b2300000-0000-4000-8000-000000000001'::uuid, 'ran_long', 85
);
select is((select result->>'outcome' from t_result where label = 'a_record1'), 'applied', 'T-CRUD-1: first record applies');
select is((select result->>'action' from t_result where label = 'a_record1'), 'record_session_outcome', 'T-CRUD-1b: action tag is record_session_outcome');
select is((select result->'session_outcome'->>'outcome' from t_result where label = 'a_record1'), 'ran_long', 'T-CRUD-2: session_outcome.outcome is ran_long');
select is((select (result->'session_outcome'->>'planned_minutes')::int from t_result where label = 'a_record1'), 60, 'T-CRUD-3: planned_minutes derived from the block''s own 60min duration');
select is((select (result->'session_outcome'->>'actual_minutes')::int from t_result where label = 'a_record1'), 85, 'T-CRUD-4: actual_minutes round-trips');
select is((select (result->'session_outcome'->>'delta_minutes')::int from t_result where label = 'a_record1'), 25, 'T-CRUD-5: delta_minutes = actual - planned (85 - 60 = 25)');
select is((select (result->'session_outcome'->>'version')::int from t_result where label = 'a_record1'), 1, 'T-CRUD-6: version starts at 1');
select is((select (result->'session_outcome'->>'shared_with_pair')::boolean from t_result where label = 'a_record1'), false, 'T-CRUD-7: shared_with_pair is false (forward column pin)');
select is(
    (select array_agg(k order by k) from jsonb_object_keys((select result->'session_outcome' from t_result where label = 'a_record1')) k),
    array['actual_minutes','created_at','delta_minutes','id','outcome','owner_id','planned_minutes','shared_with_pair','time_block_id','updated_at','version'],
    'T-CRUD-8: the session_outcome object has the exact contract key set'
);

-- Re-record (upsert semantics): same (owner, block), a DIFFERENT operation_id, flips to
-- finished_early -- must reuse the SAME row id and bump version, not create a second row.
insert into t_result (label, result) select 'a_record2', public.rpc_record_session_outcome(
    'a2300000-0000-4000-8000-000000000002'::uuid, 'b2300000-0000-4000-8000-000000000001'::uuid, 'finished_early', 45
);
select is((select result->>'outcome' from t_result where label = 'a_record2'), 'applied', 'T-CRUD-9: re-record applies');
select is(
    (select result->'session_outcome'->>'id' from t_result where label = 'a_record2'),
    (select result->'session_outcome'->>'id' from t_result where label = 'a_record1'),
    'T-CRUD-10: re-recording keeps the SAME session_outcomes row id (upsert, not a new row)'
);
select is((select result->'session_outcome'->>'outcome' from t_result where label = 'a_record2'), 'finished_early', 'T-CRUD-11: outcome flips to finished_early');
select is((select (result->'session_outcome'->>'actual_minutes')::int from t_result where label = 'a_record2'), 45, 'T-CRUD-12: actual_minutes updates to 45');
select is((select (result->'session_outcome'->>'delta_minutes')::int from t_result where label = 'a_record2'), -15, 'T-CRUD-13: delta_minutes is negative for an early finish (45 - 60 = -15)');
select is((select (result->'session_outcome'->>'version')::int from t_result where label = 'a_record2'), 2, 'T-CRUD-14: version bumps to 2 on re-record');

-- Replay: the SAME operation_id as the re-record call, with different (ignored) args.
insert into t_result (label, result) select 'a_record2_replay', public.rpc_record_session_outcome(
    'a2300000-0000-4000-8000-000000000002'::uuid, 'b2300000-0000-4000-8000-000000000001'::uuid, 'didnt_happen', null
);
select is(
    (select result from t_result where label = 'a_record2_replay'),
    (select result from t_result where label = 'a_record2'),
    'T-CRUD-15: replaying the same operation_id returns the byte-identical result regardless of new arguments'
);

-- didnt_happen: actual_minutes/delta_minutes both null.
insert into t_result (label, result) select 'a_didnt_happen', public.rpc_record_session_outcome(
    'a2300000-0000-4000-8000-000000000004'::uuid, 'b2300000-0000-4000-8000-000000000002'::uuid, 'didnt_happen', null
);
select is((select result->>'outcome' from t_result where label = 'a_didnt_happen'), 'applied', 'T-CRUD-16: didnt_happen applies');
select ok((select result->'session_outcome'->'actual_minutes' from t_result where label = 'a_didnt_happen') = 'null'::jsonb, 'T-CRUD-17: didnt_happen carries a null actual_minutes');
select ok((select result->'session_outcome'->'delta_minutes' from t_result where label = 'a_didnt_happen') = 'null'::jsonb, 'T-CRUD-18: didnt_happen carries a null delta_minutes');
select is((select (result->'session_outcome'->>'planned_minutes')::int from t_result where label = 'a_didnt_happen'), 30, 'T-CRUD-19: planned_minutes still derives from the (unaffected) block duration');

-- rescheduled: same null-actual shape, on a fresh block.
insert into t_result (label, result) select 'a_rescheduled', public.rpc_record_session_outcome(
    'a2300000-0000-4000-8000-000000000005'::uuid, 'b2300000-0000-4000-8000-000000000005'::uuid, 'rescheduled', null
);
select is((select result->>'outcome' from t_result where label = 'a_rescheduled'), 'applied', 'T-CRUD-20: rescheduled applies');
select ok((select result->'session_outcome'->'actual_minutes' from t_result where label = 'a_rescheduled') = 'null'::jsonb, 'T-CRUD-21: rescheduled carries a null actual_minutes');

-- ===================================================================================
-- T-VALID-*: outcome/actual_minutes validation.
-- ===================================================================================
select throws_ok(
    $$select public.rpc_record_session_outcome('a2300000-0000-4000-8000-000000000006'::uuid, 'b2300000-0000-4000-8000-000000000001'::uuid, 'bogus', 10)$$,
    '22023', null, 'T-VALID-1: an unrecognized outcome value is rejected'
);
select throws_ok(
    $$select public.rpc_record_session_outcome('a2300000-0000-4000-8000-000000000007'::uuid, 'b2300000-0000-4000-8000-000000000001'::uuid, 'ran_long', null)$$,
    '22023', null, 'T-VALID-2: ran_long requires a non-null actual_minutes'
);
select throws_ok(
    $$select public.rpc_record_session_outcome('a2300000-0000-4000-8000-000000000008'::uuid, 'b2300000-0000-4000-8000-000000000002'::uuid, 'didnt_happen', 10)$$,
    '22023', null, 'T-VALID-3: didnt_happen forbids a non-null actual_minutes'
);
select throws_ok(
    $$select public.rpc_record_session_outcome('a2300000-0000-4000-8000-000000000009'::uuid, 'b2300000-0000-4000-8000-000000000001'::uuid, 'ran_long', 0)$$,
    '22023', null, 'T-VALID-4: actual_minutes=0 is out of the 1..1440 bound'
);
select throws_ok(
    $$select public.rpc_record_session_outcome('a230000a-0000-4000-8000-000000000001'::uuid, 'b2300000-0000-4000-8000-000000000001'::uuid, 'ran_long', 1441)$$,
    '22023', null, 'T-VALID-5: actual_minutes=1441 is out of the 1..1440 bound'
);

-- ===================================================================================
-- T-ELAPSED-*: a future block's session cannot be recorded yet.
-- ===================================================================================
select throws_ok(
    $$select public.rpc_record_session_outcome('a230000a-0000-4000-8000-000000000002'::uuid, 'b2300000-0000-4000-8000-000000000003'::uuid, 'ran_long', 30)$$,
    '22023', null, 'T-ELAPSED-1: recording an outcome for a not-yet-elapsed block is rejected'
);

-- ===================================================================================
-- T-OWNER-*: the block must be caller-owned; missing and not-mine fold to the same 42501.
-- ===================================================================================
select throws_ok(
    $$select public.rpc_record_session_outcome('a230000a-0000-4000-8000-000000000003'::uuid, 'b2300000-0000-4000-8000-000000000004'::uuid, 'ran_long', 30)$$,
    '42501', null, 'T-OWNER-1: Alice cannot record an outcome for Priya''s block'
);
select throws_ok(
    $$select public.rpc_record_session_outcome('a230000a-0000-4000-8000-000000000004'::uuid, gen_random_uuid(), 'ran_long', 30)$$,
    '42501', null, 'T-OWNER-2: a nonexistent block id folds to the same 42501 as not-mine'
);

-- ===================================================================================
-- T-CROSS-*: the SAME operation_id first used for a different action (stage4's
-- rpc_upsert_external_busy) trips the cross-action guard when replayed here.
-- ===================================================================================
insert into t_result (label, result) select 'a_busy', public.rpc_upsert_external_busy(
    'a230000a-0000-4000-8000-000000000005'::uuid, null, '2026-09-19T14:00:00Z'::timestamptz, '2026-09-19T15:00:00Z'::timestamptz, 'America/Toronto', null
);
select is((select result->>'outcome' from t_result where label = 'a_busy'), 'applied', 'setup: the cross-action operation_id was first used by rpc_upsert_external_busy');
select throws_ok(
    $$select public.rpc_record_session_outcome('a230000a-0000-4000-8000-000000000005'::uuid, 'b2300000-0000-4000-8000-000000000001'::uuid, 'ran_long', 30)$$,
    '22023', null, 'T-CROSS-1: reusing an upsert_external_busy operation_id here raises the cross-action guard'
);

-- ===================================================================================
-- T-DBCONS-*: DB-level backstops when the RPC is bypassed entirely (service role, direct
-- table writes).
-- ===================================================================================
reset role;
select throws_ok(
    $$insert into public.session_outcomes (owner_id, time_block_id, outcome, actual_minutes) values ('00000000-0000-0000-0000-000000023a01', 'b2300000-0000-4000-8000-000000000001', 'ran_long', 10)$$,
    '23505', null, 'T-DBCONS-1: a second direct insert for the same (owner_id, time_block_id) violates the unique constraint'
);
select throws_ok(
    $$insert into public.session_outcomes (owner_id, time_block_id, outcome, actual_minutes) values ('00000000-0000-0000-0000-000000023a01', gen_random_uuid(), 'ran_long', 10)$$,
    '23503', null, 'T-DBCONS-2: a time_block_id/owner_id pair absent from time_blocks violates the composite FK'
);
select throws_ok(
    $$insert into public.session_outcomes (owner_id, time_block_id, outcome, actual_minutes) values ('00000000-0000-0000-0000-000000023b01', 'b2300000-0000-4000-8000-000000000001', 'ran_long', 10)$$,
    '23503', null, 'T-DBCONS-3: Priya''s owner_id paired with Alice''s block also violates the composite FK (cross-owner linkage is impossible)'
);
set local role authenticated;

-- ===================================================================================
-- T-ISO-*: owner-only RLS on the base table.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000023b01","role":"authenticated"}';
select is(
    (select count(*)::int from public.session_outcomes where owner_id = '00000000-0000-0000-0000-000000023a01'::uuid),
    0,
    'T-ISO-1: Priya sees zero of Alice''s session_outcomes rows (RLS owner-only)'
);
select is(
    (select count(*)::int from public.session_outcomes where time_block_id = 'b2300000-0000-4000-8000-000000000001'),
    0,
    'T-ISO-2: Priya sees zero rows even filtering directly by Alice''s time_block_id'
);

-- ===================================================================================
-- T-DIRECT-*: all four verbs are directly grant-reachable for the OWNER (contract:
-- "owner-only RLS all four verbs" -- not stage4's zero-write-grant pattern).
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000023a01","role":"authenticated"}';
select lives_ok(
    $$insert into public.session_outcomes (owner_id, time_block_id, outcome, actual_minutes) values ('00000000-0000-0000-0000-000000023a01', 'b2300000-0000-4000-8000-000000000006', 'ran_long', 20)$$,
    'T-DIRECT-1: Alice can directly INSERT her own session_outcomes row (owner-only RLS, not RPC-only)'
);
select is(
    (select count(*)::int from public.session_outcomes where time_block_id = 'b2300000-0000-4000-8000-000000000006' and owner_id = '00000000-0000-0000-0000-000000023a01'::uuid),
    1,
    'T-DIRECT-2: Alice can directly SELECT the row she just inserted'
);
select lives_ok(
    $$update public.session_outcomes set outcome = 'finished_early', actual_minutes = 15 where time_block_id = 'b2300000-0000-4000-8000-000000000006'$$,
    'T-DIRECT-3: Alice can directly UPDATE her own session_outcomes row'
);
select lives_ok(
    $$delete from public.session_outcomes where time_block_id = 'b2300000-0000-4000-8000-000000000006'$$,
    'T-DIRECT-4: Alice can directly DELETE her own session_outcomes row'
);
select is(
    (select count(*)::int from public.session_outcomes where time_block_id = 'b2300000-0000-4000-8000-000000000006'),
    0,
    'T-DIRECT-5: the row is actually gone after the direct delete'
);

-- ===================================================================================
-- T-GREP-*: failure-mode guardrails (brief-10 SCOPE RULING + contract lead amendment 2).
-- ===================================================================================
reset role;
select ok(
    pg_get_functiondef('public.rpc_record_session_outcome(uuid,uuid,text,int)'::regprocedure) !~ 'realtime\.send',
    'T-GREP-1 (failure mode 2): rpc_record_session_outcome never calls realtime.send'
);
select ok(
    pg_get_functiondef('public.rpc_record_session_outcome(uuid,uuid,text,int)'::regprocedure) !~ 'time_lock_proposals',
    'T-GREP-2 (failure mode 2): rpc_record_session_outcome never touches time_lock_proposals -- no auto-proposal from rescheduled'
);
select ok(
    not exists (
        select 1 from information_schema.columns
        where table_schema = 'public' and table_name = 'commitments'
          and (column_name ilike '%outcome%' or column_name ilike '%actual_minutes%' or column_name = 'delta_minutes')
    ),
    'T-GREP-3 (failure mode 1): commitments carries no outcome-shaped column'
);
select ok(
    not exists (
        select 1 from information_schema.columns
        where table_schema = 'public' and table_name = 'time_lock_proposals'
          and (column_name ilike '%outcome%' or column_name ilike '%actual_minutes%' or column_name = 'delta_minutes')
    ),
    'T-GREP-4 (failure mode 1): time_lock_proposals carries no outcome-shaped column'
);
select ok(
    pg_get_functiondef('public.commitment_to_jsonb(public.commitments)'::regprocedure) !~ 'session_outcome',
    'T-GREP-5 (failure mode 1): commitment_to_jsonb never references session_outcomes'
);
select ok(
    pg_get_functiondef('public.time_lock_proposal_to_jsonb(public.time_lock_proposals)'::regprocedure) !~ 'session_outcome',
    'T-GREP-6 (failure mode 1): time_lock_proposal_to_jsonb never references session_outcomes'
);
set local role authenticated;

-- ===================================================================================
-- T-REPIN-*: the 0017-style key-set re-pin. Kay/Leo pair, negotiate, accept (candidate
-- deliberately in the past so the resulting locks are already elapsed), Kay records her OWN
-- outcome on her own resulting block -- rpc_get_proposal/rpc_list_proposals for that SAME
-- proposal must still carry EXACTLY 0017's key set, with no outcome key anywhere.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000023e01","role":"authenticated"}';
insert into t_result (label, result) select 'kay_invite', public.rpc_create_pair_invite('c2300000-0000-4000-8000-000000000001'::uuid, 60);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000023f01","role":"authenticated"}';
insert into t_result (label, result) select 'leo_redeem', public.rpc_redeem_pair_invite(
    'c2300000-0000-4000-8000-000000000002'::uuid, (select result->'invite'->>'code' from t_result where label = 'kay_invite'));
select is((select result->>'outcome' from t_result where label = 'leo_redeem'), 'applied', 'setup: Kay/Leo paired');

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000023e01","role":"authenticated"}';
insert into t_result (label, result) select 'kl_create', public.rpc_create_proposal(
    'c2300000-0000-4000-8000-000000000003'::uuid, 'Retention repin check', 'America/Toronto', now() + interval '1 day',
    '[{"candidate_idx":0,"starts_at_utc":"2020-06-01T10:00:00Z","ends_at_utc":"2020-06-01T11:00:00Z","duration_min":60}]'::jsonb
);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000023f01","role":"authenticated"}';
insert into t_result (label, result) select 'kl_accept', public.rpc_respond_proposal(
    'c2300000-0000-4000-8000-000000000004'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'kl_create'), 1, 'accept', 0);
select is((select result->>'outcome' from t_result where label = 'kl_accept'), 'applied', 'setup: Leo accepted candidate 0 (an already-elapsed 2020 instant)');

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000023e01","role":"authenticated"}';
insert into t_result (label, result) select 'kay_get_before', public.rpc_get_proposal((select (result->'proposal'->>'id')::uuid from t_result where label = 'kl_create'));
insert into t_result (label, result) select 'kay_record', public.rpc_record_session_outcome(
    'c2300000-0000-4000-8000-000000000005'::uuid,
    (select (result->'my_commitment'->>'time_block_id')::uuid from t_result where label = 'kay_get_before'),
    'ran_long', 70
);
select is((select result->>'outcome' from t_result where label = 'kay_record'), 'applied', 'setup: Kay recorded her own outcome on her own resulting lock block');

insert into t_result (label, result) select 'kay_get_after', public.rpc_get_proposal((select (result->'proposal'->>'id')::uuid from t_result where label = 'kl_create'));
-- The proposal is 'accepted' (no rpc_complete_lock call was made) -- that status lives in
-- rpc_list_proposals' ACTIVE scope (proposed|countered|accepted), not history.
insert into t_result (label, result) select 'kay_list_after', public.rpc_list_proposals('active', null);

select is(
    (select array_agg(k order by k) from jsonb_object_keys((select result from t_result where label = 'kay_get_after')) k),
    array['accepted_at','accepted_candidate_idx','accepted_revision','cancelled_at','completed_at',
          'created_at','creator_id','current_revision','declined_at','expired_at','id','my_commitment',
          'origin_tz','pair_id','response_deadline','responses','revisions','status','title',
          'updated_at','version'],
    'T-REPIN-1: rpc_get_proposal carries EXACTLY 0017''s key set after a session outcome exists on its lock -- no outcome key added'
);
select is(
    (select array_agg(k order by k) from (
        select jsonb_object_keys((select result->'items'->0 from t_result where label = 'kay_list_after')) as k
    ) keys),
    array['accepted_at','accepted_candidate_idx','accepted_revision','cancelled_at','completed_at',
          'created_at','creator_id','current_revision','declined_at','expired_at','id','my_commitment',
          'origin_tz','pair_id','response_deadline','responses','revisions','status','title',
          'updated_at','version'],
    'T-REPIN-2: rpc_list_proposals'' item carries the same exact key set'
);
select ok(
    (select result from t_result where label = 'kay_get_after')::text !~ 'outcome|actual_minutes|planned_minutes|delta_minutes|session_outcome',
    'T-REPIN-3: no outcome-shaped substring appears anywhere in rpc_get_proposal''s full jsonb text'
);

-- ===================================================================================
-- T-SUGGEST-*: rpc_next_time_suggestion. Sam is the data subject; a task-linked history of
-- 7 outcomes (one older-than-the-last-five, one didnt_happen/null-actual, five real), plus a
-- title-linked history for the basis/threshold/zero-match/no-params cases.
-- ===================================================================================
reset role;
insert into public.tasks (id, owner_id, title) values
    ('d2300000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000023c01', 'Sam''s recurring focus task');

insert into public.time_blocks (id, owner_id, visibility, task_id, title, starts_at_utc, ends_at_utc, origin_tz, type, status) values
    ('d2310000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000023c01', 'private', 'd2300000-0000-4000-8000-000000000001', 'Sam focus (oldest, excluded)', '2020-01-01T09:00:00Z', '2020-01-01T09:30:00Z', 'America/Toronto', 'focus', 'completed'),
    ('d2310000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000023c01', 'private', 'd2300000-0000-4000-8000-000000000001', 'Sam focus 1', '2020-02-01T09:00:00Z', '2020-02-01T09:30:00Z', 'America/Toronto', 'focus', 'completed'),
    ('d2310000-0000-4000-8000-000000000003', '00000000-0000-0000-0000-000000023c01', 'private', 'd2300000-0000-4000-8000-000000000001', 'Sam focus 2', '2020-03-01T09:00:00Z', '2020-03-01T09:30:00Z', 'America/Toronto', 'focus', 'completed'),
    ('d2310000-0000-4000-8000-000000000004', '00000000-0000-0000-0000-000000023c01', 'private', 'd2300000-0000-4000-8000-000000000001', 'Sam focus 3', '2020-04-01T09:00:00Z', '2020-04-01T09:30:00Z', 'America/Toronto', 'focus', 'completed'),
    ('d2310000-0000-4000-8000-000000000005', '00000000-0000-0000-0000-000000023c01', 'private', 'd2300000-0000-4000-8000-000000000001', 'Sam focus 4', '2020-05-01T09:00:00Z', '2020-05-01T09:30:00Z', 'America/Toronto', 'focus', 'completed'),
    ('d2310000-0000-4000-8000-000000000006', '00000000-0000-0000-0000-000000023c01', 'private', 'd2300000-0000-4000-8000-000000000001', 'Sam focus 5 (most recent)', '2020-06-01T09:00:00Z', '2020-06-01T09:30:00Z', 'America/Toronto', 'focus', 'completed'),
    ('d2310000-0000-4000-8000-000000000007', '00000000-0000-0000-0000-000000023c01', 'private', 'd2300000-0000-4000-8000-000000000001', 'Sam focus (didnt happen, skipped)', '2020-05-15T09:00:00Z', '2020-05-15T09:30:00Z', 'America/Toronto', 'focus', 'completed'),
    ('d2310000-0000-4000-8000-000000000008', '00000000-0000-0000-0000-000000023c01', 'private', null, 'Evening Study', '2021-01-01T09:00:00Z', '2021-01-01T10:00:00Z', 'America/Toronto', 'focus', 'completed'),
    ('d2310000-0000-4000-8000-000000000009', '00000000-0000-0000-0000-000000023c01', 'private', null, 'EVENING STUDY', '2021-02-01T09:00:00Z', '2021-02-01T10:00:00Z', 'America/Toronto', 'focus', 'completed'),
    ('d231000a-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000023c01', 'private', null, 'Solo Session', '2021-03-01T09:00:00Z', '2021-03-01T09:30:00Z', 'America/Toronto', 'focus', 'completed');

insert into public.session_outcomes (owner_id, time_block_id, outcome, actual_minutes) values
    ('00000000-0000-0000-0000-000000023c01', 'd2310000-0000-4000-8000-000000000001', 'ran_long', 999),
    ('00000000-0000-0000-0000-000000023c01', 'd2310000-0000-4000-8000-000000000002', 'ran_long', 10),
    ('00000000-0000-0000-0000-000000023c01', 'd2310000-0000-4000-8000-000000000003', 'ran_long', 20),
    ('00000000-0000-0000-0000-000000023c01', 'd2310000-0000-4000-8000-000000000004', 'ran_long', 30),
    ('00000000-0000-0000-0000-000000023c01', 'd2310000-0000-4000-8000-000000000005', 'ran_long', 40),
    ('00000000-0000-0000-0000-000000023c01', 'd2310000-0000-4000-8000-000000000006', 'ran_long', 50),
    ('00000000-0000-0000-0000-000000023c01', 'd2310000-0000-4000-8000-000000000007', 'didnt_happen', null),
    ('00000000-0000-0000-0000-000000023c01', 'd2310000-0000-4000-8000-000000000008', 'finished_early', 50),
    ('00000000-0000-0000-0000-000000023c01', 'd2310000-0000-4000-8000-000000000009', 'ran_long', 70),
    ('00000000-0000-0000-0000-000000023c01', 'd231000a-0000-4000-8000-000000000001', 'ran_long', 42);
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000023c01","role":"authenticated"}';

insert into t_result (label, result) select 'sam_task', public.rpc_next_time_suggestion('d2300000-0000-4000-8000-000000000001'::uuid, null);
select is((select (result->>'sample_size')::int from t_result where label = 'sam_task'), 5, 'T-SUGGEST-1: exactly 5 of the 6 non-null task-matching outcomes count (the oldest is excluded by LAST FIVE)');
select is((select (result->>'suggested_minutes')::int from t_result where label = 'sam_task'), 30, 'T-SUGGEST-2: median of the last five (10,20,30,40,50) is 30 -- the excluded 999 outlier and the null-actual row never entered the pool');
select is((select result->>'basis' from t_result where label = 'sam_task'), 'task', 'T-SUGGEST-3: basis is task when p_task_id is given');
select is(
    (select array_agg(k order by k) from jsonb_object_keys((select result from t_result where label = 'sam_task')) k),
    array['basis','sample_size','suggested_minutes'],
    'T-SUGGEST-4: the suggestion object has exactly {suggested_minutes, sample_size, basis}'
);

insert into t_result (label, result) select 'sam_title_before', public.rpc_next_time_suggestion(null, 'evening study');
select is((select (result->>'sample_size')::int from t_result where label = 'sam_title_before'), 2, 'T-SUGGEST-5: title matching is case-insensitive (Evening Study + EVENING STUDY both match ''evening study'')');
select is((select (result->>'suggested_minutes')::int from t_result where label = 'sam_title_before'), 60, 'T-SUGGEST-6: median of (50, 70) is 60 at exactly the sample_size=2 threshold');
select is((select result->>'basis' from t_result where label = 'sam_title_before'), 'title', 'T-SUGGEST-7: basis is title when only p_title_key is given');

insert into t_result (label, result) select 'sam_solo', public.rpc_next_time_suggestion(null, 'solo session');
select is((select (result->>'sample_size')::int from t_result where label = 'sam_solo'), 1, 'T-SUGGEST-8: exactly one matching outcome for "Solo Session"');
select ok((select result->'suggested_minutes' from t_result where label = 'sam_solo') = 'null'::jsonb, 'T-SUGGEST-9: below the sample_size>=2 honesty threshold, suggested_minutes is null even though data exists');

insert into t_result (label, result) select 'sam_no_match', public.rpc_next_time_suggestion(null, 'does not exist anywhere');
select is((select (result->>'sample_size')::int from t_result where label = 'sam_no_match'), 0, 'T-SUGGEST-10: zero matches for a title nobody used');
select ok((select result->'suggested_minutes' from t_result where label = 'sam_no_match') = 'null'::jsonb, 'T-SUGGEST-11: zero matches yields a null suggestion');
select is((select result->>'basis' from t_result where label = 'sam_no_match'), 'title', 'T-SUGGEST-12: basis still reports title (the criterion attempted), independent of whether anything matched');

insert into t_result (label, result) select 'sam_no_params', public.rpc_next_time_suggestion(null, null);
select ok((select result->'basis' from t_result where label = 'sam_no_params') = 'null'::jsonb, 'T-SUGGEST-13: with neither parameter given, basis is null');
select is((select (result->>'sample_size')::int from t_result where label = 'sam_no_params'), 0, 'T-SUGGEST-14: with neither parameter given, sample_size is 0');

-- precedence: task_id wins even when a matching title is also supplied.
insert into t_result (label, result) select 'sam_precedence', public.rpc_next_time_suggestion('d2300000-0000-4000-8000-000000000001'::uuid, 'evening study');
select is((select result->>'basis' from t_result where label = 'sam_precedence'), 'task', 'T-SUGGEST-15: p_task_id takes precedence over p_title_key when both are supplied');
select is((select (result->>'suggested_minutes')::int from t_result where label = 'sam_precedence'), 30, 'T-SUGGEST-16: the precedence result matches the task-only computation (30), not the title-only one (60)');

-- ===================================================================================
-- T-PEER-ISO-*: Nora floods 50 outcomes under the SAME title Sam matched on; Sam's own
-- suggestion for that title must stay byte-identical to what it was before the flood.
-- ===================================================================================
reset role;
insert into public.time_blocks (id, owner_id, visibility, title, starts_at_utc, ends_at_utc, origin_tz, type, status)
select
    gen_random_uuid(),
    '00000000-0000-0000-0000-000000023d01',
    'private',
    'Evening Study',
    ('2022-01-01T09:00:00Z'::timestamptz) + (interval '1 day' * n),
    ('2022-01-01T10:00:00Z'::timestamptz) + (interval '1 day' * n),
    'America/Toronto', 'focus', 'completed'
from generate_series(1, 50) as n;

insert into public.session_outcomes (owner_id, time_block_id, outcome, actual_minutes)
select '00000000-0000-0000-0000-000000023d01', b.id, 'ran_long', 15 + (row_number() over (order by b.starts_at_utc))
from public.time_blocks b
where b.owner_id = '00000000-0000-0000-0000-000000023d01' and b.title = 'Evening Study';

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000023c01","role":"authenticated"}';
insert into t_result (label, result) select 'sam_title_after', public.rpc_next_time_suggestion(null, 'evening study');
select is(
    (select result from t_result where label = 'sam_title_after'),
    (select result from t_result where label = 'sam_title_before'),
    'T-PEER-ISO-1: Nora''s 50 same-titled outcomes leave Sam''s own suggestion byte-identical'
);

select * from finish();
rollback;
