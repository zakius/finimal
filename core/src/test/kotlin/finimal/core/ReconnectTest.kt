package finimal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReconnectTest {
    @Test fun backsOffThenGivesUpAfterTheWindow() {
        val delays = (0..5).map { Reconnect.Retrying(5_000, it).delayMs(6_000) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), delays)
        assertEquals(10_000L, Reconnect.Retrying(5_000, 40).delayMs(5_000 + 59_999))
        assertNull(Reconnect.Retrying(5_000, 3).delayMs(5_000 + RECONNECT_WINDOW_MS))
    }

    @Test fun firstFailureStartsTheWindow() =
        assertEquals(Reconnect.Retrying(5_000, 0), Reconnect.Idle.failed(5_000))

    @Test fun laterFailuresKeepTheWindow() =
        assertEquals(Reconnect.Retrying(0, 2), Reconnect.Idle.failed(0).reloaded().reloaded().failed(30_000))

    @Test fun nextOutageAfterGivingUpStartsFresh() =
        assertEquals(Reconnect.Retrying(90_000, 0), Reconnect.GaveUp.failed(90_000))
}
