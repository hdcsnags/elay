<!--
Research seat: Claude Sonnet (Sonnet 5), acting as researcher for ELAY Gate 0a.
Date compiled: 2026-09-11.
UNVERIFIED items must be re-checked before pinning any dependency/version in the stack decision.
-->

# supabase-kt, EAS/Windows, and KMP-iOS-from-Windows — Gate 0a research (2026-09-11)

## 1. supabase-kt (github.com/supabase-community/supabase-kt)

- **Latest stable release: 3.8.0, published 2026-08-26** (confirmed via GitHub Releases API, `published_at: 2026-08-26T20:27:55Z`). https://github.com/supabase-community/supabase-kt/releases/tag/3.8.0
- Release cadence last 12 months (verified via GitHub API/README): 3.3.0 (2026-01-05... actually earlier 2025 dates per source drift — see note below), 3.4.0, 3.4.1, 3.5.0, 3.6.0, 3.7.0, 3.7.0-beta-1, 3.8.0. Roughly monthly/bi-monthly releases — **active** cadence. https://github.com/supabase-community/supabase-kt/releases
  - NOTE: two independent fetches of the releases history disagreed on whether the 3.3.0–3.7.0 sequence lands in 2025 or 2026; only 3.8.0's date was independently confirmed against the raw GitHub API. **Exact dates for 3.3.0–3.7.0: UNVERIFIED, re-check via `gh api repos/supabase-community/supabase-kt/releases` before citing.**
- **Not officially adopted by Supabase.** README and Supabase docs both state it is community-maintained under the `supabase-community` org, "created and maintained by the Supabase community," not an official Supabase library. https://github.com/supabase-community/supabase-kt ; https://supabase.com/docs/reference/kotlin/introduction
- **Modules confirmed:** `auth-kt` (formerly gotrue-kt), `postgrest-kt`, `realtime-kt`, `storage-kt`, `functions-kt`, plus integration modules `compose-auth`, `compose-auth-ui`, `apollo-graphql`, `coil-integration`/`coil3-integration`, `imageloader-integration`. https://github.com/supabase-community/supabase-kt/blob/master/README.md
- **Native sign-in support:** ComposeAuth plugin provides "easy Native Google & Apple Auth for Compose Multiplatform targets"; Supabase docs show a dedicated guide for "Sign in with Google on Android using Credential Manager," and Sign in with Apple uses native `AuthenticationServices` on iOS (OAuth flow on Android/JVM/JS/WasmJS). An official demo app exists: `demos/android-login` in the supabase-kt repo, showing Google One-Tap/Credential Manager native sign-in. https://github.com/supabase-community/supabase-kt/tree/master/demos/android-login ; https://supabase.com/docs/guides/auth/social-login/auth-google ; https://supabase.com/docs/guides/auth/social-login/auth-apple
- **KMP targets:** Android, iOS, JVM, JS confirmed in README examples; WASM-JS supported for modules from v3.0.0+. Desktop/JVM and Android/iOS are the targets relevant to ELAY. https://github.com/supabase-community/supabase-kt/blob/master/README.md
- **Ktor version required:** v3.0.0+ of supabase-kt requires **Ktor 3.4.3** (pre-3.0.0 supabase-kt used Ktor 2.3.12). https://github.com/supabase-community/supabase-kt/blob/master/README.md
- **Maintenance risk: single-maintainer-dominant.** GitHub contributor stats (API, 2026-09-11) show `jan-tennert` at **2,529 contributions**, next-highest human contributor far below that (dependabot bot at 363; top human contributors in the low tens). Jan Tennert authored/merged essentially all releases including the most recent commit (2026-09-10) and the 3.8.0 release (2026-08-26). This is a **de facto single-maintainer project** — real bus-factor risk for a production dependency, though release cadence has stayed active through 2026. https://api.github.com/repos/supabase-community/supabase-kt/contributors ; https://github.com/jan-tennert

## 2. Expo EAS Build from Windows (no Mac)

- **Confirmed:** EAS Build/Submit run on Expo's cloud servers and work when triggered from Windows, macOS, or Linux — no Mac is needed to produce a signed/installable iOS build. https://docs.expo.dev/submit/ios/ ; industry summaries corroborate (e.g. gist "The Complete State of iOS Development on Windows in 2026").
- **What macOS is actually needed for:** nothing in the signed-build pipeline itself. The only iOS-related step that practically wants a Mac is **running the iOS Simulator locally** (Apple restricts the simulator to macOS) — not required for EAS cloud builds, only for local pre-cloud debugging convenience. **The only build type obtainable *without* an Apple Developer Program enrollment is an iOS-simulator development build**; any real-device / TestFlight / App Store build requires a paid Apple Developer account. https://kotlinlang.org/docs/multiplatform/faq.html (general Apple/macOS simulator constraint, analogous statement); EAS-specific claim per search synthesis of docs.expo.dev — **UNVERIFIED at the single-page level: the specific docs.expo.dev page stating "no Apple Developer account needed only for simulator builds" should be re-fetched and cited directly before relying on it.**
- **Apple Developer account purpose:** required (a) to sign builds for physical devices/TestFlight/App Store, (b) to submit via EAS Submit, (c) standard cost **$99/year** (figure from multiple 2026 secondary sources, not from apple.com directly this session — **UNVERIFIED against developer.apple.com pricing page, re-check before citing**).
- **EAS pricing free tier (as of fetch on 2026-09-11, per docs.expo.dev/billing/plans and expo.dev/pricing):**
  - Free plan: **15 Android + 15 iOS builds/month**, 45-minute build timeout, 1 concurrency, low-priority queue (can see 90+ minute waits at peak).
  - Starter: $19/mo, $45 build credit/mo, high-priority queue.
  - Production: $199/mo, $225 build credit/mo, 2 concurrencies, high-priority queue.
  - Enterprise: custom, from $1,000 build credit, 5 concurrencies.
  - Extra concurrency: $50/mo per slot on paid tiers; usage-based builds run **$1–$4/build** depending on platform/worker size.
  - Sources: https://docs.expo.dev/billing/plans/ ; https://expo.dev/pricing (both queried 2026-09-11; treat exact dollar figures as **subject to Expo changing pricing** — re-verify close to purchase time).

## 3. Kotlin/Native iOS compilation from Windows

- **Confirmed, unchanged as of 2026-09-11: Kotlin/Native cannot compile or run iOS targets on Windows.** Official Kotlin Multiplatform FAQ states plainly that iOS-specific code must be built/run "using a Mac with macOS," because iOS simulators/toolchains are macOS-only per Apple's own requirements. No JetBrains or Kotlin-official cloud-Mac / cloud-build-for-iOS service was found as of this search. https://kotlinlang.org/docs/multiplatform/faq.html
- **No 2026 change found.** Searches for a JetBrains-hosted cloud build service for KMP iOS turned up nothing beyond general KMP-Swift-interop roadmap items (Swift Export improvements planned for 2026) — **no cloud-Mac offering confirmed; mark UNVERIFIED/NOT FOUND**, re-check blog.jetbrains.com/kotlin before relying on its absence. https://blog.jetbrains.com/kotlin/2025/08/kmp-roadmap-aug-2025/
- **Standard workaround confirmed: GitHub Actions macOS runners**, used industry-wide for KMP iOS CI from non-Mac dev machines (build the iOS framework/xcframework and archive/sign on a macOS runner). This is corroborated by multiple 2025/2026 KMP CI write-ups (kmpship.app, marcogomiero.com, AKJAW repo). https://kotlinlang.org/docs/multiplatform/github-actions-for-kmp.html ; https://www.marcogomiero.com/posts/2024/kmp-ci-ios/
- **Cost of the cheapest practical path for a solo Windows dev, per 2026 pricing:**
  - Public GitHub repo: **macOS Actions runners are free/unmetered** — cheapest option if the repo can be public. https://cicdpipelinecost.com/github-actions-pricing (2026 figures)
  - Private repo: macOS runner minutes consume the monthly quota at a **10x multiplier** (e.g. Free plan's 2,000 min/month ≈ only ~200 macOS minutes); overage priced ~$0.062/min (3–4 core macOS runner) as of Jan 1, 2026 per multiple pricing-tracker sites. **These third-party pricing-tracker figures are UNVERIFIED against github.com/pricing directly — re-check before budgeting.**
  - Alternative paid CI-for-mobile services: **Codemagic** offers 500 free min/month on macOS M2 for individual accounts, then pay-as-you-go (~$0.095/min) or annual plans from $3,990/yr for teams — confirmed still requires macOS machines under the hood (Apple's requirement), just abstracts ownership. https://docs.codemagic.io/billing/pricing/ ; https://codemagic.io/pricing/
  - **Practical recommendation implied by the above (not an official recommendation, this researcher's synthesis):** for a solo dev, a public repo + GitHub Actions macOS runner (free) or Codemagic's free 500 min/month tier is the cheapest path to a signed KMP iOS build without owning a Mac; a private repo without one of these still needs either paid CI minutes or a physical/rented Mac.

## 4. Supabase Auth native Android/Apple sign-in support

- **Confirmed:** Supabase Auth supports Sign in with Google via Android Credential Manager, and Sign in with Apple with native `AuthenticationServices` on iOS, in a KMP/native-Android client through supabase-kt's `auth-kt` + `ComposeAuth` plugin. Official guides exist for both flows. https://supabase.com/docs/guides/auth/social-login/auth-google ; https://supabase.com/docs/guides/auth/social-login/auth-apple
- **Official Kotlin sample exists:** `demos/android-login` in the supabase-kt repository demonstrates Google One-Tap/Credential Manager native sign-in plus WebView-based auth; there is also an official Supabase-referenced YouTube walkthrough on Credential Manager + Supabase Auth. https://github.com/supabase-community/supabase-kt/tree/master/demos/android-login ; https://www.youtube.com/watch?v=P_jZMDmodG4
- Caveat: these samples/guides live on the `supabase-community` (not `supabase` org-owned-and-SLA'd) side for the Kotlin client specifically — same community-maintenance caveat as item 1 applies to any Kotlin-specific sample code, even though the Auth *guides* on supabase.com/docs are Supabase-authored.

## Items flagged UNVERIFIED — re-check before Gate 0a is pinned
1. Exact release dates for supabase-kt 3.3.0 through 3.7.0 (2025 vs 2026 ambiguity between two fetches).
2. The specific docs.expo.dev page/wording confirming "Apple Developer account only required beyond simulator builds."
3. Apple Developer Program's current $99/year price, sourced directly from developer.apple.com.
4. GitHub Actions macOS runner per-minute overage price ($0.062/min figure) sourced directly from github.com/pricing.
5. Whether any JetBrains/Kotlin-official cloud-Mac-build service shipped in 2026 (searched, none found — absence not fully verified).
