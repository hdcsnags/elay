@file:Suppress("ktlint:standard:filename", "MatchingDeclarationName")

package dev.elay.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import elay.shared.generated.resources.Res
import elay.shared.generated.resources.newsreader_italic
import elay.shared.generated.resources.newsreader_medium
import elay.shared.generated.resources.plus_jakarta_sans_medium
import elay.shared.generated.resources.plus_jakarta_sans_regular
import elay.shared.generated.resources.plus_jakarta_sans_semibold
import org.jetbrains.compose.resources.Font

/**
 * The three font families [elayTypography] composes its 15 roles from (stage6 contract §B §2):
 * `display`/`displayItalic` are Newsreader (upright Medium / italic Regular — lead amendment 2's
 * face budget), `body` is Plus Jakarta Sans.
 */
data class ElayFontFamilies(
    val display: FontFamily,
    val displayItalic: FontFamily,
    val body: FontFamily,
)

/**
 * D2: the five bundled faces (Latin-subset statics instanced from the OFL variable fonts,
 * 224 KB total against the contract's 600 KB ceiling). Every family ends without a platform
 * entry because Compose falls back to the platform default automatically for missing glyphs;
 * the faces are subset to Latin + typographic punctuation, so non-Latin text falls through.
 */
@Composable
fun elayFontFamilies(): ElayFontFamilies =
    ElayFontFamilies(
        display =
            FontFamily(
                Font(Res.font.newsreader_medium, weight = FontWeight.Medium),
            ),
        displayItalic =
            FontFamily(
                Font(Res.font.newsreader_italic, weight = FontWeight.Normal, style = FontStyle.Italic),
            ),
        body =
            FontFamily(
                Font(Res.font.plus_jakarta_sans_regular, weight = FontWeight.Normal),
                Font(Res.font.plus_jakarta_sans_medium, weight = FontWeight.Medium),
                Font(Res.font.plus_jakarta_sans_semibold, weight = FontWeight.SemiBold),
            ),
    )
