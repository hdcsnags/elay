package dev.elay.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * ELAY's motion tokens (stage6 contract §B §4 "Considered Motion" + lead amendment 5). Everyday
 * navigation stays instant/quick; the four named signature moments — the realtime proposal flip,
 * the time-lock seal, the wrap-up card retiring, and the horizon glide — are the *only* places
 * these longer durations should appear (amendment 5: "no fifth moment without a contract
 * amendment"). `wrapUpCollapse` is its own named constant (not reused from `expressive`) because
 * §B pins it to a distinct 320ms, matching the wrap-up card's height-collapse choreography.
 */
@Immutable
data class ElayMotion(
    val instant: Int = 0,
    val quick: Int = 120,
    val standard: Int = 220,
    val wrapUpCollapse: Int = 320,
    val expressive: Int = 400,
    val deliberate: Int = 650,
    val easeEditorial: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    val easeExit: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f),
)

val LocalElayMotion = staticCompositionLocalOf { ElayMotion() }
