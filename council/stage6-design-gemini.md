# stage6-design-gemini — §B the design language (Gemini 3.8 Flash lane, 2026-09-23)

*Dispatched blind via council-dispatch (audit mode, read-only clean, 221s); grounded in the production screenshots under `research/shots/`. Saved verbatim by the lead.*

# §B — THE DESIGN LANGUAGE ITSELF: TASTE, RESTRAINT, AND THE INTIMATE OPERATIONAL SANCTUARY

Grounding: Michael's brand ruling (2026-09-23), `ELAY-SPEC.md` §2 (calm operations assistant voice), and the current production captures (`research/shots/stage4-e2e-01-plan-gauge.png`, `stage5-e2e-01-today-wrapup.png`, `stage3-e2e-02-share-sheet.png`, `stage2-e2e-22-incoming-card.png`, and `stage2-03-composer.png`).

The current honest state of ELAY is a functionally sound developer build cloaked in Android's stock Material 3: default purple tones (`#6650A4`), oppressive gray rectangular slabs (`#E7E0EB`), raw millisecond timestamps (`08:40:19.162`), vertical letter-wrapping bugs ("C-a-n-t"), bare "UTC" rows, and the generic green Android robot launcher icon. 

To meet Michael's explicit bar—an app that a discerning, fashion-literate woman would proudly keep on her home screen and show to friends—ELAY must abandon the clichés of tech dashboards and gendered tropes. It must feel like an editorial publication from a venerable fashion house (restraint, bespoke typography, intentional whitespace) married to an intimate sanctuary for two people planning their lives together.

---

## 1. Palette: Warm Editorial Restraint & The Chromatic Pair Story

The palette rejects saturated tech blues and cold Android lavenders. Instead, it draws from the tactile warmth of high fashion houses (Loro Piana, The Row, Céline): unbleached silk, warm cashmere stone, deep espresso ink, and a confident signature accent in **Smoked Cassis / Deep Fig**.

```
+---------------------------------------------------------------------------------------+
| TOKEN               | LIGHT (Ecru & Espresso)       | DARK (Smoked Velvet Fig)        |
+---------------------+-------------------------------+---------------------------------+
| surface             | #FBF9F6 (Warm Alabaster)      | #171415 (Obsidian Truffle)      |
| surfaceContainer    | #F3EFEA (Cashmere Stone)      | #211D1F (Smoked Fig Bark)       |
| surfaceContainerHigh| #EBE5DD (Pressed Linen)       | #2B2628 (Deep Umber Caviar)     |
| onSurface           | #1C1917 (Espresso Ink)        | #F4EFEA (Unbleached Silk)       |
| onSurfaceVariant    | #68625D (Warm Taupe)          | #A8A19B (Muted Taupe Wool)      |
| outline             | #DCD6CD (Soft Thread)         | #3E373A (Muted Obsidian Ash)    |
| outlineVariant      | #EAE5DE (Whisper Line)        | #2A2426 (Faint Seam)            |
| primary             | #6D3240 (Smoked Cassis Wine)  | #DF9EAC (Rose Fig Silk)         |
| onPrimary           | #FFFFFF (Pure Linen)          | #441926 (Deep Fig Caviar)       |
| primaryContainer    | #F4EAEB (Muted Petal Fog)     | #4F2633 (Velvet Wine Shadow)    |
| onPrimaryContainer  | #3D121E (Black Cherry)        | #FAD8DF (Blush Chalk)           |
| tertiary (Accent 2) | #8C6541 (Ambered Cedar)       | #E0B286 (Warm Ochre Gold)       |
| onTertiary          | #FFFFFF (Pure Linen)          | #462A0D (Roasted Chestnut)      |
+---------------------+-------------------------------+---------------------------------+
```

### The Accent Story & Pair Dynamics
We use **one confident signature accent** (`primary`: Smoked Cassis Wine, `#6D3240`) supported by a warm mineral neutral (`tertiary`: Ambered Cedar, `#8C6541`). We explicitly **reject** assigning chromatic identities (e.g., "blue for you, rose for them") to the two members of the pair. Dividing a shared calendar into a multi-color chat-bubble aesthetic destroys the intimacy of the space and introduces cognitive visual noise. 

Instead, the two-person dynamic is expressed through **typographic hierarchy and optical grounding**:
- **"For You"** (Primary): Rendered in confident `onSurface` ink (`#1C1917` / `#F4EFEA`) with regular or medium weight.
- **"For Them"** (Partner): Rendered in intimate proximity via `onSurfaceVariant` warm taupe (`#68625D` / `#A8A19B`), positioned directly beneath your time with a subtle typographic anchor.
- When an action is mutually committed ("On both plans"), both members' segments are united under the single `primary` Cassis seal.

---

## 2. Typography: The Editorial Voice

We choose a deliberate high-fashion pairing: **Newsreader** (Display & Editorial Voice) and **Plus Jakarta Sans** (Humanist Operational Sans), both open-source Google Fonts available for local CMP bundling.

A single superfamily lacks the literary contrast required for an editorial product; pure serif families render small calendar grids and stepper numbers unreadable; pure sans families default back to tech-dashboard utilitarianism. The tension between an unhurried, literary French serif and a precise, geometric-humanist sans gives ELAY its bespoke, high-fashion atmosphere.

```
+-------------------+--------------------+------+---------+--------+----------------------------+
| STYLE             | FONT FAMILY        | SIZE | LINE HT | WEIGHT | USAGE                      |
+-------------------+--------------------+------+---------+--------+----------------------------+
| displayLarge      | Newsreader         | 34sp | 40sp    | SemiBld| First-time onboarding hero |
| headlineMedium    | Newsreader         | 26sp | 32sp    | Medium | Screen titles (Today, Plan)|
| headlineSmall     | Newsreader         | 22sp | 28sp    | Medium | Modal & sheet top headers  |
| titleLarge        | Newsreader (Italic)| 19sp | 24sp    | Regular| Empty states & reflections |
| titleMedium       | Plus Jakarta Sans  | 16sp | 22sp    | SemiBld| Card headers, block titles |
| bodyLarge         | Plus Jakarta Sans  | 15sp | 22sp    | Regular| Primary task / block body  |
| bodyMedium        | Plus Jakarta Sans  | 14sp | 20sp    | Regular| Secondary descriptions     |
| bodySmall         | Plus Jakarta Sans  | 12sp | 16sp    | Medium | Dual-time lines, captions  |
| labelLarge        | Plus Jakarta Sans  | 14sp | 20sp    | SemiBld| Steppers, primary buttons  |
| labelMedium       | Plus Jakarta Sans  | 12sp | 16sp    | SemiBld| Eyebrow caps (0.5sp track) |
| labelSmall        | Plus Jakarta Sans  | 11sp | 14sp    | Medium | Partner relative offsets   |
+-------------------+--------------------+------+---------+--------+----------------------------+
```

### Where the Editorial Voice Speaks
1. **Screen Titles & Anchors:** `headlineMedium` Newsreader replaces the generic Roboto titles seen in `stage4-e2e-01-plan-gauge.png`. "Today", "Together", and "Plan" stand unhurried, anchored with 24dp top clearance.
2. **Reflections & Empty States:** Newsreader Italic (`titleLarge`) brings warmth to quiet moments (*"An open day"*, *"Session wrap-up"*).
3. **Operational Clarity:** Numbers, steppers, and dual-time displays use Plus Jakarta Sans with tabular figures (`tnum`). Time calculations never wear decorative serifs.

---

## 3. Shape, Space, and Elevation Language

Today's screens suffer from heavy rectangular cards with flat gray fills (`#E7E0EB`) and arbitrary padding that visually crowds the screen (evident in `stage5-e2e-01-today-wrapup.png` and `stage4-e2e-01-plan-gauge.png`).

```
+------------------+---------------+------------------------------------------------------------+
| ELEMENT          | TOKEN RADIUS  | TREATMENT                                                  |
+------------------+---------------+------------------------------------------------------------+
| Outer Cards      | 20.dp         | surfaceContainer (#F3EFEA) fill, 0dp elevation, no shadow |
| Inner Tiles      | 14.dp         | 1dp outlineVariant (#EAE5DE) border, surface fill          |
| Stepper Buttons  | 12.dp         | 40x40dp touch target, soft pill or squircle                |
| Status Pills     | 999.dp (Caps) | Fully rounded pill, 6dp vertical padding                   |
| Bottom Sheets    | 28.dp Top     | 28dp top-left / top-right curve with 36x4dp soft handle   |
+------------------+---------------+------------------------------------------------------------+
```

### Whitespace as Structure
- **Eliminate Card Walls:** Replace nested boxed containers with generous whitespace. Screen content margins increase from 16dp to 20dp.
- **Rhythm:** Headers sit 28dp above lists; distinct functional sections are separated by 24dp of open space; related items group within 8dp.
- **Elevation Floor:** Drop shadows are completely banned on flat surfaces. Depth is achieved purely through warm tonal layering: `#FBF9F6` canvas beneath `#F3EFEA` cards, framed with whisper-thin hairlines (`#EAE5DE`).

---

## 4. Considered Motion: Four Signature Moments

In keeping with the calm operations assistant ethos, everyday navigation and taps remain immediate and crisp (0–150ms). We define exactly four signature moments where motion delivers emotional resonance:

1. **The Realtime Card Flip (Proposal Received / Updated):**
   - *Trigger:* Remote partner sends, counters, or accepts a proposal (`stage2-e2e-20-realtime-flip.png`).
   - *Choreography:* The incoming proposal card gently contracts (scale 0.98, 120ms fade-out) and expands smoothly into the updated state (scale 1.0, 230ms `CubicBezier(0.2, 0.0, 0.0, 1.0)`). A brief 400ms soft cassis ambient aura fades around the card perimeter, confirming the sync without jarring 3D flips.
2. **The "Time Lock Active" Seal:**
   - *Trigger:* Proposal moves to `Confirmed` status.
   - *Choreography:* The two independent dual-time lines ("for you" and "for partner") softly slide 4dp vertically toward one another (280ms `EaseInOutCubic`), converging as the padlock icon transitions from a hollow outline to solid Smoked Cassis. It delivers a tactile feeling of a latch clicking shut.
3. **The Wrap-up Card Retiring:**
   - *Trigger:* User taps an outcome chip ("Finished early", "Right on time") on Today (`stage5-e2e-02-ranlong-tap.png`).
   - *Choreography:* The button grid crossfades (180ms) into the calm reassurance: *"Noted for next time."* After a 1,800ms quiet pause, the card container's height collapses smoothly to 0dp (320ms `FastOutSlowInEasing`), drawing the upcoming schedule gently upward.
4. **The Horizon Glide (Day Switching):**
   - *Trigger:* Tapping `Prev` / `Next` or swiping the calendar rail on Plan.
   - *Choreography:* 220ms horizontal slide (16dp offset) accompanied by a subtle alpha fade. Content glides smoothly along the calendar horizon rather than flashing into view.

---

## 5. The App Icon and Splash Pipeline

The default green Android robot in `research/shots/` is replaced with an elegant, iconic mark honoring the name **ELAY**.

```
       .---.           .---.
      /     \         /     \
     |   E   |-------|   L   |       Interlocking Ligature
      \     /         \     /        Two continuous gold-weight ribbon loops
       '---'           '---'         forming an unbroken infinity lock
```

### The Geometry
- **Monogram Mark:** A continuous geometric ligature blending the capital letter **E** into an architectural **L**, looping back in a subtle, unbroken lock geometry. It evokes two lives linking together without resembling a mechanical padlock or a generic clock.
- **Stroke Architecture:** Constructed within a 108x108 viewport (inner safe zone 72x72). The vector path utilizes a steady 3.5dp stroke with softened, rounded caps (`round` join/cap).
- **Background & Color:** A background of Deep Obsidian Truffle (`#171415`) with the ligature stroke finished in Warm Cashmere Silk (`#F3EFEA`). An intimate focal dot rests at the intersection in Smoked Cassis (`#DF9EAC`).
- **Android 13+ Themed / Monochrome Icon:** A clean, single-compound path vector (`ic_launcher_monochrome.xml`) stripped of shadows and gradients, adapting seamlessly to the user's Dynamic Color palette.
- **Android 12+ Splash Screen:** Window background set to `#FBF9F6` (light) / `#171415` (dark), centered on the 120dp ELAY monogram. It dissolves into the main screen via an unhurried 300ms alpha fade, eliminating the harsh white flash.

---

## 6. Empty States & Correcting The Small Shames

We replace every developer placeholder and visual defect captured in previous test runs with intentional, polished design:

```
+-----------------------------+------------------------------------+------------------------------------+
| SURFACE / DEFECT            | TODAY'S REALITY (SCREENSHOTS)      | DESIGNED REPLACEMENT               |
+-----------------------------+------------------------------------+------------------------------------+
| 1. Today Schedule Timestamps| Raw seconds/nanoseconds:           | Clean 12-hour operational span:    |
|    (TodayScreen.kt:388)     | "08:40:19.162 – 09:40:19.162"      | "8:40 – 9:40 AM"                   |
|                             | (stage5-e2e-01-today-wrapup.png)   | Plus Jakarta Sans Medium, 13sp.    |
+-----------------------------+------------------------------------+------------------------------------+
| 2. Member Timezone Row      | Bare debug text:                   | Humanized relational phrase:       |
|    (TogetherStateViews:216) | "a   UTC" / "b2   UTC"             | "London · Same time as you"        |
|                             | (stage3-e2e-02-share-sheet.png)    | or "New York · 5h behind".         |
+-----------------------------+------------------------------------+------------------------------------+
| 3. Plan Empty Day           | Gray rectangular slab:             | Unboxed open space:                |
|    (PlanScreen.kt:356)      | "Nothing on the calendar for this  | Newsreader Italic "An open day"    |
|                             | day" (stage4-e2e-01-plan-gauge.png)| + "No commitments scheduled."      |
+-----------------------------+------------------------------------+------------------------------------+
| 4. Incoming Proposal Actions| Broken column wrap:                | Balanced action bar:               |
|    (ProposalFeed.kt)        | "C / a / n / ' / t" stacked        | Pill buttons with min-touch width  |
|                             | (stage2-e2e-22-incoming-card.png)  | + trailing quiet text link.        |
+-----------------------------+------------------------------------+------------------------------------+
| 5. Proposal Title Scaffolds | Trailing scaffold text:            | Clean title + subline:             |
|    (ProposalComposerSheet)  | "Study session. by"                | "Study session" (TitleLarge)       |
|                             | (stage2-e2e-22-incoming-card.png)  | "With Alex" (BodySmall taupe).     |
+-----------------------------+------------------------------------+------------------------------------+
```

### Concrete Empty State Design
- **Empty Today Screen:** No gray boxes. Center screen features generous 48dp top breathing room, a 32dp warm cassis line-art leaf/spark emblem, followed by Newsreader `headlineSmall`: *"Your day is clear"*, and a calm secondary caption: *"Take your time, or pull focus items from your plan."* An outlined pill button `"+ Add focus block"` rests below.
- **Empty Plan Day:** Replaces the heavy gray card in `stage4-e2e-01-plan-gauge.png`. The capacity bar quietly reflects zero with a soft taupe track, while the timeline displays an open dashed horizon line with a whisper-light caption: *"Open morning · Open afternoon"*.

---

## 7. Dark Theme: The Smoked Velvet Sanctuary

Dark theme is not an automated mathematical inversion of light hexes. A pure black (`#000000`) background or cold dark gray (`#121212`) creates harsh contrast and eye strain. 

ELAY’s dark theme is conceived as **"Late Night Salon / Smoked Velvet & Espresso"**:
- **Canvas (`#171415`):** A warm, deep obsidian brown-black infused with a hint of roasted fig.
- **Containers (`#211D1F` & `#2B2628`):** Deep smoked-fig bark cards that float with warm, subtle differentiation against the canvas.
- **Typography (`#F4EFEA` & `#A8A19B`):** Warm unbleached silk and muted taupe wool prevent halation, maintaining comfortable readability in low light.
- **The Glow of the Accent:** The Smoked Cassis shifts to **Rose Fig Silk** (`#DF9EAC`), a luminous, desaturated rose-plum that provides clear visual hierarchy without harsh neon glare.
- **The Pair Experience at Night:** When checking a proposal late at night, the interface feels restful, private, and intimate—like whispering across a dimly lit room.

---

## 8. The Verifier's "Taste Test" Checklist

A binary, screenshot-verifiable checklist to distinguish faithful execution from sloppy developer default:

1. [ ] **No Default Purple or Gray Slabs:** Zero instances of Material purple (`#6650A4`) or harsh lavender-gray cards (`#E7E0EB`). All surfaces conform to Warm Alabaster (`#FBF9F6`) or Smoked Obsidian (`#171415`).
2. [ ] **Editorial Typography Hierarchy:** Screen titles ("Today", "Plan", "Together") render in serif *Newsreader*, while time ranges and buttons render in humanist *Plus Jakarta Sans*.
3. [ ] **Zero Raw Timestamps:** No schedule row contains seconds or millisecond substrings (e.g., `08:40:19.162` is completely absent; all times render in clean `h:mm a` format).
4. [ ] **Humanized Timezone Relationships:** Member cards on Together never display raw `"UTC"` or technical IANA strings (`"Etc/UTC"`); they show a friendly city label and relational offset (`"London · Same time as you"`).
5. [ ] **Unboxed Empty States:** Empty states on Today, Plan, and Inbox contain no heavy rectangular gray background slabs; they use open whitespace, Newsreader Italic, and subtle hairline dividers.
6. [ ] **Zero Button Text Wrapping:** Proposal action rows never wrap letters vertically (the `"C-a-n-t"` defect in `stage2-e2e-22-incoming-card.png` is impossible; text sits on a single horizontal baseline).
7. [ ] **Clean Proposal Headers:** Proposal cards never display raw scaffold strings (e.g., `"Study session. by"` is sanitized to clean title and author lines).
8. [ ] **Unified Accent Restraint:** Only the signature Cassis (`#6D3240` / `#DF9EAC`) and Cedar Amber (`#8C6541` / `#E0B286`) appear as accents; no "his/hers" rainbow coding across partners.
9. [ ] **Bespoke App Icon:** Launcher icon displays the custom interlocking ELAY monogram vector, with no trace of the default Android robot.
10. [ ] **Dark Mode Warmth:** Dark mode screenshots demonstrate warm obsidian undertones (`#171415` / `#211D1F`) rather than sterile `#000000` or `#121212` slate.

---

BEGIN VERDICT
{"lane":"design-language","palette_anchor":"#6D3240","display_font":"Newsreader","body_font":"Plus Jakarta Sans","signature_motions":["realtime_proposal_flip","timelock_seal","wrapup_retire","horizon_glide"],"copy_ready":true,"confidence":0.96}
END VERDICT
