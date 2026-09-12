-- pgTAP: rpc_list_proposals full projection (migration 20260912150000). The Stage 2 live
-- E2E found list items shipped as the bare core shape while the contract requires the same
-- participant-safe projection as rpc_get_proposal (council/stage2-timelock-sol.md §2:
-- "return participant-safe active/history snapshots, revisions, responses, and
-- caller-owned commitment"). Asserts FIELD SETS (house rule), nested revision/candidate
-- content, caller-scoped my_commitment (peer's never exposed), and that list items match
-- rpc_get_proposal's projection for the same proposal.
begin;
create extension if not exists pgtap with schema extensions;
select plan(10);

-- setup: one pair (A/B), one proposal, B accepts candidate 0.
insert into auth.users (id, email) values
    ('00000000-0000-0000-0000-0000000017a1', 'list-a@test.local'),
    ('00000000-0000-0000-0000-0000000017b1', 'list-b@test.local');
insert into public.profiles (user_id, display_name, home_tz) values
    ('00000000-0000-0000-0000-0000000017a1', 'List A', 'America/Toronto'),
    ('00000000-0000-0000-0000-0000000017b1', 'List B', 'America/Vancouver')
on conflict (user_id) do update set display_name = excluded.display_name, home_tz = excluded.home_tz;

create temporary table t_result (label text, result jsonb);
grant all on t_result to authenticated, anon;

set local role authenticated;
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000017a1","role":"authenticated"}';
insert into t_result (label, result) select 'a_invite', public.rpc_create_pair_invite('c1700000-0000-4000-8000-000000000001'::uuid, 60);

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000017b1","role":"authenticated"}';
insert into t_result (label, result) select 'b_redeem', public.rpc_redeem_pair_invite(
    'c1700000-0000-4000-8000-000000000002'::uuid, (select result->'invite'->>'code' from t_result where label = 'a_invite'));
select is((select result->>'outcome' from t_result where label = 'b_redeem'), 'applied', 'setup: A/B paired');

set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000017a1","role":"authenticated"}';
insert into t_result (label, result) select 'p_create', public.rpc_create_proposal(
    'c1700000-0000-4000-8000-000000000003'::uuid, 'Projection check', 'America/Toronto', now() + interval '2 days',
    '[{"candidate_idx":0,"starts_at_utc":"2026-09-20T19:00:00Z","ends_at_utc":"2026-09-20T20:00:00Z","duration_min":60}]'::jsonb
);
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000017b1","role":"authenticated"}';
insert into t_result (label, result) select 'b_accept', public.rpc_respond_proposal(
    'c1700000-0000-4000-8000-000000000004'::uuid,
    (select (result->'proposal'->>'id')::uuid from t_result where label = 'p_create'), 1, 'accept', 0);
select is((select result->>'outcome' from t_result where label = 'b_accept'), 'applied', 'setup: B accepted candidate 0');

-- the projection, as A.
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000017a1","role":"authenticated"}';
insert into t_result (label, result) select 'a_list', public.rpc_list_proposals('active', null);

select is(
    (select jsonb_array_length(result->'items') from t_result where label = 'a_list'), 1,
    'LIST-PROJ-1: active list has exactly one item for this pair'
);
select is(
    (select array_agg(k order by k) from (
        select jsonb_object_keys((select result->'items'->0 from t_result where label = 'a_list')) as k
    ) keys),
    array['accepted_at','accepted_candidate_idx','accepted_revision','cancelled_at','completed_at',
          'created_at','creator_id','current_revision','declined_at','expired_at','id','my_commitment',
          'origin_tz','pair_id','response_deadline','responses','revisions','status','title',
          'updated_at','version'],
    'LIST-PROJ-2: list item carries the EXACT rpc_get_proposal key set (core + revisions + responses + my_commitment)'
);
select is(
    (select jsonb_array_length(result->'items'->0->'revisions') from t_result where label = 'a_list'), 1,
    'LIST-PROJ-3: the item nests its one revision'
);
select is(
    (select result->'items'->0->'revisions'->0->'candidates'->0->>'starts_at_utc' from t_result where label = 'a_list'),
    '2026-09-20T19:00:00Z',
    'LIST-PROJ-4: nested revision carries the candidate instants the cards render from'
);
select is(
    (select jsonb_array_length(result->'items'->0->'responses') from t_result where label = 'a_list'), 1,
    'LIST-PROJ-5: the item nests B''s accept response'
);
-- Acceptance creates per-member commitment rows, so BOTH callers have one — the projection
-- must return the CALLER's own row, never the peer's.
select is(
    (select result->'items'->0->'my_commitment'->>'user_id' from t_result where label = 'a_list'),
    '00000000-0000-0000-0000-0000000017a1',
    'LIST-PROJ-6: my_commitment is A''s own commitment row (per-member rows created on accept)'
);

-- as B: same item, but my_commitment is B's row — the peer's commitment is never exposed.
set local request.jwt.claims to '{"sub":"00000000-0000-0000-0000-0000000017b1","role":"authenticated"}';
insert into t_result (label, result) select 'b_list', public.rpc_list_proposals('active', null);
select is(
    (select result->'items'->0->'my_commitment'->>'user_id' from t_result where label = 'b_list'),
    '00000000-0000-0000-0000-0000000017b1',
    'LIST-PROJ-7: B sees B''s own commitment in the same list item, not A''s'
);

-- list item == get projection for the same proposal (the contract''s "same shape" clause).
select is(
    (select result->'items'->0 from t_result where label = 'b_list'),
    public.rpc_get_proposal((select (result->'proposal'->>'id')::uuid from t_result where label = 'p_create')),
    'LIST-PROJ-8: the list item is byte-identical to rpc_get_proposal''s projection'
);

select * from finish();
rollback;
