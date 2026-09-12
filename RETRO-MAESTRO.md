# RETRO — Maestro orchestrating itself (ELAY PoC, Fable 5 leading)

*The real test (Michael, 2026-09-11): "when you are actually done, do I open my computer and have a working product… or do I have a scaffold app, basic UI, stubs where I wasn't the blocker?" This file is the weak-link ledger for the next window's upgrade planning: orchestrator vs models vs setup. Updated at every gate; brutal honesty is the point. Next run rotates the lead seat (Astra or Sol).*

## The success metric
Not gates-with-evidence. A product Michael can open and use, end to end, built without him as a blocker. For ELAY that means at minimum: sign-in, a persistent personal planner (Gate 1), households (Gate 2), and the signature time-lock negotiation (Gate 3) — an app that does the thing on the tin. Everything below is graded against that.

## State of the vertical slice (updated 2026-09-12 ~00:00)
Opening the app today: 3 of 6 surfaces on FAKE in-memory data, no sign-in, no persistence in the UI path, zero network calls ever made by the app. Meanwhile the horizontal layers are real and tested: schema+RLS+RPCs (pgTAP 170/170 local AND Linux CI), Room layer (iOS-CI-verified), sync engine (73 tests), CI ×3 incl. per-push iOS builds. **Verdict so far: strong foundations, no product.** In flight: seat R (real repository) → concierge DI/auth wiring → fake→real swap → C2 surfaces → Gate 1 E2E on two accounts.

## Weak-link ledger (evidence, not vibes)

### Orchestrator (Fable 5) — failures owned
1. **The 16h stall (worst failure so far).** Ended a turn at a milestone with unblocked work remaining and no scheduled wake-up; event-driven session never re-entered. User noticed; protocol now has §1.10 and the loop is armed. An orchestrator that stops is indistinguishable from one that finished.
2. **Breadth-first bias.** Sequenced by architectural layer (schema→data→UI-on-fakes) instead of driving one vertical slice to "usable" first. Correct for risk-retirement, wrong for the actual metric until 2026-09-11 recalibration.
3. Raced its own seat messages once (stand-down + fix-it to C1 crossing → duplicate work).
4. **Hollow-green, self-inflicted (2026-09-12 Stage 2):** ran the gate as `gradlew … | tail`, the pipe swallowed a BUILD FAILED into exit 0, and a STALE APK got installed and E2E'd for a full round before the on-device error message exposed it. The same class of failure the process exists to catch in seats — the orchestrator's own gates need the same rigor (`/build-gate` now mandates pipefail + a GATE_GREEN marker).

### Verification wins worth keeping (2026-09-12 Stage 2)
- The **silent-swallow rule paid for itself a third time**: one println at the proposal refetch swallow point turned "feed mysteriously empty" into an exact MissingFieldException path in one run.
- **RAISE LOG inside the checker's own transaction** (survives its rollback) root-caused the Realtime Unauthorized in two cycles after three plausible theories tested clean — instrument where the decision is made, not where the symptom shows. The finding is generalizable: **Realtime's join probe row has `private=false, event=null`; any policy on realtime.messages predicating on those columns silently kills every private-channel join** (this had been latent since Stage 1 and is why the realtime observation kept slipping).

### Models (seats)
- **Strong:** honest UNVERIFIED reporting everywhere (Sol declining to fake success when its sandbox broke; A/B2 flagging exactly what they couldn't run); B2 decompiling jars to verify SDK claims; seats catching machinery blind spots (B1 found the vacuous detekt rail).
- **Weak:** C1 wait-looped on its own background tasks twice instead of finishing (cost ~2 rounds); hollow-green risk is real and recurring (zero-test allTests, NO-SOURCE detekt — both caught by process, not by models); every seat's "green" needed concierge re-verification and 3 of 5 rounds needed concierge fixes (test-harness assumptions, layout squeeze, coroutines-1.11 semantics).

### Setup (this machine / the era)
1. **Codex write sandbox broken on Windows** (`helper_unknown_error`) — Sol excluded from coder seats entirely; write-probe verified. Fix: codex CLI update or sandbox config (PING).
2. **MaestroClaw claude_code MODE=code broken by design** (task mode can't write; the skip-permissions path is rightly classifier-blocked). Workaround: harness subagents in scratch clones — works well, but it means MaestroClaw itself didn't run the coder half of this PoC. Real Maestro upgrade item.
3. **Memory pressure**: emulator + Docker Supabase + parallel Gradle seats OOM-killed a watcher; forced one-heavy-seat-at-a-time serialization. A beefier box or remote seats would parallelize the round.
4. **No Mac locally** — handled cleanly by CI (free public-repo macOS runners), genuinely not a blocker in 2026.
5. Windows toolchain paper cuts (compileSdkMinor/37.0, gradlew exec bit, CRLF) — each cost minutes and is now recorded; an era tax, not a blocker.

### Stage 3 verification round (2026-09-12) — what the process caught
- **Two independent verifiers converged on the same blocker** (the web-counter dead end) from different methods (Opus: live SQL repros; Gemini: static contract diff) — the strongest signal yet that the verify-before-close pattern works. The blocker's root cause is a recurring MODEL failure class worth naming: **a seat pattern-matching a contract sentence's surface ("web counter mints nothing" → "counter forbidden") instead of its stated rationale** — the same §A paragraph explained the rule. Guardrail idea for the next run: contracts should phrase prohibitions as "X is forbidden BECAUSE Y; Z remains required".
- The verifier caught pgTAP DEFENDING a defect (the deny-test pinned the bug) — green tests prove conformance to the test author's reading, not to the contract. Independent verification remains non-optional.
- Codex credits ran out mid-run (setup): Sol+Astra replaced by Opus subagents + Gemini with no quality loss this round — the Opus verification passes were the deepest of the project.

### Stage 4 round (2026-09-12) — the verifier audited the ORCHESTRATOR
- The deepest finding class this round was **honesty defects the lead shipped**: copy claiming an external calendar that cannot exist, a rate-limited "couldn't check" rendered as "Free", and — most instructive — **the lead's own evidence package failing** (byte-identical screenshots under filenames advertising a capture that wasn't in frame) and **a false "fixed" claim in STATE** (an F7 sed that never matched). Verification catches the orchestrator too; that is the system working, and the reason the verify-before-close pattern must survive into the next run regardless of who leads.
- Pattern for the ledger: prose honesty and artifact honesty are separate properties — STATE's text was accurate while its cited artifact set was not. Closure checklists should verify artifact md5-distinctness and filename-content match, not just that files exist.

## Can an app run start-to-finish in 2026?
Provisional answer after day 1: the pieces all exist — council argues, seats build against contracts, verification catches the lies, CI holds the floor. What breaks the illusion is orchestration continuity (fixed) and sequencing toward the user's definition of done (fixed as of tonight). The rest is throughput. This section gets a final answer when ELAY either is, or is not, a usable product with Michael never having been the blocker.
