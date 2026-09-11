-- milestones: lean children of goals; no owner/visibility columns of their own — access is
-- derived entirely from goal ownership (contract phase1-planner.md §1).
-- Rule (spec §14.2 / ADR-007): RLS + grants ship WITH the table, plus negative tests
-- (supabase/tests/0003_milestones_rls_test.sql).

create table public.milestones (
    id          uuid primary key default gen_random_uuid(),
    goal_id     uuid not null references public.goals (id) on delete cascade,
    title       text not null,
    target_date date,
    sort_order  int not null default 0,
    status      text not null default 'pending',
    version     bigint not null default 1,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),

    constraint milestones_title_length_check check (char_length(title) between 1 and 200),
    constraint milestones_sort_order_check check (sort_order >= 0),
    constraint milestones_status_check check (status in ('pending', 'active', 'completed', 'skipped'))
);

comment on table public.milestones is 'Ordered milestones within a goal; access derived from goal ownership (ADR-007, contract §1).';

create trigger milestones_set_updated_at
    before update on public.milestones
    for each row
    execute function public.set_updated_at();

-- Wire-format serializer: snake_case keys, ISO-8601 UTC instants, exact enum strings (contract §4).
create or replace function public.milestone_to_jsonb(m public.milestones)
returns jsonb
language sql
stable
as $$
    select jsonb_build_object(
        'id', m.id,
        'goal_id', m.goal_id,
        'title', m.title,
        'target_date', to_char(m.target_date, 'YYYY-MM-DD'),
        'sort_order', m.sort_order,
        'status', m.status,
        'version', m.version,
        'created_at', public.iso_utc(m.created_at),
        'updated_at', public.iso_utc(m.updated_at)
    );
$$;

alter table public.milestones enable row level security;

revoke all on table public.milestones from anon, authenticated;
grant select, insert, update, delete on table public.milestones to authenticated;

-- single ALL policy: a milestone is visible/mutable iff its parent goal is owned by the caller
-- (contract §1: "RLS: exists (select 1 from goals g where g.id = goal_id and g.owner_id =
-- (select auth.uid())) for ALL ops").
create policy milestones_all_via_goal_owner on public.milestones
    for all to authenticated
    using (
        exists (
            select 1 from public.goals g
            where g.id = goal_id
              and g.owner_id = (select auth.uid())
        )
    )
    with check (
        exists (
            select 1 from public.goals g
            where g.id = goal_id
              and g.owner_id = (select auth.uid())
        )
    );
