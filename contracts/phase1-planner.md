# Phase 1 contract — personal planner (frozen 2026-09-11, council round `2026-09-11-phase1-design`)

*Binding for coder seats A, B1, B2, C1, C2. Change requests go to the concierge, never into code. ADR-000 wins ties. Enum wire values are EXACT strings below.*

## 1. Schema (seat A: `supabase/migrations/` + `supabase/tests/` only)

Migrations, one per table, each WITH grants + RLS + paired pgTAP (allow/deny/constraint cases): `goals`, `milestones`, `tasks`, `captures`, `time_blocks`, then `mutation_receipts` + RPCs.

Common shareable columns (goals, tasks, time_blocks): `id uuid PK default gen_random_uuid()` · `owner_id uuid not null references auth.users(id) on delete cascade` · `household_id uuid null` · `visibility text not null default 'private' check in ('private','busy_only','title_only','full')` · **Phase 1 guard: `check (household_id is null and visibility = 'private')`** (dropped by a Phase 2 migration) · `version bigint not null default 1` · `created_at/updated_at timestamptz not null default now()`.

- **goals**: `title` (1..200), `notes text`, `target_date date`, `status in ('active','paused','completed','archived') default 'active'`.
- **milestones** (lean child, no owner/visibility — access via goal): `goal_id uuid not null references goals(id) on delete cascade`, `title` (1..200), `target_date date`, `sort_order int not null default 0 check (>=0)`, `status in ('pending','active','completed','skipped') default 'pending'`, `version`, timestamps. RLS: `exists (select 1 from goals g where g.id = goal_id and g.owner_id = (select auth.uid()))` for ALL ops.
- **tasks**: common + `goal_id uuid null`, `milestone_id uuid null`, `title` (1..200), `notes`, `status in ('todo','in_progress','completed','cancelled') default 'todo'`, `priority smallint not null default 1 check (0..3)`, `effort smallint null check (1..5)`, `estimate_min int null check (1..1440)`, `due_start_utc/due_end_utc timestamptz null` + `check (due_end_utc is null or due_start_utc is null or due_end_utc >= due_start_utc)`, `recurrence_rule text null`, `tags text[] not null default '{}'`. **Composite owner-aware FK:** `foreign key (goal_id, owner_id) references goals (id, owner_id)` (goals gets `unique (id, owner_id)`); same pattern for `milestone_id` via milestones→goal ownership (validate in RPC).
- **captures** (owner-only, no sharing columns): `body` (1..10000), `source in ('quick','voice','share','manual') default 'quick'`, `ai_parse_status in ('unparsed','parsed','failed','dismissed') default 'unparsed'`, `captured_at timestamptz default now()`, `clarified_task_id uuid null`, `version`, timestamps.
- **time_blocks**: common + `task_id uuid null` (composite owner FK), `title null` (1..200), `starts_at_utc/ends_at_utc timestamptz not null`, `check (ends_at_utc > starts_at_utc)`, `check (ends_at_utc - starts_at_utc between interval '1 minute' and interval '24 hours')`, `origin_tz text not null` validated by trigger against `pg_timezone_names`, `type in ('personal','focus','routine') default 'personal'`, `status in ('scheduled','completed','cancelled') default 'scheduled'`, `recurrence_rule text null`, `all_day boolean not null default false`.

RLS everywhere: revoke all from anon+authenticated, grant CRUD to authenticated, `owner_id = (select auth.uid())` USING + WITH CHECK (milestones via goal). pgTAP must include: peer denial by guessed UUID, anon full denial, every CHECK bound (one reject case each), composite-FK cross-owner rejection.

## 2. RPCs + receipts (seat A)

`mutation_receipts`: `owner_id uuid not null`, `operation_id uuid not null`, `result_version bigint not null`, `applied_at timestamptz default now()`, `unique (owner_id, operation_id)`; owner-only RLS.

Typed RPCs, `security invoker`, one transaction each: `rpc_upsert_goal`, `rpc_upsert_milestone`, `rpc_upsert_task`, `rpc_upsert_capture`, `rpc_upsert_time_block`, `rpc_delete_{same}` — signature pattern:
`rpc_upsert_task(p_operation_id uuid, p_expected_version bigint, p_row jsonb) returns jsonb`.
Behavior: (1) if receipt exists for `(auth.uid(), p_operation_id)` → return stored result (idempotent replay); (2) on insert `p_expected_version = 0`; on update row must exist with `version = p_expected_version`, else return `{"outcome":"conflict","current": <row>}` **without mutating**; (3) validate FK ownership, apply, bump `version`, write receipt, return `{"outcome":"applied","row": <row>}`. Deletes: same shape, tombstone outcome `applied`. Never raise for conflicts — raise only for authz/validation (RLS handles authz; constraint violations surface as errors → client `FAILED`).

## 3. Outbox replay (seat B1 local, seat B2 engine)

Statuses: `PENDING → IN_FLIGHT → (ACKNOWLEDGED | CONFLICT | FAILED)`. Strict FIFO by `(createdAtEpochMs, operationId)` **per aggregateId**; one in flight at a time globally (Phase 1 simplicity). Retry on network/5xx/429: full-jitter exponential backoff, base 1 s, factor 2, **cap 120 s**, persisted attempts. Auth failure: pause queue, request session refresh. 4xx validation: `FAILED` + surface. `conflict` outcome: mark row `CONFLICT`, store server row alongside local patch; auto-rebase ONLY when server-changed fields ∩ client-patched fields = ∅, else hold for user choice (always hold for time_blocks). ACK writes Room state + receipt version atomically (one Room transaction).

## 4. Wire formats (seats A, B2 — must match byte-for-byte)

`p_row` JSON: snake_case keys exactly as the SQL columns; instants as ISO-8601 UTC (`2026-09-11T19:00:00Z`); dates as `YYYY-MM-DD`; enums as the SQL strings above; arrays as JSON arrays. Kotlin side: `@Serializable` DTOs in `dev.elay.data.remote.dto` with `@SerialName` per field — the contract test is a shared JSON fixture file per entity at `contracts/fixtures/*.json` that BOTH pgTAP (seat A) and Kotlin serialization tests (seat B2) round-trip.

## 5. Frozen interfaces (concierge-owned, land before dispatch)

`dev.elay.domain.model.PlannerModels` (Goal, Milestone, Task, Capture, TimeBlock, enums, typed ids) · `dev.elay.domain.repository.PlannerRepository` + `ProfileRepository` (suspend + Flow surfaces per council DAO list) · `dev.elay.data.remote.AuthGateway` (session state Flow, signInEmail, signOut, refresh) · `dev.elay.data.remote.DataGateway` (typed upsert/delete per entity returning `MutationResult = Applied(row,version) | Conflict(current) | Failed(reason)`) · `dev.elay.sync.MutationCommand` (sealed, one per RPC) · `dev.elay.sync.SyncCoordinator` (enqueue, replayOnce, status Flow) · `dev.elay.ui.Routes` expanded: `GoalDetailRoute(id)`, `TaskDetailRoute(id)`, `SettingsRoute`.

## 6. Seat paths (disjoint — violating this fails the round)

| Seat | May touch ONLY | Must make green |
|---|---|---|
| A (Sol, code) | `supabase/migrations/`, `supabase/tests/`, `contracts/fixtures/` | `supabase db reset && supabase db lint && supabase test db` locally documented; pgTAP cases per §1–2 |
| B1 (Sonnet) | `shared/src/*/kotlin/dev/elay/data/local/` (extends the outbox skeleton), Room tests | Windows gate + Room smoke incl. new entities (iosTest) |
| B2 (Sonnet, after A+B1) | `shared/src/*/kotlin/dev/elay/data/remote/impl/`, `dev/elay/sync/impl/`, fixture round-trip tests | Windows gate + fixture tests |
| C1 (Sonnet) | `shared/src/commonMain/kotlin/dev/elay/ui/` (Today, Inbox, Plan + fakes in `ui/fake/`), UI tests | Windows gate + emulator screenshots per `/build-gate` |
| C2 (later) | `ui/` (Goals, GoalDetail, TaskDetail, Settings) | same |
| Concierge | everything else: build files, DI, auth glue, `App.kt`, navigation wiring, integration | full gate + CI ×3 |

No seat edits `gradle/`, `settings.gradle.kts`, root `build.gradle.kts`, `.github/`, this file, or another seat's paths (phase0-foundation rules stand).
