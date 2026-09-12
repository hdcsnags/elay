-- pgTAP: Stage 1 peer-read projections (exact-key jsonb per visibility tier) and the
-- realtime.messages private-broadcast SELECT policy (council/stage1-pairing-contract-sol.md
-- §1, §3; contracts/stage1-pairing.md).
--
-- Sol §6 items covered here (the rest live in 0012, per the seat's own split -- see that
-- file's header comment):
--   exact-key projection assertions            -> T-KEYS-*
--   private-row absence                        -> T-PRIVATE-ABSENT
--   guessed pair/topic denial                  -> T-RT-GUESSED-TOPIC, T-RT-OLD-TOPIC
--   old-topic denied after rotation             -> T-RT-OLD-TOPIC
--   former-member API/projection denial         -> T-RT-FORMER-MEMBER, T-FORMER-PROJECTION

begin;
create extension if not exists pgtap with schema extensions;
select plan(31);

select has_function('public', 'rpc_list_pair_shared_goals', array[]::name[], 'rpc_list_pair_shared_goals exists');
select has_function('public', 'rpc_list_pair_shared_tasks', array[]::name[], 'rpc_list_pair_shared_tasks exists');
select has_function('public', 'rpc_list_pair_shared_time_blocks', array[]::name[], 'rpc_list_pair_shared_time_blocks exists');
select hasnt_function('public', 'rpc_list_pair_shared_captures', 'T-NO-CAPTURES-PROJECTION: captures never get a projection RPC (contract §1)');

-- ===================================================================================
-- setup: users P (owner side sharing objects) and Q (peer/partner) paired; R unpaired.
-- ===================================================================================
insert into auth.users (id, email) values
    ('00000000-0000-0000-0000-00000000aaa1', 'p@test.local'),
    ('00000000-0000-0000-0000-00000000bbb1', 'q@test.local'),
    ('00000000-0000-0000-0000-00000000ccc1', 'r@test.local');

insert into public.profiles (user_id, display_name, home_tz) values
    ('00000000-0000-0000-0000-00000000aaa1', 'User P', 'America/Toronto'),
    ('00000000-0000-0000-0000-00000000bbb1', 'User Q', 'America/Toronto'),
    ('00000000-0000-0000-0000-00000000ccc1', 'User R', 'America/Toronto')
on conflict (user_id) do update set display_name = excluded.display_name, home_tz = excluded.home_tz;

create temporary table t_result (label text, result jsonb);
grant all on t_result to authenticated;

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000aaa1","role":"authenticated"}';

insert into t_result (label, result)
select 'p_create', public.rpc_create_pair_invite('50000000-0000-4000-8000-000000000001'::uuid, 60);

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000bbb1","role":"authenticated"}';

insert into t_result (label, result)
select 'q_redeem', public.rpc_redeem_pair_invite(
    '50000000-0000-4000-8000-000000000002'::uuid,
    (select result->'invite'->>'code' from t_result where label = 'p_create')
);
select is((select result->>'outcome' from t_result where label = 'q_redeem'), 'applied', 'setup: Q redeemed P''s invite');

-- P owns the shared rows; seed one goal (+ a milestone under the full one), one task, one
-- time_block per visibility tier that is reachable (busy_only is unreachable for goals).
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000aaa1","role":"authenticated"}';
reset role;

-- The 'full' row of each entity populates every nullable column (notes, recurrence_rule,
-- goal_id/milestone_id, task_id, ...) so jsonb_strip_nulls (contract §1) has nothing to
-- strip and the exact-key assertions below see the maximal key set for that tier.
insert into public.goals (id, owner_id, title, notes, target_date, visibility, pair_id) values
    ('60000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-00000000aaa1', 'Private goal', null, null, 'private', null),
    ('60000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-00000000aaa1', 'Title-only goal', null, '2026-12-01', 'title_only',
        (select result->'pair'->>'pair_id' from t_result where label = 'q_redeem')::uuid),
    ('60000000-0000-4000-8000-000000000003', '00000000-0000-0000-0000-00000000aaa1', 'Full goal', 'some notes', '2026-12-15', 'full',
        (select result->'pair'->>'pair_id' from t_result where label = 'q_redeem')::uuid);

insert into public.milestones (id, goal_id, title, sort_order) values
    ('61000000-0000-4000-8000-000000000001', '60000000-0000-4000-8000-000000000003', 'Milestone under full goal', 0);

insert into public.tasks (
    id, owner_id, title, notes, goal_id, milestone_id, effort, estimate_min, recurrence_rule,
    due_start_utc, due_end_utc, visibility, pair_id
) values
    ('62000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-00000000aaa1', 'Busy task', null, null, null, null, null, null,
        '2026-09-12T19:00:00Z', '2026-09-12T20:00:00Z', 'busy_only',
        (select result->'pair'->>'pair_id' from t_result where label = 'q_redeem')::uuid),
    ('62000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-00000000aaa1', 'Title-only task', null, null, null, null, null, null,
        '2026-09-13T19:00:00Z', '2026-09-13T20:00:00Z', 'title_only',
        (select result->'pair'->>'pair_id' from t_result where label = 'q_redeem')::uuid),
    ('62000000-0000-4000-8000-000000000003', '00000000-0000-0000-0000-00000000aaa1', 'Full task', 'task notes',
        '60000000-0000-4000-8000-000000000003', '61000000-0000-4000-8000-000000000001', 3, 45, 'FREQ=WEEKLY',
        '2026-09-14T19:00:00Z', '2026-09-14T20:00:00Z', 'full',
        (select result->'pair'->>'pair_id' from t_result where label = 'q_redeem')::uuid);

insert into public.time_blocks (
    id, owner_id, title, task_id, recurrence_rule, starts_at_utc, ends_at_utc, origin_tz, visibility, pair_id
) values
    ('63000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-00000000aaa1', 'Busy block', null, null,
        '2026-09-12T19:00:00Z', '2026-09-12T20:00:00Z', 'America/Toronto', 'busy_only',
        (select result->'pair'->>'pair_id' from t_result where label = 'q_redeem')::uuid),
    ('63000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-00000000aaa1', 'Title-only block', null, null,
        '2026-09-13T19:00:00Z', '2026-09-13T20:00:00Z', 'America/Toronto', 'title_only',
        (select result->'pair'->>'pair_id' from t_result where label = 'q_redeem')::uuid),
    ('63000000-0000-4000-8000-000000000003', '00000000-0000-0000-0000-00000000aaa1', 'Full block',
        '62000000-0000-4000-8000-000000000003', 'FREQ=DAILY',
        '2026-09-14T19:00:00Z', '2026-09-14T20:00:00Z', 'America/Toronto', 'full',
        (select result->'pair'->>'pair_id' from t_result where label = 'q_redeem')::uuid);

-- CHECK-constraint reject cases (unreachable tiers / incomplete busy_only ranges).
select throws_ok(
    $$insert into public.goals (owner_id, title, visibility, pair_id)
      values ('00000000-0000-0000-0000-00000000aaa1', 'bad', 'busy_only',
              (select result->'pair'->>'pair_id' from t_result where label = 'q_redeem')::uuid)$$,
    '23514', null,
    'T-NO-BUSY-ONLY-GOAL: a goal cannot be busy_only (goals_no_busy_only_check)'
);
select throws_ok(
    $$insert into public.tasks (owner_id, title, visibility, pair_id, due_start_utc)
      values ('00000000-0000-0000-0000-00000000aaa1', 'bad', 'busy_only',
              (select result->'pair'->>'pair_id' from t_result where label = 'q_redeem')::uuid,
              '2026-09-12T19:00:00Z')$$,
    '23514', null,
    'T-BUSY-ONLY-NEEDS-DUE-RANGE: a busy_only task requires both due_start_utc and due_end_utc'
);

-- ===================================================================================
-- act as Q (the peer): exact-key assertions.
-- ===================================================================================
set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000bbb1","role":"authenticated"}';

insert into t_result (label, result)
select 'q_goals', public.rpc_list_pair_shared_goals();
insert into t_result (label, result)
select 'q_tasks', public.rpc_list_pair_shared_tasks();
insert into t_result (label, result)
select 'q_blocks', public.rpc_list_pair_shared_time_blocks();

select is(jsonb_array_length((select result from t_result where label = 'q_goals')), 2, 'Q sees exactly 2 shared goals (private is absent)');
select is(jsonb_array_length((select result from t_result where label = 'q_tasks')), 3, 'Q sees exactly 3 shared tasks');
select is(jsonb_array_length((select result from t_result where label = 'q_blocks')), 3, 'Q sees exactly 3 shared time blocks');

-- T-PRIVATE-ABSENT
select ok(
    not exists (
        select 1 from jsonb_array_elements((select result from t_result where label = 'q_goals')) g
        where g->>'id' = '60000000-0000-4000-8000-000000000001'
    ),
    'T-PRIVATE-ABSENT: the private goal never appears in the peer projection'
);

-- T-KEYS-GOAL-TITLE-ONLY
select is(
    (select array_agg(k order by k) from jsonb_object_keys((
        select g from jsonb_array_elements((select result from t_result where label = 'q_goals')) g
        where g->>'id' = '60000000-0000-4000-8000-000000000002'
    )) k),
    array['id', 'target_date', 'title']::text[],
    'T-KEYS-GOAL-TITLE-ONLY: exact key set {id, title, target_date}'
);

-- T-KEYS-GOAL-FULL (+ milestones nested)
select is(
    (select array_agg(k order by k) from jsonb_object_keys((
        select g from jsonb_array_elements((select result from t_result where label = 'q_goals')) g
        where g->>'id' = '60000000-0000-4000-8000-000000000003'
    )) k),
    array['created_at', 'id', 'milestones', 'notes', 'owner_id', 'pair_id', 'status', 'target_date', 'title', 'updated_at', 'version', 'visibility']::text[],
    'T-KEYS-GOAL-FULL: exact key set is the full goal_to_jsonb shape + milestones'
);
select is(
    jsonb_array_length((
        select g->'milestones' from jsonb_array_elements((select result from t_result where label = 'q_goals')) g
        where g->>'id' = '60000000-0000-4000-8000-000000000003'
    )),
    1,
    'T-MILESTONES-UNDER-FULL: the full goal carries its one milestone'
);
select ok(
    not ((
        select g ? 'milestones' from jsonb_array_elements((select result from t_result where label = 'q_goals')) g
        where g->>'id' = '60000000-0000-4000-8000-000000000002'
    )),
    'T-MILESTONES-ONLY-UNDER-FULL: a title_only goal carries no milestones key at all'
);

-- T-KEYS-TASK-BUSY-ONLY
select is(
    (select array_agg(k order by k) from jsonb_object_keys((
        select t from jsonb_array_elements((select result from t_result where label = 'q_tasks')) t
        where t->>'due_start_utc' = '2026-09-12T19:00:00Z'
    )) k),
    array['due_end_utc', 'due_start_utc']::text[],
    'T-KEYS-TASK-BUSY-ONLY: exact key set {due_start_utc, due_end_utc} -- no id, no title'
);

-- T-KEYS-TASK-TITLE-ONLY
select is(
    (select array_agg(k order by k) from jsonb_object_keys((
        select t from jsonb_array_elements((select result from t_result where label = 'q_tasks')) t
        where t->>'id' = '62000000-0000-4000-8000-000000000002'
    )) k),
    array['due_end_utc', 'due_start_utc', 'id', 'title']::text[],
    'T-KEYS-TASK-TITLE-ONLY: exact key set {id, title, due_start_utc, due_end_utc}'
);

-- T-KEYS-TASK-FULL
select is(
    (select array_agg(k order by k) from jsonb_object_keys((
        select t from jsonb_array_elements((select result from t_result where label = 'q_tasks')) t
        where t->>'id' = '62000000-0000-4000-8000-000000000003'
    )) k),
    array['created_at', 'due_end_utc', 'due_start_utc', 'effort', 'estimate_min', 'goal_id',
          'id', 'milestone_id', 'notes', 'owner_id', 'pair_id', 'priority', 'recurrence_rule',
          'status', 'tags', 'title', 'updated_at', 'version', 'visibility']::text[],
    'T-KEYS-TASK-FULL: exact key set is the full task_to_jsonb shape'
);

-- T-KEYS-BLOCK-BUSY-ONLY
select is(
    (select array_agg(k order by k) from jsonb_object_keys((
        select b from jsonb_array_elements((select result from t_result where label = 'q_blocks')) b
        where b->>'starts_at_utc' = '2026-09-12T19:00:00Z'
    )) k),
    array['ends_at_utc', 'starts_at_utc']::text[],
    'T-KEYS-BLOCK-BUSY-ONLY: exact key set {starts_at_utc, ends_at_utc} -- no id, no title'
);

-- T-KEYS-BLOCK-TITLE-ONLY
select is(
    (select array_agg(k order by k) from jsonb_object_keys((
        select b from jsonb_array_elements((select result from t_result where label = 'q_blocks')) b
        where b->>'id' = '63000000-0000-4000-8000-000000000002'
    )) k),
    array['ends_at_utc', 'id', 'starts_at_utc', 'title']::text[],
    'T-KEYS-BLOCK-TITLE-ONLY: exact key set {id, title, starts_at_utc, ends_at_utc}'
);

-- T-KEYS-BLOCK-FULL
select is(
    (select array_agg(k order by k) from jsonb_object_keys((
        select b from jsonb_array_elements((select result from t_result where label = 'q_blocks')) b
        where b->>'id' = '63000000-0000-4000-8000-000000000003'
    )) k),
    array['all_day', 'created_at', 'ends_at_utc', 'id', 'origin_tz', 'owner_id', 'pair_id',
          'recurrence_rule', 'starts_at_utc', 'status', 'task_id', 'title', 'type',
          'updated_at', 'version', 'visibility']::text[],
    'T-KEYS-BLOCK-FULL: exact key set is the full time_block_to_jsonb shape'
);

-- ===================================================================================
-- unpaired caller (R) sees empty projections for everything.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000ccc1","role":"authenticated"}';
select is(public.rpc_list_pair_shared_goals(), '[]'::jsonb, 'T-UNPAIRED-EMPTY-GOALS: an unpaired caller gets an empty array');
select is(public.rpc_list_pair_shared_tasks(), '[]'::jsonb, 'T-UNPAIRED-EMPTY-TASKS: an unpaired caller gets an empty array');
select is(public.rpc_list_pair_shared_time_blocks(), '[]'::jsonb, 'T-UNPAIRED-EMPTY-BLOCKS: an unpaired caller gets an empty array');

-- ===================================================================================
-- realtime.messages SELECT policy: guessed-topic denial, old-topic-after-rotation
-- denial, and the caller's own correct topic being readable.
-- ===================================================================================
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000aaa1","role":"authenticated"}';

select is(
    (select public.rpc_get_pair()->>'channel_topic'),
    'pair:' || (select result->'pair'->>'pair_id' from t_result where label = 'q_redeem') || ':' ||
        (select channel_generation from public.pairs where id = (select result->'pair'->>'pair_id' from t_result where label = 'q_redeem')::uuid)::text,
    'sanity: P''s current channel_topic matches the live pairs row'
);

set local "realtime.topic" to 'pair:deadbeef-0000-4000-8000-000000000000:deadbeef-0000-4000-8000-000000000000';
select is(
    (select count(*)::int from realtime.messages),
    0,
    'T-RT-GUESSED-TOPIC: a fabricated pair/topic never matches, so P sees zero rows under it'
);

set local "realtime.topic" to 'pair:deadbeef-0000-4000-8000-000000000000:00000000-0000-0000-0000-000000000000';
select is(
    (select count(*)::int from realtime.messages),
    0,
    'T-RT-OLD-TOPIC (foreign pair id): a topic naming someone else''s pair id is denied even with a plausible generation'
);

select set_config('realtime.topic', (select public.rpc_get_pair()->>'channel_topic'), true);
select ok(
    (select count(*)::int from realtime.messages) >= 1,
    'T-RT-OWN-TOPIC: P can read realtime.messages under their own current topic'
);

-- Q leaves; the topic Q used to be entitled to is now denied to Q ("former-member" via
-- realtime), and P (remaining member) gets a rotated topic.
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-00000000bbb1","role":"authenticated"}';

insert into t_result (label, result)
select 'q_leave', public.rpc_leave_pair('50000000-0000-4000-8000-000000000003'::uuid);
select is((select result->>'outcome' from t_result where label = 'q_leave'), 'applied', 'setup: Q leaves the pair');

select set_config('realtime.topic', (select result->'pair'->>'channel_topic' from t_result where label = 'q_redeem'), true);
select is(
    (select count(*)::int from realtime.messages),
    0,
    'T-RT-FORMER-MEMBER: Q''s old topic (from redemption time) is denied to Q now that Q has left'
);

select is(public.rpc_get_pair(), null, 'T-FORMER-PROJECTION: rpc_get_pair returns null for the departed member Q');

select * from finish();
rollback;
