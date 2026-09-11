package dev.elay.ui.today

import kotlinx.datetime.Instant
import kotlin.time.Duration

/**
 * Calm, non-alarmist countdown text for a block relative to [now] (spec §2 —
 * "Move this to tomorrow?" tone, never a countdown that reads as pressure).
 * Pure: given the same inputs it always renders the same label.
 */
fun startInLabel(
    now: Instant,
    startsAt: Instant,
    endsAt: Instant,
): String =
    when {
        now < startsAt -> "Starts in ${(startsAt - now).toClockPhrase()}"
        now < endsAt -> "In progress"
        else -> "Finished"
    }

private fun Duration.toClockPhrase(): String {
    val totalMinutes = inWholeMinutes.coerceAtLeast(0)
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

private const val MINUTES_PER_HOUR = 60
