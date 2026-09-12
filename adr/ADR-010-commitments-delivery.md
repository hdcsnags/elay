# ADR-010 — Per-participant commitments and durable delivery jobs

**Status:** accepted (concierge, 2026-09-11, adopting Astra's finding; rules two §16 open product questions under Michael's autonomy grant)

**The gap.** §4 says acceptance "creates a commitment record" but §7's schema has no commitment relation, and §16 left unresolved whether commitments are per-participant. Without a defined delivery model, a calendar write-back timeout after creation makes retries duplicate events, and one participant's disconnect could cancel the group's lock.

**Contract.**
1. **`commitments` table** (schema addition to §7): one row per accepted participant per proposal — `proposal_id`, `user_id`, `state` (active/withdrawn/completed), `created_from_revision`, timestamps. The group lock's life is the proposal + participant responses; a commitment is each person's own stake in it.
2. **§16 ruling — independence:** commitments are per-participant. One participant disabling calendar write-back (or leaving) withdraws *their* commitment and notifies others; it does not cancel the group event unless the withdrawal breaks the proposal's required-attendee threshold, which is an explicit state transition with notification.
3. **§16 ruling — counters (AMENDED 2026-09-12, product round unanimous):** a proposal or counter carries **one to three candidate times**; acceptance selects exactly one winner atomically in the same transaction (losers recorded in the revision history). Sol's finding: single-candidate serial counters create negotiation ping-pong that loses to chat. Revision history stays append-only and auditable.
4. **Durable delivery:** side effects (calendar write-back, push notifications) are **server jobs** in a `delivery_jobs` table — unique operation identity (proposal id + revision + user id + kind), at-least-once execution with idempotency, retry with backoff, dead-letter after N attempts. Jobs **re-check authorization at execution time** (connection may be revoked between enqueue and run).
5. **No client-side fan-out:** the accepting client performs one RPC; everything downstream is server-owned. A client timeout after the RPC cannot duplicate events because the job identity is unique.
