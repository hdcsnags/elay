-- time_blocks: owner-scoped calendar events, optionally linked to a task (contract §1).
-- Rule (spec §14.2 / ADR-007): RLS + grants ship WITH the table, plus negative tests
-- (supabase/tests/0006_time_blocks_rls_test.sql).
-- origin_tz is the creator's IANA zone (ADR-006): recurrence and "what did the user mean"
-- are always resolved in this zone, never by UTC arithmetic.

create table public.time_blocks (
    id              uuid primary key default gen_random_uuid(),
    owner_id        uuid not null references auth.users (id) on delete cascade,
    household_id    uuid null,
    visibility      text not null default 'private',
    task_id         uuid null,
    title           text null,
    starts_at_utc   timestamptz not null,
    ends_at_utc     timestamptz not null,
    origin_tz       text not null,
    type            text not null default 'personal',
    status          text not null default 'scheduled',
    recurrence_rule text null,
    all_day         boolean not null default false,
    version         bigint not null default 1,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),

    constraint time_blocks_visibility_check check (visibility in ('private', 'busy_only', 'title_only', 'full')),
    -- Phase 1 guard (contract §1): sharing columns are inert until a Phase 2 migration drops this.
    constraint time_blocks_phase1_private_only check (household_id is null and visibility = 'private'),
    constraint time_blocks_title_length_check check (title is null or char_length(title) between 1 and 200),
    constraint time_blocks_end_after_start_check check (ends_at_utc > starts_at_utc),
    constraint time_blocks_duration_bounds_check check (
        ends_at_utc - starts_at_utc between interval '1 minute' and interval '24 hours'
    ),
    constraint time_blocks_type_check check (type in ('personal', 'focus', 'routine')),
    constraint time_blocks_status_check check (status in ('scheduled', 'completed', 'cancelled')),
    -- Composite owner-aware FK (contract §1): a task_id can only reference a task this same
    -- owner_id already owns. `on delete cascade` (not `set null`) for the same reason as
    -- tasks.goal_id -> goals: `set null` on a composite FK would also null out the NOT NULL
    -- owner_id column and raise on every task delete that still has time blocks.
    constraint time_blocks_task_owner_fk foreign key (task_id, owner_id)
        references public.tasks (id, owner_id) on delete cascade
);

comment on table public.time_blocks is 'Owner-scoped calendar events; Phase 1 is private-only (ADR-007). origin_tz per ADR-006.';
comment on constraint time_blocks_phase1_private_only on public.time_blocks is 'Phase 1 guard: household sharing is inert until Phase 2 drops this constraint.';
comment on column public.time_blocks.origin_tz is 'IANA zone name preserving creator intent; validated against pg_timezone_names by trigger (ADR-006).';

create trigger time_blocks_set_updated_at
    before update on public.time_blocks
    for each row
    execute function public.set_updated_at();

-- origin_tz must be a real IANA zone name (ADR-006); validated here rather than by a CHECK
-- constraint because CHECK expressions must be immutable and pg_timezone_names is a system view.
create or replace function public.validate_origin_tz()
returns trigger
language plpgsql
as $$
begin
    if not exists (select 1 from pg_timezone_names where name = new.origin_tz) then
        raise exception 'origin_tz "%" is not a recognized IANA timezone name', new.origin_tz
            using errcode = '22023';
    end if;
    return new;
end;
$$;

create trigger time_blocks_validate_origin_tz
    before insert or update on public.time_blocks
    for each row
    execute function public.validate_origin_tz();

-- Wire-format serializer: snake_case keys, ISO-8601 UTC instants, exact enum strings (contract §4).
create or replace function public.time_block_to_jsonb(b public.time_blocks)
returns jsonb
language sql
stable
as $$
    select jsonb_build_object(
        'id', b.id,
        'owner_id', b.owner_id,
        'household_id', b.household_id,
        'visibility', b.visibility,
        'task_id', b.task_id,
        'title', b.title,
        'starts_at_utc', public.iso_utc(b.starts_at_utc),
        'ends_at_utc', public.iso_utc(b.ends_at_utc),
        'origin_tz', b.origin_tz,
        'type', b.type,
        'status', b.status,
        'recurrence_rule', b.recurrence_rule,
        'all_day', b.all_day,
        'version', b.version,
        'created_at', public.iso_utc(b.created_at),
        'updated_at', public.iso_utc(b.updated_at)
    );
$$;

alter table public.time_blocks enable row level security;

revoke all on table public.time_blocks from anon, authenticated;
grant select, insert, update, delete on table public.time_blocks to authenticated;

create policy time_blocks_select_own on public.time_blocks
    for select to authenticated
    using (owner_id = (select auth.uid()));

create policy time_blocks_insert_own on public.time_blocks
    for insert to authenticated
    with check (owner_id = (select auth.uid()));

create policy time_blocks_update_own on public.time_blocks
    for update to authenticated
    using (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

create policy time_blocks_delete_own on public.time_blocks
    for delete to authenticated
    using (owner_id = (select auth.uid()));
