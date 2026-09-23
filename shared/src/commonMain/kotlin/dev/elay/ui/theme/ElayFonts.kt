@file:Suppress("ktlint:standard:filename", "MatchingDeclarationName")

package dev.elay.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/**
 * The three font families [elayTypography] composes its 15 roles from (stage6 contract §B §2):
 * `display`/`displayItalic` are Newsreader (upright Medium / italic Regular — lead amendment 2's
 * two-face budget), `body` is Plus Jakarta Sans.
 */
data class ElayFontFamilies(
    val display: FontFamily,
    val displayItalic: FontFamily,
    val body: FontFamily,
)

/**
 * Seat grant boundary (brief-13): D2 bundles the actual `.ttf` faces and replaces the body of
 * *this function only* with `FontFamily(Font(Res.font.newsreader_medium, ...), FontFamily.Default)`
 * -style declarations. Until then every family defaults to the platform font so the type scale's
 * structure (sizes/weights/line-heights in [elayTypography]) ships and is testable today.
 */
@Composable
fun elayFontFamilies(): ElayFontFamilies =
    ElayFontFamilies(
        display = FontFamily.Default,
        displayItalic = FontFamily.Default,
        body = FontFamily.Default,
    )
