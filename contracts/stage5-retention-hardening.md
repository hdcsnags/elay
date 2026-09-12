# Stage 5 contract — retention + hardening (FROZEN 2026-09-12)

*The hardening lane's §A (Opus seat, conf 0.87 — `council/stage5-hardening-opus.md`) and Gemini's §B (conf 0.96 — `council/stage5-retention-gemini.md`) are binding. House rules from all prior stage contracts apply. Constraints: no hosted Supabase, no store accounts, no FCM (PING) — "store-shaped" means signed-ready artifacts + listing drafts in-repo, never a submission.*

## Adopted (highlights; lane docs are the full text)
- **`session_outcomes` table** (never columns on pair-projected `commitments`): owner-only RLS all verbs, `outcome in (ran_long, finished_early, didnt_happen, rescheduled)`, `actual_minutes` null-iff rule, one outcome per (owner, block) re-recordable with version bump, composite owner-aware FK (requires adding `time_blocks (id, owner_id)` unique), `shared_with_pair` inert-by-CHECK forward column (the Phase-1 private-only pattern). **Private, not shared** (spec §7 "do not infer sharing"; completion-fact sharing already exists via proposal status).
- **RPCs on the house ledger:** `rpc_record_session_outcome` (elapsed-only: future block → 22023; receipts; cross-action guard) and `rpc_next_time_suggestion` (server-side median of last 5 matching `actual_minutes`, emitted only at `sample_size >= 2`, else null — the honesty threshold pinned in pgTAP). Outcomes bypass the outbox by design (idempotent receipt + foreground retry; lost outcome = lost nicety, never planner data).
- **pgTAP `0023`** per §A's adversarial list incl. the 0017-style key-set re-pin proving no outcome key enters any pair projection.
- **MASVS checklist (lead-owned build work), MUSTs:** allowBackup=false + extraction rules; explicit release cleartext=false + network_security_config; release-build assertion that Supabase URL + `RSVP_LINK_BASE` come from config and are https (loopback forbidden in release); R8 + proguard-rules (kotlinx-serialization/Room/driver keeps) verified by a **minified live-RPC smoke**, not compile; the seven `ELAY *` printlns route through release-stripped `ElayLog` (class-name-only auth errors, hashed topic) + detekt `ForbiddenMethodCall` on println; exported-components pin (exactly one); keystore hygiene (`keystore.properties` + `*.p12` to .gitignore; keystore OUTSIDE the repo at `%USERPROFILE%\.elay\`; signing config omitted when absent); dependency audit FILE (no pre-gate version bumps); FLAG_SECURE declined-and-recorded.
- **Release lane:** versionCode = MAJOR*10000+MINOR*100+PATCH from one toml source (1.0.0 → 10000); `bundleRelease` + `jarsigner -verify` + bundletool minified smoke added to /build-gate; CI asserts shape (unsigned bundle, manifest greps, mapping archive), signing stays local. PING: Michael holds the keystore password.
- **Gemini's §B is C7's design source of truth** (conf 0.96): the post-session feedback moment and the next-time suggestion both live on the **Today card** (one primary surface), one-tap outcomes with skip-ability and calm copy, store listing drafts in the §B text, a11y rules.

## Lead amendments (binding)
1. Migration `20260912210000_stage5_retention_hardening.sql`, pgTAP `0023_stage5_retention_test.sql`.
2. The three §A failure modes are binding as written (commitments-columns forbidden / no new realtime event or auto-proposal from `rescheduled` / printlns replaced never deleted).
3. Store listing drafts land as `docs/store-listing.md` (C7 grant) from §B's text.
4. All build-file/manifest/R8/keystore/CI work is the LEAD's, not seats' (standing rule).

## Seat grants (disjoint)
| Seat | Paths |
|---|---|
| A7 (SQL) | `supabase/migrations/20260912210000_stage5_retention_hardening.sql`, `supabase/tests/0023_stage5_retention_test.sql`, `contracts/fixtures/session-outcome-*.json` |
| B8 (client data) | `domain/model/SessionOutcomeModels.kt` (new), `domain/repository/OutcomeRepository.kt` (new), `data/remote/dto/SessionOutcomeDtos.kt` (new), `data/remote/impl/SupabaseOutcomeRepository.kt` (new), matching commonTest |
| D2 (logging) | `util/ElayLog.kt` expect/actual (new) + exactly the seven println call sites §A names, matching commonTest where sensible |
| C7 (UI, after B8) | Today-card feedback moment + next-time surface per §B (`ui/today/**`), `docs/store-listing.md`, matching commonTest |
| Lead | build files/manifests/proguard/gitignore/detekt/CI/keystore/versioning, DI, merges, minified smoke, gate + closure |

## Post-pre-gate lead amendments (2026-09-12, binding)
5. **F24/F25 formally deferred to the outcome-history pass** (next UI round): a server read
   RPC for recorded outcomes (so the wrap-up card stops re-prompting across cold starts and
   a history surface can exist) and the foreground retry with a STABLE per-(block,attempt)
   operation id plus a visible failure state (today a Failed record is silent). Until then
   re-answering is safe (idempotent upsert, version bump) but nagging.
6. **F18 resolution**: the enforceable println rail is the root `assertNoPrintln` Gradle
   task (wired into `check` + the CI release-shape job); detekt's ForbiddenMethodCall stays
   configured but is documented as inert without type resolution — never cite it as the rail.
7. **F11 resolution**: `RsvpLinkConfig.base` is set at app init from BuildConfig
   (`RSVP_LINK_BASE` in local.properties); `assertReleaseEndpoints` requires https AND
   non-loopback for both endpoints in release, escape-hatched ONLY by
   `-Pelay.allowInsecureRelease=true` (loudly logged, local smoke only).
