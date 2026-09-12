-- pgTAP: Stage 4 honest availability (migration 20260912200000_stage4_honest_availability.sql,
-- contracts/stage4-honest-availability.md -- FROZEN). Covers the freeze's adversarial list:
--   structure / RLS / grants                                -> T-STRUCT-*
--   anon + authenticated denied direct table writes/reads    -> T-DENY-TABLE-*
--   anon (+ authenticated where service_role-only) denied RPCs -> T-DENY-FN-*
--   private helpers not executable by anon/authenticated     -> F-HELPER-*
--   manual CRUD happy path + receipt replay + cross-action   -> T-CRUD-*
--   client cannot fabricate source_tag / cannot touch a synced row via the manual path
--                                                             -> T-CLIENTTAG-*
--   cross-user isolation on external_busy/availability_sources -> T-ISO-*
--   19:00-20:00 + 19:30-20:30 -> single 19:00-20:30 coalescing (union of ELAY + external) -> T-COALESCE-*
--   stale-busy-still-busy (busy always wins)                 -> T-STALE-BUSY
--   fresh-but-non-covering -> unknown                        -> T-NONCOVER
--   free_per_calendar (fresh + covering, no overlap)          -> T-FREECAL
--   free_per_elay only with zero sources                      -> T-FREEELAY
--   exact-key sets on BOTH hint RPCs                          -> T-KEYS-*
--   60/15min per-caller limit shared across both hint RPCs, opaque shape -> T-RATE-*
--   former-member loses hints                                 -> T-FORMER-MEMBER
--   DST fixture: manual window over a Vancouver transition     -> T-DST-*
--   grep-proofs: external_ref_hash never leaves any RPC        -> T-GREP-*
begin;
create extension if not exists pgtap with schema extensions;
select plan(97);

-- ===================================================================================
-- structure
-- ===================================================================================
select has_table('public', 'external_busy', 'T-STRUCT-1: external_busy table exists');
select has_table('public', 'availability_sources', 'T-STRUCT-2: availability_sources table exists');
select has_table('public', 'conflict_hints_attempts', 'T-STRUCT-3: conflict_hints_attempts table exists');

select ok((select relrowsecurity from pg_class where oid = 'public.external_busy'::regclass), 'T-STRUCT-4: RLS enabled on external_busy');
select ok((select relrowsecurity from pg_class where oid = 'public.availability_sources'::regclass), 'T-STRUCT-5: RLS enabled on availability_sources');
select ok((select relrowsecurity from pg_class where oid = 'public.conflict_hints_attempts'::regclass), 'T-STRUCT-6: RLS enabled on conflict_hints_attempts');

select has_function('public', 'rpc_upsert_external_busy', array['uuid','uuid','timestamptz','timestamptz','text','text'], 'T-STRUCT-7: rpc_upsert_external_busy exists with the exact 6-arg signature');
select has_function('public', 'rpc_delete_external_busy', array['uuid','uuid'], 'T-STRUCT-8: rpc_delete_external_busy exists');
select has_function('public', 'fn_sync_external_busy', array['uuid','text','timestamptz','timestamptz','jsonb'], 'T-STRUCT-9: fn_sync_external_busy exists');
select has_function('public', 'rpc_my_availability_sources', array[]::name[], 'T-STRUCT-10: rpc_my_availability_sources exists');
select has_function('public', 'rpc_proposal_conflict_hints', array['jsonb'], 'T-STRUCT-11: rpc_proposal_conflict_hints exists');
select has_function('public', 'rpc_self_conflict_hints', array['jsonb'], 'T-STRUCT-12: rpc_self_conflict_hints exists');

-- ===================================================================================
-- setup: pair A/B (main ladder + CRUD tests), pair E/F (leave test), pair G/H (rate-limit
-- sharing test), C (solo, isolation + self-hints free_per_elay), D (solo, DST fixture).
-- ===================================================================================
insert into auth.users (id, email) values
    ('00000000-0000-0000-0000-000000022a01', 'alice@avail.local'),
    ('00000000-0000-0000-0000-000000022b01', 'bob@avail.local'),
    ('00000000-0000-0000-0000-000000022c01', 'carol@avail.local'),
    ('00000000-0000-0000-0000-000000022d01', 'dana@avail.local'),
    ('00000000-0000-0000-0000-000000022e01', 'eve@avail.local'),
    ('00000000-0000-0000-0000-000000022f01', 'frank@avail.local'),
    ('00000000-0000-0000-0000-000000022901', 'gina@avail.local'),
    ('00000000-0000-0000-0000-000000022902', 'henry@avail.local');

insert into public.profiles (user_id, display_name, home_tz) values
    ('00000000-0000-0000-0000-000000022a01', 'Avail Alice', 'America/Toronto'),
    ('00000000-0000-0000-0000-000000022b01', 'Avail Bob', 'America/Vancouver'),
    ('00000000-0000-0000-0000-000000022c01', 'Avail Carol', 'America/Toronto'),
    ('00000000-0000-0000-0000-000000022d01', 'Avail Dana', 'America/Vancouver'),
    ('00000000-0000-0000-0000-000000022e01', 'Avail Eve', 'America/Toronto'),
    ('00000000-0000-0000-0000-000000022f01', 'Avail Frank', 'America/Vancouver'),
    ('00000000-0000-0000-0000-000000022901', 'Avail Gina', 'America/Toronto'),
    ('00000000-0000-0000-0000-000000022902', 'Avail Henry', 'America/Vancouver')
on conflict (user_id) do update set display_name = excluded.display_name, home_tz = excluded.home_tz;

create temporary table t_result (label text, result jsonb);
grant all on t_result to authenticated, anon;

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000022a01","role":"authenticated"}';
insert into t_result (label, result) select 'a_invite', public.rpc_create_pair_invite('f2200000-0000-4000-8000-000000000001'::uuid, 60);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000022b01","role":"authenticated"}';
insert into t_result (label, result) select 'b_redeem', public.rpc_redeem_pair_invite(
    'f2200000-0000-4000-8000-000000000002'::uuid, (select result->'invite'->>'code' from t_result where label = 'a_invite'));
select is((select result->>'outcome' from t_result where label = 'b_redeem'), 'applied', 'setup: A/B paired');

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000022e01","role":"authenticated"}';
insert into t_result (label, result) select 'e_invite', public.rpc_create_pair_invite('f2200000-0000-4000-8000-000000000003'::uuid, 60);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000022f01","role":"authenticated"}';
insert into t_result (label, result) select 'f_redeem', public.rpc_redeem_pair_invite(
    'f2200000-0000-4000-8000-000000000004'::uuid, (select result->'invite'->>'code' from t_result where label = 'e_invite'));
select is((select result->>'outcome' from t_result where label = 'f_redeem'), 'applied', 'setup: E/F paired');

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000022901","role":"authenticated"}';
insert into t_result (label, result) select 'g_invite', public.rpc_create_pair_invite('f2200000-0000-4000-8000-000000000005'::uuid, 60);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000022902","role":"authenticated"}';
insert into t_result (label, result) select 'h_redeem', public.rpc_redeem_pair_invite(
    'f2200000-0000-4000-8000-000000000006'::uuid, (select result->'invite'->>'code' from t_result where label = 'g_invite'));
select is((select result->>'outcome' from t_result where label = 'h_redeem'), 'applied', 'setup: G/H paired');

-- ===================================================================================
-- T-DENY-TABLE-*: anon denied any direct table access; authenticated denied direct access
-- to conflict_hints_attempts (no grant at all -- it is an internal ledger).
-- ===================================================================================
set local role anon;
set local request.jwt.claims to '{}';
select throws_ok('select 1 from public.external_busy', '42501', null, 'T-DENY-TABLE-1: anon denied external_busy');
select throws_ok('select 1 from public.availability_sources', '42501', null, 'T-DENY-TABLE-2: anon denied availability_sources');
select throws_ok('select 1 from public.conflict_hints_attempts', '42501', null, 'T-DENY-TABLE-3: anon denied conflict_hints_attempts');
select throws_ok(
    $$select public.rpc_upsert_external_busy(gen_random_uuid(), null, now(), now() + interval '1 hour', 'America/Toronto', null)$$,
    '42501', null, 'T-DENY-FN-1: anon cannot execute rpc_upsert_external_busy'
);
select throws_ok(
    $$select public.rpc_delete_external_busy(gen_random_uuid(), gen_random_uuid())$$,
    '42501', null, 'T-DENY-FN-2: anon cannot execute rpc_delete_external_busy'
);
select throws_ok(
    $$select public.rpc_my_availability_sources()$$,
    '42501', null, 'T-DENY-FN-3: anon cannot execute rpc_my_availability_sources'
);
select throws_ok(
    $$select public.rpc_proposal_conflict_hints('[]'::jsonb)$$,
    '42501', null, 'T-DENY-FN-4: anon cannot execute rpc_proposal_conflict_hints'
);
select throws_ok(
    $$select public.rpc_self_conflict_hints('[]'::jsonb)$$,
    '42501', null, 'T-DENY-FN-5: anon cannot execute rpc_self_conflict_hints'
);
select throws_ok(
    $$select public.fn_sync_external_busy(gen_random_uuid(), 'google', now(), now() + interval '1 day', '[]'::jsonb)$$,
    '42501', null, 'T-DENY-FN-6: anon cannot execute fn_sync_external_busy (service_role only)'
);

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000022a01","role":"authenticated"}';
select throws_ok('select 1 from public.conflict_hints_attempts', '42501', null, 'T-DENY-TABLE-4: authenticated denied conflict_hints_attempts (internal ledger, no grant at all)');
select throws_ok(
    $$select public.fn_sync_external_busy(gen_random_uuid(), 'google', now(), now() + interval '1 day', '[]'::jsonb)$$,
    '42501', null, 'T-DENY-FN-7: authenticated cannot execute fn_sync_external_busy (service_role only)'
);

-- ===================================================================================
-- F-HELPER-*: private helpers must not be executable by anon or authenticated.
-- ===================================================================================
select ok(not has_function_privilege('anon', 'public.default_freshness_ttl_minutes(text)', 'execute'), 'F-HELPER-1a: anon cannot execute default_freshness_ttl_minutes');
select ok(not has_function_privilege('authenticated', 'public.default_freshness_ttl_minutes(text)', 'execute'), 'F-HELPER-1b: authenticated cannot execute default_freshness_ttl_minutes');
select ok(not has_function_privilege('anon', 'public.external_busy_to_jsonb(public.external_busy)', 'execute'), 'F-HELPER-2a: anon cannot execute external_busy_to_jsonb');
select ok(not has_function_privilege('authenticated', 'public.external_busy_to_jsonb(public.external_busy)', 'execute'), 'F-HELPER-2b: authenticated cannot execute external_busy_to_jsonb');
select ok(not has_function_privilege('anon', 'public.availability_source_to_jsonb(public.availability_sources)', 'execute'), 'F-HELPER-3a: anon cannot execute availability_source_to_jsonb');
select ok(not has_function_privilege('authenticated', 'public.availability_source_to_jsonb(public.availability_sources)', 'execute'), 'F-HELPER-3b: authenticated cannot execute availability_source_to_jsonb');
select ok(not has_function_privilege('anon', 'public.stamp_availability_source(uuid,text,timestamptz,timestamptz,timestamptz)', 'execute'), 'F-HELPER-4a: anon cannot execute stamp_availability_source');
select ok(not has_function_privilege('authenticated', 'public.stamp_availability_source(uuid,text,timestamptz,timestamptz,timestamptz)', 'execute'), 'F-HELPER-4b: authenticated cannot execute stamp_availability_source');
select ok(not has_function_privilege('anon', 'public.external_busy_intervals_valid(jsonb)', 'execute'), 'F-HELPER-5a: anon cannot execute external_busy_intervals_valid');
select ok(not has_function_privilege('authenticated', 'public.external_busy_intervals_valid(jsonb)', 'execute'), 'F-HELPER-5b: authenticated cannot execute external_busy_intervals_valid');
select ok(not has_function_privilege('anon', 'public.hints_rate_limited(uuid)', 'execute'), 'F-HELPER-6a: anon cannot execute hints_rate_limited');
select ok(not has_function_privilege('authenticated', 'public.hints_rate_limited(uuid)', 'execute'), 'F-HELPER-6b: authenticated cannot execute hints_rate_limited');
select ok(not has_function_privilege('anon', 'public.hints_opaque_shape(jsonb)', 'execute'), 'F-HELPER-7a: anon cannot execute hints_opaque_shape');
select ok(not has_function_privilege('authenticated', 'public.hints_opaque_shape(jsonb)', 'execute'), 'F-HELPER-7b: authenticated cannot execute hints_opaque_shape');
select ok(not has_function_privilege('anon', 'public.coalesced_busy_windows(uuid,timestamptz,timestamptz)', 'execute'), 'F-HELPER-8a: anon cannot execute coalesced_busy_windows');
select ok(not has_function_privilege('authenticated', 'public.coalesced_busy_windows(uuid,timestamptz,timestamptz)', 'execute'), 'F-HELPER-8b: authenticated cannot execute coalesced_busy_windows');
select ok(not has_function_privilege('anon', 'public.candidate_busy_hints(uuid,jsonb)', 'execute'), 'F-HELPER-9a: anon cannot execute candidate_busy_hints');
select ok(not has_function_privilege('authenticated', 'public.candidate_busy_hints(uuid,jsonb)', 'execute'), 'F-HELPER-9b: authenticated cannot execute candidate_busy_hints');

-- ===================================================================================
-- T-CRUD-*: Alice manages her own manual busy list.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000022a01","role":"authenticated"}';
insert into t_result (label, result) select 'a_upsert1', public.rpc_upsert_external_busy(
    'f2200000-0000-4000-8000-000000000010'::uuid, null,
    '2026-09-19T14:00:00Z'::timestamptz, '2026-09-19T15:00:00Z'::timestamptz,
    'America/Toronto', 'Dentist'
);
select is((select result->>'outcome' from t_result where label = 'a_upsert1'), 'applied', 'T-CRUD-1: manual upsert applies');
select is((select result->'external_busy'->>'source_tag' from t_result where label = 'a_upsert1'), 'manual', 'T-CRUD-2: source_tag is hard-coded manual');
select ok(not ((select result->'external_busy' from t_result where label = 'a_upsert1') ? 'external_ref_hash'), 'T-CRUD-3: the returned row never carries external_ref_hash');
select is((select result->'external_busy'->>'label' from t_result where label = 'a_upsert1'), 'Dentist', 'T-CRUD-4: label round-trips');

insert into t_result (label, result) select 'a_sources_after_upsert1', public.rpc_my_availability_sources();
select is(
    (select (elem->>'freshness_ttl_minutes')::int from t_result t, jsonb_array_elements(t.result) elem where t.label = 'a_sources_after_upsert1' and elem->>'source_tag' = 'manual'),
    43200,
    'T-CRUD-5: touching the manual list stamps availability_sources with the manual TTL default (43200min/30d)'
);
select is(
    (select elem->>'status' from t_result t, jsonb_array_elements(t.result) elem where t.label = 'a_sources_after_upsert1' and elem->>'source_tag' = 'manual'),
    'active',
    'T-CRUD-6: the stamped manual source is active'
);
select ok(
    (select (elem->>'last_synced_at')::timestamptz > now() - interval '1 minute' from t_result t, jsonb_array_elements(t.result) elem where t.label = 'a_sources_after_upsert1' and elem->>'source_tag' = 'manual'),
    'T-CRUD-7: last_synced_at was just stamped'
);

-- Update via p_id.
insert into t_result (label, result) select 'a_upsert2', public.rpc_upsert_external_busy(
    'f2200000-0000-4000-8000-000000000011'::uuid,
    (select (result->'external_busy'->>'id')::uuid from t_result where label = 'a_upsert1'),
    '2026-09-19T16:00:00Z'::timestamptz, '2026-09-19T17:00:00Z'::timestamptz,
    'America/Toronto', 'Dentist (moved)'
);
select is((select result->'external_busy'->>'id' from t_result where label = 'a_upsert2'), (select result->'external_busy'->>'id' from t_result where label = 'a_upsert1'), 'T-CRUD-8: updating by p_id keeps the same row id');
select is((select result->'external_busy'->>'starts_at_utc' from t_result where label = 'a_upsert2'), '2026-09-19T16:00:00Z', 'T-CRUD-9: the update persisted the new start time');

-- Mint replay: same operation_id returns the byte-identical result.
insert into t_result (label, result) select 'a_upsert2_replay', public.rpc_upsert_external_busy(
    'f2200000-0000-4000-8000-000000000011'::uuid,
    (select (result->'external_busy'->>'id')::uuid from t_result where label = 'a_upsert1'),
    '1999-01-01T00:00:00Z'::timestamptz, '1999-01-01T01:00:00Z'::timestamptz,
    'America/Toronto', 'ignored on replay'
);
select is(
    (select result from t_result where label = 'a_upsert2_replay'),
    (select result from t_result where label = 'a_upsert2'),
    'T-CRUD-10: replaying the same operation_id returns the byte-identical result regardless of new arguments'
);

-- Cross-action: the SAME operation_id used for delete after being used for upsert.
select throws_ok(
    format(
        $$select public.rpc_delete_external_busy('f2200000-0000-4000-8000-000000000010'::uuid, %L)$$,
        (select result->'external_busy'->>'id' from t_result where label = 'a_upsert1')
    ),
    '22023', null, 'T-CRUD-11: reusing an upsert''s operation_id for delete raises the cross-action guard'
);

-- T-CLIENTTAG-1: no entry point accepts a client-supplied source_tag (7 positional args ->
-- no matching overload).
select throws_ok(
    $$select public.rpc_upsert_external_busy(gen_random_uuid(), null, now(), now() + interval '1 hour', 'America/Toronto', null, 'google')$$,
    '42883', null, 'T-CLIENTTAG-1: a 7-arg call (as if smuggling source_tag) has no matching function overload'
);

-- T-CLIENTTAG-2: rpc_upsert_external_busy/rpc_delete_external_busy cannot touch a
-- service_role-synced (google) row -- folds to the same not-found as "not mine".
reset role;
insert into t_result (label, result) select 'a_sync_google', public.fn_sync_external_busy(
    '00000000-0000-0000-0000-000000022a01'::uuid, 'google',
    '2026-09-01T00:00:00Z'::timestamptz, '2026-10-17T00:00:00Z'::timestamptz,
    '[{"starts_at_utc":"2026-09-25T14:00:00Z","ends_at_utc":"2026-09-25T15:00:00Z","external_ref":"gcal-evt-1","label":"Synced meeting"}]'::jsonb
);
select is((select result->>'outcome' from t_result where label = 'a_sync_google'), 'applied', 'setup: fn_sync_external_busy seeds a google row for Alice');
select is((select (result->>'count')::int from t_result where label = 'a_sync_google'), 1, 'T-SYNC-1: fn_sync_external_busy reports the inserted count');
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000022a01","role":"authenticated"}';

select throws_ok(
    $$select public.rpc_delete_external_busy(gen_random_uuid(), (select id from public.external_busy where owner_id = '00000000-0000-0000-0000-000000022a01'::uuid and source_tag = 'google'))$$,
    'P0002', null, 'T-CLIENTTAG-2: rpc_delete_external_busy cannot delete a synced (google) row'
);
select throws_ok(
    $$select public.rpc_upsert_external_busy(gen_random_uuid(), (select id from public.external_busy where owner_id = '00000000-0000-0000-0000-000000022a01'::uuid and source_tag = 'google'), now(), now() + interval '1 hour', 'America/Toronto', null)$$,
    '23505', null, 'T-CLIENTTAG-3: rpc_upsert_external_busy cannot edit a synced (google) row (true-upsert lead fix: the update filter excludes it, and the insert path then collides on the existing pk -- the synced row survives untouched)'
);

-- T-CRUD-12/13: delete Alice's own manual row.
insert into t_result (label, result) select 'a_delete', public.rpc_delete_external_busy(
    'f2200000-0000-4000-8000-000000000012'::uuid,
    (select (result->'external_busy'->>'id')::uuid from t_result where label = 'a_upsert1')
);
select is((select result->>'outcome' from t_result where label = 'a_delete'), 'applied', 'T-CRUD-12: delete applies');
select is(
    (select count(*)::int from public.external_busy where id = (select (result->'external_busy'->>'id')::uuid from t_result where label = 'a_upsert1')),
    0,
    'T-CRUD-13: the row is actually gone'
);
insert into t_result (label, result) select 'a_delete_replay', public.rpc_delete_external_busy(
    'f2200000-0000-4000-8000-000000000012'::uuid,
    (select (result->'external_busy'->>'id')::uuid from t_result where label = 'a_upsert1')
);
select is(
    (select result from t_result where label = 'a_delete_replay'),
    (select result from t_result where label = 'a_delete'),
    'T-CRUD-14: replaying the delete returns the byte-identical result even though the row no longer exists'
);

-- ===================================================================================
-- T-ISO-*: cross-user isolation -- Carol cannot see Alice's rows through the base tables.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000022c01","role":"authenticated"}';
select is(
    (select count(*)::int from public.external_busy where owner_id = '00000000-0000-0000-0000-000000022a01'::uuid),
    0,
    'T-ISO-1: Carol sees zero of Alice''s external_busy rows (RLS owner-only)'
);
select is(
    (select count(*)::int from public.availability_sources where owner_id = '00000000-0000-0000-0000-000000022a01'::uuid),
    0,
    'T-ISO-2: Carol sees zero of Alice''s availability_sources rows (RLS owner-only)'
);

-- ===================================================================================
-- T-COALESCE-* / T-STALE-BUSY / T-NONCOVER / T-KEYS-1: Bob is the data subject (his rows
-- set up directly, bypassing the manual RPC's own auto-stamping so each scenario's
-- availability_sources state is exact); Alice calls rpc_proposal_conflict_hints about him.
-- ===================================================================================
reset role;
-- idx0: coalescing -- Bob's own ELAY block (19:00-20:00) UNIONS with his external_busy
-- (19:30-20:30, source_tag=manual) into a single 19:00-20:30 window.
insert into public.time_blocks (owner_id, visibility, title, starts_at_utc, ends_at_utc, origin_tz, type, status)
values ('00000000-0000-0000-0000-000000022b01'::uuid, 'private', 'Bob personal block', '2026-09-20T19:00:00Z', '2026-09-20T20:00:00Z', 'America/Vancouver', 'personal', 'scheduled');
insert into public.external_busy (owner_id, source_tag, external_ref_hash, starts_at_utc, ends_at_utc, origin_tz)
values ('00000000-0000-0000-0000-000000022b01'::uuid, 'manual', null, '2026-09-20T19:30:00Z', '2026-09-20T20:30:00Z', 'America/Vancouver');

-- idx1: stale-busy-still-busy -- a busy external_busy row exists AND its only availability_
-- sources row (google) is deliberately stale; busy must still win.
insert into public.external_busy (owner_id, source_tag, external_ref_hash, starts_at_utc, ends_at_utc, origin_tz)
values ('00000000-0000-0000-0000-000000022b01'::uuid, 'google', extensions.digest('bob-stale-evt', 'sha256'), '2026-09-21T19:00:00Z', '2026-09-21T20:00:00Z', 'America/Vancouver');
insert into public.availability_sources (owner_id, source_tag, status, last_synced_at, freshness_ttl_minutes, window_start_utc, window_end_utc)
values ('00000000-0000-0000-0000-000000022b01'::uuid, 'google', 'active', now() - interval '2 days', 360, now() - interval '10 days', now() + interval '35 days');

-- idx2: fresh-but-non-covering -- a fresh 'manual' source whose window ends BEFORE this
-- candidate's end (19:30 < 20:00), and no busy overlap at all in this window.
insert into public.availability_sources (owner_id, source_tag, status, last_synced_at, freshness_ttl_minutes, window_start_utc, window_end_utc)
values ('00000000-0000-0000-0000-000000022b01'::uuid, 'manual', 'active', now(), 43200, now() - interval '1 day', '2026-09-22T19:30:00Z');

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000022a01","role":"authenticated"}';
insert into t_result (label, result) select 'a_hints_1', public.rpc_proposal_conflict_hints(
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-20T18:00:00Z","ends_at_utc":"2026-09-20T21:00:00Z","duration_min":180},
      {"candidate_idx":1,"starts_at_utc":"2026-09-21T19:00:00Z","ends_at_utc":"2026-09-21T20:00:00Z","duration_min":60},
      {"candidate_idx":2,"starts_at_utc":"2026-09-22T19:00:00Z","ends_at_utc":"2026-09-22T20:00:00Z","duration_min":60}]'::jsonb
);

select is((select result->0->>'has_conflict' from t_result where label = 'a_hints_1')::boolean, true, 'T-COALESCE-1: candidate 0 has_conflict=true');
select is(jsonb_array_length((select result->0->'busy_windows' from t_result where label = 'a_hints_1')), 1, 'T-COALESCE-2: the two overlapping/adjacent intervals coalesce into exactly one window');
select is((select result->0->'busy_windows'->0->>'starts_at_utc' from t_result where label = 'a_hints_1'), '2026-09-20T19:00:00Z', 'T-COALESCE-3: the merged window starts at the earlier of the two (19:00)');
select is((select result->0->'busy_windows'->0->>'ends_at_utc' from t_result where label = 'a_hints_1'), '2026-09-20T20:30:00Z', 'T-COALESCE-4: the merged window ends at the later of the two (20:30)');
select is((select result->0->>'certainty' from t_result where label = 'a_hints_1'), 'busy', 'T-COALESCE-5: certainty=busy for the merged conflict');

select is((select result->1->>'has_conflict' from t_result where label = 'a_hints_1')::boolean, true, 'T-STALE-BUSY-1: candidate 1 (stale source, but a real overlap) has_conflict=true');
select is((select result->1->>'certainty' from t_result where label = 'a_hints_1'), 'busy', 'T-STALE-BUSY-2: certainty=busy even though Bob''s only bookkeeping source is stale -- busy always wins');

select is((select result->2->>'has_conflict' from t_result where label = 'a_hints_1')::boolean, false, 'T-NONCOVER-1: candidate 2 has no overlap');
select is((select result->2->>'certainty' from t_result where label = 'a_hints_1'), 'unknown', 'T-NONCOVER-2: certainty=unknown -- Bob''s manual source is fresh but does not cover this candidate''s end');

select is(
    (select array_agg(k order by k) from jsonb_object_keys((select result->0 from t_result where label = 'a_hints_1')) k),
    array['busy_windows','candidate_idx','certainty','has_conflict'],
    'T-KEYS-1: rpc_proposal_conflict_hints hint object has exactly {candidate_idx,has_conflict,busy_windows,certainty}'
);

-- T-FREECAL: re-stamp Bob's manual source to cover a later candidate, still fresh, no overlap.
reset role;
update public.availability_sources
set window_start_utc = now() - interval '1 day', window_end_utc = now() + interval '35 days', last_synced_at = now()
where owner_id = '00000000-0000-0000-0000-000000022b01'::uuid and source_tag = 'manual';
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000022a01","role":"authenticated"}';
insert into t_result (label, result) select 'a_hints_2', public.rpc_proposal_conflict_hints(
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-23T19:00:00Z","ends_at_utc":"2026-09-23T20:00:00Z","duration_min":60}]'::jsonb
);
select is((select result->0->>'has_conflict' from t_result where label = 'a_hints_2')::boolean, false, 'T-FREECAL-1: no overlap');
select is((select result->0->>'certainty' from t_result where label = 'a_hints_2'), 'free_per_calendar', 'T-FREECAL-2: certainty=free_per_calendar (fresh + covering source, no overlap)');

-- ===================================================================================
-- T-FREEELAY / T-KEYS-2: Carol has zero external_busy/availability_sources rows at all;
-- calls rpc_self_conflict_hints about her OWN calendar (no pair/partner required).
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000022c01","role":"authenticated"}';
insert into t_result (label, result) select 'c_self_hints', public.rpc_self_conflict_hints(
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-24T10:00:00Z","ends_at_utc":"2026-09-24T11:00:00Z","duration_min":60}]'::jsonb
);
select is((select result->0->>'has_conflict' from t_result where label = 'c_self_hints')::boolean, false, 'T-FREEELAY-1: no overlap');
select is((select result->0->>'certainty' from t_result where label = 'c_self_hints'), 'free_per_elay', 'T-FREEELAY-2: certainty=free_per_elay -- Carol has zero availability_sources rows');
select is(
    (select array_agg(k order by k) from jsonb_object_keys((select result->0 from t_result where label = 'c_self_hints')) k),
    array['busy_windows','candidate_idx','certainty','has_conflict'],
    'T-KEYS-2: rpc_self_conflict_hints hint object has the exact same key set'
);

-- ===================================================================================
-- T-FORMER-MEMBER: Frank leaves the pair; Eve''s subsequent hints call is denied.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000022f01","role":"authenticated"}';
insert into t_result (label, result) select 'f_leave', public.rpc_leave_pair('f2200000-0000-4000-8000-000000000020'::uuid);
select is((select result->>'outcome' from t_result where label = 'f_leave'), 'applied', 'setup: Frank leaves the pair');

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000022e01","role":"authenticated"}';
select throws_ok(
    $$select public.rpc_proposal_conflict_hints('[{"candidate_idx":0,"starts_at_utc":"2026-09-25T10:00:00Z","ends_at_utc":"2026-09-25T11:00:00Z","duration_min":60}]'::jsonb)$$,
    '42501', null, 'T-FORMER-MEMBER: Eve (whose only partner left) is denied hints'
);

-- ===================================================================================
-- T-RATE-*: 60/15min per caller, shared across BOTH hint RPCs. Gina/Henry are dedicated
-- (zero prior hints calls) so the count is exact.
-- ===================================================================================
do $$
declare
    i int;
    v_out jsonb;
begin
    perform set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-000000022901","role":"authenticated"}', true);
    for i in 1 .. 60 loop
        select public.rpc_self_conflict_hints(
            '[{"candidate_idx":0,"starts_at_utc":"2026-09-26T10:00:00Z","ends_at_utc":"2026-09-26T11:00:00Z","duration_min":60}]'::jsonb
        ) into v_out;
        if v_out->0->>'certainty' <> 'free_per_elay' then
            raise exception 'T-RATE setup invariant broken: call % unexpectedly returned %', i, v_out;
        end if;
    end loop;
end;
$$;

insert into t_result (label, result) select 'gina_61st_self', public.rpc_self_conflict_hints(
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-26T10:00:00Z","ends_at_utc":"2026-09-26T11:00:00Z","duration_min":60}]'::jsonb
);
select is((select result->0->>'certainty' from t_result where label = 'gina_61st_self'), 'unknown', 'T-RATE-1: the 61st self-hints call in 15min trips the shared 60/15min limit -> opaque certainty=unknown');
select is((select result->0->>'has_conflict' from t_result where label = 'gina_61st_self')::boolean, false, 'T-RATE-2: the opaque shape discloses no conflict data');
select is((select (result->0->>'candidate_idx')::int from t_result where label = 'gina_61st_self'), 0, 'T-RATE-3: the opaque shape preserves the input candidate_idx');

insert into t_result (label, result) select 'gina_62nd_proposal', public.rpc_proposal_conflict_hints(
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-26T10:00:00Z","ends_at_utc":"2026-09-26T11:00:00Z","duration_min":60}]'::jsonb
);
select is((select result->0->>'certainty' from t_result where label = 'gina_62nd_proposal'), 'unknown', 'T-RATE-4: the SAME per-caller limit also trips on rpc_proposal_conflict_hints, proving it is shared, not per-RPC');

reset role;
select ok(
    (select count(*)::int from public.conflict_hints_attempts where caller_id = '00000000-0000-0000-0000-000000022901'::uuid) >= 62,
    'T-RATE-5: every attempt (including the rate-limited ones) was logged unconditionally'
);
set local role authenticated;

-- ===================================================================================
-- T-DST-*: manual busy window over the America/Vancouver 2026-03-08 spring-forward gap
-- (mirrors 0016's ADR-006 fixture, applied to a manual external_busy row instead of a
-- proposal candidate). The gap-resolved instant is 03:00 PDT, not the nonexistent 02:30.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000022d01","role":"authenticated"}';
insert into t_result (label, result) select 'd_dst_upsert', public.rpc_upsert_external_busy(
    'f2200000-0000-4000-8000-000000000030'::uuid, null,
    '2026-03-08T10:00:00Z'::timestamptz, '2026-03-08T10:30:00Z'::timestamptz,
    'America/Vancouver', 'Spring-forward gap check'
);
select is((select result->>'outcome' from t_result where label = 'd_dst_upsert'), 'applied', 'T-DST-1: the gap-resolved manual window is accepted');
select is(
    to_char(timezone('America/Vancouver', (select (result->'external_busy'->>'starts_at_utc')::timestamptz from t_result where label = 'd_dst_upsert')), 'YYYY-MM-DD HH24:MI'),
    '2026-03-08 03:00',
    'T-DST-2: the stored instant reads as 03:00 local Vancouver -- the first valid instant after the gap, not the nonexistent 02:30'
);
select is(
    (select extract(epoch from ((result->'external_busy'->>'ends_at_utc')::timestamptz - (result->'external_busy'->>'starts_at_utc')::timestamptz)) / 60 from t_result where label = 'd_dst_upsert')::int,
    30,
    'T-DST-3: the 30-minute duration is preserved across the gap'
);
insert into t_result (label, result) select 'd_sources', public.rpc_my_availability_sources();
select is(
    (select elem->>'source_tag' from t_result t, jsonb_array_elements(t.result) elem where t.label = 'd_sources'),
    'manual',
    'T-DST-4: touching the manual list stamped Dana''s own manual availability_sources row'
);

-- ===================================================================================
-- T-GREP-*: external_ref_hash never leaves any RPC.
-- ===================================================================================
reset role;
select ok(
    pg_get_functiondef('public.external_busy_to_jsonb(public.external_busy)'::regprocedure) !~ 'external_ref_hash',
    'T-GREP-1: the external_busy wire projector never references external_ref_hash'
);
select ok(
    not exists (
        select 1 from public.mutation_receipts
        where result::text like '%external_ref_hash%'
    ),
    'T-GREP-2: no mutation_receipts row anywhere carries the external_ref_hash key/value'
);
select is(
    (select array_agg(k order by k) from jsonb_object_keys((select elem from t_result t, jsonb_array_elements(t.result) elem where t.label = 'a_sources_after_upsert1' and elem->>'source_tag' = 'manual')) k),
    array['freshness_ttl_minutes','last_synced_at','source_tag','status','window_end_utc','window_start_utc'],
    'T-GREP-3: rpc_my_availability_sources exposes exactly the contract''s fields, nothing token/hash-shaped'
);

-- Pre-gate re-verify pins: the two lead-added guards must carry negative tests.
reset role;
select throws_ok(
    $$select public.fn_sync_external_busy(gen_random_uuid(), 'manual', now(), now() + interval '1 day', '[]'::jsonb)$$,
    '22023', null,
    'F12: fn_sync_external_busy rejects source_tag=manual (an edge-function bug must never wipe hand-entered rows)'
);
-- F8: rows older than a day are purged by the limiter's opportunistic delete.
insert into public.conflict_hints_attempts (caller_id, attempted_at)
select '00000000-0000-0000-0000-000000022a01', now() - interval '3 days' from generate_series(1, 5);
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000022a01","role":"authenticated"}';
select lives_ok(
    $$select public.rpc_self_conflict_hints('[{"candidate_idx":0,"starts_at_utc":"2026-10-05T19:00:00Z","ends_at_utc":"2026-10-05T20:00:00Z","duration_min":60}]'::jsonb)$$,
    'F8 setup: one hints call runs the opportunistic purge'
);
reset role;
select is(
    (select count(*)::int from public.conflict_hints_attempts
     where caller_id = '00000000-0000-0000-0000-000000022a01' and attempted_at < now() - interval '1 day'),
    0,
    'F8: attempt rows older than a day are purged'
);
set local role authenticated;

select * from finish();
rollback;
