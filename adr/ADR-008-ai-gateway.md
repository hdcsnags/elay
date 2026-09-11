# ADR-008 — AI gateway boundary: server-side, purpose-scoped, AI proposes / humans commit

**Status:** accepted (spec §10 contractual)

**Decision.** The app never calls a model vendor. An authenticated Supabase Edge Function ("planning gateway") accepts a narrow purpose enum (`parse_dump`, `split_task`, `suggest_schedule`, `explain_conflict`, `weekly_review`) + schema-validated payload; strips fields the purpose doesn't need; holds the provider key server-side; enforces max input/output, model allow-list, per-user quota, timeout, spend ceiling; returns JSON conforming to a versioned schema — invalid output is rejected/retried, prose never mutates the database.

**The human gate.** AI output is untrusted structured *suggestion*; deterministic app logic + explicit user confirmation perform every real change (§10 rule of thumb: AI is never authoritative about permissions, membership, timezone conversion, conflicts, or whether an action committed). Retrieved user text is data, never instructions (§12 Phase 5, prompt-injection rule).

**Kill switch & telemetry.** Per-feature/provider flags; core planner runs fully with AI off (§14 rule 9). Log purpose/latency/cost metadata, not raw private prompts.
