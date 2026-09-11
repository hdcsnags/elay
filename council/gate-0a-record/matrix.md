# Agreement matrix — Gate 0a architecture decisions (2026-09-11)

Seats: Sol (gpt-5.6-sol, codex read-only, 131s) · Gemini 3.8 Flash (agy, 65s). Both blind, both read-only verified **clean**, both emitted parseable verdicts.

| Question | Sol | Gemini | Result |
|---|---|---|---|
| Backend | **supabase** (supabase-kt) | **supabase** (supabase-kt) | ✅ CONVERGE |
| Local data | **sqldelight** (mature, fewer compiler-plugin risks) | **room3** (first-party GA, Michael knows Room, bundled sqlite) | ❌ SPLIT — Michael rules |
| Navigation | **classic** navigation-compose | **classic** navigation-compose | ✅ CONVERGE (both cite Nav3's missing non-JVM ui artifact, sheet line 16) |
| UI kit | **material3** + ELAY token/component wrapper | **material3** + ELAY token wrapper (`ElayTheme`) | ✅ CONVERGE (both exclude M3 Expressive: Android-only) |
| iOS pipeline | **github-actions** (+ local Mac for interactive diagnosis) | **github-actions** | ✅ CONVERGE (`gh workflow run` / `gh run watch` loop for agents; per-PR iOS simulator compile mandatory from Phase 0) |
| Challenge | "iOS second" must not mean "compile later" — iOS green in CI from Phase 0 | Same + Michael underestimates Windows-host KMP friction | Converge in substance |
| Confidence | 0.91 | 0.95 | — |

## Concierge verification (against `research/supabase-kt-and-ios-2026-09.md`, Sonnet researcher, web, 2026-09-11)

- **supabase-kt viability**: researcher confirms 3.8.0 stable (2026-08-26, GitHub API), active cadence, native Google (Credential Manager) + Apple sign-in supported, Android/iOS/JVM targets. Sol's "current 3.6.0" is STALE (unverified memory — codex sandbox has no network); does not change the verdict. **Named risk both seats under-weighted: de facto single maintainer (jan-tennert)** — mitigated by both seats' own guardrail (thin ELAY-owned adapters around the SDK).
- **GitLive Firebase wraps native SDKs** (brittle iOS link deps): consistent with toolchain sheet line 30 and researcher §1.
- **GitHub Actions macOS**: researcher confirms it as the standard KMP-iOS path. **Cost fork Michael must know: public repo = free macOS runners; private repo = 10× minute multiplier (~200 effective macOS min/month on the free plan), overage ~$0.062/min (UNVERIFIED against github.com — matches Sol's figure).** Codemagic free 500 min/month is the fallback.
- **Room 3 GA 2026-07-01 / SQLDelight maturity**: both consistent with toolchain sheet lines 22–23. Sol's "SQLDelight 2.3.2 current" UNVERIFIED (sheet says 2.2.1) — re-pin at Gate 0b regardless.
- Both seats' spec citations (§2 nav surfaces, §4 state machine, §7 visibility, §8 RLS, §10 AI gateway) spot-checked against `ELAY-SPEC.md` — accurate.

## The split, argued

- **Sol → SQLDelight**: years of KMP production use; generated typed queries; transparent migrations; fewer compiler-plugin (KSP) interactions — matters when agents edit Gradle config; Room 3 is a 2-month-old KSP-only line.
- **Gemini → Room 3**: Google first-party KMP persistence standard, GA; coroutines-first; `sqlite-bundled` gives identical SQLite behavior on Android and iOS; Michael ships Room today in Eliana's Rhythm — lowest review friction for the human who verifies every patch.

**Concierge default: Room 3** — Michael's ability to read and verify agent output is a ruling axis (his 09-10 words), and he knows Room; first-party beats community for a load-bearing dependency; the KSP-version-alignment risk is the same risk Gemini's guardrail already pins (all versions in `libs.versions.toml`, coder seats forbidden from editing build files). **Written revisit trigger**: if Room/KSP breaks the iOS-simulator compile in Phase 0–1 more than once, switch to SQLDelight before the schema grows.

## Adopted as working decisions pending Michael (protocol §1.3: council converged, concierge verified)

backend=supabase(-kt, pinned, behind ELAY-owned interfaces) · navigation=classic · ui_kit=material3+tokens · ios_pipeline=github-actions with per-PR simulator compile. Astra verification pass runs on the full Gate 0a decision record after Michael rules on the split (seat economics: one Astra pass, at the end).
