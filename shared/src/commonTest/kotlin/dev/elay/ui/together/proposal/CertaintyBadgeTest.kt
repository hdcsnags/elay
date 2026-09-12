package dev.elay.ui.together.proposal

import dev.elay.domain.availability.Certainty
import kotlin.test.Test
import kotlin.test.assertEquals

/** [certaintyLabel]/[certaintyAccessibilityDescription] — §B 1.3/§B 5.1's exact self-perspective
 * calm copy for every [Certainty] rung, exercised with no Compose (this seat's grant §6). */
class CertaintyBadgeTest {
    @Test
    fun everyCertaintyHasItsExactSelfPerspectiveLabel() {
        assertEquals("Free per calendar", certaintyLabel(Certainty.FreePerCalendar))
        assertEquals("Free on ELAY (no external calendar)", certaintyLabel(Certainty.FreePerElay))
        assertEquals("Free on ELAY · Calendar not synced recently", certaintyLabel(Certainty.Unknown))
        assertEquals("You have a scheduled block at this time", certaintyLabel(Certainty.Busy))
    }

    @Test
    fun everyCertaintyHasAFullSpokenAccessibilityDescription() {
        assertEquals(
            "Availability verified with external calendar: Free",
            certaintyAccessibilityDescription(Certainty.FreePerCalendar),
        )
        assertEquals(
            "Free on ELAY. External calendar not connected",
            certaintyAccessibilityDescription(Certainty.FreePerElay),
        )
        assertEquals(
            "Free on ELAY. External calendar was not synced recently",
            certaintyAccessibilityDescription(Certainty.Unknown),
        )
        assertEquals(
            "Conflict: Already busy during this time",
            certaintyAccessibilityDescription(Certainty.Busy),
        )
    }

    @Test
    fun noLabelEverMentionsColorOrUsesRedOnlyLanguage() {
        Certainty.entries.forEach { certainty ->
            val label = certaintyLabel(certainty)
            assertEquals(false, label.contains("red", ignoreCase = true))
            assertEquals(false, label.contains("error", ignoreCase = true))
        }
    }
}
