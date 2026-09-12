package dev.elay.ui.today

import dev.elay.domain.model.TimeBlock
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

/**
 * A block's planned duration in whole minutes (contracts/stage5-retention-hardening.md, C7's
 * grant) — the "planned" side of the plan-vs-actual pair `session_outcomes.actual_minutes` is
 * compared against. Never negative in practice (Phase 1's `endsAt > startsAt` invariant), but
 * `coerceAtLeast(0)` keeps this pure and total for any stray malformed block rather than crashing.
 */
fun TimeBlock.plannedMinutes(): Int = (endsAt - startsAt).inWholeMinutes.coerceAtLeast(0).toInt()

/**
 * Wrap-up card subtitle (council/stage5-retention-gemini.md §B 1.2: `"{Title} · Scheduled for
 * {duration}m"`, e.g. `"Study session · Scheduled for 60m"`).
 */
fun wrapUpSubtitle(block: TimeBlock): String =
    "${block.title ?: "Untitled block"} · Scheduled for ${block.plannedMinutes()}m"

/**
 * Next-Time card body rationale (§B 2.2's "Body Rationale (Dynamic)" — the ran-long/finished-early
 * variants only: this seat's [dev.elay.domain.model.NextTimeSuggestion] carries no outcome-kind or
 * per-row delta (only a server-opaque [dev.elay.domain.model.NextTimeSuggestion.basis] string this
 * seat is told never to pattern-match), so the "didn't happen" variant (§B: "Study session didn't
 * happen on Sunday. Try again this week?") isn't derivable here and is out of this seat's honest
 * scope — UNVERIFIED/flagged for the lead. [standardMinutes] is the block's own current planned
 * duration (the client-known baseline the suggestion is compared against, never invented), and
 * [suggestedMinutes] is the server's own recommendation — the copy only ever renders a delta this
 * seat computed from two numbers it already had, never a delta invented client-side.
 */
fun nextTimeBodyCopy(
    standardMinutes: Int,
    suggestedMinutes: Int,
): String {
    val delta = suggestedMinutes - standardMinutes
    return if (delta > 0) {
        "Last time ran ${delta}m long. Book ${suggestedMinutes}m this time?"
    } else {
        "Finished ${-delta}m early last time. Book ${suggestedMinutes}m?"
    }
}

/**
 * Wrap-up card's merged accessibility node (§B 4.1.2's own snippet, verbatim: "Session wrap-up for
 * ${block.title}. Scheduled for ${durationMinutes} minutes. How did the time go?") — read once by
 * TalkBack/VoiceOver before the chip row per §B's `Modifier.semantics(mergeDescendants = true)`.
 */
fun wrapUpAccessibilityDescription(block: TimeBlock): String =
    "Session wrap-up for ${block.title ?: "this block"}. " +
        "Scheduled for ${block.plannedMinutes()} minutes. How did the time go?"

/**
 * Next-Time card's merged accessibility node — [NextTimeCardUiState.isSharedLock] borrows §B 2.2's
 * "NEXT TIME TOGETHER" framing only for an actual together session (see [NextTimeCardUiState]'s
 * kdoc for why partner name itself isn't available to this seat).
 */
fun nextTimeAccessibilityDescription(card: NextTimeCardUiState): String {
    val overline = if (card.isSharedLock) "Next time together" else "Next time"
    return "$overline: ${card.title}. ${nextTimeBodyCopy(card.standardMinutes, card.suggestedMinutes)}"
}
