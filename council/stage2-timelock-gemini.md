# gemini — agy_cli (gemini-3.8-flash-high) — 2026-09-12-stage2-timelock

- success: true
- elapsed: 78s
- read-only verification: **clean**
- copy: C:\New folder\claw-scratch\elay-gemini-176sTH\repo


## Output

Model: Gemini 3.8 Flash

# Stage 2 UX Specification: Time-Lock Negotiation & The Dual-Time Signature

## Grounding & Context
This specification defines the complete screen, copy, and interaction architecture for ELAY Stage 2 (Time-Lock Core). Per `council/master-plan-v2.md`, ELAY's week-one hook is: *"propose → accept → on both calendars in under ~15 seconds, three 'no timezone math' moments in week one."* 

This UX seat is grounded strictly in architectural constraints established across the foundation:
- **`ELAY-SPEC.md` §4 & `adr/ADR-006`:** Canonical time is always stored as UTC instants with origin IANA timezones. Stored time is never local strings or hard-coded offsets; the client calculates and renders dual-time presentation locally from viewer device time and partner `homeTz` (`PairMember.homeTz`).
- **`adr/ADR-010` §3 (Amended):** Proposals and counters carry 1 to 3 candidate times. Acceptance chooses exactly one winner atomically; losers are preserved in append-only revision history.
- **`ELAY-SPEC.md` §2 & `adr/ADR-007`:** Tone is a *"calm operations assistant: clear, brief, practical, and non-judgmental"* (inform, never nag). Visibility is strictly private by default; conflicts expose busy windows only (`busy_only`), never private event titles, categories, or reasons.

---

## 1. Proposal Composer (Together & Plan Surfaces)

### 1.1 Entry Points & Launch Modes
The composer opens as a modal bottom sheet (`ProposalComposerSheet`), expanding to full screen if more than one candidate slot is added.
- **From Together:** Primary action button `[Propose time lock]` at the top of the feed or floating action button (`+`).
- **From Plan:** Tapping an open slot or dragging across a time range on the planner grid surfaces a context popover with `[Propose with Alex]`. The selected day and time slot pre-fill Candidate 1.

### 1.2 Candidate Time Pickers (1 to 3 Slots)
- **Slot 1 (Default, Required):** Date, Start Time, and Duration chips (pre-selected defaults: tomorrow, 10:00 AM, 60 minutes).
- **Slots 2 & 3 (Optional Alternatives):** A lightweight dashed outline card with action `+ Add another option` (appears below Slot 1 until 3 slots are reached). Sol's finding in `adr/ADR-010` shows multi-option upfront proposals eliminate serial counter ping-pong.
- **Duration Presets:** Tappable pill selectors: `[30m]` `[45m]` `[60m]` `[90m]` `[Custom]`.
- **Slot Removal:** Slots 2 and 3 feature a subtle `Remove option` icon button (`contentDescription = "Remove option 2"`).

### 1.3 Live Dual-Time Preview Component
Each candidate slot displays an active, reactive preview banner immediately beneath its time inputs:
- **Preview Format:**
  `[Candidate N] 7:00 – 8:00 PM for you · 10:00 – 11:00 PM for Alex`
- **Day Crossover:** If the instant crosses local midnight for either member, an inline day badge appears next to the respective time:
  `Fri 11:30 PM for you · Sat 2:30 AM for Alex (+1 day)`
- **Dynamic Feedback:** Updates in real-time as the user adjusts time pickers. No network call is made; calculation uses `kotlinx-datetime` between `LocalDeviceZone` and `Alex.homeTz`.

### 1.4 Response Deadline Picker
Located below candidate slots. Sane, calm defaults prevent proposal rot:
- **Default:** `24 hours before first candidate` (or `In 12 hours` if Candidate 1 is today).
- **Options Segmented / Dropdown:**
  - `12 hours before`
  - `24 hours before` (Default)
  - `2 hours before` (for same-day proposals)
  - `Custom date & time`
- **Helper text below deadline:** *"If Alex hasn't responded by then, this proposal will quietly expire."*

### 1.5 Title & Context (Optional)
- **Field:** `OutlinedTextField` labeled `"What are you doing? (optional)"`
- **Placeholder:** `"e.g., Deep work session, Weekly check-in"`
- Max length: 60 characters. Per Stage 1 Realtime privacy rules, titles are never broadcast over unsanitized realtime channels.

### 1.6 Exact Calm Copy Matrix for Composer
Per `ELAY-SPEC.md` §2, copy is honest, calm, and free of panic or exclamation marks.

| Element / State | Exact Display String | Rationale & Guidance |
| :--- | :--- | :--- |
| **Sheet Header** | `Propose a time lock` | Clear verb, no jargon. |
| **Partner Subheader** | `With Alex (America/Toronto)` | Confirms partner and their home zone. |
| **Add Slot Button** | `+ Add alternative time (up to 3)` | Clear limit per ADR-010. |
| **Slot Label** | `Option 1`, `Option 2`, `Option 3` | Clean ordinal identifiers. |
| **Deadline Label** | `Response deadline` | Explains expiration clearly. |
| **Submit Button** | `Send proposal` | Active, decisive. |
| **Submitting State** | `Sending proposal…` | Active progress indicator. |
| **Offline Banner** | `Offline · Proposal will send when reconnected` | Reassuring outbox visibility. |
| **Error: In Past** | `This time has already passed` | Direct, non-judgmental. |
| **Error: Duration** | `Duration must be between 1 minute and 24 hours` | Matches DB constraint bounds. |
| **Error: Deadline Late**| `Deadline must be before the first proposed time` | Logical consistency rule. |
| **Error: Overlap** | `Options 1 and 2 are at the exact same time` | Prevents duplicate candidates. |

---

## 2. Proposal Card States (Together Feed)

All proposals live in the `Together` tab stream (`shared/src/commonMain/kotlin/dev/elay/ui/together/`). Cards use M3 surface elevation (`surfaceContainerLow`) with crisp, quiet borders.

```
+-------------------------------------------------------------+
| TIME LOCK PROPOSAL · Incoming                      Expires 4h|
| Study session                                               |
| Alex proposed 2 options:                                    |
|                                                             |
|  [O] Option 1 (Selected)                                    |
|      Thu, Sep 12 · 7:00 – 8:00 PM for you                   |
|      Thu, Sep 12 · 10:00 – 11:00 PM for Alex                |
|                                                             |
|  [ ] Option 2                                               |
|      Fri, Sep 13 · 8:00 – 9:00 AM for you                   |
|      Fri, Sep 13 · 11:00 AM – 12:00 PM for Alex             |
|                                                             |
|  [ Accept Option 1 ]    [ Suggest different ]    [ Can't ]  |
+-------------------------------------------------------------+
```

### 2.1 Incoming Proposal Card (Recipient's View)
- **Top Bar:** Eyebrow `TIME LOCK PROPOSAL · Incoming` (muted text) + right-aligned countdown: `Expires in 4h 12m`.
- **Title:** Optional proposal title (e.g., `"Study session"`) or fallback `"Shared block"`.
- **Subheader:** `"Alex proposed 2 options:"` (or 1 option / 3 options).
- **Interactive Candidate Cards (Chips):** Each candidate is a selectable radio card showing full dual-time representation. Tapping selects that candidate as active.
- **Action Buttons (Row):**
  - **Primary:** `[Accept Option 1]` (Filled Button; updates dynamically to reflect currently selected option).
  - **Secondary:** `[Suggest different]` (Outlined Button; opens Composer in counter-proposal mode).
  - **Tertiary:** `[Can't]` (Text Button; opens quiet confirmation dialog).
- **Decline Confirmation Modal:**
  - Title: `"Decline this proposal?"`
  - Body: `"Alex will be notified that you can't make this time work. You can propose a new time whenever you're ready."`
  - Buttons: `[Decline proposal]` (Destructive/Calm) · `[Back]`

### 2.2 Outgoing Proposal Card (Sender's View)
- **Top Bar:** Eyebrow `TIME LOCK PROPOSAL · Sent` + right-aligned `Waiting for Alex`.
- **Body:** Lists the 1–3 sent candidate options with dual-time chips.
- **Deadline Status:** A calm secondary line: `"Response deadline: Today at 6:00 PM (in 3 hours)"`.
- **Action:** `[Withdraw proposal]` (Text Button).
  - Withdrawal Dialog: `"Withdraw this proposal?"` / `"Alex won't be able to accept it anymore."` / Buttons: `[Withdraw]` · `[Keep open]`.

### 2.3 Countered Proposal Card
When a partner counters, the proposal state transitions per `ELAY-SPEC.md` §4.
- **Top Bar:** Eyebrow `TIME LOCK PROPOSAL · Countered` (subtle primary container tint).
- **Banner Notice:** `"Alex suggested different times:"`
- **Candidate List:** Shows the new candidate options (Revision N+1).
- **Revision History Drawer Link:** Tappable pill `Previous times (Revision 1)` unfolds a collapsed list showing the initial times struck through with muted opacity.
- **Action Buttons:** Same as Incoming: `[Accept Option 1]`, `[Suggest different]`, `[Can't]`.

### 2.4 Accepted Time-Lock Card (The Winning Lock)
Once accepted by either party, the card transforms into a confirmed commitment:
- **Top Bar:** Eyebrow `TIME LOCK CONFIRMED` (subtle teal/green accent dot).
- **Title:** Proposal title.
- **Winning Slot Display:** Prominently displays the chosen candidate time in full dual-time typography.
- **Status Confirmation Line:**
  *"On both plans · Time lock active"*
- **Action Row:**
  - `[View in Plan]` (Navigates directly to the block on the user's `PlanRoute` calendar grid).
  - `[Reschedule]` (Opens proposal composer pre-filled with this time lock's parameters).

### 2.5 Declined, Expired, and Cancelled Cards (Calm Resolution)
Per `ELAY-SPEC.md` §2, no red error boxes or shame banners appear.
- **Declined Card:**
  - Eyebrow: `PROPOSAL CLOSED`
  - Text: `"Alex couldn't make these times work."` (or `"You declined this proposal."`)
  - Action: `[Propose another time]` (Outlined Button) · `[Dismiss]`
- **Expired Card:**
  - Eyebrow: `PROPOSAL EXPIRED`
  - Text: `"This proposal reached its response deadline without an answer."`
  - Action: `[Propose again]` (Outlined Button) · `[Dismiss]`
- **Cancelled Card:**
  - Eyebrow: `PROPOSAL WITHDRAWN`
  - Text: `"Alex withdrew this proposal."` (or `"You withdrew this proposal."`)
  - Action: `[Propose new time]` · `[Dismiss]`

---

## 3. The Dual-Time Visual Rule: The Signature System

The dual-time readout is ELAY’s core brand and mechanical signature. It must be consistent across composer, feed cards, plan sheets, and notifications.

```
+-------------------------------------------------------------------+
|  7:00 – 8:00 PM for you        (Bold, 15sp, OnSurface)            |
|  10:00 – 11:00 PM for Alex     (Regular, 13sp, OnSurfaceVariant)  |
|  * 1 hr earlier than usual due to clock change                    |
+-------------------------------------------------------------------+
```

### 3.1 Hierarchy, Typography, and Ordering Rules
1. **Viewer-First Ordering:** The current viewer's local time is ALWAYS primary and listed first. The partner’s time is secondary and listed second.
2. **Visual Weight Hierarchy:**
   - **Primary Line (Viewer Time):** `MaterialTheme.typography.titleMedium` (Weight: SemiBold / 600, Size: 15sp, Color: `MaterialTheme.colorScheme.onSurface`).
   - **Separator:** Centered dot (`·`) or line break depending on container width (cards stack vertically; composer chips use inline dot if width >= 340dp).
   - **Secondary Line (Partner Time):** `MaterialTheme.typography.bodyMedium` (Weight: Normal / 400, Size: 13sp, Color: `MaterialTheme.colorScheme.onSurfaceVariant`).
3. **Identity Clarity:** Primary line uses `"for you"`; secondary line uses `"for [PartnerDisplayName]"` (e.g. `"for Alex"`). If partner display name is blank, fall back to `"for them"`.

### 3.2 Relative Day Markers (Day-Crossover)
When an instant spans different calendar dates between the two time zones:
- If Partner is on a different calendar day, append a relative day tag:
  - If partner is +1 calendar day: `(+1 day)` or `(Tomorrow)`
  - If partner is -1 calendar day: `(-1 day)` or `(Yesterday)`
- In multi-day or forward planning views: explicitly state the day of week:
  - Line 1: `Fri, Sep 12 · 11:30 PM – 12:30 AM for you`
  - Line 2: `Sat, Sep 13 · 2:30 – 3:30 AM for Alex (+1 day)`

### 3.3 DST-Weirdness Handling (No Technical Jargon)
During daylight saving transition gaps where regional shift dates diverge (e.g., North America shifting two weeks before Europe, or Toronto vs London offset temporarily becoming 4 hours instead of 5):
- **Jargon Prohibition:** Never show `"DST Gap"`, `"Fold"`, `"EDT vs BST"`, or UTC offsets (`UTC-4 vs UTC-5`).
- **Calm Explanatory Cue:** If the offset between the two paired members on the proposed date differs from their current baseline offset, render a small, informative info pill or caption:
  - Indicator: Small clock-shift icon + caption text:
    `"Time difference is 4 hours on this day (1 hour less than usual due to clock change)"`
- This ensures users are never surprised by seasonal shifts while preserving complete trust in the app's calculation.

---

## 4. Conflict Hints & Non-Nagging Philosophy

Grounding in `ELAY-SPEC.md` §2 (*"inform, never nag"*) and `adr/ADR-007` (*"busy-only, never why"*):

### 4.1 Composer Conflict Hints (Self-Conflicts)
When the user picks a candidate time that overlaps with an existing block on their own planner:
- **Visual Presentation:** A calm, soft amber/neutral pill directly under the candidate slot:
  `[!] You have a scheduled block from 7:30 – 8:30 PM`
- **Philosophy:** Informational only. It does **not** block submission. The button `[Send proposal]` remains fully enabled. There are no confirmation modals, no red warning banners, and no guilt prompts.

### 4.2 Response Conflict Hints (Recipient Availability)
When an incoming proposal conflicts with the responder's existing blocks:
- **Display on Chip:**
  `Option 1: 7:00 – 8:00 PM for you · 10:00 – 11:00 PM for Alex`
  `[Notice] You have a personal block during this time (busy 7:00 – 8:00 PM)`
- **Behavior:** The responder can still tap `[Accept Option 1]`. If tapped, an inline prompt quietly asks:
  `"You're busy at this time. Accept and double-book?"`
  Buttons: `[Accept anyway]` · `[Choose another option]`.

### 4.3 Partner Conflict Hinting (Privacy Preservation)
If the server projection indicates the partner has a conflicting `busy_only` block:
- **Display:** `"Alex may be busy during Option 2"`
- **Strict Privacy Rule:** Absolutely no details are surfaced (no task title, no category, no location). It states availability status and nothing else.

---

## 5. Accessibility & Screen Reader Specification

### 5.1 Dual-Time Readouts for TalkBack / VoiceOver
Screen readers struggle with shorthand like `"7:00-8:00 PM for you · 10:00-11:00 PM for Alex (+1 day)"`.
- Provide an explicit, fully vocalized `contentDescription` on the dual-time container:
```kotlin
Modifier.semantics {
    contentDescription = "Option $index: $viewerStartFormatted to $viewerEndFormatted your time, " +
        "which is $partnerStartFormatted to $partnerEndFormatted on $partnerDayOfWeek for $partnerName"
}
```
- **Example Spoken Output:**
  *"Option 1: 7:00 PM to 8:00 PM your time, which is 10:00 PM to 11:00 PM for Alex."*
  *"Option 2: 11:30 PM Friday to 12:30 AM Saturday your time, which is 2:30 AM to 3:30 AM Saturday, next day, for Alex."*

### 5.2 Interactive Candidate Chips
- Candidates must report semantic roles: `Role.RadioButton` or `Role.Checkbox`.
- State description explicitly vocalizes: `"Selected: Option 1 of 3"` or `"Unselected: Option 2 of 3"`.
- Minimum touch target: 48dp height and width on all candidate cards, remove buttons, and action triggers.

### 5.3 Live Countdowns & Deadlines
- Live countdown tickers (`"Expires in 4h 12m"`) must NOT trigger continuous accessibility announcements on every tick.
- Set `liveRegion = LiveRegionMode.Polite` with announcements triggered **only** on initial screen load and when passing critical thresholds (e.g., `"Proposal expires in 1 hour"`, `"Proposal has expired"`).

---

```json
{"lane":"ux","screens":["ProposalComposerSheet","TogetherProposalCards","ProposalCounterScreen","PlanProposalOverlay"],"dual_time_rule":"Viewer local time primary (SemiBold 15sp OnSurface 'for you') followed by partner time secondary (Regular 13sp OnSurfaceVariant 'for [Name]'), relative day badge (+1 day) on date boundary crossover, plain-language clock-change caption for DST divergences, full vocalized phonetics for accessibility","copy_ready":true,"confidence":0.98}
```
