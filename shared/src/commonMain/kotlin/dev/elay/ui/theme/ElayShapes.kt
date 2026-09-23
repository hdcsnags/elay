// ElayRadius is one of two top-level declarations this file owns (elayShapes maps it onto
// Material3's Shapes slots below) — the filename tracks the file's *subject* (brief-13
// deliverable 3: "ElayShapes.kt"), not the one object detekt's MatchingDeclarationName
// heuristic happens to notice first.
@file:Suppress("MatchingDeclarationName")

package dev.elay.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ELAY's corner radii (stage6 contract §B §3's shape table). `pill` is deliberately a very large
 * `Dp` rather than `CornerSize(percent = 50)`: Compose clamps a [RoundedCornerShape] radius to
 * half of the shape's shortest side, so any `pill`-sized component still renders fully rounded
 * regardless of its actual height, and a plain `Dp` composes more simply with `elayShapes` below.
 */
object ElayRadius {
    val field: Dp = 12.dp
    val stepper: Dp = 12.dp
    val tile: Dp = 14.dp
    val card: Dp = 20.dp
    val sheet: Dp = 28.dp
    val pill: Dp = 999.dp
}

/**
 * Maps ELAY's radii onto Material3's five shape slots. `medium` — not `large` — takes the 20dp
 * card radius because `CardDefaults.shape` (a bare, not-yet-migrated `Card()`) reads
 * `MaterialTheme.shapes.medium`: this is what turns the 14 existing `Card()` call sites into
 * ELAY's 20dp outer-card radius the moment this theme merges, ahead of their own migration slice
 * (D4/D5/D6) to the named `ElayCard` wrapper (`ElayComponents.kt`).
 */
val elayShapes: Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(ElayRadius.field),
        small = RoundedCornerShape(ElayRadius.field),
        medium = RoundedCornerShape(ElayRadius.card),
        large = RoundedCornerShape(ElayRadius.card),
        extraLarge = RoundedCornerShape(ElayRadius.sheet),
    )
