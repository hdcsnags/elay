Coder seat A — ELAY Phase 1: schema migrations, typed RPCs, pgTAP. You have WRITE access in your own clone; your diff is collected as a patch for concierge review.

READ FIRST: `contracts/phase1-planner.md` (§1, §2, §4 are your spec — enum strings and column names are EXACT), `adr/ADR-006` (time), `adr/ADR-007` (visibility), and the existing house style in `supabase/migrations/20260911043836_profiles.sql` + `supabase/tests/0001_profiles_rls_test.sql`.

BUILD, in `supabase/migrations/` (one timestamped file per table, in dependency order) and `supabase/tests/`:
1. Tables per contract §1: goals, milestones, tasks, captures, time_blocks — with ALL CHECK constraints, the Phase-1 `household_id is null and visibility='private'` guard (goals/tasks/time_blocks), composite owner-aware FKs (goals gets `unique (id, owner_id)`; tasks/time_blocks reference `(goal_id|task_id, owner_id)`), the `origin_tz` validation trigger against `pg_timezone_names`, and `updated_at` triggers. Grants + RLS in the same file as each table.
2. `mutation_receipts` + the ten typed RPCs per contract §2 (upsert/delete per entity; jsonb in/out; idempotent replay via receipts; version-conflict returns `{"outcome":"conflict","current":...}` WITHOUT mutating; milestone RPC validates goal ownership).
3. pgTAP suites (numbered under `supabase/tests/`): per table — owner CRUD allowed, peer denied by guessed UUID (select/update/delete return empty), anon denied outright, one reject case per CHECK bound, composite-FK cross-owner rejection; per RPC — happy path bumps version + writes receipt, replay of same operation_id returns stored result without double-applying, stale expected_version returns conflict and leaves the row untouched.
4. Wire fixtures: `contracts/fixtures/{goal,milestone,task,capture,time_block}.json` — one canonical row each, snake_case, ISO-8601 UTC instants, exact enum strings (contract §4). These are shared with the Kotlin seat; make them exactly what your RPCs accept.

TOUCH ONLY: `supabase/migrations/`, `supabase/tests/`, `contracts/fixtures/`. Never: gradle files, shared/, .github/, contracts/*.md.

VERIFY: you likely cannot run Docker in your sandbox — do NOT claim tests pass if you could not run them. Statically re-check every enum/column against contract §1 and say in your report: what you built, what you verified and how, what is UNVERIFIED (the concierge runs `supabase db reset && supabase db lint --level error && supabase test db` on merge — write SQL that survives it the first time).

Begin your report with the model you are running as.
