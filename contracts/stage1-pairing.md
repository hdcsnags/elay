# Stage 1 contract — pairing + realtime (FROZEN 2026-09-12)

*Authored by Sol (contracts lane), reviewed and amended by the lead. The full prose with rationale: Maestro `docs/audits/elay/2026-09-12-stage1-pairing-contract/sol.md` — **that document's §1–§6 are binding** as if inlined here. This file records the lead's amendments and the seat grants. House rules from `contracts/phase1-planner.md` (wire format, receipts, verification, path discipline) apply throughout.*

## Adopted verbatim from Sol's contract
- Dedicated `pairs` / `pair_members` / `pair_invites` schema with `channel_generation`, partial unique indexes (one active pair per user; two-member cap), member-cap trigger as defense-in-depth.
- Invite codes: 10-char Crockford Base32 (`XXXXX-XXXXX`), SHA-256 stored, **HMAC-derived from a migration-generated private key** so replay reconstructs the code without persisting it; uniform `invalid_or_unavailable` responses; rate-limited redemption.
- One atomic migration: rename `household_id`→`pair_id` on goals/tasks/time_blocks, drop the three `*_phase1_private_only` guards, new CHECK `(visibility='private' and pair_id is null) or (visibility<>'private' and pair_id is not null)`, owner WITH CHECK binds non-private rows to the owner's active pair.
- Peer reads ONLY via `security definer set search_path=''` projection RPCs (`rpc_list_pair_shared_{goals,tasks,time_blocks}`) with exact-key JSON per visibility tier; milestones only under a `full` goal; captures never.
- RPCs `rpc_create_pair_invite` / `rpc_redeem_pair_invite` / `rpc_leave_pair`: advisory + row locks, receipts with an `action` field (cross-RPC replay rejection), version bumps, generation rotation on membership change.
- Realtime: private Broadcast only, topic `pair:<uuid>:<generation>`, `realtime.messages` SELECT policy tied to active membership + current generation, trigger-published events only (`pair.member_joined.v1`, `pair.member_left.v1`; proposal/commitment names reserved), no planner rows or codes over the wire.
- Frozen Kotlin surface: `PairRepository` (observePair/createInvite/redeemInvite/leave), `PairState = Loading|Unpaired|Inviting|Paired|Failed`, DTOs as listed; Together renders pairing lifecycle ONLY in Stage 1.
- The full §6 adversarial pgTAP list is mandatory, including exact-key projection assertions and the old-topic/new-topic rotation proof.

## Lead amendments (binding)
1. **Fixture/DTO coupling from the rename** (missed in the slicing): A2 ALSO updates `contracts/fixtures/{goal,task,time_block}.json` (`household_id`→`pair_id`); B3 receives an explicit grant to update the matching `@SerialName` in the five existing planner DTOs (`data/remote/dto/` — field renames ONLY, nothing else). The lead verifies fixture round-trips at merge.
2. **Local realtime config**: A2 verifies private-channel broadcast works on the local stack (`supabase/config.toml` realtime settings) and documents any required config delta in its report — do not assume hosted-default behavior.
3. Client `subscribe(private = true)` is mandatory in B3's implementation; a public-channel subscribe is a review-fail.
4. Room caching of pair state is NOT in Stage 1 (PairRepository is remote+memory via `rpc_get_pair`; offline pair state arrives with Stage 2's outbox integration) — keeps B3 disjoint from data/local.

## Seat grants (disjoint; violating = round fails)
| Seat | Paths |
|---|---|
| A2 (SQL) | `supabase/migrations/20260912*_stage1_pairing.sql`, `supabase/tests/0012_*.sql`, `0013_*.sql`, `contracts/fixtures/pair-*.json` + the three renamed planner fixtures |
| B3 (client data) | `domain/model/PairModels.kt`, `domain/repository/PairRepository.kt`, `data/remote/dto/PairDtos.kt` (+ @SerialName renames in existing planner DTOs per amendment 1), `data/remote/impl/SupabasePairRepository.kt`, matching commonTest |
| C2 (UI, after B3) | `ui/together/`, `ui/goal/`, `ui/task/`, matching commonTest, the NavHost lambdas for those routes in App.kt |
| Lead | DI/user-scope PairRepository ownership, navigation, integration fixtures, gate + emulator E2E |
