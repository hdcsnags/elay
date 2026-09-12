# sol — codex_cli (gpt-5.6-sol) — 2026-09-12-stage2-timelock

- success: true
- elapsed: 151s
- read-only verification: **clean**
- copy: C:\New folder\claw-scratch\elay-sol-F1WDK1\repo


## Output

Model: GPT-5 (Codex)

## §A — Stage 2 time-lock contract

This contract follows the product rule that UTC is canonical while IANA zones preserve intent ([ELAY-SPEC.md §4](</C:/New folder/claw-scratch/elay-sol-F1WDK1/repo/ELAY-SPEC.md:158>)), the amended one-to-three-candidate rule and atomic winner selection ([ADR-010](</C:/New folder/claw-scratch/elay-sol-F1WDK1/repo/adr/ADR-010-commitments-delivery.md:8>)), and the existing receipt, wire-format, RLS, private-Broadcast, and generated-topic conventions ([phase1-planner.md](</C:/New folder/claw-scratch/elay-sol-F1WDK1/repo/contracts/phase1-planner.md:19>), [stage1-pairing.md](</C:/New folder/claw-scratch/elay-sol-F1WDK1/repo/contracts/stage1-pairing.md:10>)).

### 1. Schema and authorization

`time_lock_proposals`:

- `id uuid primary key default gen_random_uuid()`
- `pair_id uuid not null references pairs(id)`
- `creator_id uuid not null references auth.users(id)`
- `title text not null check (char_length(title) between 1 and 200)`
- `status text not null check in ('draft','proposed','accepted','countered','declined','expired','cancelled','completed')`
- `response_deadline timestamptz not null`
- `origin_tz text not null`, validated against `pg_timezone_names` using the existing trigger pattern
- `current_revision int not null default 1 check (current_revision between 1 and 20)`
- nullable `accepted_revision int`, `accepted_candidate_idx smallint`
- `version bigint not null default 1 check (version > 0)`
- `created_at`, `updated_at`, and nullable transition timestamps.

Checks require both accepted fields exactly when status is `accepted` or `completed`; declined/expired/cancelled cannot have a winner; deadline must be after creation. `draft` is retained because §4 names it, but `rpc_create_proposal` sends immediately as `proposed`; no Stage-2 draft RPC is exposed.

`proposal_revisions`:

- `(proposal_id, revision_no)` primary key
- `author_id`, `origin_tz`, `candidates jsonb`, `created_at`
- candidate JSON is an ordered array of one to three exact objects: `candidate_idx`, `starts_at_utc`, `ends_at_utc`, `duration_min`.
- An immutable validation function enforces contiguous zero-based indexes, valid timestamps, `end > start`, duration 1–1,440 minutes, and equality between `duration_min` and the instant interval.
- A trigger rejects UPDATE and DELETE. Proposal deletion is also unavailable, preserving the specification’s instruction that counters “do not overwrite the original” ([ELAY-SPEC.md](</C:/New folder/claw-scratch/elay-sol-F1WDK1/repo/ELAY-SPEC.md:214>)).

`proposal_responses`:

- `id`, `proposal_id`, `revision_no`, `user_id`
- `response text check in ('accept','decline','counter')`
- nullable `candidate_idx`, nullable `counter_revision`, `responded_at`
- unique `(proposal_id, revision_no, user_id)`
- accept requires only `candidate_idx`; decline requires neither pointer; counter requires only `counter_revision = revision_no + 1`.

Only the non-author member may respond to the current revision. A counter response and its new revision are inserted in the same transaction.

`commitments`:

- `id`, `proposal_id`, `user_id`
- `state text check in ('active','withdrawn','completed')`
- `created_from_revision`, `candidate_idx`
- `time_block_id uuid not null unique references time_blocks(id)`
- `version`, `created_at`, `updated_at`, nullable `withdrawn_at/completed_at`
- unique `(proposal_id,user_id)`.

**Block model: per-member rows.** Acceptance creates one `time_blocks` row for each active pair member. Each has that member as `owner_id`, identical winning UTC instants and `origin_tz`, `type='shared_lock'`, `status='scheduled'`, and a unique `(source_proposal_id,owner_id)` link. These rows remain `visibility='private', pair_id=null`; the proposal is the shared object. This preserves the shipped constraint that private blocks have no pair ID ([migration](</C:/New folder/claw-scratch/elay-sol-F1WDK1/repo/supabase/migrations/20260912010000_stage1_pairing.sql:365>)), retains owner-only base RLS, prevents duplicate peer projections, and implements ADR-010’s “each person’s own stake” independence. Add `shared_lock` to the time-block type CHECK and nullable `source_proposal_id/source_revision`.

Indexes cover `(pair_id,status,response_deadline)`, `(pair_id,updated_at desc)`, `(proposal_id,revision_no)`, response lookup, `(user_id,state)`, and scheduled block overlap `(owner_id,starts_at_utc,ends_at_utc)`.

Enable RLS everywhere; revoke all from `anon` and `authenticated` before minimal grants. Active pair members may SELECT proposals, revisions, and responses for their own pair. No direct client writes exist. Commitments and `time_blocks` remain owner-readable only; peer state is returned through proposal projections. All membership checks derive from `auth.uid()` and active `pair_members`, never a supplied pair or owner. Former members immediately lose all proposal projections and Realtime access.

### 2. RPCs and receipts

All mutation RPCs are `security definer set search_path=''`, authenticate explicitly, revoke PUBLIC, grant only `authenticated`, lock the proposal `FOR UPDATE`, and use the existing `(owner_id,operation_id)` receipt. Results contain an `action`; replay with another action raises `22023`, matching the shipped cross-RPC guard. Wire keys are snake_case and instants ISO-8601 UTC ([phase1 contract](</C:/New folder/claw-scratch/elay-sol-F1WDK1/repo/contracts/phase1-planner.md:33>)).

`rpc_create_proposal(operation_id,title,origin_tz,response_deadline,candidates)`:

- Derive the caller’s active, two-member pair.
- Require deadline between five minutes and seven days ahead.
- Validate one-to-three candidates.
- Insert proposal as `proposed`, revision 1 authored by caller, receipt, and sanitized created event atomically.
- Return `{"outcome":"applied","action":"create_proposal","proposal":…}`.

`rpc_respond_proposal(operation_id,proposal_id,expected_revision,response,candidate_idx,new_origin_tz,new_deadline,new_candidates)`:

- Before mutation, expire an active proposal when `now() >= response_deadline`.
- Require `expected_revision=current_revision`, status `proposed|countered`, active membership, and caller != current revision author.
- **Accept:** validate the chosen index, insert the response, two owner-private blocks, and two commitments; set winner fields/status/version in the same transaction. Unique commitment and source-block indexes backstop a double-accept race.
- **Decline:** insert response and set `declined`.
- **Counter:** require revision < 20, validate new candidates/deadline, insert counter response plus immutable revision `n+1`, set `current_revision=n+1`, `status='countered'`, replace the response deadline, and bump version. It is “open” because only the new revision’s non-author may answer.

Stale revisions return a non-mutating `{"outcome":"conflict","current_revision":n,"status":…}` rather than raising.

`rpc_cancel_proposal(operation_id,proposal_id)` permits only the creator and only `proposed|countered`; it performs the deadline check, then transitions to `cancelled`. Accepted locks are not retroactively cancelled through this RPC.

`rpc_complete_lock(operation_id,proposal_id)` permits an owner of an active commitment once the winning interval has begun. It completes only that caller’s commitment and block. When both commitments are completed, it also transitions the proposal to `completed`; thus one person’s state remains independent.

`fn_expire_proposals(batch_size default 500)` is service/test callable, not authenticated-client executable. It uses `FOR UPDATE SKIP LOCKED`, transitions overdue `proposed|countered` rows to `expired`, increments versions, emits updates, and returns affected IDs. Every mutation RPC repeats the deadline check, so sweep scheduling is not a correctness dependency.

`rpc_proposal_conflict_hints(candidates)` derives the other active member and intersects candidates only with that responder’s scheduled ELAY blocks. Return exact keys `{candidate_idx,has_conflict,busy_windows:[{starts_at_utc,ends_at_utc}]}`; intervals are clipped to the candidate window and contain no block ID, title, type, task, or provenance. This is the ADR-007 busy-only projection—“conflict messages may say ‘busy’, never why” ([ADR-007](</C:/New folder/claw-scratch/elay-sol-F1WDK1/repo/adr/ADR-007-visibility.md:9>)). No external calendar data participates.

Read projections: `rpc_list_proposals(scope,cursor)` and `rpc_get_proposal(id)` return participant-safe active/history snapshots, revisions, responses, and caller-owned commitment/block IDs. They never expose the other member’s private block row.

### 3. Realtime

Publish on the existing private `pair:<uuid>:<channel_generation>` topic; Stage 1 already reserves these event names and forbids planner rows over Broadcast ([stage1 contract](</C:/New folder/claw-scratch/elay-sol-F1WDK1/repo/contracts/stage1-pairing.md:11>)):

- `pair.proposal_created.v1`: `{pair_id,proposal_id,status,current_revision,version}`
- `pair.proposal_updated.v1`: `{pair_id,proposal_id,status,current_revision,version}`
- `pair.commitment_changed.v1`: `{pair_id,proposal_id,commitment_id,user_id,state,version}`

No title, candidate instant, conflict, response detail, or block content is broadcast. Events are invalidation hints; clients refetch authoritative projections.

### 4. Frozen Kotlin surface

```kotlin
interface ProposalRepository : AutoCloseable {
    fun observeActive(): Flow<List<ProposalSummary>>
    fun observeHistory(): Flow<List<ProposalSummary>>
    suspend fun create(command: CreateProposal): ProposalResult
    suspend fun respond(command: RespondProposal): ProposalResult
    suspend fun cancel(operationId: String, proposalId: ProposalId): ProposalResult
    suspend fun complete(operationId: String, proposalId: ProposalId): ProposalResult
}
```

Freeze `Candidate(index, startsAt: Instant, endsAt: Instant, durationMinutes)`, `ProposalRevision`, `ProposalResponse`, `Commitment`, and `ProposalState` matching all eight server strings. `CreateProposal` and `Counter` carry `originZoneId` plus one-to-three candidates. Rendering receives `Instant`, `originZoneId`, `viewerZoneId`, and `otherMemberZoneId`; the server returns no formatted time or fixed offset.

### 5. Mandatory adversarial pgTAP

Cover anonymous and non-participant denial; guessed proposal and cross-pair IDs; former-member denial; direct writes; invalid zone/deadline/candidate count/duration/index; immutable history; stale revision; revision monotonicity and the 20-revision bound; response by revision author; double-accept concurrency with exactly one winner, two blocks, and two commitments; accept after expiry/cancel/decline; cancel by non-creator; sweep idempotence; complete-one versus complete-both; and every RPC’s exact receipt replay plus cross-action operation-ID rejection.

DST fixtures must place candidates **at** Vancouver/Toronto transitions: spring gap uses ADR-006’s first-valid-instant policy and preserved duration; fall fold selects the first occurrence/earlier offset ([ADR-006](</C:/New folder/claw-scratch/elay-sol-F1WDK1/repo/adr/ADR-006-time-storage.md:9>)). Assert UTC winner equality, both member block instants, dual zone IDs, day crossover, and no hard-coded-offset SQL/Kotlin grep. Finally, prove changing or later withdrawing one member’s calendar-delivery preference cannot mutate the proposal, the other commitment, or either ELAY block.

### 6. Seat slicing and guardrails

- **A3 SQL:** Stage-2 migration, pgTAP, SQL fixtures; owns all schema/RPC/Realtime work.
- **B4 client data:** proposal domain models/interface, DTOs, Supabase repository, serialization/reconnect tests.
- **C3 UI:** `ui/together/proposal/`, composer integration under Plan, and UI tests; consumes only the frozen repository.
- **Lead:** DI/user-scope lifetime, navigation wiring, fixture integration, two-session emulator run, full gate and CI.

Likeliest failures:

1. A seat implements acceptance as client fan-out or creates only one shared block. Guardrail: one locked RPC transaction plus assertions for exactly two owner rows and two commitments.
2. Revision/status logic silently accepts stale responses or overwrites candidates. Guardrail: append-only trigger, expected-revision comparison under row lock, and concurrency tests.
3. Candidate details or private block metadata leak through RLS/Realtime/conflict hints. Guardrail: exact-key projection tests, owner-only base rows, private Broadcast payload fixtures, and deny tests for every attacker role.

BEGIN VERDICT
{"lane":"contract","block_model":"per_member_rows","expiry":"rpc_checks_plus_sweep","sections_ready":true,"seat_slices":["A3: Stage-2 SQL migration, pgTAP, SQL fixtures","B4: proposal domain, DTOs, repository and serialization/realtime tests","C3: proposal composer/cards UI and UI tests","Lead: DI, navigation, integration and gate"],"failure_modes":["Non-atomic acceptance or one shared block; require one locked transaction and exactly two owner rows plus commitments","Stale response or overwritten revision history; require row lock, expected_revision and append-only enforcement","Private fields leaked through projections, conflict hints or Broadcast; require exact-key tests and metadata-only events"],"confidence":0.96}
END VERDICT