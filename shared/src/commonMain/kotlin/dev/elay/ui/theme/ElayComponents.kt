package dev.elay.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * ELAY's named overrides of Material3's public `*Defaults` APIs (stage6 §A §1, "component-level
 * overrides required"; failure mode 1: never fork the component source, only compose its public
 * defaults). These are ADDITIVE for this slice — no existing screen call site changes here; D4/
 * D5/D6 adopt them when they migrate their own screens.
 */

/**
 * The outer-card treatment (§B §3: 20dp radius, `surfaceContainer` fill, 0dp elevation, no
 * shadow). [withHairline] draws the 1dp `outlineVariant`-family border §B calls for on inner
 * tiles that sit directly on the card's own fill rather than on the canvas.
 */
@Composable
fun ElayCard(
    modifier: Modifier = Modifier,
    withHairline: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalElayColors.current
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(ElayRadius.card),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceRaised, contentColor = colors.textPrimary),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (withHairline) BorderStroke(1.dp, colors.hairline) else null,
        content = content,
    )
}

/**
 * Replaces the purple pill indicator every shot shows today: selected state reads
 * [ElayColorTokens.onAccentContainer] on an [ElayColorTokens.accentContainer] indicator,
 * unselected reads the quiet taupe [ElayColorTokens.textMuted].
 */
@Composable
fun elayNavigationBarItemColors(): NavigationBarItemColors {
    val colors = LocalElayColors.current
    return NavigationBarItemDefaults.colors(
        selectedIconColor = colors.onAccentContainer,
        selectedTextColor = colors.onAccentContainer,
        indicatorColor = colors.accentContainer,
        unselectedIconColor = colors.textMuted,
        unselectedTextColor = colors.textMuted,
    )
}

/** [ElaySheetColors] bundles the three named surfaces §A calls out for `ModalBottomSheet`. */
@Immutable
data class ElaySheetColors(
    val container: Color,
    val scrim: Color,
    val dragHandle: Color,
)

/**
 * §B §3's 28dp-top sheet: `surfaceRaised` container, a warm-ink scrim (never a cold black, and
 * theme-invariant like Material's own default scrim), and a quiet taupe drag handle that clears
 * the non-text 3:1 floor against [ElaySheetColors.container].
 */
@Composable
fun elaySheetDefaults(): ElaySheetColors {
    val colors = LocalElayColors.current
    return ElaySheetColors(
        container = colors.surfaceRaised,
        scrim = SHEET_SCRIM_INK.copy(alpha = SHEET_SCRIM_ALPHA),
        dragHandle = colors.textMuted,
    )
}

private val SHEET_SCRIM_INK = Color(0xFF171415)
private const val SHEET_SCRIM_ALPHA = 0.5f

/** §B §1's chip treatment: quiet unselected fill, the Cassis container when selected. */
@Composable
fun elayFilterChipColors(): SelectableChipColors {
    val colors = LocalElayColors.current
    return FilterChipDefaults.filterChipColors(
        containerColor = colors.surfaceRaised,
        labelColor = colors.textPrimary,
        iconColor = colors.textMuted,
        selectedContainerColor = colors.accentContainer,
        selectedLabelColor = colors.onAccentContainer,
        selectedLeadingIconColor = colors.onAccentContainer,
        selectedTrailingIconColor = colors.onAccentContainer,
    )
}

/** §B §1's field treatment: Cassis focus/label, hairline resting border. */
@Composable
fun elayTextFieldColors(): TextFieldColors {
    val colors = LocalElayColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        focusedBorderColor = colors.accentPrimary,
        unfocusedBorderColor = colors.hairline,
        focusedLabelColor = colors.accentPrimary,
        unfocusedLabelColor = colors.textMuted,
        cursorColor = colors.accentPrimary,
    )
}

/** §B §3/§6's single-baseline pill buttons (the "C-a-n-t" wrap fix's shape half). */
val elayButtonShape = RoundedCornerShape(ElayRadius.pill)

/** Comfortable pill padding that keeps the 48dp touch floor without the shape looking cramped. */
val elayButtonContentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
