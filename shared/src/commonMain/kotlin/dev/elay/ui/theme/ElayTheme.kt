package dev.elay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * ELAY's design layer entry point (ADR-004, stage6 §A §1): Material3 primitives under
 * ELAY-owned tokens. [darkTheme] is a mandatory parameter, not an inline
 * [isSystemInDarkTheme] read — without it neither a `@Preview` nor a screenshot test can pin a
 * scheme, and stage6 failure mode 2 forbids a second `isSystemInDarkTheme()` call site anywhere
 * outside this file.
 *
 * ELAY tokens are the source of truth; [MaterialTheme] receives a *projection* of them
 * ([ElayColorTokens.toMaterialColorScheme]) so every surviving `MaterialTheme.colorScheme.*` /
 * `.typography.*` / `.shapes.*` read across the 44 UI files lands on ELAY values the moment this
 * merges, with no screen edits required. [LocalElayColors], [LocalElaySpacing], and
 * [LocalElayMotion] are provided alongside for the values Material3 has no slot for (semantic
 * hairlines, the spacing scale, the four signature-motion tokens).
 *
 * The root [Surface] is the ground every screen stands on: it paints `background` and sets
 * `LocalContentColor` to `onBackground`, so a screen composed outside a `Scaffold` (sign-in,
 * loading) reads in the mode-correct ink instead of Compose's black default (found on the
 * D2/D3 emulator pass: dark-mode sign-in title rendered near-black on Obsidian).
 */
@Composable
fun ElayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val tokens = if (darkTheme) elayDarkColors() else elayLightColors()
    CompositionLocalProvider(
        LocalElayColors provides tokens,
        LocalElaySpacing provides ElaySpacing(),
        LocalElayMotion provides ElayMotion(),
    ) {
        MaterialTheme(
            colorScheme = tokens.toMaterialColorScheme(darkTheme),
            typography = elayTypography(elayFontFamilies()),
            shapes = elayShapes,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                content = content,
            )
        }
    }
}
