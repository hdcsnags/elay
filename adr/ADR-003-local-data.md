# ADR-003 — Local data: Room 3 + append-only mutation outbox

**Status:** accepted (Michael, 2026-09-11: "ship room" — resolving the council's one split; Sol argued SQLDelight)

**Decision.** `androidx.room3` (KSP, `sqlite-bundled` for identical SQLite on Android/iOS) holds the local cache (Today/Inbox/recent planner state) and an **append-only outbox**: `operation_id` (client UUID, idempotency key), aggregate id, `expected_version`, type enum, payload JSON, status (`PENDING/IN_FLIGHT/ACKNOWLEDGED/CONFLICT`), attempts, timestamps.

**Sync shape (both seats converged).** Mutations replay sequentially to Supabase RPCs. Simple field edits merge automatically; time-lock operations go to one transactional server RPC that validates state transition + conflicts, appends a revision, bumps the version, returns authoritative state. An offline "accept" renders as **pending**, never "accepted", until acknowledged (§6 offline strategy).

**Why Room over SQLDelight.** First-party Google KMP persistence, GA 2026-07-01; Michael ships Room today (review fluency); bundled sqlite removes OS-version divergence. Sol's maturity concern is real but bounded.

**Revisit trigger (written before the schema grows).** If Room/KSP breaks the iOS-simulator compile more than once in Phase 0–1, switch to SQLDelight immediately.
