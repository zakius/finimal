package com.zakius.finimal.desktop

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowTest {
    private val laptop = Rectangle(0, 0, 1366, 728) // 768 px less a 40 px taskbar
    private val right = Rectangle(1366, 0, 1920, 1040)

    @Test fun theDefaultSizeFitsASmallScreen() =
        assertEquals(Rectangle(43, 0, 1280, 728), WindowControl.fit(Rectangle(0, 0, 1280, 800), false, laptop, listOf(laptop)))

    @Test fun aSavedPlaceStaysOnItsScreen() =
        assertEquals(Rectangle(1500, 100, 1280, 800), WindowControl.fit(Rectangle(1500, 100, 1280, 800), true, laptop, listOf(laptop, right)))

    @Test fun aSavedPlaceFromABiggerScreenIsPulledIn() =
        assertEquals(Rectangle(0, 0, 1366, 728), WindowControl.fit(Rectangle(200, 300, 1800, 1000), true, laptop, listOf(laptop)))

    @Test fun aPlaceOnAnUnpluggedScreenIsCentredOnTheMainOne() =
        assertEquals(Rectangle(43, 0, 1280, 728), WindowControl.fit(Rectangle(3000, 100, 1280, 800), true, laptop, listOf(laptop)))
}
