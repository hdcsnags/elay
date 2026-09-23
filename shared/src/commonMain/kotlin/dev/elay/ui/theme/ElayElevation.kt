package dev.elay.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ELAY's elevation model (stage6 contract §B §3 "Elevation Floor" + §A §2's derivation rules).
 * Drop shadows are banned on flat surfaces — depth is warm tonal layering, not a shadow, so every
 * shadow-elevation level below is pinned to `0.dp` for both themes.
 *
 * The dark-mode rule this file exists to name: a "raised" surface in dark mode is a *lighter*
 * step than the canvas beneath it ([ElayColorTokens.surfaceRaised] `#211D1F` sits above
 * [ElayColorTokens.surfacePaper] `#171415`), never a shadow — Compose's shadow rendering is
 * near-invisible against a dark background, so depth there can only read as a warmer, brighter
 * fill. `tonalElevation` is likewise pinned to `0.dp` so Material3's own primary-tint elevation
 * overlay (which would otherwise wash every surface toward [ElayColorTokens.accentPrimary]) never
 * re-enters through the back door.
 */
@Immutable
data class ElayElevation(
    val flat: Dp = 0.dp,
    val raised: Dp = 0.dp,
    val overlay: Dp = 0.dp,
    val tonalElevation: Dp = 0.dp,
)
