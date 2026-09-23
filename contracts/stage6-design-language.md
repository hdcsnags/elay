# Stage 6 contract — the design language (FROZEN 2026-09-23)

*Two blind lanes, both binding: §A design-system contract (Opus seat, conf 0.9 —
`council/stage6-design-opus.md`) and §B the design language itself (Gemini seat, conf 0.96 —
`council/stage6-design-gemini.md`). House rules from all prior stage contracts apply. The bar
for this stage (Michael's brand ruling, 2026-09-23): would a discerning, fashion-literate
woman choose to keep this app on her phone — taste over cliché, restraint over saturation.*

## Adopted (highlights; lane docs are the full text)

**From §B (the look):**
- **Palette**: warm editorial restraint. Light "Ecru & Espresso" (surface `#FBF9F6`, containers
  `#F3EFEA`/`#EBE5DD`, ink `#1C1917`, taupe `#68625D`, hairlines `#DCD6CD`/`#EAE5DE`); dark
  "Smoked Velvet Fig" (canvas `#171415`, containers `#211D1F`/`#2B2628`, silk `#F4EFEA`, taupe
  wool `#A8A19B`). One signature accent: **Smoked Cassis `#6D3240`** (dark: Rose Fig Silk
  `#DF9EAC`), supported by **Ambered Cedar `#8C6541`** (dark `#E0B286`). Full token table in §B §1.
- **No chromatic his/hers split** — the pair dynamic is typographic (ink for you, taupe for
  them, united under the Cassis seal when "On both plans").
- **Type**: **Newsreader** (editorial serif — screen titles, empty states/reflections in
  italic) + **Plus Jakarta Sans** (operational sans — body, times, steppers, tabular figures).
  Full 11-role scale with sizes/weights in §B §2.
- **Shape/space**: cards 20dp radius 0-elevation tonal fills, inner tiles 14dp with hairline
  borders, pills fully rounded, sheets 28dp top; screen gutter 16→20dp; shadows banned on flat
  surfaces — depth is warm tonal layering.
- **Motion — exactly four signature moments** (everything else instant): realtime proposal
  flip (scale 0.98 contract/expand + soft cassis aura), the time-lock seal (dual-time lines
  converge 4dp as the padlock fills), wrap-up card retiring ("Noted for next time." → quiet
  pause → 320ms height collapse), horizon glide on Plan day switch (220ms slide+fade).
- **Icon**: interlocking E→L ligature monogram — two continuous ribbon loops forming an
  unbroken lock; 3.5dp rounded stroke in Cashmere Silk on Obsidian Truffle, cassis focal dot
  at the intersection; monochrome single-path variant; splash = monogram on paper/ink per mode.
- **The small shames, each with its designed replacement** (§B §6 table): raw-seconds rows →
  `8:40 – 9:40 AM`; bare "UTC" rows → humanized zone + relation; gray empty slabs → unboxed
  Newsreader-italic open states; the vertical "C-a-n-t" button wrap → single-baseline pills;
  "Study session. by" scaffold → clean title + author subline.
- **Dark theme is its own mood** ("late-night salon"), never an inversion; §B §7.
- **The 10-point binary taste checklist** (§B §8) is the visual pre-gate instrument.

**From §A (the build):**
- **Token layer** — eight files in `ui/theme/` (`ElayColors` semantic tokens +
  `toMaterialColorScheme()` projection, `ElayType`, `ElayShapes`, `ElaySpacing` incl.
  `hourHeight=56`, `ElayElevation`, `ElayMotion`, `ElayFonts`, rewritten
  `ElayTheme(darkTheme: Boolean = isSystemInDarkTheme())`) + `ElayComponents.kt` for the named
  Material leaks (Card ×14, NavigationBar, ModalBottomSheet ×2, AlertDialog ×2, chips,
  OutlinedTextField ×5, buttons, progress) — thin wrappers over public `*Defaults` only.
- **Light+dark from six 12-step ramps** with derivation rules (dark raised = lighter step,
  never shadow; accent drops ~15% chroma in dark; `tonalElevation` pinned 0.dp). **WCAG AA
  floors enforced by pure-Kotlin `ElayContrastTest`** in commonTest (text ≥4.5:1, large text +
  non-text UI ≥3:1) over an explicit pair table for both schemes — a rail, not an eyeball.
- **Fonts bundled** (downloadable forbidden: androidMain-only + FOUT): static Latin-subset
  `.ttf` in the already-wired `composeResources/font/`, ≤600 KB hard ceiling, platform default
  last in every `FontFamily` as fallback.
- **Icon/splash pipeline** exactly as §A §3: adaptive foreground/background/monochrome
  vectors (66dp safe circle, flat fills, monochrome = single white path), mipmap XML +
  legacy PNGs (minSdk 24), `core-splashscreen` with `Theme.Elay.Starting`,
  `installSplashScreen()` before `super.onCreate`, **never** keep-on-screen across session
  refresh; iOS = one 1024px no-alpha PNG + AccentColor.
- **Web parity**: RSVP page keeps its system font stack; colours/rhythm pinned to
  `contracts/fixtures/design-tokens.json` by a Kotlin commonTest AND a Deno test over the ten
  `:root` custom properties.
- **The three §A failure modes are binding as written** (never replace/fork MaterialTheme; no
  `Color(0xFF…)`/`isSystemInDarkTheme()` outside `ui/theme/`; frozen copy/semantics/48dp
  floor/colour-independence untouchable).
- **Named visual regressions to screenshot-verify**: the 14dp-tall 15-minute Plan block;
  WrapUpCard's five-chip FlowRow re-wrap; the dark cold-start flash; CertaintyBadge desync.

## Seat grants (disjoint; §A §5 is the authority)

| Slice | Grant | After |
|---|---|---|
| LEAD-0 | manifest, `res/values{,-night}/themes.xml`, `libs.versions.toml`, `androidApp/build.gradle.kts`, `config/detekt.yml`, `MainActivity.kt` | — |
| D1 tokens | `ui/theme/**` + `commonTest/.../theme/**` (incl. `ElayContrastTest`); no screen edits | LEAD-0 |
| D2 fonts | `composeResources/font/*.ttf`, `ui/theme/ElayFonts.kt` | D1 |
| D3 icon | `androidApp/src/main/res/{drawable,mipmap-*}/ic_launcher*`, iOS appiconset + AccentColor | LEAD-0 |
| D4 | `ui/today/**`, `ui/plan/**` (raw-seconds fix, `CAPACITY_OVERFLOW_COLOR`→caution token) | D1, D2 |
| D5 | `ui/together/**` (UTC rows, CertaintyBadge literals + isSystemInDarkTheme, scaffold titles) | D1, D2 |
| D6 | `App.kt`, `ui/Routes.kt`, `ui/inbox/**`, `ui/auth/**`, `ui/settings/**`, `ui/task/**`, `ui/goal/**` | D1, D2 |
| D7 web | `supabase/functions/rsvp/index.ts` (STYLE const), `docs/design-tokens.md`, `contracts/fixtures/design-tokens.json` | D1 |

D4/D5/D6/D7 run parallel. Lead owns build files, DI, merges, the gate, and the visual pre-gate.

## Lead amendments (binding)

1. **Token-name reconciliation**: §A's illustrative `youTint`/`themTint` are DROPPED —
   §B explicitly rejects chromatic pair identity; the pair story is typographic. §A's
   `availabilityCaution` takes **Ambered Cedar** (`#8C6541` light / `#E0B286` dark), retiring
   the raw `#FFB74D` amber; Cedar is also the Material `tertiary` projection. `accentPrimary`
   = Cassis/Rose Fig per §B's table, which is the authoritative hex source for
   `elayLightColors()`/`elayDarkColors()` and `design-tokens.json`.
2. **Font faces (the 600 KB ceiling meets §B's italic)**: ship five static Latin-subset
   faces — Newsreader Medium, Newsreader Italic (regular), Plus Jakarta Sans Regular /
   Medium / SemiBold. `displayLarge` uses Newsreader Medium (not SemiBold — one face saved;
   the role is unused today). If the five exceed 600 KB after subsetting, drop PJS Medium
   first (Regular+SemiBold suffice); the italic is load-bearing (§B's empty states) and
   drops last. D2 records the actual byte total in its diff notes.
3. **One shared vector, and it is the monogram**: §B's empty-state "leaf/spark emblem" is
   replaced by the ELAY monogram mark at small scale in a quiet accent tint — the same
   geometry serves icon, splash, and empty states (§A allows at most one shared vector;
   brand coherence wins).
4. **Timezone humanization is defect repair, not a copy rebrand**: the bare-"UTC" row's new
   strings (city from the IANA id, underscores→spaces; relation as "Same time as you" /
   "Nh ahead" / "Nh behind"; ids without a city, e.g. `Etc/UTC`, render as the bare
   designation + relation) land in D5 WITH commonTest coverage. All frozen stage copy
   stands verbatim (failure mode 3).
5. **Motion maps onto tokens**: §B's four moments implement via `ElayMotion`
   (quick=120, standard=220, expressive=400 + the wrap-up collapse at 320ms as a named
   constant); §B's easings land as `easeEditorial`/`easeExit`. No fifth moment without a
   contract amendment.
6. **The visual pre-gate instrument** = §B's 10 taste checks + §A's 4 named regression
   screenshots (15-min block, WrapUpCard chips, dark cold start, CertaintyBadge in both
   modes) + `ElayContrastTest` green. Run by the Astra seat (codex restored) on before/after
   shots in `research/shots/`; the stage closes only on its verdict plus the standard
   adversarial verification round.
7. **detekt `MagicNumber` stays off until D6 merges** (§A out-of-scope note); revisiting it
   afterwards is recorded debt, not Stage 6 scope.
