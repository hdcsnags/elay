# sol — codex_cli (gpt-5.6-sol) — 2026-09-11-architecture-decisions

- success: true
- elapsed: 131s
- read-only verification: **clean**
- copy: C:\New folder\claw-scratch\elay-sol-qPMfYe\repo


## Output

GPT-5 (Codex).

## 1. Backend

**Verdict: managed Supabase through `supabase-kt`.**

ELAY has eight time-lock states, four visibility levels, participant responses, immutable counter revisions, membership, audit history, and private conflict checks. That is relational, transactional work. PostgreSQL can atomically validate membership, expected proposal version, legal transition, and conflicts before creating a commitment; Firestore would distribute those invariants across documents, Security Rules, and functions. Supabase also directly preserves the brief’s “untrusted client → grants/RLS → privileged function” boundary ([§5–8](<C:/New folder/claw-scratch/elay-sol-qPMfYe/repo/ELAY-SPEC.md:208>)) and supplies the required AI gateway boundary ([§10](<C:/New folder/claw-scratch/elay-sol-qPMfYe/repo/ELAY-SPEC.md:462>)).

Both KMP clients are community-maintained. The sheet explicitly leaves `supabase-kt` unresearched and GitLive’s version unverified ([toolchain lines 29–32](<C:/New folder/claw-scratch/elay-sol-qPMfYe/repo/research/toolchain-2026-09.md:29>)); current repositories show active `supabase-kt` 3.6.0 and GitLive 2.5.0, so neither eliminates wrapper risk. Supabase wins on product fit, not SDK pedigree.

**Phase 0 consequence:** pin the CLI and SDK; hide `supabase-kt` behind ELAY-owned auth/data/realtime interfaces; create migrations, transactional proposal RPCs, and pgTAP RLS tests before UI CRUD.

## 2. Local data and sync

**Verdict: SQLDelight.**

Room 3.0.2 is now multiplatform and coroutine-first, but it is a new KSP-only line. SQLDelight has years of KMP use, generated typed queries, transparent migrations, and fewer compiler-plugin interactions—better for agents working across Windows and macOS. The sheet calls it the safer choice ([lines 21–27](<C:/New folder/claw-scratch/elay-sol-qPMfYe/repo/research/toolchain-2026-09.md:21>)); its quoted 2.2.1 is already behind the current 2.3.2 release, proving why Gate 0b must re-pin rather than copy the sample TOML.

Use cache tables plus an append-only outbox. Every mutation carries `operation_id`, aggregate ID, `expected_version`, payload, creation time, attempt count, and status. Captures can reconcile simply; time locks cannot. Proposal/accept/counter commands go to one transactional server RPC with a unique idempotency key. The server validates the state transition and conflicts, appends a revision, increments the version, and returns authoritative state. An offline acceptance remains visibly “pending,” never “accepted,” until acknowledged—matching [§6 and §13](<C:/New folder/claw-scratch/elay-sol-qPMfYe/repo/ELAY-SPEC.md:304>).

**Phase 0 consequence:** define SQLDelight schemas, numbered migrations, outbox lifecycle, retry policy, and the client/server command contract in an ADR.

## 3. Navigation and UI kit

**Verdict: classic Navigation Compose plus Material 3.**

The requirement is six stable top-level surfaces—Today, Plan, Goals, Inbox, Together, Review—not a navigation experiment ([§2](<C:/New folder/claw-scratch/elay-sol-qPMfYe/repo/ELAY-SPEC.md:80>)). Classic `org.jetbrains.androidx.navigation:navigation-compose` already covers shared routing and deep links. Navigation 3 currently adds explicit polymorphic serialization and, according to the sheet, lacks its UI artifact on non-JVM targets, requiring a hand-built `NavDisplay` ([lines 15–19](<C:/New folder/claw-scratch/elay-sol-qPMfYe/repo/research/toolchain-2026-09.md:15>)). That is risk without Phase 0 value.

Use shared Material 3 primitives for accessibility, controls, typography behavior, and platform consistency, wrapped in a small ELAY token/component layer. “Material 3” need not mean generic-looking: color, shape, spacing, motion, navigation chrome, and signature time-lock cards remain ELAY-owned. Do not adopt Material 3 Expressive because it is presently Android-only.

**Phase 0 consequence:** declare typed route contracts for all six surfaces, implement one adaptive app shell, and establish tokens plus a small component catalog; no platform-forked navigation.

## 4. iOS build pipeline

**Verdict: GitHub Actions macOS runner, with a local Mac for interactive diagnosis.**

A local Mac has near-zero marginal compute cost but introduces machine drift, availability, and awkward Windows-agent access. Hosted CI adds another vendor, billing system, and secret surface before ELAY needs its release features. GitHub Actions gives agents a standard trigger/log loop—`gh workflow run`, `gh run watch --exit-status`, `gh run view --log-failed`—and protected environment secrets for later App Store Connect keys. Standard macOS currently costs **$0.062/minute** beyond included usage, so a 20-minute build is about **$1.24** ([GitHub pricing](https://docs.github.com/en/billing/reference/actions-runner-pricing)).

The sheet is categorical that Windows cannot compile Apple targets ([lines 34–37](<C:/New folder/claw-scratch/elay-sol-qPMfYe/repo/research/toolchain-2026-09.md:34>)). Therefore, CI must exist in Phase 0; “iOS second” cannot mean “compile later.” Before the first local Mac is configured, use unsigned simulator builds in Actions. Add signing secrets only when TestFlight work begins, scoped to an approval-protected environment.

**Phase 0 consequence:** add a macOS workflow on every pull request, pin Xcode, cache Gradle/Konan safely, retain build logs, and prohibit Phase 1 if the simulator build is red.

## 5. Challenge

Michael is not wrong to drop React Native: Kotlin fluency, recent Compose experience, native-looking UI preference, and available Macs outweigh Expo’s easier cloud-build path. He would be wrong only if “iOS second” permits Apple compilation to drift; iOS must be green in CI from Phase 0 even while Android remains the development lead.

## Concrete Phase 0 exit gate

The folder currently has no Gradle project or Supabase harness, so these are the contracts the scaffold must make executable. Phase 0 is not closed merely because the domain ADR can be explained; these commands must all return zero, consistent with [§11’s foundation requirements](<C:/New folder/claw-scratch/elay-sol-qPMfYe/repo/ELAY-SPEC.md:502>) and the local [build-gate instructions](<C:/New folder/claw-scratch/elay-sol-qPMfYe/repo/.claude/skills/build-gate/SKILL.md:1>).

Windows PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat --version
.\gradlew.bat :composeApp:assembleDebug :shared:allTests --console=plain
.\gradlew.bat :composeApp:lintDebug ktlintCheck detekt --console=plain
npx supabase start
npx supabase db reset --local
npx supabase db lint --level error
npx supabase test db --local
```

`supabase test db` runs pgTAP files under `supabase/tests`; Phase 0’s suite must include owner, peer, former-member, anonymous, and attacker-shaped allow/deny cases, as demanded by [§8](<C:/New folder/claw-scratch/elay-sol-qPMfYe/repo/ELAY-SPEC.md:411>). The CLI and Docker stack should be pinned/reproducible, not fetched at an implicit floating version.

Mac or GitHub Actions:

```bash
xcodebuild -version
./gradlew :shared:allTests :shared:iosSimulatorArm64Test --console=plain
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 --console=plain
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

If the generated project uses a workspace, substitute `-workspace iosApp/iosApp.xcworkspace`; the workflow must encode one form and keep it green.

## Three likely Phase 1–3 failures

1. **RLS or Realtime leaks private household data.** Agents add a table or channel but omit the corresponding former-member/guessed-ID denial.  
   **Guardrail:** every migration contains grants, RLS, and paired positive/negative pgTAP tests; run direct REST and Realtime adversarial tests at Gates 1–3.

2. **Offline retries corrupt time-lock negotiation.** Two devices accept/counter from stale state, or a retry creates duplicate commitments.  
   **Guardrail:** server-only transition RPC, `expected_version`, unique idempotency key, append-only revisions, transactional conflict check, and no optimistic terminal status before acknowledgement.

3. **Android stays green while iOS silently rots.** A community SDK, serialization model, or `expect/actual` change compiles only on JVM.  
   **Guardrail:** mandatory per-PR iOS simulator framework and Xcode build, thin SDK adapters, small `expect/actual` surface, and no phase gate while either platform is red.

BEGIN VERDICT
{"backend":"supabase","local_data":"sqldelight","navigation":"classic","ui_kit":"material3","ios_pipeline":"github-actions","confidence":0.91,"phase0_gate_windows":["$env:JAVA_HOME = 'C:\\Program Files\\Android\\Android Studio1\\jbr'","\\.\\gradlew.bat --version","\\.\\gradlew.bat :composeApp:assembleDebug :shared:allTests --console=plain","\\.\\gradlew.bat :composeApp:lintDebug ktlintCheck detekt --console=plain","npx supabase start","npx supabase db reset --local","npx supabase db lint --level error","npx supabase test db --local"],"phase0_gate_mac":["xcodebuild -version","./gradlew :shared:allTests :shared:iosSimulatorArm64Test --console=plain","./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 --console=plain","xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build"],"failure_modes":[{"risk":"RLS or Realtime leaks private household data.","guardrail":"Require grants, RLS, and positive/negative pgTAP plus direct REST/Realtime adversarial tests with every migration."},{"risk":"Offline retries or concurrent devices corrupt time-lock state.","guardrail":"Use one transactional server transition RPC with expected versions, unique idempotency keys, append-only revisions, and acknowledged terminal states."},{"risk":"Windows-first development allows iOS compilation to rot.","guardrail":"Require the iOS simulator framework and Xcode build on every pull request; keep SDK adapters and expect/actual surfaces small."}],"michael_is_wrong_about":"Only if “iOS second” means deferring Apple compilation; iOS must be green in CI from Phase 0."}
END VERDICT