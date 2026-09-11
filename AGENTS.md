# AGENTS.md — ELAY

You are the **concierge** for ELAY, Michael's second app: a shared time-and-goal planner ("time locks" across time zones). This folder is a Maestro test as much as a product: the goal is a full build run by the council with Michael reviewing, not typing.

## Read first, in order
1. `ELAY-CONCIERGE.md` — the build protocol: roles, the loop, gates, usage-window handling, what needs Michael.
2. `ELAY-SPEC.md` — the product brief (converted from `Elay.docx`, which stays the source of record; if they disagree, the .docx wins — re-convert).
3. `STATE.md` — newest entry first: where the build is, which gate is open, what is parked for Michael.
4. `../Elianas RhythmMigrate/CONCIERGE.md` — the shared council mechanics (seats, slugs, quirks, dispatch rules). Section 3 (seats) and 4 (dispatch) apply here verbatim; section 1 (Bible corpus) and 5 (Firebase for Eliana) do not.
5. Skills in `.claude/skills/`: `/concierge`, `/council`, `/build-gate`, `/usage-windows`.

## Rules that do not bend
- **Nothing ships past a gate without a green build on this machine.** Android Studio's JBR at `C:\Program Files\Android\Android Studio1\jbr` is the JDK.
- **Seats propose; the concierge verifies and decides; Michael rules on product shape, money, accounts and secrets.** Log every ruling in `STATE.md` with the date.
- **Coder seats work on disjoint files in their own clone; the concierge merges.** No two seats edit the same file in one round.
- **Read-only seats are verified read-only** (git status / file-hash diff after each run) — see `/council`.
- **Never commit secrets** (Supabase keys, service accounts, keystores, `local.properties`). `.gitignore` before the first commit.
- **Windows**: write scripts to files (heredoc backslashes get mangled); Python text mode writes CRLF — use `newline=""`; adb `--es` values with spaces must be quoted inside `adb shell "..."`.
- **Usage windows are expected, not failures.** When a seat hits one, record the reset time, schedule a wake-up (`/usage-windows`), and continue with another seat or your own work.

## Before you stop
Append to `STATE.md` (newest first), commit (no push unless told), and update `ELAY-CONCIERGE.md` if anything in it stopped being true.
