# MASTER BUILD PLAN v2 — the pairs-first pivot (Big Fable synthesis, 2026-09-12)

*Source: product round `docs/audits/elay/2026-09-12-product-assessment/` — Sol (conf 0.90), Gemini (0.95), blind Fable 5 seat (0.72), all read-only clean. **Unanimous verdict: pivot.** Adopted by the lead under the 2026-09-11 autonomy grant; Michael may veto any line. Supersedes sequencing in spec §11–12 where they conflict; foundations (ADRs 000–010, schema, contracts) stand — this reshapes scope and order, not architecture.*

## The product, restated
ELAY V1 is a **private coordination app for a recurring pair**: propose a shared time in your zone, they see it in theirs (dual-time everywhere), agree in seconds — with a personal planner spine underneath so it's useful alone. NOT four audiences, NOT a PM tool, NOT an AI planner. Week-one hook (converged): *propose → accept → on both calendars in under ~15 seconds, three "no timezone math" moments in week one.*

## Scope rulings (council-converged, lead-adopted)
- **CUT from V1**: Review tab; Goals as a nav surface (goal linkage stays in data; UI folds into Plan later); milestone machinery in UI; group/quorum mechanics (pairs only); multiple households; AI except the brain-dump parser (Phase 5, feature-flagged); all Growth/Pro items.
- **NAV**: four tabs — **Today · Plan · Together · Inbox**. Settings via Today header. Routes stay in the frozen file; Goals/Review routes remain (hidden from the bar) so nothing breaks.
- **ADD**: no-install **web RSVP** (signed expiring token → browser page with dual-time preview, accept/decline/counter; edge function + public response table); 1–3 candidate times per proposal (**ADR-010 §3 amended** — multiple candidates per revision, atomic winner selection); dual-time preview as the signature visual in composer, cards, notifications; proposal inbox with response deadlines; post-session "ran long/finished early/reschedule" feedback.
- **RESEQUENCE**: conflict-only calendar read moves before time-lock beta (honest availability labels: "free per ELAY" vs "free per calendar"); onboarding celebrates "invite your person."

## Stages and lanes (parallel where disjoint; lead verifies every merge)
| Stage | What ships | Lanes |
|---|---|---|
| **0 — Personal spine** (in flight) | Sign-in, real repository (seat R now), DI wiring, 4-tab reshape, Gate 1 E2E: 2 accounts, capture→clarify→schedule→complete, persistent | Sonnet-R (repo) · lead (DI/auth/nav) |
| **1 — Pairing + push** | Invite→accept for a pair, member visibility basics, FCM push, deep links | Sol writes the pairing contract · Sonnet builds · lead wires |
| **2 — Time-lock core** | Proposal composer (1–3 candidates, dual-time), state machine server-side, proposal inbox, revision history, DST test matrix | Sol: contracts + adversarial tests · Sonnet ×2 (server RPCs / client UI) · Gemini: screen+copy specs · **Astra: pre-gate verification pass** |
| **3 — Web RSVP** | Signed expiring token, edge-function response page (dual-time, no install), counter from web | Sol: security contract (token scope/expiry/abuse) · Sonnet builds · lead verifies |
| **4 — Honest availability** | Google Calendar conflict-only read, certainty labels, capacity gauge (Gemini's) | contract first; Kimi adversarial pass when quota refreshes |
| **5 — Retention + hardening** | Plan-vs-actual loop, next-time widget, MASVS pass, store-shaped artifacts | Sol security lane · lead gates |

## Lane assignments (by demonstrated strength this run, not self-claims alone)
- **Sol** — contracts + adversarial verification lane (its verification work was the sharpest; its write sandbox is broken on this box anyway, so read-only fits twice over). Note: honest even when blocked.
- **Gemini** — UX/copy/screen-spec lane (claimed the backend lane, but its demonstrated edge is fast, concrete product/UX reads — fuel gauge, warm dual-time cards, 4-tab cut; backend code goes to Sonnet under Sol-written contracts).
- **Sonnet** — coder seats (5-for-5 on honest reports and boundary discipline this run).
- **Astra** — reserved for the Stage-2 time-lock verification pass (highest-stakes correctness) and the final pre-"launch" audit.
- **Kimi** — adversarial personas on negotiation/privacy surfaces when the monthly quota refreshes (403 until next cycle, probed 2026-09-12).
- **Fable (lead)** — briefs, merges, re-verifies every green, wires, gates, never parks the loop (§1.10).

## What Michael can veto (defaults applied meanwhile)
The pivot itself · the 4-tab set · pairs-only V1 · web-RSVP inclusion · ADR-010 candidates amendment. Everything else is execution.
