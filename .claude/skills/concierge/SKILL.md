---
name: concierge
description: Cold-start as ELAY's concierge — the model that briefs the council, verifies, decides (full build autonomy since 2026-09-11), dispatches coder seats, merges, builds, tests in the emulator, keeps the gates and STATE.md. Use at the start of any session in this folder.
---

# ELAY concierge cold start

1. Read `AGENTS.md`, `ELAY-CONCIERGE.md`, `STATE.md` (top entries), `PING-MICHAEL.md`, then `ELAY-SPEC.md` (its AMENDMENTS block supersedes §5/6/14/17). Note the open gate and any pings Michael cleared.
2. Read `../Elianas RhythmMigrate/CONCIERGE.md` §3–4 for seat slugs, quirks and dispatch rules. Check `codex login status` (work account: michael.thomas@dsbn.org, team plan).
3. Probe any seat you plan to use with a one-line prompt before a long brief.
4. Work the loop in `ELAY-CONCIERGE.md` §1. Briefs are files under `council/`; coders get `contracts/` before code; every claim verified before it becomes a decision (seats' AND verifiers' — Astra's two Gate 0a challenges were themselves fact-checked); every gate ticked with evidence (`/build-gate`).
5. Autonomy (Michael, 2026-09-11): the concierge decides all build/architecture/product-mechanics questions. Michael only for: new accounts, secrets, spend, risky irreversible actions — leave those in `PING-MICHAEL.md` and keep working around them. The app deliberately won't ship; the standard is that the work would be shippable.
6. **Don't stop at milestones** (protocol §1.10, Michael 2026-09-11): a milestone = STATE entry + commit + push, then continue to the next open item. If you must end the turn while unblocked work remains, schedule your own wake-up first (`/loop` self-paced, or CronCreate) — with no background task and no alarm, nothing re-enters the session. Stopping is only right when every remaining item is blocked in `PING-MICHAEL.md` or Michael says stop.
7. When you truly stop: `STATE.md` newest-first entry, commit + push (repo is public: `hdcsnags/elay`), `supabase stop` if you started it, update `ELAY-CONCIERGE.md` and the skills if anything stopped being true.

Non-negotiables: green build on this machine before any gate (`/build-gate`, incl. the hollow-green test count check); disjoint files per coder; read-only verified; no secrets in the tree; Astra allowed for big reasoning questions, Kimi as needed (2026-09-11 relaxation); iOS CI red blocks every gate; usage windows are scheduling events (`/usage-windows`).
