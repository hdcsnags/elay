-- pgTAP: migration 20260912160000 — the pair_broadcast_select policy must pass Realtime's
-- JOIN authorization probe. Proven live 2026-09-12: the Realtime server tests read access
-- by inserting a probe row with `private = false` and `event = null` and selecting it back
-- as the joining user; Stage 1's `private is true` conjunct made every private-channel
-- join fail Unauthorized (no pair broadcast was ever delivered). This suite pins the
-- probe-row shape so the conjunct cannot come back, and re-pins the topic-scoping that
-- actually carries the security.
begin;
create extension if not exists pgtap with schema extensions;
select plan(4);

insert into auth.users (id, email) values
    ('00000000-0000-0000-0000-0000000018a1', 'rt-a@test.local'),
    ('00000000-0000-0000-0000-0000000018b1', 'rt-b@test.local');
insert into public.profiles (user_id, display_name, home_tz) values
    ('00000000-0000-0000-0000-0000000018a1', 'RT A', 'America/Toronto'),
    ('00000000-0000-0000-0000-0000000018b1', 'RT B', 'America/Vancouver')
on conflict (user_id) do update set display_name = excluded.display_name, home_tz = excluded.home_tz;

create temporary table t_result (label text, result jsonb);
grant all on t_result to authenticated, anon;

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000018a1","role":"authenticated"}';
insert into t_result (label, result) select 'a_invite', public.rpc_create_pair_invite('d1800000-0000-4000-8000-000000000001'::uuid, 60);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000018b1","role":"authenticated"}';
insert into t_result (label, result) select 'b_redeem', public.rpc_redeem_pair_invite(
    'd1800000-0000-4000-8000-000000000002'::uuid, (select result->'invite'->>'code' from t_result where label = 'a_invite'));
select is((select result->>'outcome' from t_result where label = 'b_redeem'), 'applied', 'setup: A/B paired');

-- Realtime's join probe row: private = false, event = null (the shape the server inserts
-- when it authorizes a join). Inserted as postgres (the server inserts it superuser-side).
reset role;
insert into realtime.messages (topic, extension, private, event, payload)
select 'pair:' || p.id::text || ':' || p.channel_generation::text, 'broadcast', false, null, null
from public.pairs p
where p.id = (select (result->'pair'->>'pair_id')::uuid from t_result where label = 'b_redeem');

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000018a1","role":"authenticated"}';
select set_config('realtime.topic', (select public.rpc_get_pair()->>'channel_topic'), true);
select ok(
    (select count(*) from realtime.messages
     where private is false and event is null) >= 1,
    'JOIN-PROBE-1: a member can read Realtime''s join probe row (private=false, event=null) under their own topic'
);

-- The security still holds: a fabricated topic matches nothing.
set local "realtime.topic" to 'pair:deadbeef-0000-4000-8000-000000000000:deadbeef-0000-4000-8000-000000000000';
select is(
    (select count(*)::int from realtime.messages), 0,
    'JOIN-PROBE-2: a fabricated topic is still denied (topic-function scoping intact)'
);

-- An unpaired caller reads nothing even with the real topic in the GUC.
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-000000000099","role":"authenticated"}';
select set_config('realtime.topic', (
    select 'pair:' || p.id::text || ':' || p.channel_generation::text
    from public.pairs p
    where p.id = (select (result->'pair'->>'pair_id')::uuid from t_result where label = 'b_redeem')
), true);
select is(
    (select count(*)::int from realtime.messages), 0,
    'JOIN-PROBE-3: a non-member is denied even when naming the real topic'
);

select * from finish();
rollback;
