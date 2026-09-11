# ELAY

A shared time-and-goal planner: propose a "time lock" in your local time, everyone else negotiates it in theirs. Kotlin Multiplatform + Compose Multiplatform (Android-first, iOS a compile target from day one), Supabase (Postgres + RLS) behind ELAY-owned interfaces, Room 3 local cache with a mutation outbox.

**This app is a build experiment, not a product.** It exists to test an AI-council development process (multiple frontier models arguing design read-only, coder seats building modules against written contracts, a concierge model verifying, merging, and gating) with the human owner ruling only on risky actions. The working protocol lives in [`ELAY-CONCIERGE.md`](ELAY-CONCIERGE.md); the product brief in [`ELAY-SPEC.md`](ELAY-SPEC.md); decisions in [`adr/`](adr/) and [`council/`](council/); the session-by-session log in [`STATE.md`](STATE.md).

## Layout

| Path | What |
|---|---|
| `shared/` | KMP library: domain, time-lock state machine, timezone engine, data layer |
| `androidApp/` | Android application (Compose) |
| `iosApp/` | Xcode shell (built on macOS CI) |
| `supabase/` | Migrations, pgTAP RLS tests, edge functions |
| `adr/`, `contracts/`, `council/` | Decisions, coder-facing contracts, council briefs + verdicts |

## Build (Windows dev machine)

```powershell
$env:JAVA_HOME = "<Android Studio JBR>"
.\gradlew.bat ktlintCheck detekt :shared:allTests :androidApp:assembleDebug :androidApp:lintDebug
npx supabase start; npx supabase db reset --local; npx supabase test db --local
```

iOS compiles in CI (`.github/workflows/ios-verify.yml`, macOS runner) — Kotlin/Native Apple targets cannot build on Windows.

## Provenance

Scaffold derived from [JetBrains/KMP-App-Template](https://github.com/Kotlin/KMP-App-Template) (Apache-2.0). Time-zone rule, security model, and the AI boundary are contractual — see `adr/ADR-000-domain.md`.
