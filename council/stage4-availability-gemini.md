# gemini — agy_cli ((default)) — 2026-09-12-council

- success: true
- elapsed: 105s
- read-only verification: **clean**
- copy: C:\New folder\claw-scratch\elay-gemini-NHtRkg\repo


## Output

Model: Gemini 3.8 Flash (High)

# Stage 4 UX Specification: Honest Availability, Certainty Labels & Capacity Gauge

## Grounding & Architectural Context
Per [`council/master-plan-v2.md`](file:///C:/New%20folder/claw-scratch/elay-gemini-NHtRkg/repo/council/master-plan-v2.md#L12-L21), Stage 4 resequences honest availability ahead of the time-lock beta: *"conflict-only calendar read moves before time-lock beta (honest availability labels: 'free per ELAY' vs 'free per calendar'); capacity gauge (Gemini's)."* Because the Google Cloud OAuth client does not yet exist (`PING-MICHAEL.md`), Stage 4 establishes the provider seam and UI mechanisms behind a manual/fake provider with zero contract changes required when the Google adapter lands.

This UX specification defines the presentation rules, exact calm copy, interaction models, and accessibility contracts for:
1. **Certainty Labels:** Distinguishing between calendar-verified availability, ELAY-only schedules, and unconfirmed states.
2. **The Capacity Gauge:** Grounded in [`ELAY-SPEC.md §2`](file:///C:/New%20folder/claw-scratch/elay-gemini-NHtRkg/repo/ELAY-SPEC.md#L77-L151) (*"daily capacity budget... tasks can be unscheduled because there is genuinely no room"*).
3. **The Manual "I'm busy then" Provider UI:** Lightest-possible entry for external commitments.
4. **Staleness UX:** Calm, guilt-free degradation when external sources age.
5. **Accessibility:** Comprehensive screen reader semantics and touch compliance across all surfaces.

---

## 1. Certainty Labels: Architecture, Surfaces, & Visual Weight

### 1.1 The Core Certainty States
Availability is not a binary switch; it is a spectrum of certainty. In ELAY, four states exist:
1. **Calendar Verified (`free_per_calendar`):** The connected external calendar has synced within the freshness threshold (<24h) and contains no overlapping busy blocks, AND there are no conflicting ELAY `time_blocks`.
2. **ELAY Verified (`free_per_elay`):** There are no overlapping ELAY `time_blocks`, but either no external calendar is connected or sync is unverified/stale.
3. **Sync Unconfirmed (`unknown`):** The external calendar has not synced within the freshness threshold (>24h), leaving external availability uncertain.
4. **Busy / Conflict (`busy`):** An overlapping scheduled block exists in ELAY or in the external busy provider.

### 1.2 Placement Across Surfaces
Certainty labels appear on three primary surfaces:
- **Proposal Composer Candidate Pickers ([`ProposalComposerSheet.kt`](file:///C:/New%20folder/claw-scratch/elay-gemini-NHtRkg/repo/shared/src/commonMain/kotlin/dev/elay/ui/together/proposal/ProposalComposerSheet.kt#L106-L140)):** Displayed inside each `CandidateSlot` immediately beneath the dual-time preview line. It gives the proposer instant feedback on both their own availability and their partner’s projected availability (derived from `rpc_proposal_conflict_hints`).
- **Proposal Feed Response Chips ([`ProposalFeed.kt`](file:///C:/New%20folder/claw-scratch/elay-gemini-NHtRkg/repo/shared/src/commonMain/kotlin/dev/elay/ui/together/proposal/ProposalFeed.kt#L464-L486)):** Displayed inside `CandidateChipRow` on incoming, outgoing, and web RSVP cards so responders know whether accepting will collide with an un-synced or external commitment.
- **Plan Day Timeline ([`PlanScreen.kt`](file:///C:/New%20folder/claw-scratch/elay-gemini-NHtRkg/repo/shared/src/commonMain/kotlin/dev/elay/ui/plan/PlanScreen.kt#L143)):** Displayed as a quiet header indicator in the day summary and as a status badge when inspecting open time slots.

### 1.3 Exact Calm Copy Matrix
Per [`ELAY-SPEC.md §2`](file:///C:/New%20folder/claw-scratch/elay-gemini-NHtRkg/repo/ELAY-SPEC.md#L89-L91), ELAY speaks as a calm operations assistant: clear, brief, practical, non-judgmental. Red warning banners and guilt-inducing alarms are strictly forbidden. Under [`adr/ADR-007`](file:///C:/New%20folder/claw-scratch/elay-gemini-NHtRkg/repo/adr/ADR-007-visibility.md#L5-L10), partner conflicts are strictly `busy_only`—never exposing titles, reasons, or calendar names.

| State | Viewer Copy (Self) | Partner Copy (Peer Perspective) | Tone Rationale |
| :--- | :--- | :--- | :--- |
| **`free_per_calendar`** | `Free per calendar` | `Alex is free per calendar` | Confident, verified. |
| **`free_per_elay`** | `Free on ELAY (no external calendar)` | `Alex is free on ELAY` | Honest about scope; sets expectations. |
| **`unknown` (stale)** | `Free on ELAY · Calendar not synced recently` | `Alex is free on ELAY · Calendar unconfirmed` | Transparent without blaming. |
| **`busy` (personal)** | `You have a scheduled block at this time` | `—` | Informative self-conflict. |
| **`busy` (partner)** | `—` | `Alex is busy at this time` | Strict ADR-007 compliance: busy-only, never why. |

### 1.4 Visual Weight & Color Rules
- **No Red Error States:** Red (`MaterialTheme.colorScheme.error`) is reserved exclusively for destructive data-loss confirmations or severe network dropouts. Availability states use neutral and secondary surface tones.
- **Badge Anatomy:** Rendered as a compact, pill-shaped badge (`Modifier.height(24.dp).padding(horizontal = 8.dp)`) with `MaterialTheme.typography.labelSmall` (11sp, medium weight).
- **Colors:**
  - `free_per_calendar`: Subtle primary container tint (`colorScheme.secondaryContainer`, text `colorScheme.onSecondaryContainer`), accompanied by a quiet 12dp checkmark icon.
  - `free_per_elay`: Muted outline badge (`colorScheme.surfaceVariant`, text `colorScheme.onSurfaceVariant`), accompanied by an open dot icon.
  - `unknown`: Low-contrast neutral container with an unobtrusive clock-history icon.
  - `busy`: Soft amber container (`Color(0xFFFFF3E0)`, dark theme `Color(0xFF3E2723)`), text `colorScheme.onSurface`. Non-blocking per [`council/stage2-timelock-gemini.md §4.1`](file:///C:/New%20folder/claw-scratch/elay-gemini-NHtRkg/repo/council/stage2-timelock-gemini.md#L203-L208).

---

## 2. The Capacity Gauge

### 2.1 Concept & Measurement
The capacity gauge provides schedule realism without judgment. Grounded in [`ELAY-SPEC.md §2`](file:///C:/New%20folder/claw-scratch/elay-gemini-NHtRkg/repo/ELAY-SPEC.md#L151) (*"Capacity planning: the day has a finite budget; tasks can be unscheduled because there is genuinely no room"*), it computes the ratio of scheduled commitments against a realistic daily budget:
$$\text{Capacity Ratio} = \frac{\sum \text{Duration}(\text{ELAY Blocks}) + \sum \text{Duration}(\text{External Busy Blocks}) - \text{Overlaps}}{\text{Daily Planned Budget (Default 8h / 480m)}}$$
External busy windows are included in the sum so that time committed outside ELAY directly reflects on the day's realistic budget.

### 2.2 Gauge Home
The primary home of the capacity gauge is **`PlanScreen`**, mounted directly between the `DaySwitcher` and `DayTimeline` ([`PlanScreen.kt` line 123](file:///C:/New%20folder/claw-scratch/elay-gemini-NHtRkg/repo/shared/src/commonMain/kotlin/dev/elay/ui/plan/PlanScreen.kt#L123)). A secondary, compacted summary glance appears in the `Today` tab header. During proposal creation, a lightweight preview line in the `ProposalComposerSheet` shows the projected delta (e.g., `"+1h brings today to 6h 30m"`).

### 2.3 Visual Levels & Rendering
The gauge consists of a clean, horizontal 4-segment track (height: 6dp, corner radius: 3dp, track color: `colorScheme.surfaceVariant`) paired with a clear, calm status line underneath:

```
[====                  ] 2h 30m planned · Plenty of space
[==========            ] 5h 15m planned · Balanced focus
[==================    ] 7h 30m planned · Day is full
[======================]! 8h 45m planned · 45m over 8h budget
```

1. **Comfortable (0% – 49% of budget):**
   - **Track Fill:** Single segment filled with soft tertiary container tone (`colorScheme.tertiary`).
   - **Copy:** `"{hours}h {mins}m planned · Plenty of space"`
2. **Balanced (50% – 79% of budget):**
   - **Track Fill:** Two segments filled with solid primary tone (`colorScheme.primary`).
   - **Copy:** `"{hours}h {mins}m planned · Balanced focus"`
3. **Full (80% – 100% of budget):**
   - **Track Fill:** Three segments filled with calm secondary tone (`colorScheme.secondary`).
   - **Copy:** `"{hours}h {mins}m planned · Day is full"`
4. **Stretched (>100% of budget):**
   - **Track Fill:** All four segments filled, with a quiet trailing dash extension in warm muted amber (`Color(0xFFFFB74D)`). Never flashing, never red.
   - **Copy:** `"{hours}h {mins}m planned · {over}m over {budget}h budget"`

### 2.4 Capacity Gauge Accessibility
- **Semantic Role:** `Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo(current = plannedMinutes.toFloat(), range = 0f..budgetMinutes.toFloat()) }`
- **Spoken Text (`stateDescription`):** `"Capacity: 5 hours 15 minutes scheduled of 8 hour budget. Balanced focus."`
- **High Contrast:** The track fill maintains a minimum 3:1 contrast ratio against the track background across both light and dark themes.

---

## 3. The Manual "I'm Busy Then" Provider UI

### 3.1 Architecture & Scope Ruling
**Ruling:** The manual busy provider **MUST ship as a lightweight UI surface** (not dev-only seeding). Without a production Google Cloud OAuth client, real dogfooding and testing require a concrete way for Michael and test pairs to input off-platform obligations. 

### 3.2 The Lightest-Possible UI Surface
To avoid friction, marking an external busy block bypasses the heavyweight task/goal creation flows:
- **Trigger:** An auxiliary button `[+ Busy]` placed next to `[+ Add block]` in the `PlanHeader` ([`PlanScreen.kt` line 175](file:///C:/New%20folder/claw-scratch/elay-gemini-NHtRkg/repo/shared/src/commonMain/kotlin/dev/elay/ui/plan/PlanScreen.kt#L175)), or a 1-tap option in the timeline slot long-press context menu.
- **BottomSheet (`ManualBusySheet`):** Contains only three inputs:
  1. Date (defaults to currently selected planner day).
  2. Start Time & End Time (steppers or 30/60/90m duration presets).
  3. Optional Label (defaults to `"External commitment"`; placeholder `"e.g., Doctor, Dentist, Flight"`).
- **Save Action:** A single tap on `[Mark busy]` creates the record immediately. Zero requirement for categories, priority, or goal linkage.

```
+-------------------------------------------------------------+
| Mark external busy time                                   X |
| Date: Today, Sep 12                                         |
| Time: [ - ] 2:00 PM [ + ]   Duration: [ 30m ] [ 60m ] [ 90m ]|
| Note (optional): Doctor's appointment                       |
|                                                             |
| [ Cancel ]                                    [ Mark busy ] |
+-------------------------------------------------------------+
```

### 3.3 Provenance Labeling Later
- **Owner Presentation (Plan Timeline):** Rendered on the owner's grid with a distinct diagonal hatched border and a quiet label:
  - Manual source: `"Busy · Added by hand"`
  - Future Google Calendar source: `"Busy · Google Calendar"`
- **Peer Presentation (ADR-007 Boundary):** Filtered through the server's `rpc_proposal_conflict_hints` projection. The partner's client receives *only* the clipped `{starts_at_utc, ends_at_utc}` interval. The UI displays `"Alex is busy at this time"`. The origin (manual vs Google) is completely masked from the peer.

---

## 4. Staleness UX: Graceful Degradation Without Guilt

### 4.1 Freshness Ladders
When calendar sync is delayed or credentials expire, availability confidence degrades incrementally:
- **Fresh (<6 hours):** Label: `"Free per calendar"`. Icon: Solid check dot.
- **Recent (6–24 hours):** Label: `"Free per calendar (synced today)"`. Icon: Outline check dot.
- **Stale (24–72 hours):** Label: `"Free on ELAY · Calendar synced 2d ago"`. Icon: Neutral clock.
- **Dormant (>72 hours / Disconnected):** Label: `"Free on ELAY · Calendar unconfirmed"`. Icon: Muted warning dot.

### 4.2 Non-Nagging Tone & Recovery Affordances
- **No Guilt Banners:** The app never shouts `"Sync failed!"`, `"Re-authenticate immediately!"`, or `"Your calendar is out of date!"`.
- **Owner Affordance:** On the owner's `Plan` screen or in `Settings`, a quiet sub-caption appears under the date header:
  `"Calendar last checked 3 days ago · "` followed by a calm inline text button: `[Refresh now]`.
- **Peer Affordance:** When viewing a partner whose calendar sync is stale, the proposal composer simply shows:
  `"Alex is free on ELAY (calendar not synced recently)"`.
  It provides honest disclosure without prompting the user to badger their partner.

---

## 5. Accessibility & Screen Reader Specification

1. **Phonetic Certainty Descriptions:**
   Labels must not be spoken as shorthand. Semantics explicitly describe the state:
   ```kotlin
   Modifier.semantics {
       contentDescription = when (certainty) {
           Certainty.CalendarVerified -> "Availability verified with external calendar: Free"
           Certainty.ElayOnly -> "Free on ELAY. External calendar not connected"
           Certainty.Stale -> "Free on ELAY. External calendar was last synced $staleDays ago"
           Certainty.Busy -> "Conflict: Already busy during this time"
       }
   }
   ```
2. **Polite Live Announcements:**
   Changes in capacity or sync status must use `LiveRegionMode.Polite` (`ProposalFeed.kt` line 136). Screen readers announce updates only on screen entry or explicit user adjustment, never interrupting mid-interaction.
3. **Touch Targets:**
   All manual busy steppers, preset duration chips, and refresh triggers strictly maintain a minimum touch bounding box of 48dp × 48dp.
4. **Color Independence:**
   Every certainty indicator and capacity state combines distinct iconography, clear typographic labels, and progress fill so that no information relies solely on color perception.

---

```json
{"lane":"ux","surfaces":["ProposalComposerSheet","ProposalFeed","WebRSVP","PlanScreen"],"gauge_home":"PlanScreen (top rail below DaySwitcher)","copy_ready":true,"confidence":0.98}
```
