package dev.elay.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * ELAY's editorial voice (stage6 contract §B §2 + lead amendment 2). Newsreader carries every
 * display/headline role — always [FontWeight.Medium], never SemiBold: amendment 2 ships exactly
 * two Newsreader faces (Medium upright, Italic regular) to fit the 600KB font budget, so
 * requesting a weight neither face has would make Compose fake-embolden the Medium face instead
 * of rendering the real one. `titleLarge` alone is the italic voice (empty states, reflections).
 * Plus Jakarta Sans carries every operational role (titles below titleLarge, body, labels).
 *
 * `displayMedium`, `displaySmall`, `headlineLarge`, and `titleSmall` are not in §B §2's table —
 * per brief-13 item 2 ("fill the unused display-star and headlineLarge slots per the same
 * editorial logic"), this seat interpolates them onto the same descending scale as the pinned roles
 * (34/32/30/28/26/22sp) so the four currently-unread slots are ready the moment a screen adopts
 * them, without inventing a style family the rest of the table doesn't already use.
 */
fun elayTypography(f: ElayFontFamilies): Typography {
    fun style(
        family: FontFamily,
        size: TextUnit,
        lineHeight: TextUnit,
        weight: FontWeight,
        tracking: TextUnit = 0.sp,
        italic: Boolean = false,
    ): TextStyle =
        TextStyle(
            fontFamily = family,
            fontSize = size,
            lineHeight = lineHeight,
            fontWeight = weight,
            letterSpacing = tracking,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        )

    return Typography(
        displayLarge = style(f.display, 34.sp, 40.sp, FontWeight.Medium),
        displayMedium = style(f.display, 32.sp, 38.sp, FontWeight.Medium),
        displaySmall = style(f.display, 30.sp, 36.sp, FontWeight.Medium),
        headlineLarge = style(f.display, 28.sp, 34.sp, FontWeight.Medium),
        headlineMedium = style(f.display, 26.sp, 32.sp, FontWeight.Medium),
        headlineSmall = style(f.display, 22.sp, 28.sp, FontWeight.Medium),
        titleLarge = style(f.displayItalic, 19.sp, 24.sp, FontWeight.Normal, italic = true),
        titleMedium = style(f.body, 16.sp, 22.sp, FontWeight.SemiBold),
        titleSmall = style(f.body, 14.sp, 20.sp, FontWeight.Medium),
        bodyLarge = style(f.body, 15.sp, 22.sp, FontWeight.Normal),
        bodyMedium = style(f.body, 14.sp, 20.sp, FontWeight.Normal),
        bodySmall = style(f.body, 12.sp, 16.sp, FontWeight.Medium),
        labelLarge = style(f.body, 14.sp, 20.sp, FontWeight.SemiBold),
        labelMedium = style(f.body, 12.sp, 16.sp, FontWeight.SemiBold, tracking = 0.5.sp),
        labelSmall = style(f.body, 11.sp, 14.sp, FontWeight.Medium),
    )
}
