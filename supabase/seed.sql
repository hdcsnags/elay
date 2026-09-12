-- Local-only seed (supabase db reset): the two E2E test accounts, so a reset no longer
-- wipes the sign-in state every live emulator session depends on (STATE 2026-09-12 noted
-- this as a recurring cost). Idempotent (guarded per email); local dev only — seed.sql
-- never runs against a hosted project via db push. Passwords are the documented local
-- test creds, not secrets. The handle_new_user trigger (20260912120000) DOES fire on these
-- direct inserts (plain AFTER INSERT row trigger — verified in a dry run); the explicit
-- profile insert below is a guarded no-op fallback kept in case the trigger is ever scoped.

insert into auth.users (
    instance_id, id, aud, role, email, encrypted_password,
    email_confirmed_at, raw_app_meta_data, raw_user_meta_data, created_at, updated_at
)
select
    '00000000-0000-0000-0000-000000000000',
    '688cacc5-550d-47ac-ab5e-b8de43e509cf', 'authenticated', 'authenticated',
    'a@elay.test', extensions.crypt('elaypass123', extensions.gen_salt('bf')),
    now(), '{"provider":"email","providers":["email"]}', '{}', now(), now()
where not exists (select 1 from auth.users where email = 'a@elay.test');

insert into auth.users (
    instance_id, id, aud, role, email, encrypted_password,
    email_confirmed_at, raw_app_meta_data, raw_user_meta_data, created_at, updated_at
)
select
    '00000000-0000-0000-0000-000000000000',
    gen_random_uuid(), 'authenticated', 'authenticated',
    'b2@elay.test', extensions.crypt('elaypass123', extensions.gen_salt('bf')),
    now(), '{"provider":"email","providers":["email"]}', '{}', now(), now()
where not exists (select 1 from auth.users where email = 'b2@elay.test');

insert into auth.identities (id, user_id, identity_data, provider, provider_id, created_at, updated_at, last_sign_in_at)
select gen_random_uuid(), u.id,
       jsonb_build_object('sub', u.id::text, 'email', u.email, 'email_verified', true, 'phone_verified', false),
       'email', u.id::text, now(), now(), now()
from auth.users u
where u.email in ('a@elay.test', 'b2@elay.test')
  and not exists (select 1 from auth.identities i where i.user_id = u.id and i.provider = 'email');

insert into public.profiles (user_id, display_name, home_tz)
select u.id, split_part(u.email, '@', 1), 'Etc/UTC'
from auth.users u
where u.email in ('a@elay.test', 'b2@elay.test')
on conflict (user_id) do nothing;
