# Stage 3 §A — web RSVP security contract (Opus lane, 2026-09-12, conf 0.9)

*Sol's contract lane ran on an Opus seat this round (codex workspace out of credits — PING).
Verbatim lane output below; binding as adopted in `contracts/stage3-web-rsvp.md`.*

Binding sources: `master-plan-v2.md` scopes Stage 3 as "signed expiring token → browser page with dual-time preview, accept/decline/counter; edge function + public response table," with "Sol: security contract (token scope/expiry/abuse) · Sonnet builds · lead verifies." The recipient is always the other **pair member** — an account — so this is a convenience channel over the shipped negotiation, not a guest system. Every ruling below inherits the shipped Stage-2 machinery rather than re-implementing it.

## 1. Token design

**Scheme: HMAC, not JWT.** The house already owns this primitive: `pair_invite_hmac_keys` is a "Singleton private key … No grants to any role; readable only from inside SECURITY DEFINER functions owned by the migration role," and `pair_invite_code` derives a capability as `extensions.hmac(convert_to(p_invite_id::text,'UTF8'), v_key, 'sha256')`. Stage 3 adds a second singleton, `rsvp_token_hmac_keys`, on the same pattern. A JWT is rejected on three grounds: (a) it would put signing material in the Deno runtime where the Stage-1 rule "The HMAC key is generated at migration time in a no-grant private table, never committed" cannot hold; (b) JWT verification adds `alg`/`kid` confusion surface for zero benefit — there is exactly one issuer and one verifier, both inside our own transaction; (c) **self-contained claims cannot be revoked**, and revocation is the load-bearing requirement here.

**Shape.** `token = base32url(token_id::uuid) || '.' || base32url(left(hmac(token_id, key,'sha256'), 16))` — 122 bits of id plus a 128-bit MAC, ~250 bits presented, one opaque string. The token asserts only `token_id`; every binding lives on the row, so a holder learns nothing from the string and the MAC rejects garbage *before* any row lookup (no presence oracle).

**Bindings recorded at mint:** `proposal_id`, `recipient_user_id`, `minted_by`, `revision_at_mint`, `channel_generation_at_mint`, `expires_at`. The `channel_generation` binding is the Stage-1 lesson applied verbatim — comparing it at verify time makes **pair membership change revoke every outstanding token for free**, with no sweep.

**TTL:** `expires_at := proposal.response_deadline`, exactly — never later, never independently configurable. Because a counter *replaces* the deadline, an outstanding token is stale twice over and must be re-minted.

**Single-use vs multi-view.** Viewing is a read: the token is **multi-view** until expiry or revocation. Responding is the mutation and is **single-effective-use**, enforced through the shipped ledger: `operation_id := uuid_v5(token_id, p_action)`. A double-submit replays the receipt and returns the identical `{"outcome":"applied",…}`; a *different* action on the same token hits the shipped cross-action guard (`22023`), so "accept, then decline from the back button" is rejected server-side.

**Derived revocation.** Validity is computed at every verify, never trusted from a flag: row not revoked **and** `now() < expires_at` **and** `proposal.status in ('proposed','countered')` **and** `proposal.current_revision = revision_at_mint` **and** `pairs.channel_generation = channel_generation_at_mint` **and** the recipient's `pair_members.left_at is null`. Counter, cancel, acceptance, expiry, and membership loss all revoke instantly. An explicit `revoked_at` is set opportunistically, as belt.

**Re-mint:** at most one live token per `(proposal_id, revision_no, recipient_user_id)` via a partial unique index; a new mint revokes its predecessor (the invite pattern).

**Leaked-link threat model.** Assume the holder is not the recipient. The page **may** reveal: proposal title, the 1–3 current-revision candidates in both zones, both display names, the deadline. It **must never** reveal: `pair_id`, any user uuid, email, any other proposal, revision history beyond the current revision, `proposal_responses`, commitments, `time_block` ids, any planner row, and — categorically — `rpc_proposal_conflict_hints` output. Title exposure is a deliberate, bounded trade and must be **disclosed at mint**: `rpc_mint_rsvp_token` returns `"discloses": ["title","times","names"]` and the share UI must print it. Names use the LEFT JOIN + 'Someone' fallback rule.

**Enumeration resistance.** Constant-time compare via double-HMAC (`hmac(mac_a, nonce) = hmac(mac_b, nonce)` with a per-call random nonce — bytea `=` is not constant-time). Every failure *before* a verified row returns one shape, `{"outcome":"invalid_or_unavailable"}`, HTTP 200 (the redeem-invite pattern). Rich state (`expired`, `countered_since_mint`, `already_accepted`, `cancelled`, `declined`) is returned **only after** MAC and row verification succeed. Attempts are logged unconditionally into `rsvp_token_attempts` keyed by `token_hash` and client IP: 10 attempts / 15 min per token_hash, 60 / 15 min per IP, over-limit returns the same opaque shape.

## 2. Server surface

**Minting.** `rpc_mint_rsvp_token(p_operation_id, p_proposal_id)` — `security definer set search_path=''`, revoked from `public, anon`, granted to `authenticated`. The caller must be an active member of the proposal's pair **and the author of the current revision**; the recipient is derived server-side as the other active member, **never** supplied by the client. Status must be `proposed|countered`, with expire-before-mutate. Receipt action `mint_rsvp_token`; a replay re-derives the identical token from `token_id`. **The plaintext token never enters a receipt, a log, or a broadcast.**

**Edge Function** `supabase/functions/rsvp/index.ts`, two routes: `GET /rsvp/:token` (render data) and `POST /rsvp/:token/respond` (`{action, candidate_idx?, counter:{origin_tz,deadline,candidates}?}`). Authenticates to Postgres with the service-role key from `[edge_runtime.secrets]`, `verify_jwt = false` for this function only, sends **only the token plus the action payload** — recipient identity is derived in SQL. Responses carry `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, `X-Robots-Tag: noindex`; the token is never logged (log `left(sha256(token),8)`).

**Reuse of `rpc_respond_proposal` is mandatory.** `rpc_respond_proposal_web(p_token, p_action, …)` is service-role-only. It verifies the token, then `set_config('request.jwt.claims', json_build_object('sub', v_recipient,'role','authenticated')::text, true)` — transaction-local — and calls `public.rpc_respond_proposal(...)` **unchanged**. No second response implementation may exist. A web accept creates the same per-member `shared_lock` rows *because it is the same function*. Minting a real Supabase session for the link holder is **rejected**.

**Conflict envelopes.** The page must handle the shipped shape exactly (no `action` key). Map: `outcome=conflict` + `status='expired'` → expired; `+ current_revision > revision_at_mint` → countered-since-mint; `status in ('accepted','declined','cancelled')` → terminal.

**Web counter mints nothing.** Auto-minting a token addressed to the proposer would let a link holder manufacture a fresh capability — a privilege-escalation loop. The proposer learns of the counter via `pair.proposal_updated.v1` and the in-app inbox.

## 3. Schema deltas

`rsvp_tokens(id uuid pk, proposal_id, recipient_user_id, minted_by, revision_at_mint int, channel_generation_at_mint uuid, token_hash bytea not null unique, expires_at timestamptz not null, created_at, revoked_at null, consumed_at null, consumed_action text null)`, `check (expires_at > created_at)`, FK `(proposal_id, revision_at_mint) → proposal_revisions`. **Plaintext never stored** — `token_hash` is sha256 of the presented string; re-derivability from `token_id` covers idempotent replay. Indexes: unique `token_hash`, partial unique `(proposal_id, revision_at_mint, recipient_user_id) where revoked_at is null`. RLS enabled, `revoke all … from public, anon, authenticated`, **and no policies at all**. Same for `rsvp_token_hmac_keys` and `rsvp_token_attempts`.

**pgTAP adversarial list:** forged MAC; truncated/oversized token; unknown `token_id`; expired; explicitly revoked; cross-proposal and cross-recipient tokens; token minted by the non-author; replayed response returns the byte-identical receipt; second *different* action raises `22023`; stale-revision from web returns the conflict envelope and mutates nothing; minted-then-countered → invalid; recipient left the pair → generation mismatch → invalid; accepted-from-web produces **exactly two** `shared_lock` blocks and two commitments; rate-limit trips at the boundary with the identical opaque shape; grep-proofs that no plaintext token appears in `rsvp_tokens`, `mutation_receipts`, or any broadcast payload; anon and authenticated denied direct SELECT on all three new tables.

## 4. Seat slicing and guardrails

(As adopted — file numbers amended by the lead in `contracts/stage3-web-rsvp.md`.)
- **A4 (SQL):** the Stage-3 migration + pgTAP suite + `contracts/fixtures/rsvp-*.json`.
- **E1 (Edge):** `supabase/functions/rsvp/**`, `supabase/config.toml` `[functions.rsvp]` block only.
- **B5 (client data):** `RsvpModels.kt`, `ProposalRepository.mintRsvpToken`, DTOs, tests.
- **C4 (in-app share UI):** the "Share link" affordance + disclosure copy on the proposal card.
- **Lead:** merge, fixture diff, `supabase functions serve` E2E, gate.

Likeliest failures + guardrails: (1) a seat reimplements accept inside the Edge Function → `rpc_respond_proposal_web` must call `rpc_respond_proposal`; pgTAP asserts two blocks + two commitments from the web path. (2) the function trusts a recipient/proposal id from the request body → the token is the only accepted identifier; a pgTAP case passes a mismatched body and asserts it is ignored. (3) the token leaks into a receipt, log, or broadcast → grep-proof tests plus `no-referrer`/`no-store` header assertions.

VERDICT: {"lane":"security-contract","token_scheme":"hmac","stored":"hash","sections_ready":true,"confidence":0.9}
