# ADR-007 — Visibility model: explicit per-object, private by default, DB-enforced

**Status:** accepted (spec §7–§8 contractual)

**Decision.** Shareable objects (goals, tasks, time blocks, availability) carry an explicit `visibility` enum — `private` / `busy_only` / `title_only` / `full` — plus an explicit owner and (nullable) household relationship. **Sharing is never inferred from co-membership** (§7 schema rule). Enforcement is Postgres RLS + grants; the client UI is a convenience layer, not the boundary (§8). Realtime channels obey the same authorization — no presence/broadcast backdoor (§6 trust boundary 4).

**Row authorization is not field redaction (Astra, 2026-09-11).** RLS decides which ROWS a peer gets; §7's modes also constrain which FIELDS. A `busy_only` row passed by RLS still carries its title unless the read path is a **safe projection**: peers read shared objects only through visibility-aware views/RPCs (or split private-detail tables); no peer-reachable path selects base-table columns directly. Tests assert returned FIELD SETS per visibility mode, and denied updates (incl. delegated-completion edge cases), not just row counts.

**Implications.** `busy_only` exposes a time range only — conflict messages may say "busy", never why. The creator of a proposal never sees a participant's private conflicting event details. Every new table/channel ships with paired allow/deny pgTAP tests for: owner, household peer, former member, anonymous, attacker-shaped (guessed UUIDs, tampered household ids). Former-member revocation is immediate (Gate 2 test).

**Open §16 sub-questions** (concierge to rule during Phase 2 with a council round if the answer shapes the schema): busy-only provenance masking, per-participant calendar write-back independence.
