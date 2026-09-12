@file:Suppress("TooManyFunctions") // one small pure formatter per §3's dual-time/deadline/a11y text element

package dev.elay.ui.together.proposal

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Duration

/**
 * Pure dual-time formatting (council/stage2-timelock-gemini.md §3 "The Signature System"): the
 * viewer's local time is always primary, the partner's is secondary, a relative-day badge marks a
 * calendar-day crossover, and a calm caption explains any DST-driven offset shift versus "usual"
 * (today's baseline) offset between the pair — never jargon like "DST", "fold", "EDT vs BST", or a
 * raw UTC offset (§3.3). Every input is an [Instant] + IANA [TimeZone] — no fixed offsets
 * (ADR-006). No `@Composable` here — these are directly unit-testable per the seat grant.
 */

private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
private const val SECONDS_PER_MINUTE = 60

/** "7:00 AM" / "11:30 PM". */
fun formatClockTime(time: LocalTime): String {
    val hour12 = if (time.hour % 12 == 0) 12 else time.hour % 12
    val minute = time.minute.toString().padStart(2, '0')
    return "$hour12:$minute ${periodOf(time)}"
}

private fun periodOf(time: LocalTime): String = if (time.hour < 12) "AM" else "PM"

private fun clockTimeWithoutPeriod(time: LocalTime): String {
    val hour12 = if (time.hour % 12 == 0) 12 else time.hour % 12
    val minute = time.minute.toString().padStart(2, '0')
    return "$hour12:$minute"
}

/** "7:00 – 8:00 PM" (one trailing period when both ends share it) or "11:30 PM – 12:30 AM". */
fun formatTimeRange(
    start: LocalTime,
    end: LocalTime,
): String =
    if (periodOf(start) == periodOf(end)) {
        "${clockTimeWithoutPeriod(start)} – ${formatClockTime(end)}"
    } else {
        "${formatClockTime(start)} – ${formatClockTime(end)}"
    }

/** "Mon".."Sun" — no locale dependency, just the enum's own name. */
fun dayAbbreviation(dayOfWeek: DayOfWeek): String =
    dayOfWeek.name
        .lowercase()
        .replaceFirstChar { it.uppercase() }
        .take(3)

/** "Jan".."Dec". */
fun monthAbbreviation(month: Month): String =
    month.name
        .lowercase()
        .replaceFirstChar { it.uppercase() }
        .take(3)

/** "Thu, Sep 12" (§3.2's "multi-day/forward planning" date form). */
fun formatDatePrefix(date: LocalDate): String =
    "${dayAbbreviation(date.dayOfWeek)}, ${monthAbbreviation(date.month)} ${date.day}"

/**
 * The partner's relative-day tag against the viewer's calendar day for the same instant (§3.2) —
 * `null` on the same day. Uses the friendlier "(Tomorrow)"/"(Yesterday)" wording for a one-day
 * crossover (both forms are spec-sanctioned) and an explicit signed count for anything wider.
 */
fun relativeDayBadge(
    viewerDate: LocalDate,
    partnerDate: LocalDate,
): String? {
    val diff = viewerDate.daysUntil(partnerDate)
    return when {
        diff == 0 -> null
        diff == 1 -> "(Tomorrow)"
        diff == -1 -> "(Yesterday)"
        diff > 1 -> "(+$diff day)"
        else -> "($diff day)"
    }
}

/** Partner's own display name, or the calm fallback when it's blank (§3.1.3). */
fun partnerLabel(partnerDisplayName: String): String = partnerDisplayName.ifBlank { "them" }

private fun TimeZone.offsetMinutesAt(instant: Instant): Int = offsetAt(instant).totalSeconds / SECONDS_PER_MINUTE

/** partner-minus-viewer offset difference in minutes at [instant] (positive => partner's clock reads later). */
private fun offsetDifferenceMinutes(
    instant: Instant,
    viewerZone: TimeZone,
    partnerZone: TimeZone,
): Int = partnerZone.offsetMinutesAt(instant) - viewerZone.offsetMinutesAt(instant)

private fun formatDurationPhrase(minutes: Int): String {
    val hours = minutes / MINUTES_PER_HOUR
    val remainder = minutes % MINUTES_PER_HOUR
    val hourPart = if (hours > 0) "$hours hour${if (hours != 1) "s" else ""}" else null
    val minutePart = if (remainder > 0) "$remainder minute${if (remainder != 1) "s" else ""}" else null
    return listOfNotNull(hourPart, minutePart).joinToString(" ").ifEmpty { "0 minutes" }
}

/**
 * A calm, jargon-free caption (§3.3) for when the pair's offset difference on [candidateStart]
 * diverges from their difference right [now] — e.g. Toronto shifts to DST about two weeks before
 * London does, temporarily narrowing a usual 5-hour gap to 4. Never mentions "DST", "fold", zone
 * abbreviations, or a raw UTC offset. `null` when the difference on this day matches the current
 * baseline (the common case).
 */
fun clockChangeCaption(
    now: Instant,
    candidateStart: Instant,
    viewerZone: TimeZone,
    partnerZone: TimeZone,
): String? {
    val baseline = abs(offsetDifferenceMinutes(now, viewerZone, partnerZone))
    val onDay = abs(offsetDifferenceMinutes(candidateStart, viewerZone, partnerZone))
    if (baseline == onDay) return null
    val direction = if (onDay < baseline) "less" else "more"
    val delta = abs(onDay - baseline)
    return "Time difference is ${formatDurationPhrase(onDay)} on this day " +
        "(${formatDurationPhrase(delta)} $direction than usual due to clock change)"
}

/** The viewer-primary / partner-secondary text lines for one instant window (§3.1). */
data class DualTimeLines(
    val viewerLine: String,
    val partnerLine: String,
    val dstCaption: String?,
)

/**
 * The signature dual-time readout (§3): the viewer's local time is always first/primary ("for
 * you"), the partner's is second/secondary ("for $partnerDisplayName", "for them" if blank), a
 * relative-day badge appears on the partner line when the calendar day differs (§3.2), and an
 * optional plain-language clock-change caption follows (§3.3). [includeDate] prefixes each line
 * with e.g. "Thu, Sep 12 · " (feed cards); the composer's live preview (§1.3) omits it.
 */
@Suppress("LongParameterList") // the instant window + both parties' zone/name + now + the date-prefix toggle
fun buildDualTimeLines(
    startsAt: Instant,
    endsAt: Instant,
    viewerZone: TimeZone,
    partnerZone: TimeZone,
    partnerDisplayName: String,
    now: Instant,
    includeDate: Boolean = false,
): DualTimeLines {
    val viewerStart = startsAt.toLocalDateTime(viewerZone)
    val viewerEnd = endsAt.toLocalDateTime(viewerZone)
    val partnerStart = startsAt.toLocalDateTime(partnerZone)
    val partnerEnd = endsAt.toLocalDateTime(partnerZone)

    val badge = relativeDayBadge(viewerStart.date, partnerStart.date)
    val viewerPrefix = if (includeDate) "${formatDatePrefix(viewerStart.date)} · " else ""
    val partnerPrefix = if (includeDate) "${formatDatePrefix(partnerStart.date)} · " else ""

    val viewerLine = "$viewerPrefix${formatTimeRange(viewerStart.time, viewerEnd.time)} for you"
    val partnerSuffix = " for ${partnerLabel(partnerDisplayName)}" + (badge?.let { " $it" } ?: "")
    val partnerLine = "$partnerPrefix${formatTimeRange(partnerStart.time, partnerEnd.time)}$partnerSuffix"

    return DualTimeLines(
        viewerLine = viewerLine,
        partnerLine = partnerLine,
        dstCaption = clockChangeCaption(now, startsAt, viewerZone, partnerZone),
    )
}

/**
 * The fully vocalized `contentDescription` §5.1 asks for — screen readers struggle with the
 * shorthand dot-separated text — including the partner's day-of-week and "next day" when the
 * instant crosses a calendar boundary for them.
 */
fun dualTimeAccessibilityDescription(
    startsAt: Instant,
    endsAt: Instant,
    viewerZone: TimeZone,
    partnerZone: TimeZone,
    partnerDisplayName: String,
): String {
    val viewerStart = startsAt.toLocalDateTime(viewerZone)
    val viewerEnd = endsAt.toLocalDateTime(viewerZone)
    val partnerStart = startsAt.toLocalDateTime(partnerZone)
    val partnerEnd = endsAt.toLocalDateTime(partnerZone)
    val crossesForPartner = viewerStart.date != partnerStart.date

    val viewerPhrase = "${formatClockTime(viewerStart.time)} to ${formatClockTime(viewerEnd.time)} your time"
    val partnerDaySuffix =
        if (crossesForPartner) {
            " ${dayAbbreviation(partnerStart.date.dayOfWeek)}, next day"
        } else {
            ""
        }
    val partnerPhrase =
        "${formatClockTime(partnerStart.time)} to ${formatClockTime(partnerEnd.time)}$partnerDaySuffix " +
            "for ${partnerLabel(partnerDisplayName)}"
    return "$viewerPhrase, which is $partnerPhrase"
}

/** "Option 1: 7:00 PM to 8:00 PM your time, which is 10:00 PM to 11:00 PM for Alex." (§5.1). */
@Suppress("LongParameterList") // the candidate index + the same instant/zone/name inputs as buildDualTimeLines
fun candidateAccessibilityDescription(
    index: Int,
    startsAt: Instant,
    endsAt: Instant,
    viewerZone: TimeZone,
    partnerZone: TimeZone,
    partnerDisplayName: String,
): String =
    "Option ${index + 1}: " +
        dualTimeAccessibilityDescription(startsAt, endsAt, viewerZone, partnerZone, partnerDisplayName)

/** §5.2: "Selected: Option 1 of 3" / "Unselected: Option 2 of 3". */
fun candidateStateDescription(
    index: Int,
    total: Int,
    selected: Boolean,
): String {
    val label = if (selected) "Selected" else "Unselected"
    return "$label: Option ${index + 1} of $total"
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

/** "TIME LOCK PROPOSAL · Incoming" top-bar countdown (§2.1): "Expires in 4h 12m" / "Expired". */
fun deadlineCountdownLabel(
    now: Instant,
    deadline: Instant,
): String {
    if (now >= deadline) return "Expired"
    return "Expires in ${(deadline - now).toClockPhrase()}"
}

/** "Today at 6:00 PM" / "Tomorrow at 9:00 AM" / "Fri, Sep 13 at 9:00 AM" in [zone]. */
fun deadlineWallClockLabel(
    now: Instant,
    deadline: Instant,
    zone: TimeZone,
): String {
    val nowDate = now.toLocalDateTime(zone).date
    val deadlineDateTime = deadline.toLocalDateTime(zone)
    val dayPrefix =
        when (nowDate.daysUntil(deadlineDateTime.date)) {
            0 -> "Today"
            1 -> "Tomorrow"
            else -> formatDatePrefix(deadlineDateTime.date)
        }
    return "$dayPrefix at ${formatClockTime(deadlineDateTime.time)}"
}

/** §2.2's outgoing-card secondary line: "Response deadline: Today at 6:00 PM (in 3 hours)". */
fun responseDeadlineLine(
    now: Instant,
    deadline: Instant,
    zone: TimeZone,
): String {
    val wallClock = deadlineWallClockLabel(now, deadline, zone)
    return if (now >= deadline) {
        "Response deadline: $wallClock"
    } else {
        "Response deadline: $wallClock (in ${(deadline - now).toClockPhrase()})"
    }
}
