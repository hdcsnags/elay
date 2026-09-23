// ElayColorTokens is one of several top-level declarations this file owns
// (elayLightColors/elayDarkColors/toMaterialColorScheme/etc.) — the filename tracks the file's
// *subject* (brief-13 deliverable 1: "ElayColors.kt"), not the one data class detekt's
// MatchingDeclarationName heuristic happens to notice first.
@file:Suppress("MatchingDeclarationName")

package dev.elay.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * ELAY's semantic color tokens (stage6 contract §B §1 "Warm Editorial Restraint"; lead amendment
 * 1 drops §A's illustrative `youTint`/`themTint` — §B explicitly rejects a chromatic pair
 * identity, so the two-person dynamic reads through typography, not hue, and lands entirely
 * through [textPrimary]/[textMuted]). This is the single source of truth: every Material3 read
 * in the 44 UI files is a *projection* of these values via [toMaterialColorScheme], never the
 * other way around (stage6 failure mode 2).
 */
@Immutable
data class ElayColorTokens(
    val surfacePaper: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val hairline: Color,
    val hairlineFaint: Color,
    val textEditorial: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val accentPrimary: Color,
    val onAccentPrimary: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val accentQuiet: Color,
    val availabilityCalm: Color,
    val availabilityCaution: Color,
    val onAvailabilityCaution: Color,
)

/**
 * Light "Ecru & Espresso" — §B §1's table, verbatim. `textEditorial`/`textPrimary` share one ink
 * (`#1C1917`): the editorial voice is a *font* choice (Newsreader vs Plus Jakarta Sans), not a
 * second text color, so both semantic names alias the same hex on purpose.
 *
 * `accentQuiet` and `availabilityCalm` are not in §B's pinned table (lead amendment 1 explicitly
 * asks D1 to derive `availabilityCalm`; `accentQuiet` has no assigned hex either) — both are this
 * seat's derivation, same hue family as their anchor token, chosen to clear the 4.5:1 text floor
 * against all three surfaces (see `ElayContrastTest`) so they stay safe to use as either text or
 * a non-text accent later. `accentQuiet` is a dustier, lower-chroma step of Smoked Cassis for
 * quieter emphasis (a de-emphasized link or icon tint); `availabilityCalm` is a quiet sage/stone
 * green, chosen because it reads as "calm/available" without competing with the Cassis accent.
 */
fun elayLightColors(): ElayColorTokens =
    ElayColorTokens(
        surfacePaper = Color(0xFFFBF9F6),
        surfaceRaised = Color(0xFFF3EFEA),
        surfaceSunken = Color(0xFFEBE5DD),
        hairline = Color(0xFFDCD6CD),
        hairlineFaint = Color(0xFFEAE5DE),
        textEditorial = Color(0xFF1C1917),
        textPrimary = Color(0xFF1C1917),
        textMuted = Color(0xFF68625D),
        accentPrimary = Color(0xFF6D3240),
        onAccentPrimary = Color(0xFFFFFFFF),
        accentContainer = Color(0xFFF4EAEB),
        onAccentContainer = Color(0xFF3D121E),
        accentQuiet = Color(0xFF8A5560),
        availabilityCalm = Color(0xFF4F6449),
        availabilityCaution = Color(0xFF8C6541),
        onAvailabilityCaution = Color(0xFFFFFFFF),
    )

/**
 * Dark "Smoked Velvet Fig" — §B §1's table, verbatim (§B §7: "not an automated mathematical
 * inversion" — every value below is its own hand-picked hex, not a formula on the light table).
 * See [elayLightColors] for why `textEditorial`/`textPrimary` share a hex and why `accentQuiet`/
 * `availabilityCalm` are this seat's derivation rather than a pinned §B value.
 */
fun elayDarkColors(): ElayColorTokens =
    ElayColorTokens(
        surfacePaper = Color(0xFF171415),
        surfaceRaised = Color(0xFF211D1F),
        surfaceSunken = Color(0xFF2B2628),
        hairline = Color(0xFF3E373A),
        hairlineFaint = Color(0xFF2A2426),
        textEditorial = Color(0xFFF4EFEA),
        textPrimary = Color(0xFFF4EFEA),
        textMuted = Color(0xFFA8A19B),
        accentPrimary = Color(0xFFDF9EAC),
        onAccentPrimary = Color(0xFF441926),
        accentContainer = Color(0xFF4F2633),
        onAccentContainer = Color(0xFFFAD8DF),
        accentQuiet = Color(0xFFC79AA3),
        availabilityCalm = Color(0xFF9DB08F),
        availabilityCaution = Color(0xFFE0B286),
        onAvailabilityCaution = Color(0xFF462A0D),
    )

/** Defaults to light; [ElayTheme] always provides the mode-correct instance. */
val LocalElayColors = staticCompositionLocalOf { elayLightColors() }

/**
 * Cedar (Ambered Cedar / Warm Ochre Gold) container tones for the Material `tertiary` group.
 * §B's table pins `tertiary`/`onTertiary` (mapped from [ElayColorTokens.availabilityCaution] /
 * [ElayColorTokens.onAvailabilityCaution]) but not a `tertiaryContainer` pair, so this seat
 * derives one in the same hue family: a warm tan tint for the container, a deep cedar brown for
 * the on-color, both computed as simple linear blends (not a pinned §B hex, so not part of
 * [ElayColorTokens] itself — see [toMaterialColorScheme]).
 */
private fun tertiaryContainerFor(dark: Boolean): Color = if (dark) Color(0xFF43372E) else Color(0xFFE8E0D9)

private fun onTertiaryContainerFor(dark: Boolean): Color = if (dark) Color(0xFFECD8C4) else Color(0xFF4E3B2A)

/**
 * The load-bearing projection (ADR-004): ELAY tokens are the source of truth, Material3's
 * [ColorScheme] is a derived view. Every slot the 44 UI files' `MaterialTheme.colorScheme.*`
 * reads touch is set explicitly below so no M3 baseline purple can show through; the few
 * `*Fixed*` roles nothing in this tree reads are left at the builder's own default, since they
 * are unreachable by any default Material3 component this app actually uses. `error` is left at
 * the M3 default red family per the lead amendment ("stays M3 default... adjusted for warmth if
 * trivial" — no adjustment was trivial enough to risk the baseline's own tested contrast).
 * `tonalElevation` is never set here (callers pin it to `0.dp`; see `ElayElevation.kt`) so
 * Material's own primary-tint overlay can never re-enter through the back door.
 */
fun ElayColorTokens.toMaterialColorScheme(dark: Boolean): ColorScheme {
    val inverse = if (dark) elayLightColors() else elayDarkColors()
    return if (dark) darkScheme(inverse) else lightScheme(inverse)
}

/** The dark-mode half of [toMaterialColorScheme], split out to stay under detekt's LongMethod. */
private fun ElayColorTokens.darkScheme(inverse: ElayColorTokens): ColorScheme =
    darkColorScheme(
        primary = accentPrimary,
        onPrimary = onAccentPrimary,
        primaryContainer = accentContainer,
        onPrimaryContainer = onAccentContainer,
        inversePrimary = inverse.accentPrimary,
        secondary = textMuted,
        onSecondary = surfacePaper,
        secondaryContainer = surfaceSunken,
        onSecondaryContainer = textPrimary,
        tertiary = availabilityCaution,
        onTertiary = onAvailabilityCaution,
        tertiaryContainer = tertiaryContainerFor(dark = true),
        onTertiaryContainer = onTertiaryContainerFor(dark = true),
        background = surfacePaper,
        onBackground = textPrimary,
        surface = surfacePaper,
        onSurface = textPrimary,
        surfaceVariant = surfaceRaised,
        onSurfaceVariant = textMuted,
        surfaceTint = accentPrimary,
        inverseSurface = inverse.surfaceRaised,
        inverseOnSurface = inverse.textPrimary,
        outline = hairline,
        outlineVariant = hairlineFaint,
        scrim = Color(0xFF171415),
        surfaceBright = surfaceSunken,
        surfaceDim = surfacePaper,
        surfaceContainer = surfaceRaised,
        surfaceContainerHigh = surfaceSunken,
        surfaceContainerHighest = surfaceSunken,
        surfaceContainerLow = surfaceRaised,
        surfaceContainerLowest = surfacePaper,
    )

/** The light-mode half of [toMaterialColorScheme], split out to stay under detekt's LongMethod. */
private fun ElayColorTokens.lightScheme(inverse: ElayColorTokens): ColorScheme =
    lightColorScheme(
        primary = accentPrimary,
        onPrimary = onAccentPrimary,
        primaryContainer = accentContainer,
        onPrimaryContainer = onAccentContainer,
        inversePrimary = inverse.accentPrimary,
        secondary = textMuted,
        onSecondary = surfacePaper,
        secondaryContainer = surfaceSunken,
        onSecondaryContainer = textPrimary,
        tertiary = availabilityCaution,
        onTertiary = onAvailabilityCaution,
        tertiaryContainer = tertiaryContainerFor(dark = false),
        onTertiaryContainer = onTertiaryContainerFor(dark = false),
        background = surfacePaper,
        onBackground = textPrimary,
        surface = surfacePaper,
        onSurface = textPrimary,
        surfaceVariant = surfaceRaised,
        onSurfaceVariant = textMuted,
        surfaceTint = accentPrimary,
        inverseSurface = inverse.surfaceRaised,
        inverseOnSurface = inverse.textPrimary,
        outline = hairline,
        outlineVariant = hairlineFaint,
        scrim = Color(0xFF171415),
        surfaceBright = surfacePaper,
        surfaceDim = surfaceSunken,
        surfaceContainer = surfaceRaised,
        surfaceContainerHigh = surfaceSunken,
        surfaceContainerHighest = surfaceSunken,
        surfaceContainerLow = surfaceRaised,
        surfaceContainerLowest = surfacePaper,
    )
