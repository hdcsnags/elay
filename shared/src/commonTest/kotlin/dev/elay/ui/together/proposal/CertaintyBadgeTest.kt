package dev.elay.ui.together.proposal

import dev.elay.domain.availability.Certainty
import kotlin.test.Test
import kotlin.test.assertEquals

/** [certaintyLabel]/[certaintyAccessibilityDescription] — §B 1.3/§B 5.1's exact self-perspective
 * calm copy for every [Certainty] rung, exercised with no Compose (this seat's grant §6). */
class CertaintyBadgeTest {
    @Test
    fun everyCertaintyHasItsExactSelfPerspectiveLabel() {
        assertEquals("Free · matches your busy times", certaintyLabel(Certainty.FreePerCalendar))
        assertEquals("Free on ELAY", certaintyLabel(Certainty.FreePerElay))
        assertEquals("Availability not checked", certaintyLabel(Certainty.Unknown))
        assertEquals("You have a scheduled block at this time", certaintyLabel(Certainty.Busy))
    }

    @Test
    fun everyCertaintyHasAFullSpokenAccessibilityDescription() {
        assertEquals(
            "Free: no conflicts with your schedule or your marked busy times",
            certaintyAccessibilityDescription(Certainty.FreePerCalendar),
        )
        assertEquals(
            "Free: no conflicts on your ELAY schedule",
            certaintyAccessibilityDescription(Certainty.FreePerElay),
        )
        assertEquals(
            "Availability could not be checked for this time",
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
