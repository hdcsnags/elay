-- pgTAP: profile bootstrap trigger + resilient member projection (2026-09-12 fix).
begin;
create extension if not exists pgtap with schema extensions;
select plan(4);

-- trigger creates a profile on signup, deriving the name from the email local-part
insert into auth.users (id, email)
values ('00000000-0000-0000-0000-00000000b001', 'bootstrap.test@elay.test');
select results_eq(
    $$select display_name, home_tz from public.profiles
      where user_id = '00000000-0000-0000-0000-00000000b001'$$,
    $$values ('bootstrap.test'::text, 'UTC'::text)$$,
    'signup trigger creates a profile with email local-part and UTC'
);

-- trigger tolerates a missing email
insert into auth.users (id) values ('00000000-0000-0000-0000-00000000b002');
select results_eq(
    $$select display_name from public.profiles
      where user_id = '00000000-0000-0000-0000-00000000b002'$$,
    $$values ('Someone'::text)$$,
    'null-email signup falls back to Someone'
);

-- member projection survives a missing profile row (LEFT JOIN fallback)
insert into public.pairs (id, created_by)
values ('00000000-0000-0000-0000-00000000b100', '00000000-0000-0000-0000-00000000b001');
insert into public.pair_members (id, pair_id, user_id)
values ('00000000-0000-0000-0000-00000000b101', '00000000-0000-0000-0000-00000000b100',
        '00000000-0000-0000-0000-00000000b001');
delete from public.profiles where user_id = '00000000-0000-0000-0000-00000000b001';
select is(
    (public.pair_to_jsonb(p) -> 'members' -> 0 ->> 'display_name'),
    'Someone',
    'a member with no profile row still appears, named Someone'
) from public.pairs p where p.id = '00000000-0000-0000-0000-00000000b100';
select is(
    (public.pair_to_jsonb(p) -> 'members' -> 0 ->> 'home_tz'),
    'UTC',
    'missing profile falls back to UTC zone'
) from public.pairs p where p.id = '00000000-0000-0000-0000-00000000b100';

select * from finish();
rollback;
