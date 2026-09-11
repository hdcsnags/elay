-- tasks: owner-scoped work items, optionally linked to a goal/milestone (contract §1).
-- Rule (spec §14.2 / ADR-007): RLS + grants ship WITH the table, plus negative tests
-- (supabase/tests/0004_tasks_rls_test.sql).
--
-- Design note (not spelled out by the contract): the composite FK (goal_id, owner_id) ->
-- goals (id, owner_id) cannot use `on delete set null` — that would null out the NOT NULL
-- owner_id column too, along with goal_id, and raise a constraint violation on every goal
-- delete that still has tasks. `on delete cascade` is the only safe action for a composite FK
-- whose second column is NOT NULL, so deleting a goal cascades to its tasks (matching the
-- cascade the contract already specifies for milestones).

create table public.tasks (
    id              uuid primary key default gen_random_uuid(),
    owner_id        uuid not null references auth.users (id) on delete cascade,
    household_id    uuid null,
    visibility      text not null default 'private',
    goal_id         uuid null,
    milestone_id    uuid null,
    title           text not null,
    notes           text,
    status          text not null default 'todo',
    priority        smallint not null default 1,
    effort          smallint null,
    estimate_min    int null,
    due_start_utc   timestamptz null,
    due_end_utc     timestamptz null,
    recurrence_rule text null,
    tags            text[] not null default '{}',
    version         bigint not null default 1,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),

    constraint tasks_visibility_check check (visibility in ('private', 'busy_only', 'title_only', 'full')),
    -- Phase 1 guard (contract §1): sharing columns are inert until a Phase 2 migration drops this.
    constraint tasks_phase1_private_only check (household_id is null and visibility = 'private'),
    constraint tasks_title_length_check check (char_length(title) between 1 and 200),
    constraint tasks_status_check check (status in ('todo', 'in_progress', 'completed', 'cancelled')),
    constraint tasks_priority_check check (priority between 0 and 3),
    constraint tasks_effort_check check (effort is null or effort between 1 and 5),
    constraint tasks_estimate_min_check check (estimate_min is null or estimate_min between 1 and 1440),
    constraint tasks_due_range_check check (
        due_end_utc is null or due_start_utc is null or due_end_utc >= due_start_utc
    ),
    -- Lets time_blocks carry a composite owner-aware FK back to tasks (contract §1).
    constraint tasks_id_owner_id_unique unique (id, owner_id),
    -- Composite owner-aware FK (contract §1): a goal_id can only reference a goal this same
    -- owner_id already owns, so cross-owner linkage is impossible even before RLS runs.
    constraint tasks_goal_owner_fk foreign key (goal_id, owner_id)
        references public.goals (id, owner_id) on delete cascade,
    -- milestone_id has no owner_id of its own to pair with; ownership of the milestone's
    -- parent goal is validated in rpc_upsert_task (contract §1/§2), not by a DB constraint.
    constraint tasks_milestone_fk foreign key (milestone_id)
        references public.milestones (id) on delete set null
);

comment on table public.tasks is 'Owner-scoped work items, optionally under a goal/milestone; Phase 1 is private-only (ADR-007).';
comment on constraint tasks_phase1_private_only on public.tasks is 'Phase 1 guard: household sharing is inert until Phase 2 drops this constraint.';

create trigger tasks_set_updated_at
    before update on public.tasks
    for each row
    execute function public.set_updated_at();

-- Wire-format serializer: snake_case keys, ISO-8601 UTC instants, exact enum strings (contract §4).
create or replace function public.task_to_jsonb(t public.tasks)
returns jsonb
language sql
stable
as $$
    select jsonb_build_object(
        'id', t.id,
        'owner_id', t.owner_id,
        'household_id', t.household_id,
        'visibility', t.visibility,
        'goal_id', t.goal_id,
        'milestone_id', t.milestone_id,
        'title', t.title,
        'notes', t.notes,
        'status', t.status,
        'priority', t.priority,
        'effort', t.effort,
        'estimate_min', t.estimate_min,
        'due_start_utc', public.iso_utc(t.due_start_utc),
        'due_end_utc', public.iso_utc(t.due_end_utc),
        'recurrence_rule', t.recurrence_rule,
        'tags', to_jsonb(t.tags),
        'version', t.version,
        'created_at', public.iso_utc(t.created_at),
        'updated_at', public.iso_utc(t.updated_at)
    );
$$;

alter table public.tasks enable row level security;

revoke all on table public.tasks from anon, authenticated;
grant select, insert, update, delete on table public.tasks to authenticated;

create policy tasks_select_own on public.tasks
    for select to authenticated
    using (owner_id = (select auth.uid()));

create policy tasks_insert_own on public.tasks
    for insert to authenticated
    with check (owner_id = (select auth.uid()));

create policy tasks_update_own on public.tasks
    for update to authenticated
    using (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

create policy tasks_delete_own on public.tasks
    for delete to authenticated
    using (owner_id = (select auth.uid()));
