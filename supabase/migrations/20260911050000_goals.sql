-- goals: top-level planning objects, owner-only in Phase 1 (contract phase1-planner.md §1).
-- Rule (spec §14.2 / ADR-007): RLS + grants ship WITH the table, plus negative tests
-- (supabase/tests/0002_goals_rls_test.sql).

-- Shared helper: canonical wire format for a UTC instant, "YYYY-MM-DDTHH:MI:SSZ" (contract §4).
-- Used by every planner table's *_to_jsonb() helper so RPC responses and pgTAP fixtures
-- match the Kotlin DTOs byte-for-byte.
create or replace function public.iso_utc(ts timestamptz)
returns text
language sql
stable
as $$
    select to_char(ts at time zone 'utc', 'YYYY-MM-DD"T"HH24:MI:SS"Z"');
$$;

comment on function public.iso_utc(timestamptz) is 'Formats a timestamptz as ISO-8601 UTC with trailing Z (contract §4 wire format).';

-- Shared trigger: keep updated_at current on every row mutation across planner tables.
create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

comment on function public.set_updated_at() is 'Generic BEFORE UPDATE trigger: stamps updated_at = now(). Shared by all planner tables.';

create table public.goals (
    id           uuid primary key default gen_random_uuid(),
    owner_id     uuid not null references auth.users (id) on delete cascade,
    household_id uuid null,
    visibility   text not null default 'private',
    title        text not null,
    notes        text,
    target_date  date,
    status       text not null default 'active',
    version      bigint not null default 1,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),

    constraint goals_visibility_check check (visibility in ('private', 'busy_only', 'title_only', 'full')),
    -- Phase 1 guard (contract §1): sharing columns are inert until a Phase 2 migration drops this.
    constraint goals_phase1_private_only check (household_id is null and visibility = 'private'),
    constraint goals_title_length_check check (char_length(title) between 1 and 200),
    constraint goals_status_check check (status in ('active', 'paused', 'completed', 'archived')),
    -- Lets tasks/time_blocks carry a composite owner-aware FK back to goals (contract §1).
    constraint goals_id_owner_id_unique unique (id, owner_id)
);

comment on table public.goals is 'Top-level planning objects; Phase 1 enforces private-only visibility (ADR-007; dropped in Phase 2).';
comment on constraint goals_phase1_private_only on public.goals is 'Phase 1 guard: household sharing is inert until Phase 2 drops this constraint.';

create trigger goals_set_updated_at
    before update on public.goals
    for each row
    execute function public.set_updated_at();

-- Wire-format serializer: snake_case keys, ISO-8601 UTC instants, exact enum strings (contract §4).
create or replace function public.goal_to_jsonb(g public.goals)
returns jsonb
language sql
stable
as $$
    select jsonb_build_object(
        'id', g.id,
        'owner_id', g.owner_id,
        'household_id', g.household_id,
        'visibility', g.visibility,
        'title', g.title,
        'notes', g.notes,
        'target_date', to_char(g.target_date, 'YYYY-MM-DD'),
        'status', g.status,
        'version', g.version,
        'created_at', public.iso_utc(g.created_at),
        'updated_at', public.iso_utc(g.updated_at)
    );
$$;

alter table public.goals enable row level security;

-- explicit grants: authenticated users act only on their own rows (policies below); anon gets nothing.
revoke all on table public.goals from anon, authenticated;
grant select, insert, update, delete on table public.goals to authenticated;

create policy goals_select_own on public.goals
    for select to authenticated
    using (owner_id = (select auth.uid()));

create policy goals_insert_own on public.goals
    for insert to authenticated
    with check (owner_id = (select auth.uid()));

create policy goals_update_own on public.goals
    for update to authenticated
    using (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

create policy goals_delete_own on public.goals
    for delete to authenticated
    using (owner_id = (select auth.uid()));
