Coder seat C1 — ELAY Phase 1: Today, Inbox, Plan surfaces on fake repositories. You have WRITE access in your own clone; your diff is collected as a patch for concierge review.

READ FIRST: `contracts/phase1-planner.md` (§5 frozen interfaces, §6 your paths), `ELAY-SPEC.md` §2 (the experience model — the tone rule "calm operations assistant" and the anti-nag table are binding design input), `adr/ADR-004` (ElayTheme is the design layer), frozen `domain/model/PlannerModels.kt` + `domain/repository/PlannerRepository.kt` + `ui/Routes.kt`, and the shell in `shared/src/commonMain/kotlin/dev/elay/App.kt`.

BUILD, only under `shared/src/commonMain/kotlin/dev/elay/ui/` (+ commonTest):
1. `ui/fake/FakePlannerRepository.kt` — in-memory PlannerRepository with mutable state flows and a seeded, realistic dataset (a study goal with milestones, 6–8 tasks across states/priorities, 3 unparsed captures, time blocks today ± 3 days incl. one all-day). This is the ONLY data source until the data layer lands; construction happens in App wiring later — expose a factory.
2. `ui/today/TodayScreen.kt` + ViewModel: current/next time block with start-in countdown text, top ≤3 focus tasks (priority desc), inbox count chip, block list for today rendered in the device zone via kotlinx-datetime (NO fixed offsets — ADR-006). Complete/„not today" actions call the repository.
3. `ui/inbox/InboxScreen.kt` + ViewModel: capture list (newest first), quick-add text field (one tap to capture — "dump now, sort later"), clarify-to-task action (creates a Task via repository, marks capture clarified), dismiss.
4. `ui/plan/PlanScreen.kt` + ViewModel: vertical day timeline (06:00–24:00) for a selectable day (prev/today/next), blocks positioned by start/duration, unscheduled-task rail beneath, tap block → detail placeholder.
5. Replace the three PlaceholderScreen wirings for Today/Inbox/Plan in `App.kt`'s NavHost with your screens (touch ONLY those composable lambdas — the shell/bar/routes stay).
6. ViewModel unit tests in commonTest against the fake: today filtering at day boundaries, clarify flow moves capture→task, plan positioning math (pure function, test it directly).

M3 components under `ElayTheme` only; no new colors/typography (tokens come later); every interactive element gets a contentDescription; empty states designed (calm, one action), never a red failure banner (spec §2).

TOUCH ONLY: `shared/src/commonMain/kotlin/dev/elay/ui/**` (except Routes.kt — frozen), the three NavHost lambdas in App.kt, commonTest ui tests. Never: gradle files, domain/, data/, sync/, .github/, contracts/.

VERIFY: `export JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'` then `./gradlew.bat :shared:allTests :androidApp:assembleDebug ktlintCheck detekt --console=plain --no-daemon` — all green + executed-test count. Do NOT drive the emulator (concierge does the screenshot pass on merge).

Report: what you built · commands + last lines · test count · UNVERIFIED items. Begin with the model you are running as.
