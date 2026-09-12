-- pgTAP: Stage 3 no-install web RSVP (migration 20260912190000_stage3_web_rsvp.sql,
-- council/stage3-web-rsvp-security-opus.md §A). Covers the §A adversarial list:
--   structure / RLS / grants                          -> T-STRUCT-*
--   anon + authenticated denied on all three new tables -> T-DENY-TABLE-*
--   anon/authenticated denied the two service_role RPCs -> T-DENY-FN-*
--   token minted by the non-author / by a non-member    -> T-MINT-DENY-*
--   mint happy path + disclose list                     -> T-MINT-HAPPY-*
--   mint replay (byte-identical)                         -> T-MINT-REPLAY-*
--   re-mint revokes predecessor                          -> T-REMINT-*
--   render-data exact disclosure set + 'Someone' fallback -> T-RENDER-*
--   forged MAC / truncated / oversized / unknown token_id / malformed -> T-ADV-*
--   rate limiting trips at the boundary, identical opaque shape -> T-RATE-*
--   accepted-from-web produces exactly two shared_lock blocks + two commitments -> T-WEB-ACCEPT-*
--   byte-identical response replay                       -> T-WEB-REPLAY
--   web decline                                          -> T-WEB-DECLINE
--   web counter mints nothing (rejected)                 -> T-WEB-COUNTER-DENY
--   mismatched body ignored (pass-through validation, no bypass) -> T-WEB-MISMATCH
--   stale-revision-from-web / countered-since-mint        -> T-COUNTERED-*
--   minted-then-cancelled                                 -> T-CANCELLED-*
--   recipient left pair -> generation mismatch -> invalid -> T-LEFT-*
--   cross-action raises 22023 (direct, decoupled from the wrapper's own live-state gate --
--     see this seat's report on why the wrapper itself returns "conflict" instead) -> T-CROSS-ACTION
--   grep-proofs: no plaintext token in rsvp_tokens/mutation_receipts/realtime.messages -> T-GREP-*
begin;
create extension if not exists pgtap with schema extensions;
select plan(82);

-- ===================================================================================
-- structure
-- ===================================================================================
select has_table('public', 'rsvp_token_hmac_keys', 'T-STRUCT-1: rsvp_token_hmac_keys table exists');
select has_table('public', 'rsvp_tokens', 'T-STRUCT-2: rsvp_tokens table exists');
select has_table('public', 'rsvp_token_attempts', 'T-STRUCT-3: rsvp_token_attempts table exists');

select ok((select relrowsecurity from pg_class where oid = 'public.rsvp_token_hmac_keys'::regclass), 'T-STRUCT-4: RLS enabled on rsvp_token_hmac_keys');
select ok((select relrowsecurity from pg_class where oid = 'public.rsvp_tokens'::regclass), 'T-STRUCT-5: RLS enabled on rsvp_tokens');
select ok((select relrowsecurity from pg_class where oid = 'public.rsvp_token_attempts'::regclass), 'T-STRUCT-6: RLS enabled on rsvp_token_attempts');

select has_function('public', 'rpc_mint_rsvp_token', array['uuid','uuid'], 'T-STRUCT-7: rpc_mint_rsvp_token exists');
select has_function('public', 'rpc_get_rsvp_render_data', array['text','text'], 'T-STRUCT-8: rpc_get_rsvp_render_data exists');
select has_function('public', 'rpc_respond_proposal_web', array['text','text','integer','text','timestamptz','jsonb','text'], 'T-STRUCT-9: rpc_respond_proposal_web exists');

-- ===================================================================================
-- setup: pair A/B (main flow), pair C/D (non-member), pair E/F (F has no profile row,
-- for the 'Someone' fallback), pair G/H (for the leave-revokes-token case).
-- ===================================================================================
insert into auth.users (id, email) values
    ('00000000-0000-0000-0000-000000021a01', 'a@rsvp.local'),
    ('00000000-0000-0000-0000-000000021b01', 'b@rsvp.local'),
    ('00000000-0000-0000-0000-000000021c01', 'c@rsvp.local'),
    ('00000000-0000-0000-0000-000000021d01', 'd@rsvp.local'),
    ('00000000-0000-0000-0000-000000021e01', 'e@rsvp.local'),
    ('00000000-0000-0000-0000-000000021f01', 'f@rsvp.local'),
    ('00000000-0000-0000-0000-000000021901', 'g@rsvp.local'),
    ('00000000-0000-0000-0000-000000021902', 'h@rsvp.local');

insert into public.profiles (user_id, display_name, home_tz) values
    ('00000000-0000-0000-0000-000000021a01', 'RSVP Alice', 'America/Toronto'),
    ('00000000-0000-0000-0000-000000021b01', 'RSVP Bob', 'America/Vancouver'),
    ('00000000-0000-0000-0000-000000021c01', 'RSVP Carol', 'America/Toronto'),
    ('00000000-0000-0000-0000-000000021d01', 'RSVP Dave', 'America/Vancouver'),
    ('00000000-0000-0000-0000-000000021e01', 'RSVP Erin', 'America/Toronto'),
    -- F deliberately has NO profiles row (T-RENDER-SOMEONE below).
    ('00000000-0000-0000-0000-000000021901', 'RSVP Grace', 'America/Toronto'),
    ('00000000-0000-0000-0000-000000021902', 'RSVP Hank', 'America/Vancouver')
on conflict (user_id) do update set display_name = excluded.display_name, home_tz = excluded.home_tz;

create temporary table t_result (label text, result jsonb);
grant all on t_result to authenticated, anon;

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021a01","role":"authenticated"}';
insert into t_result (label, result) select 'a_invite', public.rpc_create_pair_invite('f2100000-0000-4000-8000-000000000001'::uuid, 60);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021b01","role":"authenticated"}';
insert into t_result (label, result) select 'b_redeem', public.rpc_redeem_pair_invite(
    'f2100000-0000-4000-8000-000000000002'::uuid, (select result->'invite'->>'code' from t_result where label = 'a_invite'));
select is((select result->>'outcome' from t_result where label = 'b_redeem'), 'applied', 'setup: A/B paired');

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021c01","role":"authenticated"}';
insert into t_result (label, result) select 'c_invite', public.rpc_create_pair_invite('f2100000-0000-4000-8000-000000000003'::uuid, 60);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021d01","role":"authenticated"}';
insert into t_result (label, result) select 'd_redeem', public.rpc_redeem_pair_invite(
    'f2100000-0000-4000-8000-000000000004'::uuid, (select result->'invite'->>'code' from t_result where label = 'c_invite'));
select is((select result->>'outcome' from t_result where label = 'd_redeem'), 'applied', 'setup: C/D paired');

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021e01","role":"authenticated"}';
insert into t_result (label, result) select 'e_invite', public.rpc_create_pair_invite('f2100000-0000-4000-8000-000000000005'::uuid, 60);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021f01","role":"authenticated"}';
insert into t_result (label, result) select 'f_redeem', public.rpc_redeem_pair_invite(
    'f2100000-0000-4000-8000-000000000006'::uuid, (select result->'invite'->>'code' from t_result where label = 'e_invite'));
select is((select result->>'outcome' from t_result where label = 'f_redeem'), 'applied', 'setup: E/F paired (F has no profile row)');

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021901","role":"authenticated"}';
insert into t_result (label, result) select 'g_invite', public.rpc_create_pair_invite('f2100000-0000-4000-8000-000000000007'::uuid, 60);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021902","role":"authenticated"}';
insert into t_result (label, result) select 'h_redeem', public.rpc_redeem_pair_invite(
    'f2100000-0000-4000-8000-000000000008'::uuid, (select result->'invite'->>'code' from t_result where label = 'g_invite'));
select is((select result->>'outcome' from t_result where label = 'h_redeem'), 'applied', 'setup: G/H paired');

-- Main proposal: A authors, recipient is B.
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021a01","role":"authenticated"}';
insert into t_result (label, result) select 'p1_create', public.rpc_create_proposal(
    'f2100000-0000-4000-8000-000000000010'::uuid, 'RSVP dinner', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-20T23:00:00Z","ends_at_utc":"2026-09-21T01:00:00Z","duration_min":120},
      {"candidate_idx":1,"starts_at_utc":"2026-09-21T23:00:00Z","ends_at_utc":"2026-09-22T01:00:00Z","duration_min":120}]'::jsonb
);
select is((select result->>'outcome' from t_result where label = 'p1_create'), 'applied', 'setup: A creates the main RSVP proposal');

-- ===================================================================================
-- T-DENY-TABLE-*: anon + authenticated denied direct SELECT on all three new tables.
-- ===================================================================================
set local role anon;
set local request.jwt.claims to '{}';
select throws_ok('select 1 from public.rsvp_token_hmac_keys', '42501', null, 'T-DENY-TABLE-1: anon denied rsvp_token_hmac_keys');
select throws_ok('select 1 from public.rsvp_tokens', '42501', null, 'T-DENY-TABLE-2: anon denied rsvp_tokens');
select throws_ok('select 1 from public.rsvp_token_attempts', '42501', null, 'T-DENY-TABLE-3: anon denied rsvp_token_attempts');
select throws_ok(
    $$select public.rpc_mint_rsvp_token(gen_random_uuid(), gen_random_uuid())$$,
    '42501', null, 'T-DENY-FN-1: anon cannot execute rpc_mint_rsvp_token'
);

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021a01","role":"authenticated"}';
select throws_ok('select 1 from public.rsvp_token_hmac_keys', '42501', null, 'T-DENY-TABLE-4: authenticated denied rsvp_token_hmac_keys');
select throws_ok('select 1 from public.rsvp_tokens', '42501', null, 'T-DENY-TABLE-5: authenticated denied rsvp_tokens');
select throws_ok('select 1 from public.rsvp_token_attempts', '42501', null, 'T-DENY-TABLE-6: authenticated denied rsvp_token_attempts');
select throws_ok(
    $$select public.rpc_get_rsvp_render_data('x.y')$$,
    '42501', null, 'T-DENY-FN-2: authenticated cannot execute rpc_get_rsvp_render_data (service_role only)'
);
select throws_ok(
    $$select public.rpc_respond_proposal_web('x.y', 'accept')$$,
    '42501', null, 'T-DENY-FN-3: authenticated cannot execute rpc_respond_proposal_web (service_role only)'
);

-- ===================================================================================
-- T-MINT-DENY-*: non-author and non-member cannot mint.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021b01","role":"authenticated"}';
select throws_ok(
    format($$select public.rpc_mint_rsvp_token(gen_random_uuid(), %L)$$, (select result->'proposal'->>'id' from t_result where label = 'p1_create')),
    '42501', null, 'T-MINT-DENY-1: B (not the current revision''s author) cannot mint'
);

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021c01","role":"authenticated"}';
select throws_ok(
    format($$select public.rpc_mint_rsvp_token(gen_random_uuid(), %L)$$, (select result->'proposal'->>'id' from t_result where label = 'p1_create')),
    '42501', null, 'T-MINT-DENY-2: C (not an active member of A/B''s pair) cannot mint'
);

-- ===================================================================================
-- T-MINT-HAPPY-* / T-MINT-REPLAY-*: A (author) mints for B.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021a01","role":"authenticated"}';
insert into t_result (label, result) select 'a_mint', public.rpc_mint_rsvp_token(
    'f2100000-0000-4000-8000-000000000020'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create'));
select is((select result->>'outcome' from t_result where label = 'a_mint'), 'applied', 'T-MINT-HAPPY-1: A mints a token for B');
select is(
    (select result->'discloses' from t_result where label = 'a_mint'),
    '["title","times","names"]'::jsonb,
    'T-MINT-HAPPY-2: discloses is exactly [title,times,names]'
);
select isnt((select result->>'token' from t_result where label = 'a_mint'), null, 'T-MINT-HAPPY-3: a token string is returned');
select isnt((select result->>'expires_at' from t_result where label = 'a_mint'), null, 'T-MINT-HAPPY-4: expires_at is returned');

insert into t_result (label, result) select 'a_mint_replay', public.rpc_mint_rsvp_token(
    'f2100000-0000-4000-8000-000000000020'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create'));
select is(
    (select result from t_result where label = 'a_mint_replay'),
    (select result from t_result where label = 'a_mint'),
    'T-MINT-REPLAY-1: replaying the mint operation_id returns the byte-identical result (token re-derived, not stored)'
);

-- ===================================================================================
-- T-RENDER-*: render-data exact disclosure set for the live token.
-- ===================================================================================
reset role;
insert into t_result (label, result) select 'a_render', public.rpc_get_rsvp_render_data((select result->>'token' from t_result where label = 'a_mint'));
select is((select result->>'outcome' from t_result where label = 'a_render'), 'ok', 'T-RENDER-1: render-data outcome=ok for a live token');
select is((select result->>'state' from t_result where label = 'a_render'), 'live', 'T-RENDER-2: state=live');
select is(
    (select array_agg(k order by k) from jsonb_object_keys((select result from t_result where label = 'a_render')) k),
    array['candidates','deadline','outcome','proposer_display_name','proposer_origin_tz',
          'recipient_display_name','recipient_home_tz','state','title'],
    'T-RENDER-3: exact disclosure key set, nothing more'
);
select is((select result->>'title' from t_result where label = 'a_render'), 'RSVP dinner', 'T-RENDER-4: title correct');
select is((select result->>'proposer_display_name' from t_result where label = 'a_render'), 'RSVP Alice', 'T-RENDER-5: proposer (current revision author) display name correct');
select is((select result->>'recipient_display_name' from t_result where label = 'a_render'), 'RSVP Bob', 'T-RENDER-6: recipient display name correct');
select is((select result->>'recipient_home_tz' from t_result where label = 'a_render'), 'America/Vancouver', 'T-RENDER-7: recipient home_tz correct');
select is(jsonb_array_length((select result->'candidates' from t_result where label = 'a_render')), 2, 'T-RENDER-8: current-revision candidates (both) returned');
set local role authenticated;

-- 'Someone' fallback: E authors a proposal for F (no profile row).
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021e01","role":"authenticated"}';
insert into t_result (label, result) select 'p_ef_create', public.rpc_create_proposal(
    'f2100000-0000-4000-8000-000000000030'::uuid, 'Someone-fallback check', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-23T19:00:00Z","ends_at_utc":"2026-09-23T20:00:00Z","duration_min":60}]'::jsonb
);
insert into t_result (label, result) select 'ef_mint', public.rpc_mint_rsvp_token(
    'f2100000-0000-4000-8000-000000000031'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p_ef_create'));
reset role;
-- handle_new_user (20260912120000) fires on direct auth.users inserts too (the seed.sql
-- lesson), so F GOT a profile after all -- delete it to actually exercise the fallback.
delete from public.profiles where user_id = '00000000-0000-0000-0000-000000021f01';
insert into t_result (label, result) select 'ef_render', public.rpc_get_rsvp_render_data((select result->>'token' from t_result where label = 'ef_mint'));
select is((select result->>'recipient_display_name' from t_result where label = 'ef_render'), 'Someone', 'T-RENDER-SOMEONE: recipient with no profiles row falls back to ''Someone''');
set local role authenticated;

-- ===================================================================================
-- T-REMINT-*: re-minting revokes the predecessor.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021a01","role":"authenticated"}';
insert into t_result (label, result) select 'a_remint', public.rpc_mint_rsvp_token(
    'f2100000-0000-4000-8000-000000000021'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p1_create'));
select isnt(
    (select result->>'token_id' from t_result where label = 'a_remint'),
    (select result->>'token_id' from t_result where label = 'a_mint'),
    'T-REMINT-1: a fresh operation_id mints a genuinely new token_id'
);
reset role;
insert into t_result (label, result) select 'old_render_after_remint', public.rpc_get_rsvp_render_data((select result->>'token' from t_result where label = 'a_mint'));
select is((select result->>'outcome' from t_result where label = 'old_render_after_remint'), 'invalid_or_unavailable', 'T-REMINT-2: the superseded (revoked) predecessor is now opaque');
insert into t_result (label, result) select 'new_render_after_remint', public.rpc_get_rsvp_render_data((select result->>'token' from t_result where label = 'a_remint'));
select is((select result->>'state' from t_result where label = 'new_render_after_remint'), 'live', 'T-REMINT-3: the new token is live');
set local role authenticated;

-- ===================================================================================
-- T-ADV-*: adversarial token shapes -- all fold to the single opaque shape.
-- ===================================================================================
reset role;
insert into t_result (label, result) select 'adv_forged', public.rpc_get_rsvp_render_data(
    substr((select result->>'token' from t_result where label = 'a_remint'), 1, length((select result->>'token' from t_result where label = 'a_remint')) - 1)
    || (case when right((select result->>'token' from t_result where label = 'a_remint'), 1) = 'q' then 'z' else 'q' end)
);
select is((select result->>'outcome' from t_result where label = 'adv_forged'), 'invalid_or_unavailable', 'T-ADV-1: forged MAC (last char flipped) is opaque');

insert into t_result (label, result) select 'adv_truncated', public.rpc_get_rsvp_render_data(
    substr((select result->>'token' from t_result where label = 'a_remint'), 1, 10)
);
select is((select result->>'outcome' from t_result where label = 'adv_truncated'), 'invalid_or_unavailable', 'T-ADV-2: truncated token is opaque');

insert into t_result (label, result) select 'adv_oversized', public.rpc_get_rsvp_render_data(
    (select result->>'token' from t_result where label = 'a_remint') || repeat('x', 250)
);
select is((select result->>'outcome' from t_result where label = 'adv_oversized'), 'invalid_or_unavailable', 'T-ADV-3: oversized (>200 char) token is opaque');

insert into t_result (label, result) select 'adv_unknown_id', public.rpc_get_rsvp_render_data(public.rsvp_token_encode(gen_random_uuid()));
select is((select result->>'outcome' from t_result where label = 'adv_unknown_id'), 'invalid_or_unavailable', 'T-ADV-4: a valid-MAC token for an id never minted is opaque (unknown token_id, distinct from a forged MAC)');

insert into t_result (label, result) select 'adv_empty', public.rpc_get_rsvp_render_data('');
select is((select result->>'outcome' from t_result where label = 'adv_empty'), 'invalid_or_unavailable', 'T-ADV-5: empty string is opaque');

insert into t_result (label, result) select 'adv_nodot', public.rpc_get_rsvp_render_data('abcdefgh12345678');
select is((select result->>'outcome' from t_result where label = 'adv_nodot'), 'invalid_or_unavailable', 'T-ADV-6: malformed (no separator) token is opaque');
set local role authenticated;

-- ===================================================================================
-- T-RATE-*: the per-token limit trips at the boundary with the identical opaque shape.
-- Cap is 30/15min (pre-gate F8: each web interaction resolves 2-3 times; unfurlers more).
-- Uses a fresh, otherwise-perfectly-VALID token (dedicated proposal, zero prior attempts)
-- so the 31st call's opaque result is provably due to rate limiting, not token invalidity
-- -- the first 30 calls each individually still resolve normally.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021a01","role":"authenticated"}';
insert into t_result (label, result) select 'p_rate_create', public.rpc_create_proposal(
    'f2100000-0000-4000-8000-0000000000a0'::uuid, 'Rate limit proposal', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-10-02T19:00:00Z","ends_at_utc":"2026-10-02T20:00:00Z","duration_min":60}]'::jsonb
);
insert into t_result (label, result) select 'p_rate_mint', public.rpc_mint_rsvp_token(
    'f2100000-0000-4000-8000-0000000000a1'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p_rate_create'));

reset role;
do $$
declare
    i       int;
    v_token text;
    v_out   jsonb;
begin
    select result->>'token' into v_token from t_result where label = 'p_rate_mint';
    for i in 1 .. 30 loop
        select public.rpc_get_rsvp_render_data(v_token) into v_out;
        if v_out->>'outcome' <> 'ok' then
            raise exception 'T-RATE setup invariant broken: call % unexpectedly returned %', i, v_out;
        end if;
    end loop;
end;
$$;
insert into t_result (label, result) select 'rate_11th', public.rpc_get_rsvp_render_data((select result->>'token' from t_result where label = 'p_rate_mint'));
select is((select result->>'outcome' from t_result where label = 'rate_11th'), 'invalid_or_unavailable', 'T-RATE-1: the 31st attempt against an otherwise-live token trips the 30/15min per-token limit and returns the opaque shape');
select ok(
    (select count(*)::int from public.rsvp_token_attempts
     where token_hash = extensions.digest(convert_to((select result->>'token' from t_result where label = 'p_rate_mint'), 'UTF8'), 'sha256')) >= 31,
    'T-RATE-2: every attempt (including the rate-limited one) was logged unconditionally'
);
set local role authenticated;

-- ===================================================================================
-- T-WEB-ACCEPT-*: accepted-from-web produces exactly two shared_lock blocks + two
-- commitments, by construction (rpc_respond_proposal_web delegates to the unchanged
-- rpc_respond_proposal). Fresh proposal so earlier mints don't interfere.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021a01","role":"authenticated"}';
insert into t_result (label, result) select 'p2_create', public.rpc_create_proposal(
    'f2100000-0000-4000-8000-000000000040'::uuid, 'Web accept proposal', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-24T19:00:00Z","ends_at_utc":"2026-09-24T20:00:00Z","duration_min":60}]'::jsonb
);
insert into t_result (label, result) select 'p2_mint', public.rpc_mint_rsvp_token(
    'f2100000-0000-4000-8000-000000000041'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p2_create'));

reset role;
insert into t_result (label, result) select 'p2_web_accept', public.rpc_respond_proposal_web(
    (select result->>'token' from t_result where label = 'p2_mint'), 'accept', 0);
select is((select result->>'outcome' from t_result where label = 'p2_web_accept'), 'applied', 'T-WEB-ACCEPT-1: web accept applies');
select is((select result->'proposal'->>'status' from t_result where label = 'p2_web_accept'), 'accepted', 'T-WEB-ACCEPT-2: proposal status=accepted');
select is(
    (select count(*)::int from public.time_blocks where source_proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p2_create')),
    2,
    'T-WEB-ACCEPT-3: exactly two shared_lock time_blocks exist (one per member)'
);
select is(
    (select count(*)::int from public.commitments where proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p2_create')),
    2,
    'T-WEB-ACCEPT-4: exactly two commitments exist (one per member)'
);

-- T-WEB-REPLAY: the same token+action replayed returns the byte-identical result.
insert into t_result (label, result) select 'p2_web_accept_replay', public.rpc_respond_proposal_web(
    (select result->>'token' from t_result where label = 'p2_mint'), 'accept', 0);
select is(
    (select result from t_result where label = 'p2_web_accept_replay'),
    (select result from t_result where label = 'p2_web_accept'),
    'T-WEB-REPLAY: replaying the same web accept returns the byte-identical receipt'
);
set local role authenticated;

-- ===================================================================================
-- T-WEB-DECLINE / T-WEB-COUNTER-DENY / T-WEB-MISMATCH: a second, independent proposal.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021a01","role":"authenticated"}';
insert into t_result (label, result) select 'p3_create', public.rpc_create_proposal(
    'f2100000-0000-4000-8000-000000000050'::uuid, 'Web decline proposal', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-25T19:00:00Z","ends_at_utc":"2026-09-25T20:00:00Z","duration_min":60}]'::jsonb
);
insert into t_result (label, result) select 'p3_mint', public.rpc_mint_rsvp_token(
    'f2100000-0000-4000-8000-000000000051'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p3_create'));

reset role;
-- T-WEB-COUNTER-* (pre-gate F3 flip): counter IS a permitted web action -- it rides
-- rpc_respond_proposal unchanged, bumps current_revision (derived-revoking this very
-- token), and mints NO new rsvp_tokens row (SA's actual rule: never mint a capability
-- addressed to the proposer from the web path).
insert into t_result (label, result) select 'p3_web_counter', public.rpc_respond_proposal_web(
    (select result->>'token' from t_result where label = 'p3_mint'), 'counter', null,
    'America/Vancouver', now() + interval '1 day',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-26T19:00:00Z","ends_at_utc":"2026-09-26T20:00:00Z","duration_min":60}]'::jsonb);
select is((select result->>'outcome' from t_result where label = 'p3_web_counter'), 'applied', 'T-WEB-COUNTER-1: web counter applies through the unchanged rpc_respond_proposal');
select is((select (result->'proposal'->>'current_revision')::int from t_result where label = 'p3_web_counter'), 2, 'T-WEB-COUNTER-2: the counter created revision 2');
select is(
    (select count(*)::int from public.rsvp_tokens
     where proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p3_create')
       and revision_at_mint = 2),
    0,
    'T-WEB-COUNTER-3: the web counter minted NO new rsvp_tokens row'
);
-- A DIFFERENT action on the consumed token hits the cross-action guard (contract SA
-- single-effective-use: the counter consumed this token's one response).
select throws_ok(
    format('select public.rpc_respond_proposal_web(%L, %L, 0)',
        (select result->>'token' from t_result where label = 'p3_mint'), 'accept'),
    '22023', null,
    'T-WEB-COUNTER-4: accept after the counter on the same token raises the cross-action guard');

-- T-WEB-MISMATCH / T-WEB-DECLINE moved to a fresh proposal (p3b): p3 is countered now.
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021a01","role":"authenticated"}';
insert into t_result (label, result) select 'p3b_create', public.rpc_create_proposal(
    'f2100000-0000-4000-8000-0000000000b0'::uuid, 'Web decline proposal 2', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-27T18:00:00Z","ends_at_utc":"2026-09-27T19:00:00Z","duration_min":60}]'::jsonb
);
insert into t_result (label, result) select 'p3b_mint', public.rpc_mint_rsvp_token(
    'f2100000-0000-4000-8000-0000000000b1'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p3b_create'));
reset role;
-- T-WEB-MISMATCH: an out-of-range candidate_idx is validated by the UNCHANGED underlying
-- rpc_respond_proposal, not specially bypassed or ignored by the web wrapper.
select throws_ok(
    format($$select public.rpc_respond_proposal_web(%L, 'accept', 99)$$, (select result->>'token' from t_result where label = 'p3b_mint')),
    '22023', null, 'T-WEB-MISMATCH: an out-of-range candidate_idx propagates rpc_respond_proposal''s own validation unchanged'
);

insert into t_result (label, result) select 'p3_web_decline', public.rpc_respond_proposal_web(
    (select result->>'token' from t_result where label = 'p3b_mint'), 'decline');
select is((select result->>'outcome' from t_result where label = 'p3_web_decline'), 'applied', 'T-WEB-DECLINE: web decline applies');
select is((select result->'proposal'->>'status' from t_result where label = 'p3_web_decline'), 'declined', 'T-WEB-DECLINE-2: proposal status=declined');
set local role authenticated;

-- ===================================================================================
-- T-COUNTERED-*: stale-revision-from-web (countered_since_mint) -- render-data reflects
-- it, and respond returns the conflict envelope without mutating.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021a01","role":"authenticated"}';
insert into t_result (label, result) select 'p4_create', public.rpc_create_proposal(
    'f2100000-0000-4000-8000-000000000060'::uuid, 'Countered-since-mint proposal', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-27T19:00:00Z","ends_at_utc":"2026-09-27T20:00:00Z","duration_min":60}]'::jsonb
);
insert into t_result (label, result) select 'p4_mint', public.rpc_mint_rsvp_token(
    'f2100000-0000-4000-8000-000000000061'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p4_create'));

-- B counters (native app path) -- current_revision moves to 2, deadline changes too.
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021b01","role":"authenticated"}';
insert into t_result (label, result) select 'p4_counter', public.rpc_respond_proposal(
    'f2100000-0000-4000-8000-000000000062'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'p4_create'), 1, 'counter', null,
    'America/Vancouver', now() + interval '3 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-28T19:00:00Z","ends_at_utc":"2026-09-28T20:00:00Z","duration_min":60}]'::jsonb
);
select is((select result->>'outcome' from t_result where label = 'p4_counter'), 'applied', 'setup: B counters natively, bumping current_revision to 2');

reset role;
insert into t_result (label, result) select 'p4_render_stale', public.rpc_get_rsvp_render_data((select result->>'token' from t_result where label = 'p4_mint'));
select is((select result->>'state' from t_result where label = 'p4_render_stale'), 'countered_since_mint', 'T-COUNTERED-1: render-data reports countered_since_mint for the pre-counter token');

insert into t_result (label, result) select 'p4_web_respond_stale', public.rpc_respond_proposal_web(
    (select result->>'token' from t_result where label = 'p4_mint'), 'accept', 0);
select is((select result->>'outcome' from t_result where label = 'p4_web_respond_stale'), 'conflict', 'T-COUNTERED-2: responding through the stale token returns the conflict envelope');
select is(
    (select status from public.time_lock_proposals where id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'p4_create')),
    'countered',
    'T-COUNTERED-3: the stale web response did not mutate the proposal'
);
set local role authenticated;

-- ===================================================================================
-- T-CANCELLED-*: minted-then-cancelled.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021a01","role":"authenticated"}';
insert into t_result (label, result) select 'p5_create', public.rpc_create_proposal(
    'f2100000-0000-4000-8000-000000000070'::uuid, 'Cancelled proposal', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-29T19:00:00Z","ends_at_utc":"2026-09-29T20:00:00Z","duration_min":60}]'::jsonb
);
insert into t_result (label, result) select 'p5_mint', public.rpc_mint_rsvp_token(
    'f2100000-0000-4000-8000-000000000071'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p5_create'));
insert into t_result (label, result) select 'p5_cancel', public.rpc_cancel_proposal(
    'f2100000-0000-4000-8000-000000000072'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p5_create'));
select is((select result->>'outcome' from t_result where label = 'p5_cancel'), 'applied', 'setup: A cancels natively');

reset role;
insert into t_result (label, result) select 'p5_render_cancelled', public.rpc_get_rsvp_render_data((select result->>'token' from t_result where label = 'p5_mint'));
select is((select result->>'state' from t_result where label = 'p5_render_cancelled'), 'cancelled', 'T-CANCELLED-1: render-data reports cancelled');

insert into t_result (label, result) select 'p5_web_respond_cancelled', public.rpc_respond_proposal_web(
    (select result->>'token' from t_result where label = 'p5_mint'), 'accept', 0);
select is((select result->>'outcome' from t_result where label = 'p5_web_respond_cancelled'), 'conflict', 'T-CANCELLED-2: responding through a cancelled-proposal token returns the conflict envelope');
set local role authenticated;

-- ===================================================================================
-- T-LEFT-*: recipient leaves the pair -> generation mismatch -> invalid.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021901","role":"authenticated"}';
insert into t_result (label, result) select 'p6_create', public.rpc_create_proposal(
    'f2100000-0000-4000-8000-000000000080'::uuid, 'Leave-revokes proposal', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-30T19:00:00Z","ends_at_utc":"2026-09-30T20:00:00Z","duration_min":60}]'::jsonb
);
insert into t_result (label, result) select 'p6_mint', public.rpc_mint_rsvp_token(
    'f2100000-0000-4000-8000-000000000081'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p6_create'));

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021902","role":"authenticated"}';
insert into t_result (label, result) select 'h_leave', public.rpc_leave_pair('f2100000-0000-4000-8000-000000000082'::uuid);
select is((select result->>'outcome' from t_result where label = 'h_leave'), 'applied', 'setup: H (the recipient) leaves the pair');

reset role;
insert into t_result (label, result) select 'p6_render_after_leave', public.rpc_get_rsvp_render_data((select result->>'token' from t_result where label = 'p6_mint'));
select is((select result->>'outcome' from t_result where label = 'p6_render_after_leave'), 'invalid_or_unavailable', 'T-LEFT-1: a token whose recipient has left the pair (generation rotated) is opaque');
set local role authenticated;

-- ===================================================================================
-- T-CROSS-ACTION: the underlying cross-action guard, exercised directly (decoupled from
-- rpc_respond_proposal_web's own live-state gate, which -- for accept/decline, both
-- terminal -- would otherwise short-circuit a second call as "conflict" before this guard
-- is ever reached; see this seat's report). Proves rsvp_web_operation_id's token-id-only
-- derivation makes the SAME operation_id collide across a differing action.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021a01","role":"authenticated"}';
insert into t_result (label, result) select 'p7_create', public.rpc_create_proposal(
    'f2100000-0000-4000-8000-000000000090'::uuid, 'Cross-action proposal', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-10-01T19:00:00Z","ends_at_utc":"2026-10-01T20:00:00Z","duration_min":60}]'::jsonb
);
insert into t_result (label, result) select 'p7_mint', public.rpc_mint_rsvp_token(
    'f2100000-0000-4000-8000-000000000091'::uuid, (select (result->'proposal'->>'id')::uuid from t_result where label = 'p7_create'));

-- rsvp_web_operation_id is revoke-all (not even authenticated), so it is computed here
-- under 'reset role' (superuser bypass) and the resulting PLAIN uuid value is reused
-- below under 'authenticated' -- never re-invoking the restricted function as that role.
reset role;
insert into t_result (label, result)
select 'p7_op_id', to_jsonb(public.rsvp_web_operation_id((select result->>'token_id' from t_result where label = 'p7_mint')::uuid));

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000021b01","role":"authenticated"}';
insert into t_result (label, result) select 'p7_direct_accept', public.rpc_respond_proposal(
    (select (result #>> '{}')::uuid from t_result where label = 'p7_op_id'),
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'p7_create'), 1, 'accept', 0
);
select is((select result->>'outcome' from t_result where label = 'p7_direct_accept'), 'applied', 'setup: direct accept using the web-derived operation_id succeeds');
select throws_ok(
    format(
        $$select public.rpc_respond_proposal(%L::uuid, %L, 1, 'decline')$$,
        (select (result #>> '{}') from t_result where label = 'p7_op_id'),
        (select result->'proposal'->>'id' from t_result where label = 'p7_create')
    ),
    '22023', null, 'T-CROSS-ACTION: the SAME token-derived operation_id reused for a different action (decline) hits the cross-action guard'
);

-- ===================================================================================
-- T-GREP-*: grep-proofs -- no plaintext token in rsvp_tokens, mutation_receipts, or any
-- realtime broadcast payload. Run as superuser (reset role): rsvp_tokens has zero grants
-- even to authenticated, and the mutation_receipts/realtime.messages scans below must see
-- every row, not just the current caller's own RLS-visible slice.
-- ===================================================================================
reset role;
select is(
    (select pg_typeof(token_hash)::text from public.rsvp_tokens limit 1),
    'bytea',
    'T-GREP-1: rsvp_tokens has no plaintext token column -- token_hash is bytea'
);
select ok(
    not exists (
        select 1 from public.mutation_receipts
        where operation_id = 'f2100000-0000-4000-8000-000000000020'::uuid
          and result ? 'token'
    ),
    'T-GREP-2: the stored mint receipt never carries a ''token'' key (the plaintext is re-derived on replay, never persisted)'
);
select ok(
    not exists (
        select 1 from public.mutation_receipts
        where result::text like '%' || (select result->>'token' from t_result where label = 'a_remint') || '%'
    ),
    'T-GREP-3: no mutation_receipts row contains the live plaintext token anywhere in its stored jsonb'
);
select ok(
    not exists (
        select 1 from realtime.messages
        where payload::text like '%' || (select result->>'token' from t_result where label = 'a_remint') || '%'
    ),
    'T-GREP-4: no realtime.messages payload contains the live plaintext token'
);

-- Pre-gate F5: the five internal helpers must not be executable by anon/authenticated.
select ok(not has_function_privilege('anon', 'public.rsvp_token_mac(uuid)', 'execute'), 'F5-1: anon cannot execute rsvp_token_mac');
select ok(not has_function_privilege('authenticated', 'public.rsvp_token_mac(uuid)', 'execute'), 'F5-2: authenticated cannot execute rsvp_token_mac');
select ok(not has_function_privilege('anon', 'public.rsvp_token_encode(uuid)', 'execute'), 'F5-3: anon cannot execute rsvp_token_encode');
select ok(not has_function_privilege('authenticated', 'public.rsvp_verify_token_mac(text)', 'execute'), 'F5-4: authenticated cannot execute rsvp_verify_token_mac');
select ok(not has_function_privilege('anon', 'public.rsvp_base32_decode(text, int)', 'execute'), 'F5-5: anon cannot execute rsvp_base32_decode');

select * from finish();
rollback;
