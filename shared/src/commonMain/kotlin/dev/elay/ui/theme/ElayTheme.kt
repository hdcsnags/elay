package dev.elay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * ELAY's design layer entry point (ADR-004): Material 3 primitives under
 * ELAY-owned tokens. Color/type/shape/motion tokens land with the Phase 1
 * design pass; until then this pins the single place they will live.
 */
@Composable
fun ElayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
