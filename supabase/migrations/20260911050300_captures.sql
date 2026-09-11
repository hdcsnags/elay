-- captures: owner-only quick-entry notes, optionally clarified into a task (contract §1).
-- Rule (spec §14.2 / ADR-007): RLS + grants ship WITH the table, plus negative tests
-- (supabase/tests/0005_captures_rls_test.sql).
--
-- Design note: unlike goal_id/task_id elsewhere, clarified_task_id is a plain FK (not a
-- composite owner-aware one). A composite FK here would need `on delete set null`, which
-- would also null out the NOT NULL owner_id column when the referenced task is deleted
-- (the same reason tasks.goal_id/time_blocks.task_id use `on delete cascade` instead) — but
-- cascading a *delete* of the capture itself when its clarified task disappears is the wrong
-- product behavior (the capture is the historical note; it should survive). So ownership of
-- clarified_task_id is validated explicitly in rpc_upsert_capture instead (mirrors the
-- "milestone RPC validates goal ownership" pattern from contract §2).

create table public.captures (
    id                 uuid primary key default gen_random_uuid(),
    owner_id           uuid not null references auth.users (id) on delete cascade,
    body               text not null,
    source             text not null default 'quick',
    ai_parse_status    text not null default 'unparsed',
    captured_at        timestamptz not null default now(),
    clarified_task_id  uuid null references public.tasks (id) on delete set null,
    version            bigint not null default 1,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),

    constraint captures_body_length_check check (char_length(body) between 1 and 10000),
    constraint captures_source_check check (source in ('quick', 'voice', 'share', 'manual')),
    constraint captures_ai_parse_status_check check (
        ai_parse_status in ('unparsed', 'parsed', 'failed', 'dismissed')
    )
);

comment on table public.captures is 'Owner-only quick-entry notes; no sharing columns (contract §1).';

create trigger captures_set_updated_at
    before update on public.captures
    for each row
    execute function public.set_updated_at();

-- Wire-format serializer: snake_case keys, ISO-8601 UTC instants, exact enum strings (contract §4).
create or replace function public.capture_to_jsonb(c public.captures)
returns jsonb
language sql
stable
as $$
    select jsonb_build_object(
        'id', c.id,
        'owner_id', c.owner_id,
        'body', c.body,
        'source', c.source,
        'ai_parse_status', c.ai_parse_status,
        'captured_at', public.iso_utc(c.captured_at),
        'clarified_task_id', c.clarified_task_id,
        'version', c.version,
        'created_at', public.iso_utc(c.created_at),
        'updated_at', public.iso_utc(c.updated_at)
    );
$$;

alter table public.captures enable row level security;

revoke all on table public.captures from anon, authenticated;
grant select, insert, update, delete on table public.captures to authenticated;

create policy captures_select_own on public.captures
    for select to authenticated
    using (owner_id = (select auth.uid()));

create policy captures_insert_own on public.captures
    for insert to authenticated
    with check (owner_id = (select auth.uid()));

create policy captures_update_own on public.captures
    for update to authenticated
    using (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

create policy captures_delete_own on public.captures
    for delete to authenticated
    using (owner_id = (select auth.uid()));
