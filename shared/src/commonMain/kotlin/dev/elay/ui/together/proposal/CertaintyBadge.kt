package dev.elay.ui.together.proposal

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.elay.domain.availability.Certainty

/**
 * §B 1.4's soft-amber "busy" badge tone in both themes — the one certainty state deliberately
 * off the neutral [MaterialTheme.colorScheme] palette (still never
 * [MaterialTheme.colorScheme.error]: "Non-blocking… Never flashing, never red").
 */
private val BUSY_CONTAINER_LIGHT = Color(0xFFFFF3E0)
private val BUSY_CONTAINER_DARK = Color(0xFF3E2723)

/**
 * §B 1.3's exact self-perspective calm copy matrix. Both the composer's own-candidate label
 * (deliverable 2) and the incoming-card candidate chip label (deliverable 3) read the viewer's OWN
 * schedule through `rpc_self_conflict_hints` — never the partner's — so both surfaces share this
 * single "Viewer Copy (Self)" column; the partner-perspective column (§B row "busy (partner)") is
 * out of this seat's grant (peer-directed hints are `rpc_proposal_conflict_hints`, B7's surface).
 */
fun certaintyLabel(certainty: Certainty): String =
    when (certainty) {
        // Pre-gate F16 (lead amendment over §B's literal copy): every free_per_calendar in
        // this build is derived from the user's own marked busy times (no external calendar
        // can exist until the Google adapter lands), so the copy must not claim one.
        Certainty.FreePerCalendar -> "Free · matches your busy times"
        Certainty.FreePerElay -> "Free on ELAY"
        // Pre-gate F21: unknown must never assert "free" — the server may have declined to
        // look (rate limit) or its coverage may not reach this candidate.
        Certainty.Unknown -> "Availability not checked"
        Certainty.Busy -> "You have a scheduled block at this time"
    }

/** §B 5.1's phonetic screen-reader descriptions — spoken in full sentences, never the shorthand
 * label text a badge shows sighted users. */
fun certaintyAccessibilityDescription(certainty: Certainty): String =
    when (certainty) {
        Certainty.FreePerCalendar -> "Free: no conflicts with your schedule or your marked busy times"
        Certainty.FreePerElay -> "Free: no conflicts on your ELAY schedule"
        Certainty.Unknown -> "Availability could not be checked for this time"
        Certainty.Busy -> "Conflict: Already busy during this time"
    }

/**
 * §B 1.4's pill badge: `labelSmall` text in a compact rounded [Surface], color-independent (every
 * state also carries its own [certaintyLabel] text — §B 5.4 "no information relies solely on
 * color"). Never [MaterialTheme.colorScheme.error] for [Certainty.Busy] — a soft, non-blocking
 * amber tone instead (§B 1.4/§4.1's "no guilt banners" stance extended to conflict labels).
 */
@Composable
fun CertaintyBadge(
    certainty: Certainty,
    modifier: Modifier = Modifier,
) {
    val (container, content) = certaintyColors(certainty)
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(percent = 50),
        modifier = modifier.semantics { contentDescription = certaintyAccessibilityDescription(certainty) },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(certaintyLabel(certainty), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun certaintyColors(certainty: Certainty): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (certainty) {
        Certainty.FreePerCalendar -> scheme.secondaryContainer to scheme.onSecondaryContainer
        Certainty.FreePerElay -> scheme.surfaceVariant to scheme.onSurfaceVariant
        Certainty.Unknown -> scheme.surfaceVariant to scheme.onSurfaceVariant
        Certainty.Busy -> {
            val busyContainer = if (isSystemInDarkTheme()) BUSY_CONTAINER_DARK else BUSY_CONTAINER_LIGHT
            busyContainer to scheme.onSurface
        }
    }
}
