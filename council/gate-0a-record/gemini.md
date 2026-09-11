# gemini — agy_cli (gemini-3.8-flash-high) — 2026-09-11-architecture-decisions

- success: true
- elapsed: 65s
- read-only verification: **clean**
- copy: C:\New folder\claw-scratch\elay-gemini-QxyVu7\repo


## Output

Model: Gemini 3.8 Flash (High)

---

### Decision 1: Backend
- **Verdict**: `supabase`
- **Reasoning**:
The brief establishes ELAY’s foundation on relational consistency and strict authorization. In §5 (lines 248–255), Supabase wins on relational sharing and fine-grained authorization: ELAY’s core model requires relational joins, foreign keys, and multi-entity integrity across `households`, `household_members`, `goals`, `tasks`, and `time_lock_proposals` (§7, lines 311–340). Mapping the four-tier visibility model (`private`, `busy_only`, `title_only`, `full`, §7, lines 364–373) into Firestore Security Rules without joins demands massive data denormalization, violating §7’s mandate (line 360) never to infer sharing solely from household membership. Furthermore, §4’s time-lock state machine (lines 173–206) requires atomic multi-participant transaction validation. 

On KMP, `supabase-kt` is a pure Kotlin, Ktor-based library (`toolchain-2026-09.md`, line 32) compiling directly to common bytecode without native wrappers. In contrast, GitLive Firebase (`toolchain-2026-09.md`, line 30) wraps native CocoaPods and Google Play Services, creating brittle native link dependencies on iOS.
- **Consequence for Phase 0**:
Initialize a local Supabase environment (`supabase init`, `supabase start`). Implement initial PostgreSQL migrations with Row Level Security (RLS) policies for §7 core tables, establish `pgTAP` / local test suites for negative authorization verification (§8, line 412; §14, line 665), and configure `supabase-kt` 3.x dependencies via `gradle/libs.versions.toml`.

---

### Decision 2: Local Data + Sync
- **Verdict**: `room3`
- **Reasoning**:
Room 3.0.2 (`toolchain-2026-09.md`, line 22) reached GA in July 2026 as Google’s first-party KMP persistence standard. It is coroutines-first, operates with KSP, and utilizes `androidx.sqlite:sqlite-bundled:2.7.0` (line 22) across both Android and iOS targets, guaranteeing identical SQLite behavior without OS-version divergence. Michael’s fluency with Jetpack Compose and Room minimizes cognitive overhead and code review friction. 

For offline planning (§6, line 305; §8, line 593), Room cleanly models the required mutation outbox. The outbox stores:
1. Client-generated UUID PK
2. Mutation type enum (`CREATE_PROPOSAL`, `COUNTER_PROPOSAL`, `ACCEPT_LOCK`, `UPDATE_TASK`)
3. Version/idempotency key
4. Payload JSON
5. Sync status (`PENDING`, `IN_FLIGHT`, `ACKNOWLEDGED`, `CONFLICT`)
6. Instant timestamp (`starts_at_utc`, §4, line 152)

When connectivity returns, mutations process sequentially via Supabase RPC. Simple field edits merge automatically; conflicting time-lock proposal revisions trigger interactive user resolutions (§4, line 187; §6, line 305).
- **Consequence for Phase 0**:
Add KSP (`toolchain-2026-09.md`, line 91) and Room 3.0.2 dependencies (`androidx.room3:room3-runtime`, `androidx.room3:room3-compiler`, `androidx.sqlite:sqlite-bundled`) to `composeApp/build.gradle.kts` and `shared/build.gradle.kts`. Configure schema export arguments and create a baseline local outbox database test in `shared:commonTest`.

---

### Decision 3: Navigation + UI Kit
- **Verdict**: Navigation: `classic` | UI Kit: `material3`
- **Reasoning**:
Navigation 3 for CMP is premature for a production delivery: `toolchain-2026-09.md` (line 16) explicitly notes that `navigation3-ui` lacks published artifacts for non-JVM/native targets, forcing teams to hand-roll `NavDisplay` on iOS. Conversely, classic Navigation Compose (`org.jetbrains.androidx.navigation:navigation-compose`, `toolchain-2026-09.md`, line 17) provides rock-solid, type-safe route serializability across Android and iOS, effortlessly powering the six primary top-level tabs mandated in §2 (Today, Plan, Goals, Inbox, Together, Review; lines 80–95) and nested modal sheets for proposal negotiation (§3, lines 113–115).

For UI, Compose Multiplatform Material 3 (`org.jetbrains.compose.material3:material3`, `toolchain-2026-09.md`, line 19) provides accessible, tested design primitives (`Scaffold`, `NavigationBar`, `DatePicker`, `TimePicker`) conforming to OWASP MASVS and accessibility standards (§8, line 423; §13, line 603). Avoid experimental Material 3 Expressive (`toolchain-2026-09.md`, line 19), which is Android-only. Build an ELAY token wrapper over baseline Material 3 to impart the required product warmth (§1, line 17).
- **Consequence for Phase 0**:
Implement the primary navigation scaffold in `composeApp` linking the six §2 tabs with classic Navigation Compose. Define `ElayTheme` in `shared` wrapping M3 color schemes, typography, dynamic contrast, and preview providers verified via common `@Preview` (`toolchain-2026-09.md`, line 41).

---

### Decision 4: iOS Build Pipeline
- **Verdict**: `github-actions`
- **Reasoning**:
Windows cannot produce iOS binaries because Kotlin/Native Apple targets (`iosArm64`, `iosSimulatorArm64`) require Xcode (`toolchain-2026-09.md`, line 35). Relying on a local Mac introduces significant friction: agents running on Windows cannot headlessly wake, tunnel into, or inspect a sleeping local desktop Mac without complex daemon maintenance. Hosted services like Codemagic add separate vendor billing and authentication overhead.

GitHub Actions macOS runners (`macos-14` / `macos-15`) provide an automated, zero-maintenance cloud environment. Windows agents can trigger verification workflows via the GitHub CLI (`gh workflow run`) and inspect logs/artifacts (`gh run view`) programmatically. App Store credentials, distribution certificates, and provisioning keys remain secured in GitHub Repository Secrets (§8, line 413), isolated from developer machines. 

Until the local Mac is configured, Windows agents perform full Android builds, emulator passes, and `shared:allTests`, while remote GitHub Actions runners validate Kotlin/Native compilation.
- **Consequence for Phase 0**:
Create `.github/workflows/ios-verify.yml` with a `macos-14` runner executing `./gradlew :composeApp:compileKotlinIosSimulatorArm64 :shared:iosSimulatorArm64Test`. Validate end-to-end headless triggering and log collection via `gh` CLI from the Windows terminal.

---

### Decision 5: Challenge
- **Verdict**:
Michael is entirely justified in rejecting Expo to prevent generic cross-platform aesthetics and leverage his proven Jetpack Compose fluency, but he severely underestimates the daily agentic friction of building Kotlin Multiplatform from a Windows host. Dropping React Native eliminates instant single-machine cloud builds (EAS) and first-party TypeScript SDKs, forcing the build into community KMP libraries (`supabase-kt`) and remote Mac CI validation. If he proceeds with KMP, he must treat the GitHub Actions macOS compilation gate as mandatory on every PR to prevent silent iOS regressions.

---

### Phase 0 Exit Gate, Made Concrete

#### 1. Windows Machine Commands (Pre-Phase 1 Exit)
Before Phase 0 closes on the local development machine, execute the following commands using the Android Studio bundled JDK (`toolchain-2026-09.md`, line 42):

```powershell
# Set JDK 21 environment
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"

# 1. Clean build of composeApp debug APK
.\gradlew.bat :composeApp:assembleDebug --console=plain -q

# 2. Execute all multiplatform unit and common tests (domain, timezone, outbox)
.\gradlew.bat :shared:allTests --console=plain -q

# 3. Linting and static analysis across the repository
.\gradlew.bat :composeApp:lintDebug ktlintCheck detekt --console=plain -q

# 4. Backend policy test harness (local PostgreSQL RLS verification)
supabase start
supabase test db
```

#### 2. Mac / GitHub Actions CI Commands (iOS Gate)
On the macOS runner / local Mac build pipeline:

```bash
# 1. Verify Kotlin/Native compilation for iOS Simulator
./gradlew :composeApp:compileKotlinIosSimulatorArm64 --console=plain -q

# 2. Execute shared unit tests on the iOS native target
./gradlew :shared:iosSimulatorArm64Test --console=plain -q

# 3. Validate complete Xcode project linking and framework embedding
xcodebuild -workspace iosApp/iosApp.xcworkspace \
           -scheme iosApp \
           -destination 'generic/platform=iOS Simulator' \
           build \
           CODE_SIGNING_ALLOWED=NO
```

---

### Three Critical Agent Failure Modes in Phases 1–3

#### 1. Timezone DST Drift and Fixed Offset Arithmetic
- **The Risk**:
Spec §4 (lines 147–172) and §14 (lines 646–647) strictly mandate storing shared commitments as canonical UTC instants (`starts_at_utc`, line 152) combined with IANA origin zones (`origin_tz`, line 158), prohibiting fixed offsets like "Vancouver = Toronto - 3". AI agents frequently hallucinate static hour math or convert local times via lossy date strings, silently breaking recurring rules across Daylight Saving Time boundaries (§4, line 165; §11, line 544).
- **The Guardrail**:
Mandate that all time conversions route strictly through `kotlinx-datetime:0.8.0` (`toolchain-2026-09.md`, line 27). Implement a parameterized unit test matrix in `shared:commonTest` validating Vancouver, Toronto, UTC, and non-hour offset zones (e.g., `Asia/Kolkata`) over March spring-forward and November fall-back dates (§13, lines 622–625). Add a CI grep check blocking hardcoded hour calculations.

#### 2. KMP Multiplatform KSP and Dependency Desynchronization
- **The Risk**:
When AI agents independently configure Gradle modules across phases, they frequently misalign versions between Kotlin 2.4.20, Compose Multiplatform 1.12.0, KSP 2.4.20-1.0.30, and Room 3.0.2 compiler (`toolchain-2026-09.md`, lines 54–61). Because Windows cannot run native iOS compilation tasks, agents will verify clean builds on Android and commit code that breaks iOS Kotlin/Native metadata or KSP symbol generation.
- **The Guardrail**:
Strictly centralize and pin all dependency coordinates in `gradle/libs.versions.toml`. Forbid coder agents from modifying build configuration files without explicit concierge approval. Enforce that no phase gate closes without a green `compileKotlinIosSimulatorArm64` execution on GitHub Actions.

#### 3. Client-Side Authorization Leakage Across Household Surfaces
- **The Risk**:
Spec §7 (line 360) and §8 (lines 376–410) require backend-enforced row-level security across four distinct visibility modes (`private`, `busy_only`, `title_only`, `full`). During Phase 2 (Households) and Phase 3 (Time Locks), agents often query all household records and filter unshared or private items in Kotlin client memory. This violates §8 (lines 377–378), exposing sensitive calendar titles and private goals to inspection via HTTP proxies or guessed UUIDs.
- **The Guardrail**:
Require that every new entity table ships with corresponding PostgreSQL RLS policies and pgTAP negative tests in `supabase/tests/` before UI integration. The automated test suite must simulate User B accessing User A’s private goals, unshared tasks, and non-participant proposals directly via PostgREST, asserting zero returned rows.

---

BEGIN VERDICT
{
  "backend": "supabase",
  "local_data": "room3",
  "navigation": "classic",
  "ui_kit": "material3",
  "ios_pipeline": "github-actions",
  "confidence": 0.95,
  "phase0_gate_windows": [
    ".\\gradlew.bat :composeApp:assembleDebug --console=plain -q",
    ".\\gradlew.bat :shared:allTests --console=plain -q",
    ".\\gradlew.bat :composeApp:lintDebug ktlintCheck detekt --console=plain -q",
    "supabase test db"
  ],
  "phase0_gate_mac": [
    "./gradlew :composeApp:compileKotlinIosSimulatorArm64 --console=plain -q",
    "./gradlew :shared:iosSimulatorArm64Test --console=plain -q",
    "xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme iosApp -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO"
  ],
  "failure_modes": [
    {
      "risk": "Timezone DST drift and static offset arithmetic hallucinated by agents corrupting shared time-lock state transitions across boundary dates.",
      "guardrail": "Strict kotlinx-datetime:0.8.0 domain enforcement with parameterized commonTest matrices across Vancouver/Toronto/Kolkata DST boundaries and CI grep rules."
    },
    {
      "risk": "KSP and multiplatform Gradle dependency desynchronization between Kotlin 2.4.20, Room 3.0.2, and CMP 1.12.0 breaking iOS compilation silently on Windows.",
      "guardrail": "Version pinning in gradle/libs.versions.toml, restricting build script modifications by coder seats, and mandatory GitHub Actions iOS simulator compile checks."
    },
    {
      "risk": "Client-side filtering of private or busy-only household objects leaking confidential commitments through direct Supabase queries.",
      "guardrail": "Mandatory pgTAP automated RLS negative test suite verifying zero rows returned for unauthorized UUID lookups prior to client UI implementation."
    }
  ],
  "michael_is_wrong_about": "Underestimating the toolchain friction of KMP on Windows, where iOS compilation is impossible without a continuous Mac CI loop, forfeiting Expo's friction-free cloud build ecosystem."
}
END VERDICT
