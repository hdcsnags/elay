# sol — codex_cli (gpt-5.6-sol) — 2026-09-12-stage1-pairing-contract

- success: true
- elapsed: 317s
- read-only verification: **clean**
- copy: C:\New folder\claw-scratch\elay-sol-ALQfYH\repo


## Output

Model: GPT-5

# Stage 1 contract — recurring-pair membership + Realtime

Binding sources: `council/master-plan-v2.md` restates ELAY as a “private coordination app for a recurring pair” and cuts groups, quorum mechanics, and multiple households. `ELAY-SPEC.md` §7 requires an explicit owner/workspace relationship and says, “Do not infer sharing merely because two people belong to the same household.” Section 8 makes backend authorization authoritative; §2 assigns pair status, proposals, commitments, and activity to Together. ADR-007 requires field-safe projections, ADR-009 requires immediate server revocation plus client cache purge, and ADR-010 reserves per-participant commitments for Stage 2.

## 1. Schema and authorization

Commit to dedicated `pairs`, not households capped at two. A capped household retains misleading group concepts—roles, administration, future quorum—and invites agents to implement the superseded product. Renaming the three existing `household_id` columns costs one controlled migration now and leaves the model honest.

`pairs`:

- `id uuid primary key default gen_random_uuid()`
- `created_by uuid not null references auth.users`
- `status text not null default 'active' check (status in ('active','ended'))`
- `channel_generation uuid not null default gen_random_uuid()`
- `version bigint not null default 1 check (version > 0)`
- timestamps, with `ended_at` present iff status is `ended`.

`created_by` is audit provenance, not permanent authority. Any active member may create the next invite after the other leaves.

`pair_members`:

- `id uuid primary key`, `pair_id` and `user_id` foreign keys, `joined_at`, nullable `left_at`.
- CHECK `left_at is null or left_at >= joined_at`.
- Unique `(pair_id,user_id) where left_at is null`.
- Unique `(user_id) where left_at is null`: one active pair per user.
- A trigger, plus RPC locking, rejects a third active member. The trigger is a defense against privileged/manual writes, not the primary concurrency mechanism.

`pair_invites`:

- `id`, `pair_id`, `inviter_id`, `code_hash bytea`, `created_at`, `expires_at`, nullable `redeemed_at`, `redeemed_by`, `revoked_at`.
- CHECK hash length, `expires_at > created_at`, `redeemed_by` iff `redeemed_at`, `redeemed_at <= expires_at`, and redemption/revocation are mutually exclusive.
- Partial unique index on `code_hash` while unredeemed/unrevoked.
- At most one live invite per pair; creating another revokes its predecessor.

The displayed code is ten Crockford Base32 characters, grouped `XXXXX-XXXXX`, normalized case-insensitively with hyphens removed. It carries roughly 50 bits and excludes ambiguous characters. Store only SHA-256 of the normalized code. Generate the code deterministically from HMAC(server-generated secret, invite UUID), allowing an idempotent create replay to reconstruct it without persisting the capability in `mutation_receipts`. The HMAC key is generated at migration time in a no-grant private table, never committed. Redemption is authenticated and rate-limited per account; all unavailable-code cases return the same result.

RLS is enabled on all three tables. Revoke all from `anon` and `authenticated`, then grant authenticated users only:

- `pairs`: SELECT where a non-recursive `security definer` helper confirms active membership.
- `pair_members`: SELECT for active members of that pair.
- `pair_invites`: no direct privileges or policies.
- No client INSERT/UPDATE/DELETE on any pairing table; mutations occur only through the RPCs below.
- `rpc_get_pair` exposes only the caller’s active pair plus member `user_id`, `display_name`, `home_tz`, and `joined_at`; base `profiles` remains owner-only.

The single atomic migration renames `household_id` to `pair_id` in:

- `20260911050000_goals.sql`
- `20260911050200_tasks.sql`
- `20260911050400_time_blocks.sql`

Those are the only shipped migrations containing `*_phase1_private_only`; `20260911050100_milestones.sql` derives access through goals, while `20260911050300_captures.sql` is deliberately owner-only.

Drop the three guards and replace them with:

`(visibility='private' and pair_id is null) or (visibility<>'private' and pair_id is not null)`

Add the `pairs` foreign keys and amend owner INSERT/UPDATE `WITH CHECK` so a non-private row can name only the owner’s active pair. Base-table SELECT remains owner-only.

Peer reads are exclusively `security definer set search_path=''` RPC projections: `rpc_list_pair_shared_goals`, `rpc_list_pair_shared_tasks`, and `rpc_list_pair_shared_time_blocks`. Each derives the pair from membership, never trusts a supplied owner, and builds JSON with `jsonb_strip_nulls`:

- `private`: no row.
- `busy_only`: only start/end instants; a goal cannot be `busy_only`, and a busy-only task requires a complete due range.
- `title_only`: time fields where applicable plus sanitized title; no notes, tags, linkage, location, or source.
- `full`: the explicitly approved public DTO fields.
- Milestones appear only beneath a `full` goal. Captures never appear.

Tests assert exact JSON key sets, satisfying ADR-007 rather than mistaking row RLS for redaction.

## 2. RPCs and receipts

All RPCs are `security definer set search_path=''`, explicitly check `auth.uid()`, revoke PUBLIC execution, and grant EXECUTE only to `authenticated`. They use `mutation_receipts` from `20260911050500_mutation_receipts.sql`. Every result includes `action`; replay rejects an operation ID whose recorded action differs, preventing cross-RPC receipt confusion.

`rpc_create_pair_invite(p_operation_id, p_ttl_minutes default 1440)`:

- TTL CHECK: 5 minutes through 7 days.
- Acquire a transaction advisory lock for the caller.
- Reuse the caller’s singleton pair, or create a one-member pair atomically.
- Reject if two active members exist.
- Revoke the prior live invite, create one invite, increment pair version, and record the receipt.
- Return `applied`, pair snapshot, code, and expiry. Replay returns the same invite and reconstructed code without another row.

`rpc_redeem_pair_invite(p_operation_id,p_code)`:

- Rate-limit before lookup; normalize/hash the code.
- Lock the invite and pair `FOR UPDATE`.
- Validate unused, unrevoked, unexpired, caller is not inviter, pair has one member, and caller has no active pair.
- Insert membership, mark the invite redeemed, revoke sibling invites, increment version, and rotate `channel_generation`.
- Receipt and mutation commit together.
- A replay by the successful redeemer returns the original success. Another operation or user receives `invalid_or_unavailable`. Unique indexes convert races into stable outcomes, never partial state.

`rpc_leave_pair(p_operation_id)`:

- Lock caller membership and pair.
- Mark membership left, revoke outstanding invites, increment pair version, rotate channel generation, and end the pair only when no active members remain.
- Return `applied` with `left_pair_id`; replay is identical. A fresh operation when already absent returns `not_member`.
- Server access ends in the same transaction. The client unsubscribes and purges pair/shared cache. When Stage-2 commitments exist, ADR-010 withdrawal and delivery jobs must join this transaction; Stage 1 creates no fake commitment rows.

## 3. Realtime contract

Use private Broadcast, not raw Postgres Changes:

`pair:<pair_uuid>:<channel_generation>`

The generation is load-bearing. Supabase caches channel authorization for a connection; rotating the topic on join/leave prevents a malicious former member from retaining access through an already-authorized socket. The old topic receives one sanitized membership event, while every subsequent event uses the new topic.

Stage-1 events:

- `pair.member_joined.v1`: pair ID, joined user’s safe member projection, pair version, occurrence time.
- `pair.member_left.v1`: pair ID, departed user ID, pair version, occurrence time.
- Reserved only: `pair.proposal_created.v1`, `pair.proposal_updated.v1`, `pair.commitment_changed.v1`.

No planner-object rows or invite codes flow through Realtime.

A SELECT policy on `realtime.messages` permits `extension='broadcast'` only when `realtime.topic()` equals the caller’s active pair plus current generation. There is no authenticated INSERT policy: only database triggers publish. Project settings must reject public channels, and clients subscribe with `private=true`. Supabase recommends private Broadcast with authorization for scalable database events; Postgres Changes is the simpler but less scalable option. [Supabase Realtime authorization](https://supabase.com/docs/guides/realtime/authorization), [database Broadcast guidance](https://supabase.com/docs/guides/realtime/subscribing-to-database-changes).

`PairRepository` belongs to `UserGraph`. On sign-in it fetches `rpc_get_pair`, then subscribes. Events are invalidation hints: refetch authoritative state. On reconnect, token refresh, generation change, or subscription error, refetch before resubscribing. Sign-out closes the channel and repository. Self-leave purges immediately; membership-gone on any fetch does the same, matching ADR-009.

## 4. Frozen client surface

```kotlin
interface PairRepository : AutoCloseable {
    fun observePair(): StateFlow<PairState>
    suspend fun createInvite(operationId: String, ttlMinutes: Int = 1440): InviteResult
    suspend fun redeemInvite(operationId: String, code: String): RedeemResult
    suspend fun leave(operationId: String): LeaveResult
}
```

Domain shapes: `PairId`, `PairMember`, `PairSnapshot`, and `PairState = Loading | Unpaired | Inviting | Paired | Failed`. Results are sealed applied/domain-error/network-error types; `invalid_or_unavailable` is never expanded client-side.

Wire DTOs use snake_case and ISO-8601 UTC:

- `PairSnapshotDto(pair_id,status,version,channel_topic,members,active_invite_expires_at)`
- `PairMemberDto(user_id,display_name,home_tz,joined_at)`
- `InviteCodeDto(invite_id,code,expires_at)`
- `PairRpcEnvelopeDto(outcome,action,pair,invite,left_pair_id)`

Together renders only: unpaired invitation/redeem forms; waiting status and code/expiry; paired member identity and “Shared time locks will appear here”; retry/error states; and confirmed leave. It renders no goal, task, block, availability, proposal, or commitment. Those begin with Stage 2 time locks.

## 5. Seat slicing

- A2: the single Stage-1 migration, pairing/projection/Realtime pgTAP files, and pair fixtures.
- B3: pair domain interface/models, DTOs, Supabase RPC/Broadcast implementation, and corresponding tests.
- C2: `ui/together/`, plus deferred `ui/goal/` and `ui/task/` detail surfaces and their tests.
- Lead: `App.kt`, DI/user-scope ownership, navigation callbacks, Supabase client wiring, integration fixtures, full gate and emulator flow.

## 6. Mandatory adversarial suite

pgTAP must cover anonymous denial; direct-table write denial; guessed pair/topic denial; exact projection key sets; private-row absence; tampered pair ID; invite replay; expiry boundary; revoked code; wrong code; self-redeem; concurrent double-redeem; third-member race; second-pair race; operation ID reused across RPCs; leave replay; former-member API/projection denial; old-topic acceptance before rotation but new-event absence afterward; and the remaining member’s successful resubscription. Also prove no code/hash appears through tables, projections, receipts, or Realtime.

The three likely failures are field leakage through base rows or broadcasts, concurrency allowing an invalid membership topology, and stale Realtime subscriptions surviving account or membership changes. Exact-key projection tests, constraint-backed locked RPCs, and generation-rotated user-scope channels are the required guardrails.

BEGIN VERDICT
{"pair_model":"dedicated_pairs_table","single_pair_per_user":true,"invite":"code","guard_drop":"one_migration","sections_ready":true,"seat_slices":[{"seat":"A2","paths":["supabase/migrations/20260912010000_stage1_pairing.sql","supabase/tests/0012_stage1_pairing_test.sql","supabase/tests/0013_stage1_projections_realtime_test.sql","contracts/fixtures/pair-*.json"]},{"seat":"B3","paths":["shared/src/commonMain/kotlin/dev/elay/domain/model/PairModels.kt","shared/src/commonMain/kotlin/dev/elay/domain/repository/PairRepository.kt","shared/src/commonMain/kotlin/dev/elay/data/remote/dto/PairDtos.kt","shared/src/commonMain/kotlin/dev/elay/data/remote/impl/SupabasePairRepository.kt","shared/src/commonTest/kotlin/dev/elay/data/remote/"]},{"seat":"C2","paths":["shared/src/commonMain/kotlin/dev/elay/ui/together/","shared/src/commonMain/kotlin/dev/elay/ui/goal/","shared/src/commonMain/kotlin/dev/elay/ui/task/","shared/src/commonTest/kotlin/dev/elay/ui/together/","shared/src/commonTest/kotlin/dev/elay/ui/goal/","shared/src/commonTest/kotlin/dev/elay/ui/task/"]}],"failure_modes":[{"risk":"Peer-visible base rows or Realtime payloads leak private fields.","guardrail":"Keep base RLS owner-only; expose exact-key projection RPCs and sanitized membership broadcasts only."},{"risk":"Concurrent redemption creates a third member, two pairs for one user, or multiple winners.","guardrail":"Lock invite and pair rows, take a caller advisory lock, and backstop with partial unique indexes plus a member-cap trigger."},{"risk":"Cached Realtime authorization or account-scoped subscriptions survive revocation.","guardrail":"Rotate channel_generation on every membership change; refetch before subscribe; close and purge the user-scoped repository on leave, membership-gone, or sign-out."}],"confidence":0.95}
END VERDICT