package dev.elay.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ELAY's spacing scale (stage6 contract §B §3 "Whitespace as Structure" + §A §1). `gutter` is the
 * 16→20dp screen-margin increase §B calls for; `touchMin` re-states the 48dp touch floor that
 * stage6 failure mode 3 forbids shrinking. `hourHeight` is not decorative — it is Plan's timeline
 * layout math (`HOUR_HEIGHT` today), so a screen edit that reads it must keep the 15-minute block
 * at 14dp tall (`hourHeight / 4`), the named regression in the contract's visual pre-gate.
 */
@Immutable
data class ElaySpacing(
    val hair: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 40.dp,
    val gutter: Dp = 20.dp,
    val touchMin: Dp = 48.dp,
    val hourHeight: Dp = 56.dp,
)

val LocalElaySpacing = staticCompositionLocalOf { ElaySpacing() }
