# STATE — ELAY

*Append-only session log, newest first. Every claim carries a date or is marked unverified.*

## Where the build is

| Item | Value |
|---|---|
| Phase | **0 — Product contract & repository foundation** (not started) |
| Open gate | **Gate 0a — architecture decisions** (verdicts in 2026-09-11: Sol + Gemini converge on supabase-kt · classic nav · Material 3 + tokens · GitHub Actions iOS; SPLIT on Room 3 vs SQLDelight — parked for Michael, concierge default Room 3. Astra verification after Michael rules. Matrix: Maestro `docs/audits/elay/2026-09-11-architecture-decisions/matrix.md`) |
| Stack | **KMP + Compose Multiplatform** (Michael's ruling 2026-09-11; the brief's RN + Expo path is dropped — Expo apps read as "pretty ugly" to him and he has Macs for iOS). Open: backend (Supabase via supabase-kt vs Firebase via GitLive), Room 3 vs SQLDelight, classic nav vs Nav 3, iOS pipeline (local Mac vs GitHub Actions macOS runner vs hosted CI) |
| Repo | this folder is not a git repo yet; `git init` is part of Phase 0 |
| Toolchain sheet | `research/toolchain-2026-09.md` (Sonnet research 2026-09-10; UNVERIFIED items flagged — re-check at Gate 0b) |
| Parked for Michael | **Local-data ruling (Room 3 vs SQLDelight — the one council split; default Room 3)** · ratify backend=Supabase and create the Supabase project + keys (Gate 0b, his hands per protocol) · **GitHub repo public vs private** (public = free macOS runners; private = 10× minute multiplier) · app IDs / bundle IDs · brand name + icon direction · which two test accounts · Apple developer account timing |

## Session log

### 2026-09-11 — Claude Fable 5 (Michael's rulings: full build autonomy; Room 3; public repo)

**Rulings (Michael, 2026-09-11, verbatim where it matters):** (1) **"Go public"** — the repo is public (free macOS runners). (2) **"Ship room"** — local data = Room 3; split resolved. (3) **Build autonomy:** "only stall the build when you think you need me… a DB choice etc, you're good to choose the build." The concierge now decides all build/architecture/product-mechanics questions; Michael is needed only for **risky actions**: new accounts (Supabase, GitHub), secrets, spend, anything irreversible outside this machine. (4) **Purpose:** "this project actually wont ship… this is a Maestro test really as a Orchestrator with you at the helm" — success metric is the council reasoning together and producing solid work without Michael in the loop. He has a similar private project; ELAY ideas may feed it. (5) **Seat economics relaxed:** "you can call astra over sol for bigger reasoning questions or Kimi k3 as need be." (6) **Borrow** patterns/tricks from Eliana's Rhythm and **Oris** (Michael's pointer: `C:\New folder\Oris fixing storage issue\oris\project`). (7) **Ping mechanism:** work around blockers, leave a ping list he can clear when present → `PING-MICHAEL.md`.

### 2026-09-11 — Claude Fable 5 (concierge: Gate 0a council dispatched)

Cold start per `/concierge`. Codex verified on the work account (michael.thomas@dsbn.org, **team** plan) before dispatching OpenAI seats. Probed Sol + Gemini with a one-liner (both green, read-only clean → `docs/audits/elay/2026-09-11-probe/`). First dispatch hit ENOENT — Michael's brief re-cut raced it (old filename); re-dispatched `council/brief-00-architecture-decisions.md` to **Sol + Gemini** (read-only, 25-min cap → `docs/audits/elay/2026-09-11-architecture-decisions/`). In parallel, researcher seat (Sonnet, web) dispatched on the two load-bearing unknowns: supabase-kt maturity/auth support and the cheapest iOS-build path for a solo Windows dev → `research/supabase-kt-and-ios-2026-09.md`. **Verdicts (both seats green, read-only clean, JSON parsed; Sol 131s conf 0.91, Gemini 65s conf 0.95):** converge on **Supabase via supabase-kt** (relational/RLS fit of §7–8, transactional time-lock state machine §4, Edge Functions AI gateway §10; GitLive rejected for native-wrapper brittleness), **classic navigation-compose** (Nav3 lacks non-JVM ui artifact), **Material 3 + ELAY token wrapper** (Expressive is Android-only), **GitHub Actions macOS runner** for iOS with per-PR simulator compile from Phase 0 ("iOS second ≠ compile later" — both seats). **Split: Sol → SQLDelight (maturity), Gemini → Room 3 (first-party GA, Michael's fluency)** — parked for Michael, concierge default Room 3 with a written revisit trigger. Concierge verification against the researcher sheet: supabase-kt 3.8.0 active but **single-maintainer** (thin adapters mandatory); Sol's "3.6.0" stale; macOS runner cost fork (public repo free vs private 10×) surfaced to Michael. Matrix + full reports: Maestro `docs/audits/elay/2026-09-11-architecture-decisions/`. Astra verification pass deferred until Michael rules (one Astra pass at the end, per seat economics). Repo still not git — first commit lands with Gate 0b scaffold.

### 2026-09-11 — Claude Fable 5.1 (Michael's ruling: KMP; brief re-cut)

Michael: Expo is "pretty ugly, kinda sucks"; he can wait for the Mac, use a GitHub worker, or a hosted build; and he has several Macs. So the stack IS Kotlin Multiplatform + Compose Multiplatform, and Gate 0a narrows to backend / local data / navigation / iOS pipeline — `council/brief-00-architecture-decisions.md` replaces the stack brief. He also corrected the protocol: seats CAN spawn the emulator and click through the app themselves (adb) — written into `/build-gate`.

### 2026-09-10 — Claude Fable 5.1 (bootstrap, from the Eliana's Rhythm session)

Michael: "I want to create a side app here … from scratch for Android and if I can go the KMP path upfront so iPhone can work … use the council for UI/UX design considerations … see if I can use maestro to do a full build without me." Bootstrapped this folder so the next session cold-starts as ELAY's concierge: `AGENTS.md`, `ELAY-CONCIERGE.md` (the protocol), `ELAY-SPEC.md` (converted from `Elay.docx`, 5,268 words, 40 headings), `.claude/skills/` (concierge, council, build-gate, usage-windows), `council/brief-00-stack-decision.md`, this file. Added a generic `council-dispatch.ts` to Maestro so no per-study TypeScript is needed. **Finding:** the brief recommends React Native + Expo + Supabase, not Kotlin — the first gate is that decision, and it is Michael's after the council argues it.
