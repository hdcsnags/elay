# gemini — agy_cli ((default)) — 2026-09-12-council

- success: true
- elapsed: 76s
- read-only verification: **clean**
- copy: C:\New folder\claw-scratch\elay-gemini-gPgpg1\repo


## Output

**Model:** Gemini 3.8 Flash

---

# §B — PRODUCT/UX SPECIFICATION: STAGE 5 (RETENTION & HARDENING)

## 1. The Post-Session Feedback Moment

### 1.1 Placement and Trigger Mechanics
Under `council/master-plan-v2.md` and `ELAY-SPEC.md` §3 ("Plan-vs-actual feedback: users can mark 'finished early,' 'ran long,' 'interrupted,' or 'rescheduled'"), the feedback moment closes the loop on time locks without creating administrative friction.

The feedback moment appears on the **Today tab** ([`TodayScreen.kt`](file:///C:/New%20folder/claw-scratch/elay-gemini-gPgpg1/repo/shared/src/commonMain/kotlin/dev/elay/ui/today/TodayScreen.kt#L87-L98)). Currently, `TodayViewModel` filters finished blocks out of `currentBlock` and `nextBlock` using `FINISHED_BLOCK_STATUSES` ([`TodayViewModel.kt`](file:///C:/New%20folder/claw-scratch/elay-gemini-gPgpg1/repo/shared/src/commonMain/kotlin/dev/elay/ui/today/TodayViewModel.kt#L33-L55)), leaving elapsed blocks stranded in the static schedule list.

The feedback moment triggers under two conditions:
1. **Explicit Completion:** The user taps `Complete` on `HighlightedBlockCard` ([`TodayScreen.kt`](file:///C:/New%20folder/claw-scratch/elay-gemini-gPgpg1/repo/shared/src/commonMain/kotlin/dev/elay/ui/today/TodayScreen.kt#L210)).
2. **Elapsed Boundary:** The block's scheduled window has elapsed (`now >= block.endsAt`) while its status remains `Scheduled`.

When triggered, a transient **Wrap-up Card** takes the top highlight slot on Today (above `Focus today`). If multiple blocks elapse simultaneously, only the most recently ended block displays wrap-up prompts; older elapsed blocks gracefully fall back to the timeline without stacking backlogs.

### 1.2 Calm Copy Specifications
Following `ELAY-SPEC.md` §2 ("The app should behave like a calm operations assistant: clear, brief, practical, and non-judgmental. 'Move this to tomorrow?' is better than 'You missed your goal'"), feedback copy avoids guilt, interrogation, or celebration tropes:

*   **Card Header:** `"Session wrap-up"`
*   **Card Subtitle:** `"{Title} · Scheduled for {duration}m"` (e.g., `"Study session · Scheduled for 60m"`)
*   **Prompt Text:** `"How did the time go?"`
*   **Primary Actions (One-Tap Chips):**
    *   **Finished Early:** `"Finished early"` (subtext: `"-15m"`)
    *   **On Time:** `"Right on time"` (subtext: `"60m"`)
    *   **Ran Long:** `"Ran long"` (subtext: `"+20m"`)
    *   **Didn't Happen:** `"Didn't happen"` (neutral, non-punitive)
    *   **Reschedule:** `"Reschedule"` (opens day mover)

### 1.3 One-Tap Ergonomics & State Transition
The feedback component uses a single horizontal row of elevated tonal chips. 
1. **Single Tap Recording:** Tapping any chip immediately commits the outcome locally via `session_outcomes`.
2. **Subtle Acknowledgement:** The card collapses inline with a gentle 250ms fade into a single calm confirmation label: `"Noted for next time."`
3. **Transient Vanish:** The confirmation persists for 2.5 seconds or until the user scrolls, then disappears, returning Today to its standard upcoming state.
4. **No Mandatory Sub-Sheets:** No mandatory text notes, sliders, or secondary confirmation modals. If the user taps `"Ran long"`, a small inline pill selector (`+15m`, `+30m`, `+45m`) expands smoothly below the row with `+15m` pre-selected, confirmable with a second tap or auto-saved after 3 seconds of inactivity.

### 1.4 Skip-Ability & Anti-Nag Guardrails (Spec §2)
In accordance with `ELAY-SPEC.md` §2 ("Avoid broken streaks or red failure banners" and "Plans change: one-tap move"):
*   **Explicit Dismissal:** A quiet `"Dismiss"` text button (or `×` icon with `contentDescription = "Skip feedback"`) sits in the top-right corner of the card. Tapping it drops the prompt permanently for that block without penalty.
*   **Time-to-Live (TTL):** The wrap-up prompt remains visible for at most **4 hours** post-session. Once `now > block.endsAt + 4.hours`, or once the user's local day crosses midnight, the prompt silently expires.
*   **Zero Shame Backlog:** Unanswered feedback cards never collect into an "Unreviewed" badge, unread counter, or inbox task. If skipped or ignored, the block remains recorded as scheduled.

---

## 2. The Next-Time Surface

### 2.1 Primary Surface Selection: Today In-App Card
Per the Scope Ruling in `COUNCIL_PROMPT.md` §5 ("the next-time widget: an in-app Today/Plan surface, NOT an OS home-screen widget — no Glance/WidgetKit this stage"), the **primary surface for this stage is a dedicated Today Card** (`today-card`).

While composer prefill is essential when drafting new proposals, composer prefill alone is reactive—it only aids users who have already initiated a proposal. A calm, intelligent card on the **Today surface** actively drives retention and habit formation: it surfaces the learning from the previous session at the exact moment the user checks their day.

### 2.2 Visual Anatomy & Card Copy
The Next-Time Card renders directly below the active/next block slot or in the empty-schedule area on Today:

```
┌────────────────────────────────────────────────────────┐
│ NEXT TIME TOGETHER                                     │
│ Study session with b2                                  │
│ Last time ran 20m long. Book 80m this time?            │
│                                                        │
│ [ Propose 80m ]                 [ Keep 60m ]   [ Skip ]│
└────────────────────────────────────────────────────────┘
```

*   **Overline:** `"NEXT TIME TOGETHER"` (Style: `MaterialTheme.typography.labelSmall`, muted secondary).
*   **Title:** `"{Session Title} with {Partner Name}"` (Style: `MaterialTheme.typography.titleMedium`).
*   **Body Rationale (Dynamic):**
    *   *If previous ran long:* `"Last time ran {delta}m long. Book {suggested}m this time?"`
    *   *If previous finished early:* `"Finished {delta}m early last time. Book {suggested}m?"`
    *   *If previous didn't happen:* `"Study session didn't happen on Sunday. Try again this week?"`
*   **Primary Action Button:** `"[ Propose {suggested}m ]"` (Style: Filled Button). Tapping this deep-links directly into [`ProposalComposerSheet.kt`](file:///C:/New%20folder/claw-scratch/elay-gemini-gPgpg1/repo/shared/src/commonMain/kotlin/dev/elay/ui/together/proposal/ProposalComposerSheet.kt) with duration pre-selected to the adjusted duration and title pre-filled.
*   **Secondary Action Button:** `"[ Keep {standard}m ]"` (Style: Outlined Button). Opens the composer with standard baseline duration.
*   **Tertiary Dismissal:** `"[ Skip ]"` (Style: TextButton). Dismisses the suggestion for the current cycle.

### 2.3 Surface Triggers & Lifecycle
*   **Appearance Criteria:** Appears when:
    1. A completed lock has a recorded `session_outcome` with variance (`ran_long`, `finished_early`, or `rescheduled`).
    2. There is no active lock currently scheduled with that partner in the next 24 hours.
    3. Today has capacity (i.e. capacity gauge is not in `CAPACITY_OVERFLOW_COLOR` per [`PlanScreen.kt`](file:///C:/New%20folder/claw-scratch/elay-gemini-gPgpg1/repo/shared/src/commonMain/kotlin/dev/elay/ui/plan/PlanScreen.kt#L84)).
*   **Suppression:** Only one Next-Time card appears on Today at any time. Once dismissed or acted upon, it does not reappear until a subsequent session outcome is logged.

---

## 3. Store Listing Drafts

Drafted strictly under `ELAY-SPEC.md` §2 (calm ops voice), §4 (timezone truth), §6 (privacy without surveillance), and §13 (store readiness):

### 3.1 Metadata
*   **App Name:** `ELAY: Shared Time & Planner`
*   **Subtitle (iOS / App Store, 30 char max):** `Time locks across time zones`
*   **Short Description (Android / Play Store, 80 char max):** `Plan across time zones without mental math. Private, shared time locks.`

### 3.2 Full Description
```markdown
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

### 3.3 Keyword Taxonomy
*   *Timezone Coordination:* shared calendar timezone, dual time zone planner, timezone converter meetings, cross timezone scheduling, time lock.
*   *Pairs & Couples:* couples planner, shared schedule for two, study buddy timer, accountability partner calendar.
*   *Calm Productivity:* calm daily planner, capacity planner, no shame task manager, realistic schedule, distraction-free planning.

### 3.4 Production Screenshot Shot-List (5 Key Frames)
Grounded directly in the working UI built in Stages 1–4:

1.  **Frame 1 — Together Feed: The Dual-Time Lock Card**
    *   *Surface:* [`ProposalFeed.kt`](file:///C:/New%20folder/claw-scratch/elay-gemini-gPgpg1/repo/shared/src/commonMain/kotlin/dev/elay/ui/together/proposal/ProposalFeed.kt)
    *   *Visual:* Incoming proposal card displaying dual-time stamps side-by-side (`"10:00–11:00 AM for you / 2:00–3:00 PM for b2"`), status chip `"Free · matches your busy times"`, and accept/counter action buttons.
    *   *Caption:* *"Coordinate across time zones without the mental math."*
2.  **Frame 2 — Proposal Composer: Multi-Candidate Negotiation**
    *   *Surface:* [`ProposalComposerSheet.kt`](file:///C:/New%20folder/claw-scratch/elay-gemini-gPgpg1/repo/shared/src/commonMain/kotlin/dev/elay/ui/together/proposal/ProposalComposerSheet.kt)
    *   *Visual:* Composer showing 3 candidate slots with live certainty badges (`"Free per calendar"`, `"You have a scheduled block at this time"`), and duration selector pills.
    *   *Caption:* *"Propose up to three candidate times. Agree in seconds."*
3.  **Frame 3 — Web RSVP: Zero-Install Collaboration**
    *   *Surface:* `stage3-e2e-04-accept-page.html` (Edge Function web leg)
    *   *Visual:* Clean mobile browser view: `"Responding as b2"`, dual-time proposal breakdown, single-tap `"Accept Time Lock"` button.
    *   *Caption:* *"Your partner can accept or counter from any browser. No app install needed."*
4.  **Frame 4 — Plan Screen: Timeline & Capacity Gauge**
    *   *Surface:* [`PlanScreen.kt`](file:///C:/New%20folder/claw-scratch/elay-gemini-gPgpg1/repo/shared/src/commonMain/kotlin/dev/elay/ui/plan/PlanScreen.kt)
    *   *Visual:* 06:00–24:00 day timeline with colored time blocks, shared `"Together"` tags, and the 4-segment capacity gauge (`"4h 30m planned · Comfortable space"`).
    *   *Caption:* *"Realistic capacity planning. See what actually fits in your day."*
5.  **Frame 5 — Today View: Calm Focus & Wrap-Up**
    *   *Surface:* [`TodayScreen.kt`](file:///C:/New%20folder/claw-scratch/elay-gemini-gPgpg1/repo/shared/src/commonMain/kotlin/dev/elay/ui/today/TodayScreen.kt)
    *   *Visual:* `"Happening now"` focus block, countdown indicator, `"Focus today"` (max 3 outcomes), and the post-session wrap-up card.
    *   *Caption:* *"A calm operations assistant for your daily commitments."*

---

## 4. Accessibility (A11y) & Outcome Copy Rules

### 4.1 Accessibility Standards & Semantic Tree
All Stage 5 outcome and retention surfaces must comply with Android and iOS accessibility baselines:

1.  **Touch Targets:** All feedback chips and action buttons must maintain a minimum bounding box of **48 × 48 dp** (`Modifier.heightIn(min = 48.dp)`), even when visual pill heights are 36 dp.
2.  **Semantic Grouping:** The Wrap-Up Card and Next-Time Card must group their information into cohesive accessibility nodes using `Modifier.semantics(mergeDescendants = true)` so TalkBack/VoiceOver read the context before interactive choices:
    ```kotlin
    Modifier.semantics(mergeDescendants = true) {
        contentDescription = "Session wrap-up for ${block.title}. Scheduled for ${durationMinutes} minutes. How did the time go?"
    }
    ```
3.  **Dynamic Type & Layout Flexibility:** In compliance with detekt's layout rules and the C1 text squeeze lessons ([`STATE.md`](file:///C:/New%20folder/claw-scratch/elay-gemini-gPgpg1/repo/STATE.md#L173)), outcome chip rows must support text wrapping without clipping. If font scale exceeds `1.3x`, horizontal chip rows dynamically flow into a vertical stack using `FlowRow` or column layout.
4.  **Color Independence:** Outcome states and variance chips must never rely on color alone to convey meaning:
    *   Finished early uses a clock-minus icon (`Icons.Outlined.TimerOff` / arrow-back) alongside `"-15m"`.
    *   Ran long uses a clock-plus icon (`Icons.Outlined.MoreTime` / arrow-forward) alongside `"+20m"`.
    *   Reschedule uses `Icons.Outlined.Update`.
    *   Never use `MaterialTheme.colorScheme.error` (red) for `"Didn't happen"` or `"Ran long"`. Use tonal neutrals (`surfaceVariant`) and warm amber (`CAPACITY_OVERFLOW_COLOR`) as established in [`PlanScreen.kt`](file:///C:/New%20folder/claw-scratch/elay-gemini-gPgpg1/repo/shared/src/commonMain/kotlin/dev/elay/ui/plan/PlanScreen.kt#L84).

### 4.2 Non-Judgmental Copy Canon (Spec §2 & §14)
The copy rules enforce the "calm operations assistant" voice across all outcome permutations:

| Situation | Forbidden Shame Copy | Required Calm Operations Copy | Rationale |
| :--- | :--- | :--- | :--- |
| **Session Ran Long** | *"Overdue by 25m"*, *"You ran late"*, *"Time exceeded"* | *"Ran 25m long"*, *"Session took 85m total"* | Objective measurement; no accusation of failure. |
| **Session Finished Early** | *"Crushed it!"*, *"Ahead of schedule!"* | *"Finished 15m early"*, *"Wrapped up at 3:45 PM"* | Avoids patronizing gamification. |
| **Lock Did Not Happen** | *"Missed session"*, *"Incomplete"*, *"Failed"* | *"Didn't happen"*, *"Not held today"* | Respects reality without shame. Rescheduling is first-class. |
| **Task / Lock Rollover** | *"Overdue task"*, *"You missed your goal"* | *"Move this to tomorrow?"*, *"Find another time"* | Direct quote from Spec §2 tone rule. |
| **Next-Time Recommendation**| *"Fix your bad estimate"*, *"You need more time"* | *"Last time ran 20m long — book 80m?"* | Practical operations adjustment based on actual data. |
| **Capacity Full** | *"Overbooked! Calendar alert!"* | *"Day is full · Consider moving 1 block"* | Calm capacity management without alarms. |

---

{"lane":"product-ux","feedback_surface":"today-card","next_time_surface":"today-card","copy_ready":true,"confidence":0.96}
