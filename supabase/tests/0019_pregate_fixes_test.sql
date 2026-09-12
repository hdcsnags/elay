-- pgTAP: Stage 2 pre-gate verification round (2026-09-12) — pins the two proven bugs the
-- verifier found (F2 cursor precision, F8 complete_lock version hole, fixed by migration
-- 20260912170000) and closes the contract-§5 coverage gaps it named (F7: Stage-2 broadcast
-- payload key sets; replay for accept/decline/counter/cancel).
begin;
create extension if not exists pgtap with schema extensions;
select plan(21);

-- setup: pair A/B via the real RPCs.
insert into auth.users (id, email) values
    ('00000000-0000-0000-0000-0000000019a1', 'pg-a@test.local'),
    ('00000000-0000-0000-0000-0000000019b1', 'pg-b@test.local');
insert into public.profiles (user_id, display_name, home_tz) values
    ('00000000-0000-0000-0000-0000000019a1', 'PG A', 'America/Toronto'),
    ('00000000-0000-0000-0000-0000000019b1', 'PG B', 'America/Vancouver')
on conflict (user_id) do update set display_name = excluded.display_name, home_tz = excluded.home_tz;

create temporary table t_result (label text, result jsonb);
grant all on t_result to authenticated, anon;

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000019a1","role":"authenticated"}';
insert into t_result (label, result) select 'a_invite', public.rpc_create_pair_invite('e1900000-0000-4000-8000-000000000001'::uuid, 60);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000019b1","role":"authenticated"}';
insert into t_result (label, result) select 'b_redeem', public.rpc_redeem_pair_invite(
    'e1900000-0000-4000-8000-000000000002'::uuid, (select result->'invite'->>'code' from t_result where label = 'a_invite'));
select is((select result->>'outcome' from t_result where label = 'b_redeem'), 'applied', 'setup: A/B paired');

-- ===================================================================================
-- F2: pagination must not lose rows when updated_at ties across a page boundary
-- (25 tied rows is exactly what one fn_expire_proposals sweep produces).
-- ===================================================================================
reset role;
insert into public.time_lock_proposals (id, pair_id, creator_id, title, status, response_deadline, origin_tz, current_revision, version, created_at, updated_at)
select gen_random_uuid(),
       (select (result->'pair'->>'pair_id')::uuid from t_result where label = 'b_redeem'),
       '00000000-0000-0000-0000-0000000019a1',
       'Tied ' || n, 'proposed', now() + interval '2 days', 'America/Toronto', 1, 1,
       '2026-09-12 10:00:00.123456+00', '2026-09-12 10:00:00.123456+00'
from generate_series(1, 25) n;

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000019a1","role":"authenticated"}';
insert into t_result (label, result) select 'page1', public.rpc_list_proposals('active', null);
select is(
    (select jsonb_array_length(result->'items') from t_result where label = 'page1'), 20,
    'F2-1: page 1 carries the full page of 20'
);
select isnt(
    (select result->>'next_cursor' from t_result where label = 'page1'), null,
    'F2-2: a full page yields a next_cursor'
);
insert into t_result (label, result)
select 'page2', public.rpc_list_proposals('active', (select result->>'next_cursor' from t_result where label = 'page1'));
select is(
    (select jsonb_array_length(result->'items') from t_result where label = 'page2'), 5,
    'F2-3: page 2 returns the remaining 5 tied rows (whole-second cursors lost them)'
);
select is(
    (select result->>'next_cursor' from t_result where label = 'page2'), null,
    'F2-4: the final partial page carries a null cursor'
);
select is(
    (select count(distinct id)::int from (
        select jsonb_array_elements(result->'items')->>'id' as id from t_result where label in ('page1','page2')
    ) all_ids), 25,
    'F2-5: pages 1+2 together cover all 25 rows with no duplicates'
);

-- ===================================================================================
-- F8: after rpc_complete_lock, a stale-version rpc_upsert_time_block must CONFLICT,
-- never silently revert the completed block.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000019a1","role":"authenticated"}';
insert into t_result (label, result) select 'p_create', public.rpc_create_proposal(
    'e1900000-0000-4000-8000-000000000003'::uuid, 'Completable', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-20T19:00:00Z","ends_at_utc":"2026-09-20T20:00:00Z","duration_min":60}]'::jsonb
);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000019b1","role":"authenticated"}';
insert into t_result (label, result) select 'b_accept', public.rpc_respond_proposal(
    'e1900000-0000-4000-8000-000000000004'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'p_create'), 1, 'accept', 0);
select is((select result->>'outcome' from t_result where label = 'b_accept'), 'applied', 'setup: accepted');

reset role;
update public.time_blocks
set starts_at_utc = now() - interval '1 hour', ends_at_utc = now() - interval '30 minutes'
where source_proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p_create');

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000019b1","role":"authenticated"}';
-- B's block version before completing (accept created it at version 1).
insert into t_result (label, result)
select 'b_block_before', to_jsonb((select tb from public.time_blocks tb
    where tb.source_proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p_create')
      and tb.owner_id = '00000000-0000-0000-0000-0000000019b1'));

insert into t_result (label, result) select 'b_complete', public.rpc_complete_lock(
    'e1900000-0000-4000-8000-000000000005'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'p_create'));
select is((select result->>'outcome' from t_result where label = 'b_complete'), 'applied', 'B completed their lock');

select is(
    (select tb.version from public.time_blocks tb
     where tb.id = (select (result->>'id')::uuid from t_result where label = 'b_block_before')),
    (select (result->>'version')::bigint + 1 from t_result where label = 'b_block_before'),
    'F8-1: rpc_complete_lock bumps time_blocks.version'
);

insert into t_result (label, result) select 'stale_upsert', public.rpc_upsert_time_block(
    'e1900000-0000-4000-8000-000000000006'::uuid,
    (select (result->>'version')::bigint from t_result where label = 'b_block_before'),
    jsonb_build_object(
        'id', (select result->>'id' from t_result where label = 'b_block_before'),
        'title', (select result->>'title' from t_result where label = 'b_block_before'),
        'starts_at_utc', (select result->>'starts_at_utc' from t_result where label = 'b_block_before'),
        'ends_at_utc', (select result->>'ends_at_utc' from t_result where label = 'b_block_before'),
        'origin_tz', (select result->>'origin_tz' from t_result where label = 'b_block_before'),
        'status', 'scheduled'
    )
);
select is(
    (select result->>'outcome' from t_result where label = 'stale_upsert'), 'conflict',
    'F8-2: a stale-version upsert after completion CONFLICTS instead of applying'
);
select is(
    (select tb.status from public.time_blocks tb
     where tb.id = (select (result->>'id')::uuid from t_result where label = 'b_block_before')),
    'completed',
    'F8-3: the completed block stays completed'
);

-- ===================================================================================
-- F7a: Stage-2 broadcast payload key sets (contract §3 — sanitized, exact keys, no
-- title). realtime.send writes realtime.messages rows we can assert on directly.
-- ===================================================================================
reset role;
-- realtime.send injects its own 'id' key into the stored payload; strip it before the
-- exact-key comparison (the sanitization rule is about OUR keys).
select is(
    (select array_agg(k order by k) from jsonb_object_keys((
        select payload - 'id' from realtime.messages
        where event = 'pair.proposal_created.v1'
          and payload->>'proposal_id' = (select result->'proposal'->>'id' from t_result where label = 'p_create')
        order by inserted_at desc limit 1
    )) k),
    array['current_revision','pair_id','proposal_id','status','version'],
    'F7-1: pair.proposal_created.v1 payload carries EXACTLY the five sanitized keys'
);
select is(
    (select array_agg(k order by k) from jsonb_object_keys((
        select payload - 'id' from realtime.messages
        where event = 'pair.commitment_changed.v1'
          and payload->>'proposal_id' = (select result->'proposal'->>'id' from t_result where label = 'p_create')
        order by inserted_at desc limit 1
    )) k),
    array['commitment_id','pair_id','proposal_id','state','user_id','version'],
    'F7-2: pair.commitment_changed.v1 payload carries EXACTLY its six sanitized keys'
);
select is(
    (select count(*)::int from realtime.messages
     where payload::text like '%Completable%' or payload::text like '%Tied %'),
    0,
    'F7-3: no broadcast payload ever carries a proposal title'
);
-- proposal_updated.v1 asserted DIRECTLY (re-verify residual: created/updated share
-- proposal_broadcast_payload today, but a divergence at one call site must be caught).
select is(
    (select array_agg(k order by k) from jsonb_object_keys((
        select payload - 'id' from realtime.messages
        where event = 'pair.proposal_updated.v1'
          and payload->>'proposal_id' = (select result->'proposal'->>'id' from t_result where label = 'p_create')
        order by inserted_at desc limit 1
    )) k),
    array['current_revision','pair_id','proposal_id','status','version'],
    'F7-3b: pair.proposal_updated.v1 payload carries EXACTLY the five sanitized keys'
);

-- ===================================================================================
-- F7b: receipt replay for accept / decline / counter / cancel (create/complete are
-- covered in 0015). Replay = identical result; cross-action reuse = rejection.
-- ===================================================================================
set local role authenticated;

-- accept replay (B's accept above).
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000019b1","role":"authenticated"}';
insert into t_result (label, result) select 'accept_replay', public.rpc_respond_proposal(
    'e1900000-0000-4000-8000-000000000004'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'p_create'), 1, 'accept', 0);
select is(
    (select result from t_result where label = 'accept_replay'),
    (select result from t_result where label = 'b_accept'),
    'F7-4: accept replay returns the identical receipt'
);

-- decline replay: fresh proposal by A, declined twice by B with one operation_id.
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000019a1","role":"authenticated"}';
insert into t_result (label, result) select 'p_decline', public.rpc_create_proposal(
    'e1900000-0000-4000-8000-000000000007'::uuid, 'To decline', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-21T19:00:00Z","ends_at_utc":"2026-09-21T20:00:00Z","duration_min":60}]'::jsonb
);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000019b1","role":"authenticated"}';
insert into t_result (label, result) select 'b_decline', public.rpc_respond_proposal(
    'e1900000-0000-4000-8000-000000000008'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'p_decline'), 1, 'decline');
insert into t_result (label, result) select 'decline_replay', public.rpc_respond_proposal(
    'e1900000-0000-4000-8000-000000000008'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'p_decline'), 1, 'decline');
select is(
    (select result from t_result where label = 'decline_replay'),
    (select result from t_result where label = 'b_decline'),
    'F7-5: decline replay returns the identical receipt'
);

-- counter replay: fresh proposal by A, countered twice by B with one operation_id.
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000019a1","role":"authenticated"}';
insert into t_result (label, result) select 'p_counter', public.rpc_create_proposal(
    'e1900000-0000-4000-8000-000000000009'::uuid, 'To counter', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-22T19:00:00Z","ends_at_utc":"2026-09-22T20:00:00Z","duration_min":60}]'::jsonb
);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000019b1","role":"authenticated"}';
insert into t_result (label, result) select 'b_counter', public.rpc_respond_proposal(
    'e1900000-0000-4000-8000-00000000000a'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'p_counter'), 1, 'counter', null,
    'America/Vancouver', now() + interval '3 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-23T02:00:00Z","ends_at_utc":"2026-09-23T03:00:00Z","duration_min":60}]'::jsonb);
insert into t_result (label, result) select 'counter_replay', public.rpc_respond_proposal(
    'e1900000-0000-4000-8000-00000000000a'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'p_counter'), 1, 'counter', null,
    'America/Vancouver', now() + interval '3 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-23T02:00:00Z","ends_at_utc":"2026-09-23T03:00:00Z","duration_min":60}]'::jsonb);
select is(
    (select result from t_result where label = 'counter_replay'),
    (select result from t_result where label = 'b_counter'),
    'F7-6: counter replay returns the identical receipt (no second revision)'
);
select is(
    (select (result->'proposal'->>'current_revision')::int from t_result where label = 'counter_replay'), 2,
    'F7-7: the replayed counter left current_revision at 2 (append-only history intact)'
);

-- cancel replay: A cancels the countered proposal twice with one operation_id.
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000019a1","role":"authenticated"}';
insert into t_result (label, result) select 'a_cancel', public.rpc_cancel_proposal(
    'e1900000-0000-4000-8000-00000000000b'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'p_counter'));
insert into t_result (label, result) select 'cancel_replay', public.rpc_cancel_proposal(
    'e1900000-0000-4000-8000-00000000000b'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'p_counter'));
select is(
    (select result from t_result where label = 'cancel_replay'),
    (select result from t_result where label = 'a_cancel'),
    'F7-8: cancel replay returns the identical receipt'
);

-- cross-action: reusing the cancel operation_id for complete_lock is rejected.
select throws_ok(
    format($$select public.rpc_complete_lock('e1900000-0000-4000-8000-00000000000b'::uuid, %L)$$,
        (select result->'proposal'->>'id' from t_result where label = 'p_counter')),
    '22023', null,
    'F7-9: an operation_id used for cancel is rejected by complete_lock (cross-action guard)'
);

select * from finish();
rollback;
