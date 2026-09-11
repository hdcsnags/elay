# ELAY-CONCIERGE.md — how this build runs

*Written 2026-09-10 by Fable 5.1 for whichever model sits as ELAY's concierge. This is a Maestro test — Michael's ruling 2026-09-11: **the app will not ship**; the measure of success is the council reasoning together and producing solid, verified work with the concierge at the helm and Michael out of the loop except for genuinely risky actions. Build quality standards do not drop because it won't ship — the test is whether the work would have been shippable. Read `AGENTS.md` first, then this, then `ELAY-SPEC.md`.*

## 0. Roles

| Role | Who | Does |
|---|---|---|
| **Product owner** | Michael | Rules on **risky actions only** (2026-09-11): new accounts (Supabase, GitHub, Apple), secrets, spend, anything irreversible outside this machine. Unblocks via `PING-MICHAEL.md` when present. Reads `STATE.md`. |
| **Concierge** | the model in this folder (Fable/Opus class) | Briefs the council, verifies claims, **decides all build/architecture/product-mechanics questions** (Michael 2026-09-11: "a DB choice etc, you're good to choose the build"), dispatches coders, merges, builds, tests in the emulator, keeps the gates, keeps `STATE.md`. Never lets a seat's word stand unverified. Stalls the build only on a genuine Michael-blocker, and leaves a ping. |
| **Council (read-only)** | Sol (default OpenAI), Astra (final verification only — expensive), Gemini 3.8 / 3.6 (fast first pass; reads images), Kimi K3 swarm (adversarial, quota-bound), Fable 5 (framing) | Argue design and architecture on a read-only copy. Ranked, cited, machine-readable verdicts. |
| **Coders (write)** | Sonnet (claude_code adapter, `CLAW_CLAUDE_MODEL`), Sol (codex workspace-write) | Build one module each in their own clone; return a patch. The concierge reviews and applies. |
| **Researcher** | Sonnet subagent with web tools | Toolchain versions, platform changes, licences. Dated, cited, "UNVERIFIED" where unsure. |

Seat economics (Michael, 2026-09-11, relaxing the 09-06 rule): Sol for routine rounds; **Astra may be called over Sol for bigger reasoning questions**, and **Kimi K3 as needed** (quota permitting). Check `codex login status` is on the work account before dispatching OpenAI seats.

## 1. The loop (every phase, every feature)

1. **Brief.** Write the council brief as a file under `council/` (never argv). Include: the spec sections in play, Michael's words verbatim, the exact questions, the output contract (BEGIN VERDICT JSON). Template in `/council`.
2. **Dispatch read-only** with `council-dispatch.ts` (`/council`), 2–3 seats, blind to each other. Verify read-only. Build the agreement matrix. **Verify every load-bearing claim yourself** (the toolchain sheet, the spec, a quick prototype) before it becomes a decision.
3. **Decide.** Concierge picks the most complete path where the rules allow; anything that touches product shape, money, accounts or the stack goes to Michael as a short list with a default. Log the ruling in `STATE.md`.
4. **Slice.** Split the work into modules with disjoint files and a written interface between them (data classes / SQL / route names in a `contracts/` file first). One coder seat per module; the concierge owns the wiring module.
5. **Dispatch coders** (`MODE=code`), each in its own clone, with: the contract file, the acceptance test to make green, the build command, the rule "touch only these paths". Collect `<seat>.patch`.
6. **Merge and build** on this machine. `git apply --3way`, then the gate commands (`/build-gate`). Red → fix locally if small, else return the patch with the error to the same seat (SendMessage / re-dispatch with the error appended).
7. **Emulator pass.** Install the debug build, drive the new surface with adb, screenshot, look. Fix what you see. Coder seats (Sonnet, Fable 5) can do this themselves inside their clone when given the adb path and the AVD name — ask for screenshots in their report (`/build-gate`).
8. **Gate.** The phase's exit gate (below) is a checklist in `STATE.md`; tick with evidence (command output, screenshot path). Then bump the version, build the AAB, record.
9. **Log and commit.** `STATE.md` newest first; commit the repo; no push unless told.

## 2. Gates (from the spec's exit gates, made checkable)

Every gate also requires: `git status` clean, the build commands in `/build-gate` green, no secrets in the tree.

- **Gate 0a — Architecture.** RULED 2026-09-11: KMP + Compose Multiplatform (Michael) · Supabase via supabase-kt behind ELAY-owned interfaces (council converged) · **Room 3** (Michael: "ship room") · classic navigation-compose · Material 3 + ELAY tokens · GitHub Actions macOS runner, per-PR iOS simulator compile · **public repo** (Michael: "go public"). Record: `council/gate-0a-record/`. Closes when Astra's verification pass returns and the spec is amended to match (§5 table, §6 diagram, §14 repo shape, §17 references) with the amendments recorded in `adr/`.
- **Gate 0b — Foundation.** Repo initialised with `.gitignore`, formatter, linter, unit-test runner; environments (local/dev/prod) named; backend project created **by Michael** (Supabase or Firebase) with keys in `local.properties`/`.env` only; ADRs for: stack, time storage (UTC instant + IANA zone), visibility model, AI gateway boundary; domain vocabulary page. Exit test (spec §11): the concierge can state the domain model, ownership rules, timezone rule and "AI proposes / human commits" without contradiction — write it down as `ADR-000-domain.md`.
- **Gate 1 — Personal planner.** Two test accounts fully isolated (RLS/rules tests pass); capture → clarify → schedule → complete a task on the Android emulator; Today, Inbox, Goal, Task, Plan views exist; sign-in works.
- **Gate 2 — Households.** Invite/accept/decline/leave; visibility on shared objects; removed member loses access immediately; test proving no private object leaks via UI, API, realtime or guessed ids.
- **Gate 3 — Time lock.** Proposal composer in creator-local time with recipient-local preview; state machine accept/decline/counter/cancel/expire/complete with revision history; Vancouver ↔ Toronto tests across DST dates; grep proves no hard-coded offsets.
- **Gates 4–7** follow spec §12–13 (calendar, copilot, execution, hardening) — write them the same way when Gate 3 closes.

## 3. Usage windows, quotas and timers

Seats fail in known ways: OpenAI usage window (~4 h; the adapter output names the reset time), Kimi monthly quota (`403 access_terminated`), agy timeout (~5 min; keep payloads ≤ 18 items), Claude fallback model (`CLAW_CLAUDE_FALLBACK_MODEL`). Treat all as scheduling events:
- Record the reset time in `STATE.md` and in the run's output folder.
- Continue with work that does not need that seat (your own build, another seat, the emulator pass).
- Schedule a wake-up for the reset time (`/usage-windows`: `ScheduleWakeup` in a `/loop`, or `CronCreate`), then re-dispatch **the same brief file** — nothing is lost because prompts live in files.
- Never burn a second seat's quota to compensate for the first unless Michael's rule allows it (Astra is not a fallback for Sol).

## 4. Windows + Android Studio specifics

- JDK: `export JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'` (note the `1`).
- adb / emulator: `D:\AndroidStudio\platform-tools\adb.exe`, `D:\AndroidStudio\emulator\emulator.exe`; AVDs `Pixel_9_Pro_XL12` (Android 17, 16 KB image), `Pixel_Tablet`.
- Kotlin/Native iOS targets do **not** compile on Windows; the shared module's Android and JVM targets do. iOS compiles on one of Michael's Macs (or a GitHub Actions macOS runner / hosted CI — Gate 0a decides). Design so iOS is a target, not a fork; every gate records "iOS: verified on <Mac/runner>" or "iOS: pending".
- Scripts to files, never heredocs with backslashes; Python `newline=""`; adb extras with spaces quoted inside `adb shell "…"`.
- Long paths: keep the repo shallow (`C:\New folder\Elay\app`), enable `git config core.longpaths true`.

## 5. What needs Michael (2026-09-11 ruling — a short list now)

Only: **new accounts** (a fresh Supabase org/project, a new GitHub account, Apple developer) · **secrets** he must generate/hold · **spend** · anything risky/irreversible outside this machine. Everything else — DB choice, app ids, brand placeholders, spec's §16 product questions — the concierge decides and logs. Blockers he must clear go in **`PING-MICHAEL.md`** (keep it current; he clears it when he's around). Work around blockers where possible: local Supabase (Docker) instead of a hosted project; the existing `hdcsnags` GitHub account is available and the repo is ruled **public**.

## 6. Where things live

`ELAY-SPEC.md` (from `Elay.docx`) · `STATE.md` · `council/` (briefs) · `research/` (toolchain sheets) · `adr/` · the app repo itself (created at Gate 0b) · council outputs in Maestro `docs/audits/elay/<run>/` · scratch clones in `C:\New folder\claw-scratch\`.
