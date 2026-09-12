package dev.elay.ui.together

import dev.elay.domain.model.PairError
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlin.math.abs
import kotlin.time.Duration

/**
 * Pure formatting helpers for Together (spec §2 — calm-assistant tone, never a raw error code).
 * Mirrors the house pattern in [dev.elay.ui.today.TodayFormatting].
 */

private const val INVITE_CODE_GROUP_SIZE = 5

/**
 * Groups a 10-character invite code as `XXXXX-XXXXX` (contract §1). The wire code already
 * arrives grouped (contracts/fixtures/pair-invite.json), so this mainly normalizes defensively —
 * stripping any stray separators and re-grouping — rather than assuming the server's exact
 * formatting forever.
 */
fun formatInviteCode(code: String): String {
    val bare = code.filter { it.isLetterOrDigit() }.uppercase()
    if (bare.length != INVITE_CODE_GROUP_SIZE * 2) return code
    return "${bare.take(INVITE_CODE_GROUP_SIZE)}-${bare.drop(INVITE_CODE_GROUP_SIZE)}"
}

/** Calm countdown to invite expiry, matching [dev.elay.ui.today.startInLabel]'s non-alarmist tone. */
fun expiresInLabel(
    now: Instant,
    expiresAt: Instant,
): String {
    if (now >= expiresAt) return "Expired"
    return "Expires in ${(expiresAt - now).toClockPhrase()}"
}

private fun Duration.toClockPhrase(): String {
    val totalMinutes = inWholeMinutes.coerceAtLeast(0)
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    val days = hours / HOURS_PER_DAY
    val remainingHours = hours % HOURS_PER_DAY
    return when {
        days > 0 && remainingHours > 0 -> "${days}d ${remainingHours}h"
        days > 0 -> "${days}d"
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24

/**
 * Uniform, non-leaking copy for a [PairError] (ADR-defended: never expand
 * `invalid_or_unavailable` client-side — dev.elay.domain.model.PairError's kdoc).
 */
fun PairError.calmMessage(): String =
    when (this) {
        PairError.InvalidOrUnavailable -> "That didn't go through — check the code and try again."
        PairError.NotMember -> "You're not in a pair right now."
        is PairError.Unrecognized -> "Something didn't go through. Please try again."
    }

/** Calm copy for a network failure — never the raw [dev.elay.domain.model.PairFailure.Network] reason string. */
fun networkFailureMessage(retryable: Boolean): String =
    if (retryable) {
        "Couldn't reach the server — try again in a moment."
    } else {
        "Couldn't reach the server right now."
    }

private const val SECONDS_PER_MINUTE = 60

/**
 * City-style label from an IANA zone id (pair-card member rows read "Toronto", not a bare
 * `"America/Toronto"`): the last `/`-separated segment, underscores rendered as spaces —
 * `"America/Toronto"` -> `"Toronto"`, `"Etc/UTC"` -> `"UTC"`, `"America/New_York"` -> `"New York"`.
 */
private fun cityLabel(zoneId: String): String = zoneId.substringAfterLast('/').replace('_', ' ')

/** `"UTC-4"` / `"UTC+5:30"` / `"UTC+0"` — [zone]'s current offset at [now], never a bare number. */
private fun utcOffsetSuffix(
    zone: TimeZone,
    now: Instant,
): String {
    val totalMinutes = zone.offsetAt(now).totalSeconds / SECONDS_PER_MINUTE
    val sign = if (totalMinutes < 0) "-" else "+"
    val absMinutes = abs(totalMinutes)
    val hours = absMinutes / MINUTES_PER_HOUR
    val minutes = absMinutes % MINUTES_PER_HOUR
    return if (minutes == 0) "UTC$sign$hours" else "UTC$sign$hours:${minutes.toString().padStart(2, '0')}"
}

/**
 * A member row's zone string, friendlier than a bare IANA id or a bare "UTC": city-style label
 * plus the current UTC offset, e.g. `"Toronto · UTC-4"` — matching [dev.elay.ui.together.proposal]'s
 * "never a raw offset alone, never jargon" tone (`ProposalTimeFormatting.kt`'s `clockChangeCaption`
 * doc), except here the offset itself *is* the useful fact, so it's shown, just always zone-anchored
 * so it's never mistaken for a duration. Falls back to the bare id if it isn't a recognized IANA
 * zone (defensive only — the server only ever sends valid ids). When the city label is already
 * "UTC" and its offset is zero (e.g. `"Etc/UTC"`), the offset is dropped rather than doubled up.
 */
fun formatZoneLabel(
    zoneId: String,
    now: Instant,
): String {
    val city = cityLabel(zoneId)
    val zone = runCatching { TimeZone.of(zoneId) }.getOrNull() ?: return city
    val offset = utcOffsetSuffix(zone, now)
    return if (city == "UTC" && offset == "UTC+0") city else "$city · $offset"
}
