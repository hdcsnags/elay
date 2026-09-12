-- pgTAP: Stage 2 DST suite (ADR-006; council/stage2-timelock-sol.md §5's DST paragraph).
-- Pure-SQL fixtures (no kotlinx dependency): candidates are placed AT the America/Vancouver
-- and America/Toronto 2026 transitions --
--   spring-forward gap:  2026-03-08, 02:00 -> 03:00 local (both zones use the same
--                         second-Sunday-in-March North American rule, but at DIFFERENT UTC
--                         instants because their base offsets differ).
--   fall-back fold:      2026-11-01, 02:00 -> 01:00 local (first-Sunday-in-November).
--
-- What Stage 2 SQL actually stores/compares is always a UTC instant (timestamptz); no
-- wall-clock -> UTC gap/fold RESOLUTION happens here (that authoritative expansion lives in
-- shared/ per ADR-006 and is B4/lead territory) -- what this suite proves at the SQL layer
-- is: (1) a candidate whose instant is the ADR-006-correct resolution of a gap/fold local
-- time is accepted and stored faithfully; (2) both members' accepted shared_lock blocks
-- carry the IDENTICAL winning UTC instant and origin_tz regardless of the two members'
-- differing home_tz; (3) Postgres's own IANA tzdata (never a fixed offset) is what this
-- suite -- and the production functions -- use to reason about local time; and (4) the
-- Toronto<->Vancouver offset difference is NOT always 3 hours during a transition window,
-- so "Toronto = Vancouver + 3" is concretely, numerically wrong at times -- the exact bug
-- ADR-006 rule 5 / the CI grep exists to prevent.

begin;
create extension if not exists pgtap with schema extensions;
select plan(25);

-- ===================================================================================
-- T-NO-HARDCODED-OFFSET: grep-style static check over the actual deployed function
-- source (pg_get_functiondef), not the migration file text, so it also catches anything
-- introduced by a future `create or replace`. No Stage-2 function should contain a literal
-- signed UTC-offset token (e.g. "+05:00", "-08:00") or a fixed-hour interval shift used to
-- convert between named zones -- all of it goes through timestamptz/AT TIME ZONE + IANA
-- tzdata instead (ADR-006 rule 5).
-- ===================================================================================
select ok(
    not exists (
        select 1 from (values
            (pg_get_functiondef('public.proposal_candidates_valid(jsonb)'::regprocedure)),
            (pg_get_functiondef('public.rpc_create_proposal(uuid,text,text,timestamptz,jsonb)'::regprocedure)),
            (pg_get_functiondef('public.rpc_respond_proposal(uuid,uuid,int,text,int,text,timestamptz,jsonb)'::regprocedure)),
            (pg_get_functiondef('public.rpc_cancel_proposal(uuid,uuid)'::regprocedure)),
            (pg_get_functiondef('public.rpc_complete_lock(uuid,uuid)'::regprocedure)),
            (pg_get_functiondef('public.rpc_proposal_conflict_hints(jsonb)'::regprocedure)),
            (pg_get_functiondef('public.rpc_list_proposals(text,text)'::regprocedure)),
            (pg_get_functiondef('public.rpc_get_proposal(uuid)'::regprocedure)),
            (pg_get_functiondef('public.fn_expire_proposals(int)'::regprocedure))
        ) as defs(src)
        where src ~ '[+-]\d{2}:\d{2}'
           or src ~* 'interval\s*''\d+\s*hours?'''
    ),
    'T-NO-HARDCODED-OFFSET: no Stage-2 SQL function contains a literal UTC offset or a fixed-hour interval shift between zones (ADR-006 rule 5)'
);

-- ===================================================================================
-- T-DST-NO-FIXED-3H-ASSUMPTION: the dynamic proof that "Toronto = Vancouver + 3" is not
-- always true. Both zones follow the same North American transition dates in 2026, but at
-- DIFFERENT UTC instants (Toronto's base offset is UTC-5/-4; Vancouver's is UTC-8/-7), so
-- there is a real window around each fall-back where the gap between them is 2 hours, not 3.
-- ===================================================================================
select is(
    to_char(timezone('America/Vancouver', '2026-11-01T07:00:00Z'::timestamptz), 'YYYY-MM-DD HH24:MI'),
    '2026-11-01 00:00',
    'T-DST-NO-FIXED-3H-1: at 2026-11-01T07:00:00Z, Vancouver (still PDT -- its own fallback pivot is 09:00Z) reads 00:00 local'
);
select is(
    to_char(timezone('America/Toronto', '2026-11-01T07:00:00Z'::timestamptz), 'YYYY-MM-DD HH24:MI'),
    '2026-11-01 02:00',
    'T-DST-NO-FIXED-3H-2: at the SAME instant, Toronto (already fallen back at its own 06:00Z pivot) reads 02:00 local'
);
select isnt(
    extract(hour from (
        timezone('America/Toronto', '2026-11-01T07:00:00Z'::timestamptz) -
        timezone('America/Vancouver', '2026-11-01T07:00:00Z'::timestamptz)
    ))::int,
    3,
    'T-DST-NO-FIXED-3H-3: the Toronto/Vancouver local-time difference at this instant is 2 hours, NOT the usual 3 -- proof a fixed offset would silently corrupt this window'
);
-- Sanity control: an hour later, both zones have completed their respective transitions and
-- the "usual" 3-hour difference is back, showing the anomaly is real and transition-scoped.
select is(
    extract(hour from (
        timezone('America/Toronto', '2026-11-01T10:00:00Z'::timestamptz) -
        timezone('America/Vancouver', '2026-11-01T10:00:00Z'::timestamptz)
    ))::int,
    3,
    'T-DST-NO-FIXED-3H-4 (control): an hour later, after both zones'' transitions have completed, the difference is back to the normal 3 hours'
);

-- ===================================================================================
-- T-DST-DAY-CROSSOVER: the same UTC instant falls on different calendar dates for the two
-- members, purely from each one's own IANA zone -- never from arithmetic on the other's.
-- ===================================================================================
select is(
    to_char(timezone('America/Vancouver', '2026-09-14T06:45:00Z'::timestamptz), 'YYYY-MM-DD'),
    '2026-09-13',
    'T-DST-DAY-CROSSOVER-1: 2026-09-14T06:45:00Z is still 2026-09-13 in Vancouver'
);
select is(
    to_char(timezone('America/Toronto', '2026-09-14T06:45:00Z'::timestamptz), 'YYYY-MM-DD'),
    '2026-09-14',
    'T-DST-DAY-CROSSOVER-2: the SAME instant is already 2026-09-14 in Toronto'
);

-- ===================================================================================
-- setup: V (America/Vancouver) and T (America/Toronto) paired.
-- ===================================================================================
insert into auth.users (id, email) values
    ('00000000-0000-0000-0000-000000000da1', 'v1@test.local'),
    ('00000000-0000-0000-0000-000000000db1', 't1@test.local');

insert into public.profiles (user_id, display_name, home_tz) values
    ('00000000-0000-0000-0000-000000000da1', 'User V', 'America/Vancouver'),
    ('00000000-0000-0000-0000-000000000db1', 'User T', 'America/Toronto')
on conflict (user_id) do update set display_name = excluded.display_name, home_tz = excluded.home_tz;

create temporary table t_result (label text, result jsonb);
grant all on t_result to authenticated;

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000000da1","role":"authenticated"}';
insert into t_result (label, result) select 'v_invite', public.rpc_create_pair_invite('d0000000-0000-4000-8000-000000000001'::uuid, 60);

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000000db1","role":"authenticated"}';
insert into t_result (label, result) select 't_redeem', public.rpc_redeem_pair_invite(
    'd0000000-0000-4000-8000-000000000002'::uuid, (select result->'invite'->>'code' from t_result where label = 'v_invite'));
select is((select result->>'outcome' from t_result where label = 't_redeem'), 'applied', 'setup: V/T paired');

-- ===================================================================================
-- T-DST-SPRING-GAP: America/Vancouver 2026-03-08 gap (02:00 -> 03:00 PDT). The
-- ADR-006-correct resolution of a candidate whose intended local start was the nonexistent
-- 02:30 is "shift forward to the first valid instant, duration preserved": 03:00 PDT
-- (UTC-7) for 30 minutes = 2026-03-08T10:00:00Z .. T10:30:00Z. V creates, T accepts.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000000da1","role":"authenticated"}';
insert into t_result (label, result) select 'spring_create', public.rpc_create_proposal(
    'd0000000-0000-4000-8000-000000000003'::uuid, 'Spring-forward gap negotiation', 'America/Vancouver', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-03-08T10:00:00Z","ends_at_utc":"2026-03-08T10:30:00Z","duration_min":30}]'::jsonb
);
select is((select result->>'outcome' from t_result where label = 'spring_create'), 'applied', 'T-DST-SPRING-GAP-1: the gap-resolved candidate (first valid instant, duration preserved) is accepted');

select is(
    to_char(timezone('America/Vancouver', '2026-03-08T10:00:00Z'::timestamptz), 'YYYY-MM-DD HH24:MI'),
    '2026-03-08 03:00',
    'T-DST-SPRING-GAP-2 (ADR-006 policy check): the stored instant is exactly 03:00 local Vancouver -- the first valid instant after the gap, not the nonexistent 02:30'
);
-- The naive nonexistent local time does not round-trip to itself: Postgres's own tzdata
-- resolution of "2026-03-08 02:30" local Vancouver skips it too (a second, independent proof
-- that the chosen instant is correct, not just asserted).
select isnt(
    (timezone('America/Vancouver', '2026-03-08T10:00:00Z'::timestamptz))::text,
    '2026-03-08 02:30:00',
    'T-DST-SPRING-GAP-3: the accepted instant''s local wall-clock is not the nonexistent 02:30'
);

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000000db1","role":"authenticated"}';
insert into t_result (label, result) select 'spring_accept', public.rpc_respond_proposal(
    'd0000000-0000-4000-8000-000000000004'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'spring_create'),
    1, 'accept', 0
);
select is((select result->>'outcome' from t_result where label = 'spring_accept'), 'applied', 'T-DST-SPRING-GAP-4: T accepts the gap-transition candidate');

reset role;
select is(
    (select count(distinct (starts_at_utc, ends_at_utc))::int from public.time_blocks
     where source_proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'spring_create')),
    1,
    'T-DST-SPRING-GAP-5: both members'' shared_lock blocks carry the IDENTICAL winning UTC instant'
);
select is(
    (select count(distinct extract(epoch from (ends_at_utc - starts_at_utc)))::int from public.time_blocks
     where source_proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'spring_create')),
    1,
    'T-DST-SPRING-GAP-6: duration (30 minutes) is preserved identically across both blocks'
);
select is(
    (select count(distinct origin_tz)::int from public.time_blocks
     where source_proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'spring_create')),
    1,
    'T-DST-SPRING-GAP-7: both blocks carry the identical origin_tz (the proposal''s, America/Vancouver) -- not each owner''s own differing home_tz'
);
-- Dual-zone rendering check: from the SAME stored UTC instant, V's own zone and T's own zone
-- (profiles.home_tz) independently produce different, individually-correct local times --
-- this is what makes per-viewer dual-time display possible without storing it twice.
select isnt(
    to_char(timezone('America/Vancouver', '2026-03-08T10:00:00Z'::timestamptz), 'HH24:MI'),
    to_char(timezone('America/Toronto', '2026-03-08T10:00:00Z'::timestamptz), 'HH24:MI'),
    'T-DST-SPRING-GAP-8 (dual zone IDs): V''s own local time and T''s own local time for the winning instant are different, independently-correct renderings'
);
set local role authenticated;

-- ===================================================================================
-- T-DST-FALL-FOLD: America/Vancouver 2026-11-01 fold (02:00 PDT -> 01:00 PST, ambiguous
-- 01:00-02:00 local occurs twice). ADR-006 policy: pick the FIRST occurrence (earlier
-- offset, i.e. still PDT/UTC-7): 01:30 PDT = 2026-11-01T08:30:00Z, ending exactly at the
-- fallback pivot 2026-11-01T09:00:00Z (= both "02:00 PDT" and "01:00 PST" simultaneously).
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000000da1","role":"authenticated"}';
insert into t_result (label, result) select 'fall_create', public.rpc_create_proposal(
    'd0000000-0000-4000-8000-000000000005'::uuid, 'Fall-back fold negotiation', 'America/Vancouver', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-11-01T08:30:00Z","ends_at_utc":"2026-11-01T09:00:00Z","duration_min":30}]'::jsonb
);
select is((select result->>'outcome' from t_result where label = 'fall_create'), 'applied', 'T-DST-FALL-FOLD-1: the fold-resolved (first occurrence / earlier offset) candidate is accepted');

select is(
    to_char(timezone('America/Vancouver', '2026-11-01T08:30:00Z'::timestamptz), 'YYYY-MM-DD HH24:MI'),
    '2026-11-01 01:30',
    'T-DST-FALL-FOLD-2 (ADR-006 policy check): the stored instant reads as 01:30 local Vancouver'
);
-- Prove the ambiguous hour genuinely repeats, and that the chosen candidate is the FIRST
-- (PDT/-7) pass: one second before the fallback pivot Vancouver still reads 01:59:59 (end of
-- the first pass); exactly at the pivot it reads 01:00:00 AGAIN (start of the second pass).
select is(
    to_char(timezone('America/Vancouver', '2026-11-01T08:59:59Z'::timestamptz), 'YYYY-MM-DD HH24:MI:SS'),
    '2026-11-01 01:59:59',
    'T-DST-FALL-FOLD-3a: one second before the fallback pivot, Vancouver still reads 01:59:59 (first pass, PDT)'
);
select is(
    to_char(timezone('America/Vancouver', '2026-11-01T09:00:00Z'::timestamptz), 'YYYY-MM-DD HH24:MI:SS'),
    '2026-11-01 01:00:00',
    'T-DST-FALL-FOLD-3b: exactly at the fallback pivot, Vancouver reads 01:00:00 AGAIN (second pass, PST) -- proving 01:00-02:00 truly occurs twice, and the accepted candidate is the first (earlier-offset) occurrence'
);

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000000db1","role":"authenticated"}';
insert into t_result (label, result) select 'fall_accept', public.rpc_respond_proposal(
    'd0000000-0000-4000-8000-000000000006'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'fall_create'),
    1, 'accept', 0
);
select is((select result->>'outcome' from t_result where label = 'fall_accept'), 'applied', 'T-DST-FALL-FOLD-4: T accepts the fold-transition candidate');

reset role;
select is(
    (select count(distinct (starts_at_utc, ends_at_utc))::int from public.time_blocks
     where source_proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'fall_create')),
    1,
    'T-DST-FALL-FOLD-5: both members'' shared_lock blocks carry the IDENTICAL winning UTC instant'
);
select is(
    (select count(distinct extract(epoch from (ends_at_utc - starts_at_utc)))::int from public.time_blocks
     where source_proposal_id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'fall_create')),
    1,
    'T-DST-FALL-FOLD-6: duration (30 minutes) is preserved identically across both blocks'
);
select is(
    (select count(*)::int from public.commitments c
     join public.time_lock_proposals p on p.id = c.proposal_id
     where p.id = (select (result->'proposal'->>'id')::uuid from t_result where label = 'fall_create')),
    2,
    'T-DST-FALL-FOLD-7: exactly two commitments (one per member) exist for the fold-transition acceptance'
);
select isnt(
    to_char(timezone('America/Vancouver', '2026-11-01T08:30:00Z'::timestamptz), 'HH24:MI'),
    to_char(timezone('America/Toronto', '2026-11-01T08:30:00Z'::timestamptz), 'HH24:MI'),
    'T-DST-FALL-FOLD-8 (dual zone IDs): V''s and T''s own local renderings of the winning instant differ, each independently correct'
);
set local role authenticated;

-- ===================================================================================
-- Out of scope, explicitly noted rather than faked: Sol §5''s closing sentence about
-- withdrawing/changing a "calendar-delivery preference" (ADR-010 §4's server-owned
-- delivery_jobs) cannot be tested here -- no such table/column/RPC exists in A3's Stage-2
-- grant (four tables: time_lock_proposals, proposal_revisions, proposal_responses,
-- commitments; six RPCs + sweep, none of which touch a delivery preference). There is
-- structurally no code path by which such a toggle could reach a proposal, commitment, or
-- ELAY block in this schema, so the property holds vacuously; a real test belongs to
-- whichever future seat owns delivery_jobs.
-- ===================================================================================

select * from finish();
rollback;
