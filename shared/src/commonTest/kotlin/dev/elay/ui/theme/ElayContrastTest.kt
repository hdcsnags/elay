package dev.elay.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * WCAG 2.2 AA contrast rail (stage6 contract, brief-13 item 10): a pure-Kotlin relative-luminance
 * computation over an explicit pair table for both [elayLightColors] and [elayDarkColors] — "a
 * rail, not an eyeball". Every pair reads the real token functions (not a second, hand-copied
 * hex table), so a future edit to `ElayColors.kt` that regresses a ratio fails here first.
 *
 * Every §B hex already clears its floor with margin (see this seat's diff notes for the measured
 * ratios) — there was no hex to adjust, so there is nothing here "pinning a deviation"; this test
 * exists purely as the ongoing rail.
 *
 * `hairline` / `hairlineFaint` are deliberately excluded from the non-text 3:1 floor: they are
 * decorative separators, never the sole means of conveying a boundary or a state (every card/tile
 * they outline also sits on a distinct tonal surface step, and no interactive state relies on the
 * hairline alone) — WCAG 1.4.11's floor targets UI components and graphical objects that are
 * "required to identify... a state"; a purely decorative divider reinforced by adjacent surface
 * contrast does not fall under that requirement.
 */
class ElayContrastTest {
    private fun linearize(channel: Float): Double =
        if (channel <= LINEAR_THRESHOLD) {
            channel / LINEAR_DIVISOR
        } else {
            ((channel + GAMMA_OFFSET) / GAMMA_DIVISOR).pow(GAMMA_EXPONENT)
        }

    private fun relativeLuminance(color: Color): Double =
        RED_WEIGHT * linearize(color.red) + GREEN_WEIGHT * linearize(color.green) + BLUE_WEIGHT * linearize(color.blue)

    private fun contrastRatio(
        a: Color,
        b: Color,
    ): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + LUMINANCE_OFFSET) / (min(la, lb) + LUMINANCE_OFFSET)
    }

    private fun assertMeetsFloor(
        label: String,
        foreground: Color,
        background: Color,
        floor: Double,
    ) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(ratio >= floor, "$label: contrast $ratio does not meet the $floor:1 floor")
    }

    private val schemes = listOf("light" to elayLightColors(), "dark" to elayDarkColors())

    @Test
    fun textPrimaryAndTextEditorialMeetTextFloorOnEverySurface() {
        for ((name, tokens) in schemes) {
            val surfaces =
                listOf(
                    "surfacePaper" to tokens.surfacePaper,
                    "surfaceRaised" to tokens.surfaceRaised,
                    "surfaceSunken" to tokens.surfaceSunken,
                )
            for ((surfaceName, surface) in surfaces) {
                assertMeetsFloor("$name textPrimary on $surfaceName", tokens.textPrimary, surface, TEXT_FLOOR)
                assertMeetsFloor("$name textEditorial on $surfaceName", tokens.textEditorial, surface, TEXT_FLOOR)
            }
        }
    }

    @Test
    fun mutedTextMeetsTextFloorOnPaperAndRaised() {
        for ((name, tokens) in schemes) {
            assertMeetsFloor("$name textMuted on surfacePaper", tokens.textMuted, tokens.surfacePaper, TEXT_FLOOR)
            assertMeetsFloor("$name textMuted on surfaceRaised", tokens.textMuted, tokens.surfaceRaised, TEXT_FLOOR)
        }
    }

    @Test
    fun onColorsMeetTextFloorOnTheirOwnFilledTokens() {
        for ((name, tokens) in schemes) {
            assertMeetsFloor(
                "$name onAccentPrimary on accentPrimary",
                tokens.onAccentPrimary,
                tokens.accentPrimary,
                TEXT_FLOOR,
            )
            assertMeetsFloor(
                "$name onAccentContainer on accentContainer",
                tokens.onAccentContainer,
                tokens.accentContainer,
                TEXT_FLOOR,
            )
            assertMeetsFloor(
                "$name onAvailabilityCaution on availabilityCaution",
                tokens.onAvailabilityCaution,
                tokens.availabilityCaution,
                TEXT_FLOOR,
            )
        }
    }

    @Test
    fun accentAndCautionMeetNonTextFloorOnEverySurface() {
        for ((name, tokens) in schemes) {
            val surfaces =
                listOf(
                    "surfacePaper" to tokens.surfacePaper,
                    "surfaceRaised" to tokens.surfaceRaised,
                    "surfaceSunken" to tokens.surfaceSunken,
                )
            for ((surfaceName, surface) in surfaces) {
                assertMeetsFloor(
                    "$name accentPrimary (non-text) on $surfaceName",
                    tokens.accentPrimary,
                    surface,
                    NON_TEXT_FLOOR,
                )
                assertMeetsFloor(
                    "$name availabilityCaution (non-text) on $surfaceName",
                    tokens.availabilityCaution,
                    surface,
                    NON_TEXT_FLOOR,
                )
            }
        }
    }

    /**
     * Supplementary coverage beyond brief-13 item 10's required table: [ElayColorTokens
     * .availabilityCalm] and [ElayColorTokens.accentQuiet] are this seat's own derivation (not a
     * pinned §B hex — see `ElayColors.kt`), so this pins them to the same text floor across every
     * surface now, before any screen adopts either token.
     */
    @Test
    fun derivedTokensMeetTextFloorOnEverySurface() {
        for ((name, tokens) in schemes) {
            val surfaces =
                listOf(
                    "surfacePaper" to tokens.surfacePaper,
                    "surfaceRaised" to tokens.surfaceRaised,
                    "surfaceSunken" to tokens.surfaceSunken,
                )
            for ((surfaceName, surface) in surfaces) {
                assertMeetsFloor("$name availabilityCalm on $surfaceName", tokens.availabilityCalm, surface, TEXT_FLOOR)
                assertMeetsFloor("$name accentQuiet on $surfaceName", tokens.accentQuiet, surface, TEXT_FLOOR)
            }
        }
    }

    private companion object {
        const val TEXT_FLOOR = 4.5
        const val NON_TEXT_FLOOR = 3.0
        const val LINEAR_THRESHOLD = 0.03928f
        const val LINEAR_DIVISOR = 12.92
        const val GAMMA_OFFSET = 0.055
        const val GAMMA_DIVISOR = 1.055
        const val GAMMA_EXPONENT = 2.4
        const val RED_WEIGHT = 0.2126
        const val GREEN_WEIGHT = 0.7152
        const val BLUE_WEIGHT = 0.0722
        const val LUMINANCE_OFFSET = 0.05
    }
}
