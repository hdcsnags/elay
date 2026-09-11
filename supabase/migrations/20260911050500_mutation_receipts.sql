-- mutation_receipts: idempotency ledger for the typed RPCs (contract §2).
-- Rule (spec §14.2): RLS + grants ship WITH the table, plus negative tests
-- (supabase/tests/0007..0011_rpc_*_test.sql exercise replay via this table).
--
-- Design note: the contract fixes owner_id, operation_id, result_version, applied_at and
-- unique (owner_id, operation_id) — all present below, verbatim. It does not say how a
-- replayed call recovers its *exact* original response, and result_version alone cannot
-- reconstruct a full row (especially for a delete, whose row no longer exists to re-read).
-- So this migration additively stores the literal jsonb the RPC returned in `result`; replay
-- is then just `select result from mutation_receipts where ...` with no reconstruction logic
-- and no risk of drifting from what the row looks like *now* if something else touched it
-- after the original call.

create table public.mutation_receipts (
    owner_id       uuid not null references auth.users (id) on delete cascade,
    operation_id   uuid not null,
    result_version bigint not null,
    result         jsonb not null,
    applied_at     timestamptz not null default now(),

    constraint mutation_receipts_pkey primary key (owner_id, operation_id)
);

comment on table public.mutation_receipts is 'Idempotency ledger for the planner RPCs; one row per successfully applied (owner_id, operation_id) (contract §2).';
comment on column public.mutation_receipts.result is 'Literal jsonb the RPC returned on first application; replayed verbatim on retry (additive column, not in the literal contract schema).';

alter table public.mutation_receipts enable row level security;

-- owner-only; immutable audit log, so no update/delete grant at all.
revoke all on table public.mutation_receipts from anon, authenticated;
grant select, insert on table public.mutation_receipts to authenticated;

create policy mutation_receipts_select_own on public.mutation_receipts
    for select to authenticated
    using (owner_id = (select auth.uid()));

create policy mutation_receipts_insert_own on public.mutation_receipts
    for insert to authenticated
    with check (owner_id = (select auth.uid()));
