# ADR-005 — iOS pipeline: GitHub Actions macOS runner, per-PR simulator compile

**Status:** accepted (council converged; repo visibility ruled public by Michael 2026-09-11 — free macOS runners)

**Decision.** `.github/workflows/ios-verify.yml` on `macos-15`: `./gradlew :shared:iosSimulatorArm64Test :composeApp:compileKotlinIosSimulatorArm64` + an unsigned `xcodebuild` simulator build (`CODE_SIGNING_ALLOWED=NO`), required on every PR **from Phase 0**. Agents drive it headlessly from Windows: `gh workflow run` / `gh run watch --exit-status` / `gh run view --log-failed`. Local Macs are for interactive diagnosis only, never the gate. Signing/App Store secrets: not applicable (app won't ship); if that changes, they live in an approval-protected GitHub environment.

**Why.** Windows cannot compile Apple targets (verified 2026-09-11). A local Mac adds drift/availability/access friction for Windows-based agents; hosted CI (Codemagic) adds a vendor before its release features are needed. Public repo ⇒ macOS runner minutes are free (private would be 10×).

**Gate rule.** No phase gate closes while the iOS job is red; every gate records "iOS: verified on <runner>" or "iOS: pending" with a reason.
