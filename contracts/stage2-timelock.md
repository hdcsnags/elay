# Stage 2 contract — time-lock negotiation (FROZEN 2026-09-12)

*Sol's §A (contract, conf 0.96) and Gemini's §B (UX spec, conf 0.98) are binding as written — in-repo copies: `council/stage2-timelock-sol.md` and `council/stage2-timelock-gemini.md`. House rules from phase1/stage1 contracts apply. This file records lead amendments and seat grants.*

## Adopted (highlights; the lane docs are the full text)
- **Block model: per-member rows** — acceptance atomically creates one `visibility='private', pair_id=null` time_block per member (`type='shared_lock'`, `source_proposal_id/…_revision` links, unique `(source_proposal_id, owner_id)`), two commitments, winner fields — ONE transaction. The proposal is the shared object; base RLS untouched. Accepted locks therefore appear in both members' Today/Plan through the existing pipeline automatically.
- Append-only `proposal_revisions` (1–3 candidates validated by an immutable function; UPDATE/DELETE-rejecting trigger), `proposal_responses` unique per (proposal, revision, user), revision cap 20, counter = response + revision n+1 + deadline replacement in one transaction.
- Deadline checks INSIDE every mutation RPC (expire-before-mutate) + `fn_expire_proposals` sweep (`SKIP LOCKED`, service/test-only) — sweep is not a correctness dependency.
- `rpc_proposal_conflict_hints`: busy-only, clipped windows, exact keys, responder's own ELAY blocks only.
- Realtime: existing pair topic; sanitized id/status/version payloads only.
- Gemini's §B is the C3 seat's design source of truth: dual-time rule (viewer time primary SemiBold + partner secondary + relative-day badge + plain-language DST caption), candidate chips with per-candidate dual-time, calm copy per state, a11y rules (48dp, polite live regions, "Selected: Option 1 of 3" vocalization).

## Lead amendments (binding)
1. **Frozen-enum change, lead-authorized**: `BlockType` in `domain/model/PlannerModels.kt` gains `SharedLock("shared_lock")` — B4's grant includes exactly this addition (and the type CHECK update is A3's). C1's existing Plan/Today render these rows like any block; C3 adds the dual-time visual marker for `type == SharedLock` per §B's rule.
2. Wire fixtures: A3 produces byte-verified fixtures from real RPC output (`contracts/fixtures/proposal-*.json`) — **B4 must round-trip A3's actual fixture FILES** (both seats dispatch together, so B4 embeds from the contract initially and the LEAD diffs both at merge — the Stage-1 lesson; any mismatch is fixed on B4's side unless the SQL deviates from this contract).
3. The `profiles` lesson: any projection joining profiles uses LEFT JOIN + 'Someone' fallback (house rule as of `20260912120000`).
4. `fn_expire_proposals`: also revoke from `authenticated` explicitly (service_role/tests only).

## Seat grants (disjoint)
| Seat | Paths |
|---|---|
| A3 (SQL) | `supabase/migrations/20260912*_stage2_timelock.sql`, `supabase/tests/0015_*.sql`+`0016_*.sql` (DST suite separate), `contracts/fixtures/proposal-*.json` |
| B4 (client data) | `domain/model/ProposalModels.kt`, `domain/model/PlannerModels.kt` (amendment 1 ONLY), `domain/repository/ProposalRepository.kt`, `data/remote/dto/ProposalDtos.kt`, `data/remote/impl/SupabaseProposalRepository.kt`, matching commonTest |
| C3 (UI, after B4) | `ui/together/proposal/**`, the Together feed integration (Together screen section for proposals), Plan overlay hooks per §B, `ui/plan/**` (shared_lock marker only), matching commonTest |
| Lead | DI/user-scope, navigation, fixture diff, dual-session emulator E2E (incl. live realtime observation — the Stage-1 deferral), gate + CI + Astra pre-gate verification |
