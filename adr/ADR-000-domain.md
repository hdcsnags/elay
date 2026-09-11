# ADR-000 — The ELAY domain, stated once, without contradiction

**Status:** accepted (concierge, 2026-09-11) · This is Gate 0b's exit test made durable (spec §11 Phase 0): the domain model, ownership rules, timezone rule, and the AI boundary in one page. If code or a later ADR contradicts this page, stop and reconcile.

## Vocabulary (spec §1–§4, §7)
- **Capture** — raw unsorted input (text/voice), owned by its author, private until clarified. Structure comes later, never demanded at entry.
- **Goal → Milestone → Task** — the outcome hierarchy. A task is actionable work with effort/duration/priority/due-window.
- **Time block** — canonical scheduled time (personal or shared), the thing calendars render.
- **Time-lock proposal** — a *negotiated* shared block: draft → proposed → accepted/declined/countered/expired/cancelled → completed. Counters create revisions (one alternate time per revision, ADR-010); history is append-only.
- **Commitment** — one accepted participant's stake in a proposal; per-participant and independently withdrawable (ADR-010).
- **Household** — an explicitly joined collaboration circle. Membership grants the *possibility* of sharing, never sharing itself.
- **Availability rule** — shareable busy/quiet/sleep windows, visibility-controlled like any object.
- **Visibility** — per-object: `private` / `busy_only` / `title_only` / `full`.

## Ownership rules (§7–§8)
Every shareable object has exactly one **owner** and an optional household attachment plus explicit visibility. **Sharing is never inferred from co-membership.** Peers reach shared objects only through visibility-aware projections — row access (RLS) AND field redaction (ADR-007). Delegated rights (e.g., completing someone's task) are explicit grants, not side effects. A removed member loses server access immediately; cached copies are purged on next contact and bounded by design (ADR-009). The client is untrusted; the database is the boundary.

## Timezone rule (§4, ADR-006)
Shared truth is a **UTC instant pair + the creator's IANA zone**. Viewer zones are presentation. Recurrence is the origin-zone wall-clock rule; DST gap → shift forward, fold → first occurrence; all-day events are dates, not midnight instants. No fixed offsets, anywhere, ever. One expansion implementation in `shared/` serves client and server.

## AI boundary (§10, ADR-008)
AI is a **suggester behind a server-side gateway**: purpose-scoped, redacted input, schema-validated JSON out, versioned proposal envelopes. Humans commit through deterministic, authenticated commands that revalidate permissions/conflicts/versions at commit time. AI is never authoritative about permissions, membership, time conversion, conflicts, or whether anything committed. Core planner works with AI off.

## Consistency check (why these don't collide)
Negotiation state lives server-side and transitions only via transactional RPCs (ADR-002/003) — so offline devices can *stage* intent (outbox, pending-rendered) without forking truth; visibility is enforced at read time by projections — so realtime and REST can't disagree; recurrence expands from one implementation — so preview and delivery jobs agree; commitments are per-participant — so one person's withdrawal or calendar disconnect never silently cancels a group lock.
