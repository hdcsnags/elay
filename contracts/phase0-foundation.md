# Phase 0 foundation contract — frozen before scaffold (Astra sequencing, 2026-09-11)

*Coder seats: this file + the ADRs are the interface. If a task seems to require breaking it, stop and report — do not improvise.*

## Modules & packages
- `shared/` — KMP library: Compose Multiplatform UI + domain model, time-lock state machine, timezone/recurrence engine, Room 3 database + outbox, supabase-kt behind `AuthGateway`/`DataGateway`/`RealtimeGateway` interfaces. Targets: android (AGP `androidLibrary` KMP target with host tests enabled), `iosArm64`, `iosSimulatorArm64`. *(Corrected 2026-09-11 after scaffold: the current JetBrains template layout is `androidApp` + `shared` + `iosApp` — Astra's module-separation point confirmed in practice; UI lives in `shared`.)*
- `androidApp/` — thin Android application module (id `dev.elay.app`; compileSdk 37.0 via `compileSdkMinor`, shared compiles against 36 — AGP 9.1 KMP DSL lacks `compileSdkMinor`).
- `iosApp/` — Xcode shell (compiles only on macOS CI / a Mac).
- Package root: `dev.elay` → `dev.elay.domain`, `dev.elay.data`, `dev.elay.sync`, `dev.elay.ui.<surface>`.
- `supabase/` — migrations, `functions/` (AI gateway etc.), `tests/` (pgTAP).

## Environments
`local` (Docker Supabase via CLI — the default for all phases; keys in `.env`/`local.properties`, never committed) · `dev` (hosted Supabase, only if multi-device testing demands it — PING item) · `prod` (does not exist; the app does not ship).

## Route names (typed contracts in `shared/`, ADR-004)
`today` · `plan` · `goals` · `goal/{id}` · `task/{id}` · `inbox` · `together` · `proposal/{id}` · `review` · `settings`. Deep links: `elay://proposal/{id}` first (Phase 3 notifications land there).

## Mutation/outbox contract (ADR-003, ADR-010)
Client mutations: Room outbox rows `(operation_id uuid, aggregate, expected_version, type, payload_json, status, attempts, created_at)`. Replay is sequential, idempotent by `operation_id`. Time-lock operations call exactly one transactional RPC each (`rpc_propose`, `rpc_respond`, `rpc_cancel`, `rpc_complete`); server validates transition + conflicts, appends revision, bumps version. Offline optimism renders **pending**, never terminal.

## Read contract (ADR-007)
Peer reads of shared objects go through visibility-aware views/RPCs only. Base tables: owner-only RLS. Tests assert field sets, not just row counts.

## Rules for coder seats (non-negotiable)
1. Touch only the paths your dispatch names. No two seats share a file.
2. **Never edit** `gradle/libs.versions.toml`, `settings.gradle.kts`, root build files, `.github/`, `.gitignore`, or this contract — concierge-owned.
3. Migrations ship WITH their grants + RLS + paired allow/deny pgTAP tests (spec §14 rule 2).
4. All time math through the `shared/` timezone engine (kotlinx-datetime); a hard-coded offset fails review automatically.
5. Green means: the gate commands in `/build-gate` pass on a clean clone, with nonzero executed tests.
6. Report format: what you built · what you verified (commands + output) · what you could not verify and why.

## Domain vocabulary
See `adr/ADR-000-domain.md` — the one-page domain statement. It wins ties.
