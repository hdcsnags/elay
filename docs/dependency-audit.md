# Dependency audit — Stage 5 (2026-09-12)

Deliverable per `contracts/stage5-retention-hardening.md` (MASVS item 8): a written audit,
NOT a version bump — bumping the toolchain immediately before a hardening gate trades a
known risk for an unmeasured one.

## Versions in use (gradle/libs.versions.toml)
| key | version | status |
|---|---|---|
| elay-version | 1.0.0 | stable |
| compose-material-icons | 1.7.3 | stable |
| ksp | 2.3.12 | stable |
| room | 3.0.3 | stable |
| sqlite-bundled | 2.7.1 | stable |
| kotlinx-datetime | 0.8.0 | stable |
| kotlinx-coroutines | 1.11.0 | stable |
| kotlinx-serialization-json | 1.11.0 | stable |
| supabase | 3.8.0 | stable |
| agp | 9.1.0 | stable |
| android-compileSdk | 37 | stable |
| android-compileSdkMinor | 0 | stable |
| android-libraryCompileSdk | 36 | stable |
| android-minSdk | 24 | stable |
| android-targetSdk | 37 | stable |
| androidx-activity | 1.13.0 | stable |
| androidx-lifecycle | 2.11.0-beta01 | PRE-RELEASE |
| androidx-navigation | 2.9.2 | stable |
| compose-material3 | 1.11.0-alpha07 | PRE-RELEASE |
| compose-multiplatform | 1.11.1 | stable |
| kotlin | 2.4.10 | stable |
| ktor | 3.5.1 | stable |
| detekt | 1.23.8 | stable |
| ktlint-gradle | 14.2.0 | stable |

## Notes
- **Pre-release coordinates knowingly shipped** (recorded, accepted for this PoC): any rows
  marked PRE-RELEASE above — chiefly compose-material3 alpha and androidx-lifecycle beta.
  Revisit before any real store submission.
- **supabase-kt 3.8.0**: community-maintained SDK; the app's own failure barriers
  (runCatchingSuspend + mapping-inside-catch everywhere) are the mitigation for wire drift.
- Licenses: AndroidX/Kotlin/Compose (Apache-2.0), supabase-kt (MIT), ktor (Apache-2.0).
  No copyleft dependency in the shipped app.
- Kotlin/AGP/CMP pins were verified against official docs at Gate 0
  (`research/version-pins-2026-09-11.md`) and deliberately not bumped since.

## FLAG_SECURE — declined and recorded (MASVS item 9)
`FLAG_SECURE` on MainActivity is DECLINED for this stage: the sensitive material named in
spec §8 is tokens (never rendered on screen), not plan titles, and users screenshot their
own plans as a feature. Recorded here so the next audit does not re-litigate it.
