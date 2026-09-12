# ELAY — store listing draft (Stage 5)

*C7's grant (contracts/stage5-retention-hardening.md: "Store listing drafts land as
`docs/store-listing.md` (C7 grant) from §B's text") — verbatim from
council/stage5-retention-gemini.md §3 "Store Listing Drafts". Store-shaped only: this is a draft
for a future submission, never itself a submission (no store accounts this stage per the
contract's constraints).*

## Metadata

- **App Name:** `ELAY: Shared Time & Planner`
- **Subtitle (iOS / App Store, 30 char max):** `Time locks across time zones`
- **Short Description (Android / Play Store, 80 char max):** `Plan across time zones without mental math. Private, shared time locks.`

## Full Description

```
Plan across time zones without mental arithmetic.

ELAY is a calm, private time-coordination and planning app designed for pairs, partners, and close collaborators across different time zones.

THE KEY INTERACTION: DUAL-TIME TIME LOCKS
Propose a shared focus block, study session, or call in your own local time. Your partner sees the exact same instant in theirs. No calculating offsets, no Daylight Saving surprises, and no accidental 3:00 AM wake-up calls.

CONSISTENT, CONSENSUAL COORDINATION
• Shared time locks: Propose, accept, counter-propose, or decline. Both calendars stay in sync only when both agree.
• Web RSVP without install: Share a secure web link with your person. They can view dual-time candidate slots, accept, or counter directly from their browser—no account or app install required.
• Honest availability: See when your partner is genuinely free without invading their privacy. ELAY verifies availability against your schedule while keeping appointment details completely private.

A REAL PERSONAL PLANNER UNDERNEATH
ELAY is useful alone before anyone joins you:
• Calm Today dashboard: Track your current commitment, up to three daily focus tasks, and incoming captures.
• Capacity planning: A visual daily capacity gauge shows what fits in your day without red failure alerts or shame mechanics.
• Plan-vs-actual feedback: Record whether sessions ran long or finished early with a single tap. ELAY learns your real pace and suggests realistic durations next time.

PRIVATE BY DEFAULT
No surveillance, no social feeds, no tracking. Your private tasks remain private to your account.

Calm, practical, and clear operations for your shared time.
```

## Keyword Taxonomy

- *Timezone Coordination:* shared calendar timezone, dual time zone planner, timezone converter meetings, cross timezone scheduling, time lock.
- *Pairs & Couples:* couples planner, shared schedule for two, study buddy timer, accountability partner calendar.
- *Calm Productivity:* calm daily planner, capacity planner, no shame task manager, realistic schedule, distraction-free planning.

## Production Screenshot Shot-List (5 Key Frames)

Grounded directly in the working UI built in Stages 1–4 (screenshots UNVERIFIED by this seat — the
lead screenshots at merge per the contract's standing rule; no emulator access from this seat).

1. **Frame 1 — Together Feed: The Dual-Time Lock Card**
   - *Surface:* `ProposalFeed.kt`
   - *Visual:* Incoming proposal card displaying dual-time stamps side-by-side (`"10:00–11:00 AM for you / 2:00–3:00 PM for b2"`), status chip `"Free · matches your busy times"`, and accept/counter action buttons.
   - *Caption:* *"Coordinate across time zones without the mental math."*
2. **Frame 2 — Proposal Composer: Multi-Candidate Negotiation**
   - *Surface:* `ProposalComposerSheet.kt`
   - *Visual:* Composer showing 3 candidate slots with live certainty badges (`"Free per calendar"`, `"You have a scheduled block at this time"`), and duration selector pills.
   - *Caption:* *"Propose up to three candidate times. Agree in seconds."*
3. **Frame 3 — Web RSVP: Zero-Install Collaboration**
   - *Surface:* `stage3-e2e-04-accept-page.html` (Edge Function web leg)
   - *Visual:* Clean mobile browser view: `"Responding as b2"`, dual-time proposal breakdown, single-tap `"Accept Time Lock"` button.
   - *Caption:* *"Your partner can accept or counter from any browser. No app install needed."*
4. **Frame 4 — Plan Screen: Timeline & Capacity Gauge**
   - *Surface:* `PlanScreen.kt`
   - *Visual:* 06:00–24:00 day timeline with colored time blocks, shared `"Together"` tags, and the 4-segment capacity gauge (`"4h 30m planned · Comfortable space"`).
   - *Caption:* *"Realistic capacity planning. See what actually fits in your day."*
5. **Frame 5 — Today View: Calm Focus & Wrap-Up**
   - *Surface:* `TodayScreen.kt`
   - *Visual:* `"Happening now"` focus block, countdown indicator, `"Focus today"` (max 3 outcomes), and the post-session wrap-up card.
   - *Caption:* *"A calm operations assistant for your daily commitments."*

---

Source: council/stage5-retention-gemini.md §3 (Gemini 3.8 Flash, conf 0.96). UNVERIFIED: no live
screenshots exist yet for the shot-list above — the lead captures real screenshots at merge per
the contract's standing rule ("UNVERIFIED flags honest (lead screenshots at merge)").
