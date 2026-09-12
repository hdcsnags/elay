# gemini — agy_cli ((default)) — 2026-09-12-council

- success: true
- elapsed: 77s
- read-only verification: **clean**
- copy: C:\New folder\claw-scratch\elay-gemini-utHw8W\repo


## Output

Model: Gemini 3.8 Flash (High)

# Stage 3 Web UX Specification: No-Install Web RSVP & Browser Dual-Time Experience

## Grounding & Architectural Context
Per [`council/master-plan-v2.md`](file:///C:/New%20folder/claw-scratch/elay-gemini-utHw8W/repo/council/master-plan-v2.md#L6-L11), ELAY's week-one hook is: *"propose → accept → on both calendars in under ~15 seconds, three 'no timezone math' moments in week one."* Stage 3 delivers the no-install **Web RSVP** experience: a signed expiring token rendered via a lightweight Supabase Edge Function into a responsive, mobile-first browser page.

This specification governs the presentation, exact copy, edge states, and interaction ergonomics for this web surface. It inherits all core rules from our foundation:
- **Canonical Time Storage & Local Projection ([`ELAY-SPEC.md §4`](file:///C:/New%20folder/claw-scratch/elay-gemini-utHw8W/repo/ELAY-SPEC.md#L158-L184)):** Canonical time is stored as UTC instants with origin IANA timezones. The server renders no baked time strings; the edge function projects dual-time presentation directly from the proposal's UTC instants into the recipient's known timezone (`viewer_tz`) and the proposer's origin timezone (`origin_tz`).
- **The Dual-Time Signature System ([`council/stage2-timelock-gemini.md §3`](file:///C:/New%20folder/claw-scratch/elay-gemini-utHw8W/repo/council/stage2-timelock-gemini.md#L160-L196)):** The recipient’s local time is primary and prominent ("for you"); the proposer’s time is secondary ("for Alex"); relative-day badges clarify calendar boundaries; plain-language captions explain DST divergence without technical jargon.
- **Calm Operations Assistant Voice ([`ELAY-SPEC.md §2`](file:///C:/New%20folder/claw-scratch/elay-gemini-utHw8W/repo/ELAY-SPEC.md#L89-L91)):** Tone is brief, practical, non-judgmental, and clear. No exclamation marks, no guilt banners, no dead ends.
- **Contract Parity with Shipped DB Logic ([`contracts/stage2-timelock.md`](file:///C:/New%20folder/claw-scratch/elay-gemini-utHw8W/repo/contracts/stage2-timelock.md#L6-L12) & [`20260912140000_rpc_stage2_timelock.sql`](file:///C:/New%20folder/claw-scratch/elay-gemini-utHw8W/repo/supabase/migrations/20260912140000_rpc_stage2_timelock.sql#L348-L353)):** Responses map directly to `rpc_respond_proposal`, supporting atomic acceptance (two private `shared_lock` blocks and commitments), graceful conflict envelopes, and append-only counters.

---

## 1. The Web Response Page (Mobile-First Browser)

The web RSVP page is delivered via a single, responsive layout optimized for mobile viewports (360–430px width) while scaling cleanly to desktop containers (max-width: 480px centered).

```
+-------------------------------------------------------------+
| ELAY                                           Responding as|
|                                            Jordan (New York)|
+-------------------------------------------------------------+
| TIME LOCK PROPOSAL · INCOMING                    Expires in 4h|
| Study session with Alex                                     |
| Alex proposed 2 options:                                    |
|                                                             |
|  (•) Option 1 (Selected)                                    |
|      Thu, Sep 12 · 7:00 – 8:00 PM for you                   |
|      Thu, Sep 12 · 10:00 – 11:00 PM for Alex                |
|      * Clock change: 1 hr earlier difference on this day    |
|                                                             |
|  ( ) Option 2                                               |
|      Fri, Sep 13 · 11:30 PM – 12:30 AM for you              |
|      Sat, Sep 14 · 2:30 – 3:30 AM for Alex (+1 day)         |
|                                                             |
|  [ Accept Option 1 ]                                        |
|                                                             |
|  [ Suggest different time ]        [ Can't make it ]        |
|                                                             |
|  ---------------------------------------------------------  |
|  Locks directly onto both of your ELAY plans                |
+-------------------------------------------------------------+
```

### 1.1 Header & Pair Context
- **Brand / App Identity:** Subtle, calm top bar: text logo `"ELAY"` on left.
- **Identity Banner (Recipient Affirmation):** Top right eyebrow:
  - Format: `Responding as Jordan (America/New_York)`
  - Confirms the recipient's authenticated profile from the token, reassuring the user that the system knows who is answering.

### 1.2 Proposal Meta & Countdown
- **Category Eyebrow:** `TIME LOCK PROPOSAL · INCOMING` (letter-spacing: 0.05em, muted neutral tone).
- **Countdown Badge:** Right-aligned inline with eyebrow:
  - Active: `Expires in 4h 12m` (or `Expires in 45m` if < 1h).
  - Calculated against `response_deadline` from `time_lock_proposals`.
- **Title:** Proposal title in bold header typography (`font-weight: 600`, 20px), e.g., `"Study session"`. If title is blank, fallback to `"Shared time lock"`.
- **Proposer Subtitle:** `"Alex proposed 2 options:"` (or `"Alex proposed 1 option"` / `"Alex proposed 3 options"`).

### 1.3 Candidate Selection Cards (Inheriting Dual-Time Signature)
Each candidate slot (1 to 3 items per [`ADR-010`](file:///C:/New%20folder/claw-scratch/elay-gemini-utHw8W/repo/council/master-plan-v2.md#L11)) is rendered as an interactive radio card with minimum 48px vertical touch targets:
1. **Selection Control:** Stylized radio button `(•)` or checkbox-style card selector. Candidate 1 is selected by default.
2. **Primary Line (Recipient's Zone):**
   - Font: SemiBold (`font-weight: 600`), 16px, high contrast (`#1C1B1F` / `OnSurface`).
   - Format: `Thu, Sep 12 · 7:00 – 8:00 PM for you`
3. **Secondary Line (Proposer's Zone):**
   - Font: Regular (`font-weight: 400`), 14px, medium contrast (`#49454F` / `OnSurfaceVariant`).
   - Format: `Thu, Sep 12 · 10:00 – 11:00 PM for Alex`
4. **Relative Day Badge (Date Boundary Crossover):**
   - If the instant crosses calendar midnight for the proposer, append an inline pill: `(+1 day)` or `(-1 day)`.
   - Example: `Sat, Sep 14 · 2:30 – 3:30 AM for Alex (+1 day)`
5. **DST Transition Caption Rule ([`council/stage2-timelock-gemini.md §3.3`](file:///C:/New%20folder/claw-scratch/elay-gemini-utHw8W/repo/council/stage2-timelock-gemini.md#L189-L195)):**
   - If the UTC offset divergence between the two members on the candidate date differs from their current baseline offset, display an informative footnote pill directly beneath the candidate:
   - Copy: `"Time difference is 4 hours on this day (1 hour less than usual due to clock change)"`.
   - Strictly prohibit jargon like `"fold"`, `"gap"`, or raw offset tags (`"UTC-4 vs UTC-5"`).

### 1.4 Primary Action Buttons
- **Primary Action (Filled Button):**
  - Text: `[ Accept Option 1 ]` (dynamically updates when selecting Option 2 or Option 3: `[ Accept Option 2 ]`).
  - Style: Solid fill, 48px height, rounded corners (12px radius).
- **Secondary Actions (Row):**
  - Left button (Outlined): `[ Suggest different time ]` (triggers web counter mode).
  - Right button (Ghost / Text): `[ Can't make it ]` (triggers calm decline confirmation).
- **Reassurance Footnote:**
  - Copy: `"Locks directly onto both of your ELAY plans."`

---

## 2. Terminal and Edge States

The web page must handle all transitions gracefully. Per our core design philosophy, **there are no dead ends**: every terminal state provides clear explanation and a prominent action to open or download the app.

| Edge State / Trigger | Display Title | Exact Display Body Copy | Primary Action | Secondary Action |
| :--- | :--- | :--- | :--- | :--- |
| **Success: Accepted** | `Time lock confirmed` | `Option 1 is locked in: Thu, Sep 12 · 7:00–8:00 PM for you (10:00–11:00 PM for Alex). On both plans.` | `[ Open ELAY ]` | `[ Add to device calendar ]` |
| **Success: Countered** | `Alternative suggested` | `You proposed a new time to Alex. You'll get an update once Alex responds.` | `[ Open ELAY ]` | — |
| **Success: Declined** | `Proposal declined` | `You let Alex know you can't make these times. You can propose a new time anytime.` | `[ Open ELAY ]` | — |
| **Token Expired (TTL)** | `Link expired` | `This response link has expired for your security. You can view and respond to the proposal in the app.` | `[ Open in ELAY ]` | — |
| **Proposal Expired (DB)** | `Proposal expired` | `This proposal reached its response deadline without an answer.` | `[ Open ELAY ]` | — |
| **Already Accepted** | `Already accepted` | `This time lock was already confirmed. It is on both your and Alex's plans.` | `[ Open ELAY to view ]` | — |
| **Already Declined** | `Proposal closed` | `This proposal was already declined.` | `[ Open ELAY ]` | — |
| **Already Cancelled** | `Proposal withdrawn` | `Alex withdrew this proposal.` | `[ Open ELAY ]` | — |
| **Stale Revision (Countered)** | `New times suggested` | `Alex suggested different times after this link was sent. Open the app to view the latest options.` | `[ Open latest in ELAY ]` | — |
| **Network Failure** | `Unable to connect` | `We couldn't reach the server. Please check your connection and try again.` | `[ Try again ]` | `[ Open ELAY ]` |
| **Double-Submit / In-Flight** | `Confirming…` | `Locking in your time with Alex… (Buttons disabled)` | (Spinner) | — |

### 2.1 The "On Both Plans" Success Moment
When the recipient taps `[ Accept Option 1 ]`, the page transitions smoothly to the confirmation view:
- **Visual Landmark:** Quiet checkmark icon in an emerald/teal container.
- **Headline:** `Time lock confirmed`
- **Dual-Time Lock Card:** Displays the winning slot in full dual-time typography:
  `Thu, Sep 12 · 7:00 – 8:00 PM for you`
  `Thu, Sep 12 · 10:00 – 11:00 PM for Alex`
- **Verification Text:** `"On both plans · Ready in your schedule"`
- **Call to Action:** `[ Open ELAY ]` (deep links to `elay://together/proposals/{id}` or `elay://plan`).
- **Convenience Link:** Secondary link `[ Download .ics / Add to calendar ]` allowing instant sync to the native device calendar without requiring full app install.

### 2.2 Handling Countered-Since-Mint (Stale Revision Conflict)
If Alex countered or updated the proposal after generating the link, the server returns the conflict envelope ([`contracts/fixtures/proposal-conflict.json`](file:///C:/New%20folder/claw-scratch/elay-gemini-utHw8W/repo/contracts/fixtures/proposal-conflict.json#L1-L5)):
```json
{"status": "countered", "outcome": "conflict", "current_revision": 2}
```
- The page does not throw an opaque error. It renders an informative banner:
  - **Banner Title:** `Proposal updated`
  - **Banner Copy:** `"Alex suggested different times while this link was open. View the new times to respond."`
  - **Button:** `[ View new times in ELAY ]`

---

## 3. Counter-from-Web: Scope & Dual-Time Preview

### 3.1 Recommendation: Constrained "Suggest One Different Time"
We strongly recommend a **constrained counter** scope (suggesting exactly one alternative time) rather than a full 1–3 slot multi-candidate composer.

**Justification against week-one simplicity & channel purpose:**
1. **Channel Intent:** The web RSVP link is a high-velocity convenience channel for a partner on the go responding from a browser notification or chat link. Full multi-slot composing (with duration selectors, 3 separate slot builders, and multi-zone validations) is ergonomic inside the native Compose app, but introduces high cognitive load and layout clunkiness on a mobile web page.
2. **Eliminating Complex JS Dependencies:** Authoring multiple candidate slots requires substantial client-side state management and timezone projection code. A single-slot replacement keeps the edge function bundle under 20KB with zero external npm dependencies.
3. **Clean Fallback:** If the user genuinely needs a complex 3-way poll, the counter sheet displays a calm inline prompt: *"Need to offer multiple choices? [Open the ELAY app to compose a multi-option counter]"*.

### 3.2 Counter Composer Interface
Tapping `[ Suggest different time ]` flips the card or expands an inline composer accordion:
- **Header:** `Suggest a different time`
- **Date Picker:** Native HTML `<input type="date">` pre-filled with the original proposal's date.
- **Start Time Picker:** Native HTML `<input type="time">` pre-filled with candidate 1's start time.
- **Duration Presets:** Tappable pill selectors: `[ 30m ]` `[ 45m ]` `[ 60m (Selected) ]` `[ 90m ]`.
- **Live Dual-Time Preview Banner:**
  Immediately below the pickers, a reactive preview updates as the user adjusts inputs:
  ```
  +-------------------------------------------------------------+
  | Preview for your counter-proposal:                          |
  | Fri, Sep 13 · 8:30 – 9:30 PM for you                        |
  | Fri, Sep 13 · 11:30 PM – 12:30 AM for Alex                  |
  +-------------------------------------------------------------+
  ```
- **Deadline Selection:** Sane default: `"Alex will have 24 hours to respond"`.
- **Action Buttons:**
  - `[ Send counter-proposal ]` (Submits `POST /respond` with `response: "counter"` and 1 candidate).
  - `[ Cancel ]` (Returns to initial candidate selection cards).

---

## 4. Identity & Anti-Confusion

Because web RSVP links travel over messaging channels (SMS, WhatsApp, Signal) where links might be forwarded, opened on shared desktops, or inspected by third parties, the page must prevent identity confusion and protect privacy.

### 4.1 Explicit Identity Grounding
- The page explicitly states who is authenticated by the token:
  `"You are responding as Jordan · Alex will see your answer"`
- The primary action button includes the name to prevent accidental partner confusion:
  `[ Accept Option 1 as Jordan ]`

### 4.2 Leaked Link Safe-Rendering Variant (Privacy Defense)
Deferring to §A's security ruling, a leaked link token must never expose private account data:
- **What the Page Shows:**
  - Proposer first name / display name (`Alex`)
  - Recipient first name (`Jordan`)
  - Proposal title (`Study session`)
  - Proposed time candidates in both time zones
- **What the Page NEVER Shows:**
  - Email addresses or user UUIDs
  - Partner's full calendar or conflict reasons
  - Personal notes, captures, or linked task details
  - Historical pair activity or past proposals
- **Mismatched / Unrecognized Browser Notice:**
  If opened in a desktop browser or private tab without an active ELAY session, render a gentle identity banner:
  - Copy: *"This link was sent to Jordan. If you are not Jordan, please close this page."*

---

## 5. Accessibility & Progressive Degradation

### 5.1 Accessibility (WCAG 2.1 AA Compliant)
- **Touch Targets:** All radio candidate cards, counter chips, and action buttons maintain a minimum target size of 48×48px with 8px spacing.
- **Full Vocalized Screen Reader Phonetics ([`council/stage2-timelock-gemini.md §5.1`](file:///C:/New%20folder/claw-scratch/elay-gemini-utHw8W/repo/council/stage2-timelock-gemini.md#L227-L239)):**
  Screen readers do not parse shorthand like `"7:00–8:00 PM for you · 10:00–11:00 PM for Alex (+1 day)"` cleanly. The candidate container uses explicit `aria-label`s:
  ```html
  <div role="radio" aria-checked="true" tabindex="0"
       aria-label="Option 1: 7:00 PM to 8:00 PM Thursday your time, which is 10:00 PM to 11:00 PM Thursday for Alex">
    ...
  </div>
  ```
- **Live Regions:**
  - Countdown tickers use `aria-live="polite"` and announce only on initial load and critical expiration thresholds (e.g., at 1 hour and at expiration), never ticking second-by-second.
- **Contrast & Typography:**
  - All text meets 4.5:1 contrast against surface backgrounds; borders meet 3:1 contrast.

### 5.2 No-JS & Slow-Network Progressive Enhancement
- **Server-Side Rendered HTML:**
  The Supabase Edge Function completely server-renders the HTML page with all dual-time strings already computed for both timezones.
- **No-JavaScript Form Fallback:**
  The interactive buttons live inside a standard semantic HTML form:
  ```html
  <form method="POST" action="/functions/v1/rsvp/respond">
    <input type="hidden" name="token" value="..." />
    <input type="radio" name="candidate_idx" value="0" checked id="opt-0" />
    <button type="submit" name="response" value="accept">Accept Selected Option</button>
    <button type="submit" name="response" value="decline">Can't Make It</button>
  </form>
  ```
  If JavaScript is disabled or blocked by corporate firewalls, the page functions 100% reliably via pure HTTP POST, rendering the server confirmation template upon return.
- **Slow-Network / 3G Optimizations:**
  - Zero external CSS or JS frameworks; all styling is contained in an inlined, minified `<style>` block (< 8KB).
  - Submit buttons immediately switch to disabled state with `"Sending response…"` text on first tap to prevent double-submits over slow mobile connections.

---

```json
{
  "lane": "web-ux",
  "page_states": [
    "incoming_proposal",
    "accepted_success",
    "countered_success",
    "declined_success",
    "token_expired",
    "proposal_expired",
    "already_accepted",
    "already_declined",
    "already_cancelled",
    "stale_revision_countered",
    "network_error",
    "submitting_in_flight"
  ],
  "counter_scope": "constrained",
  "copy_ready": true,
  "confidence": 0.98
}
```
