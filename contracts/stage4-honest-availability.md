# Stage 4 contract — honest availability (FROZEN 2026-09-12)

*The contract lane's §A (Opus seat, conf 0.9 — full text in the lane report reproduced below in `council/stage4-availability-opus.md`) and Gemini's §B (conf 0.98 — `council/stage4-availability-gemini.md`) are binding. House rules from all prior stage contracts apply. Google OAuth client does not exist yet (PING): everything ships behind the provider seam; the Google adapter is SPECIFIED but DEFERRED — nothing in this stage may require it to build, test, or E2E.*

## Adopted (highlights; the lane docs are the full text)
- **Separate `external_busy` table** (never a `time_blocks` type): owner-only SELECT, ZERO write grants (all writes via SECURITY DEFINER RPCs), `external_ref_hash` for dedupe never returned by any RPC, plus `availability_sources` (per-source freshness/window bookkeeping, `freshness_ttl_minutes`: manual=43200, google=360).
- **Windowed snapshot sync** `[now−1d, now+35d]`; on-demand third-party reads in user paths are forbidden (latency hostage + ships candidate times to the provider).
- **Certainty ladder, server-computed only**: `busy` (any overlap, fresh OR stale — busy always wins) · `free_per_elay` (no sources) · `free_per_calendar` (fresh + window-covering source, no overlap) · `unknown` (stale or non-covering). Staleness may only downgrade free→unknown, never erase a busy.
- **`rpc_proposal_conflict_hints` extended additively**: partner's external_busy unioned into the SAME clipped `busy_windows` with **coalescing of overlapping/adjacent intervals before return** (interval shape is a "why" — provenance masked by union), plus exactly one new key `certainty`. New `rpc_self_conflict_hints` returns the identical shape for the caller. Peer-directed responses never carry sync timestamps/source tags; staleness copy reads the caller's OWN `rpc_my_availability_sources()`.
- **Manual provider ships as UI** (one sheet, §B) via `rpc_upsert_external_busy`/`rpc_delete_external_busy` — `source_tag='manual'` hard-coded server-side (client-supplied tags forbidden: users could fabricate the provenance `free_per_calendar` trusts); receipts + cross-action 22023 per house ledger.
- **No new realtime events** (a busy feed = live read of the peer's calendar); clients refetch hints on composer/responder open. Hints RPC gains the Stage-3 attempts pattern (60/15min per caller; over-limit returns the opaque `certainty='unknown'` shape).
- Google adapter (deferred): tokens in Supabase Vault via `calendar_connections.token_ref` — never in repo/receipts/logs/fixtures/broadcasts; free-busy-only scopes; incremental sync feeding `fn_sync_external_busy`; webhooks are refresh triggers, never authorization.
- **Gemini's §B is C6's design source of truth**: certainty labels at ProposalComposerSheet/ProposalFeed/Web RSVP/Plan (calm, never red), capacity gauge lives in Plan's top rail below the day switcher, manual "I'm busy then" sheet, staleness copy without jargon/guilt, a11y notes.

## Lead amendments (binding)
1. **Web RSVP surface (§B lists it) is DEFERRED to a later pass**: the RSVP page renders certainty only when the hints shape reaches the edge function — not this stage's scope; C6 builds the three app surfaces only.
2. pgTAP suite number: `0022_stage4_availability_test.sql`; migration `20260912200000_stage4_honest_availability.sql` (A6's grant).
3. The three §A failure-mode guardrails are binding as written (the "forbidden BECAUSE … remains required" forms).
4. `fn_expire_proposals`-style grant discipline everywhere; helper functions revoked from `public, anon, authenticated` explicitly (Stage-3 F5 lesson); comments must not claim service_role gets zero rows (Stage-3 lesson — its rolbypassrls).

## Seat grants (disjoint)
| Seat | Paths |
|---|---|
| A6 (SQL) | `supabase/migrations/20260912200000_stage4_honest_availability.sql`, `supabase/tests/0022_stage4_availability_test.sql`, `contracts/fixtures/availability-*.json` |
| B7 (client data, dispatched with A6; lead diffs at merge) | `shared/src/commonMain/kotlin/dev/elay/domain/availability/**` (new), `data/remote/dto/AvailabilityDtos.kt`, `data/remote/impl/SupabaseAvailabilityRepository.kt` (new), matching commonTest |
| C6 (UI, after B7) | certainty labels at composer/responder call-sites (`ui/together/proposal/**`), the manual busy sheet + capacity gauge (`ui/plan/**`), matching commonTest |
| Lead | contract freeze, DI/user-scope, fixture diff, merges, emulator E2E, gate + CI + pre-gate verification |
