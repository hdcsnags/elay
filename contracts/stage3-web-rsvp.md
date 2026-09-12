# Stage 3 contract — no-install web RSVP (FROZEN 2026-09-12)

*The security lane's §A (Opus seat standing in for Sol — codex credits, PING; conf 0.9) and Gemini's §B (web UX, conf per its verdict) are binding as written — in-repo copies: `council/stage3-web-rsvp-security-opus.md` and `council/stage3-web-rsvp-gemini.md`. House rules from the phase1/stage1/stage2 contracts apply. This file records lead amendments and seat grants.*

## Adopted (highlights; the lane docs are the full text)
- **HMAC capability token, hash-at-rest** (`token_id.mac`, ~250 bits): second no-grant singleton key table on the `pair_invite_hmac_keys` pattern; bindings (proposal, recipient, revision, channel_generation, expiry) live on the row; **derived revocation** at every verify (deadline, status, revision, generation, membership) so counter/cancel/accept/expiry/member-leave all revoke instantly. TTL = `response_deadline`, exactly. One live token per (proposal, revision, recipient); re-mint revokes predecessor. Plaintext never stored/logged/broadcast.
- **Multi-view read / single-effective-use respond** via deterministic `operation_id = uuid_v5(token_id, action)` riding the shipped receipts + cross-action `22023` guard.
- **`rpc_respond_proposal` reuse is mandatory**: service-role-only `rpc_respond_proposal_web` verifies the token, impersonates the recipient transaction-locally via `set_config('request.jwt.claims', …, true)`, and calls the shipped RPC unchanged. Web accept ⇒ the same two `shared_lock` blocks + two commitments by construction. No client-supplied recipient/proposal ids — the token is the only identifier. **Web counter mints nothing** (privilege-escalation loop otherwise).
- Single opaque `{"outcome":"invalid_or_unavailable"}` (HTTP 200) for every pre-verification failure; rich states only after MAC+row verify; unconditional attempt logging + rate limits (10/15min per token, 60/15min per IP).
- Edge Function `supabase/functions/rsvp/`: GET render-data + POST respond, service-role from `[edge_runtime.secrets]`, `verify_jwt=false` for this function only, `no-store`/`no-referrer`/`noindex` headers, token never logged.
- **Gemini's §B is the E1/C4 design source of truth**: server-rendered mobile-first page (inline CSS <8KB, zero frameworks), dual-time rule inherited (recipient primary "for you" + proposer secondary + (+1 day) pills + plain-language DST captions), identity grounding ("Responding as Jordan · Alex will see your answer", "This link was sent to Jordan…"), the full no-dead-end edge-state copy table, **no-JS pure-HTML-form fallback**, aria-labels per its §5.1, **constrained counter** (exactly one alternative slot with live dual-time preview; multi-option counters route to the app).

## Lead amendments (binding)
1. **File numbering**: seat D1 (Stage-2 residuals, in flight) owns migration `20260912180000_delete_shared_lock_guard.sql` and pgTAP `0020`. Stage 3 therefore uses migration **`20260912190000_stage3_web_rsvp.sql`** and pgTAP **`0021_stage3_web_rsvp_test.sql`** (all references in the lane docs are amended accordingly).
2. §B's ".ics / Add to device calendar" success affordance is **stretch, not scope**: E1 may add `GET /rsvp/:token/ics` serving the accepted slot as text/calendar ONLY if the core page + tests are done; it must pass the same token verification and reveal nothing beyond the §A disclosure set.
3. §B's counter "deadline: 24 hours" default stands, but the value must clear `rpc_create_proposal`'s 5-min–7-day bound (it does) and is sent as `p_new_deadline` through the shipped counter path — no new deadline machinery.
4. §B's phrase "the recipient's authenticated profile" is amended to the §A stance: the token identifies the *intended recipient*; it does not authenticate the viewer. Copy already handles this ("If you are not Jordan, please close this page").
5. The profiles lesson stands: any name shown joins profiles LEFT JOIN with 'Someone' fallback.
6. Local verification path: `npx supabase functions serve rsvp` + curl (lead). CI runs pgTAP as usual; Edge Function unit tests run under Deno if trivially available, else lead-verified live (flag honestly).

## Seat grants (disjoint)
| Seat | Paths |
|---|---|
| A4 (SQL) | `supabase/migrations/20260912190000_stage3_web_rsvp.sql`, `supabase/tests/0021_stage3_web_rsvp_test.sql`, `contracts/fixtures/rsvp-*.json` |
| E1 (Edge) | `supabase/functions/rsvp/**`, `supabase/config.toml` (`[functions.rsvp]` block only) |
| B5 (client data, after A4 fixtures) | `domain/model/RsvpModels.kt`, `domain/repository/ProposalRepository.kt` (mint addition only), `data/remote/dto/RsvpDtos.kt`, `data/remote/impl/SupabaseProposalRepository.kt` (mint only), matching commonTest |
| C4 (share UI, after B5) | `ui/together/proposal/**` (share-link affordance + §A disclosure copy), matching commonTest |
| Lead | contract freeze, merges + fixture diff, functions-serve E2E, gate + CI, STATE |
