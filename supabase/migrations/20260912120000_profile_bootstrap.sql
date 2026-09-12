-- Profile bootstrap — fixes the Stage-1 integration gap found in the live pairing E2E
-- (2026-09-12): nothing ever created public.profiles rows, so pair_to_jsonb's INNER
-- JOIN to profiles returned members=[] and clients rendered a paired user as Unpaired.
-- Three-part fix: create profiles automatically on signup, backfill existing users,
-- and make the member projection resilient to a missing profile row.

-- 1. Auto-create a profile for every new auth user (display name from the email
--    local-part; home_tz stays 'UTC' until the user sets it — spec §9 step 3).
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.profiles (user_id, display_name, home_tz)
    values (
        new.id,
        coalesce(nullif(split_part(coalesce(new.email, ''), '@', 1), ''), 'Someone'),
        'UTC'
    )
    on conflict (user_id) do nothing;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- 2. Backfill users that signed up before this trigger existed.
insert into public.profiles (user_id, display_name, home_tz)
select u.id,
       coalesce(nullif(split_part(coalesce(u.email, ''), '@', 1), ''), 'Someone'),
       'UTC'
from auth.users u
left join public.profiles p on p.user_id = u.id
where p.user_id is null;

-- 3. Defense in depth: the member projection must not hide a member whose profile
--    row is missing (LEFT JOIN + fallback name), matching the trigger's fallback.
create or replace function public.pair_to_jsonb(p public.pairs)
returns jsonb
language sql
stable
set search_path = ''
as $$
    select jsonb_build_object(
        'pair_id', p.id,
        'status', p.status,
        'version', p.version,
        'channel_topic', 'pair:' || p.id || ':' || p.channel_generation,
        'members', coalesce((
            select jsonb_agg(jsonb_build_object(
                'user_id', pm.user_id,
                'display_name', coalesce(pr.display_name, 'Someone'),
                'home_tz', coalesce(pr.home_tz, 'UTC'),
                'joined_at', public.iso_utc(pm.joined_at)
            ) order by pm.joined_at)
            from public.pair_members pm
            left join public.profiles pr on pr.user_id = pm.user_id
            where pm.pair_id = p.id and pm.left_at is null
        ), '[]'::jsonb),
        'active_invite_expires_at', (
            select public.iso_utc(i.expires_at)
            from public.pair_invites i
            where i.pair_id = p.id
              and i.redeemed_at is null
              and i.revoked_at is null
              and i.expires_at > now()
            order by i.created_at desc
            limit 1
        )
    );
$$;
