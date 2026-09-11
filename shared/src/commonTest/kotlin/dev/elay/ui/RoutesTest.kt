package dev.elay.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class RoutesTest {
    @Test
    fun sixSurfacesWithUniqueRoutesAndLabels() {
        assertEquals(6, Surface.entries.size, "spec §2 defines six top-level surfaces")
        assertEquals(
            6,
            Surface.entries
                .map { it.route }
                .toSet()
                .size,
            "routes must be distinct",
        )
        assertEquals(
            6,
            Surface.entries
                .map { it.label }
                .toSet()
                .size,
            "labels must be distinct",
        )
    }

    @Test
    fun todayIsTheStartSurface() {
        assertEquals(Surface.Today, Surface.entries.first())
    }
}
