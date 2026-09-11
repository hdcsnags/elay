-- profiles: user preferences and display identity (spec §7).
-- Rule (spec §14.2): RLS + grants ship WITH the table, plus negative tests
-- (supabase/tests/0001_profiles_rls_test.sql).

create table public.profiles (
    user_id      uuid primary key references auth.users (id) on delete cascade,
    display_name text not null check (char_length(display_name) between 1 and 80),
    home_tz      text not null default 'UTC',
    locale       text not null default 'en',
    week_start   smallint not null default 1 check (week_start between 0 and 6),
    quiet_hours  jsonb,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

comment on table public.profiles is 'User preferences and display identity; one row per auth user (owner-only access).';
comment on column public.profiles.home_tz is 'IANA zone name; validated app-side and by the timezone engine (ADR-006).';

alter table public.profiles enable row level security;

-- explicit grants: authenticated users act only on their own row (policies below);
-- anon gets nothing; no client delete (account deletion is a server workflow, spec §8).
revoke all on table public.profiles from anon, authenticated;
grant select, insert, update on table public.profiles to authenticated;

create policy profiles_select_own on public.profiles
    for select to authenticated
    using (user_id = (select auth.uid()));

create policy profiles_insert_own on public.profiles
    for insert to authenticated
    with check (user_id = (select auth.uid()));

create policy profiles_update_own on public.profiles
    for update to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));
