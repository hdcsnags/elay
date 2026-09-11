# ADR-002 — Backend: managed Supabase via supabase-kt, behind ELAY-owned interfaces

**Status:** accepted (council converged Sol + Gemini; concierge adopted under the 2026-09-11 autonomy grant)

**Decision.** Postgres + RLS + Realtime + Edge Functions on managed Supabase (local Docker stack until a hosted project is warranted — see `PING-MICHAEL.md`). Client access through **supabase-kt, version-pinned**, and every SDK touchpoint wrapped in ELAY-owned `AuthGateway` / `DataGateway` / `RealtimeGateway` interfaces in `shared/`.

**Why.** The time-lock state machine (§4: eight states, revisions, participant responses) and the four-tier visibility model (§7) are relational, transactional work: Postgres can atomically validate membership + expected version + legal transition + conflicts in one RPC. RLS enforces the §6/§8 trust boundary at the database; Edge Functions give the §10 AI gateway. Firebase via GitLive wraps native SDKs (brittle iOS linking) and distributes invariants across documents/Rules/functions.

**Named risk.** supabase-kt is de facto single-maintainer (jan-tennert; verified 2026-09-11, `research/supabase-kt-and-ios-2026-09.md`). Mitigation IS the thin-adapter rule above: replacing the SDK must never touch domain code.

**Consequences.** Migrations-first workflow (`supabase/migrations`); every migration ships grants + RLS + paired positive/negative pgTAP tests; time-lock transitions are server-side transactional RPCs only.
