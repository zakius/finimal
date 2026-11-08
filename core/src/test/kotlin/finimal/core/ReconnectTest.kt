package finimal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReconnectTest {
    @Test fun firstFailureStartsTheWindow() {
        val retrying = Reconnect.Idle.failed(5_000)
        assertEquals(Reconnect.Retrying(5_000, 0), retrying)
        assertEquals(1_000L, retrying.delayMs(5_000))
    }

    @Test fun laterFailuresKeepTheWindowAndBackOff() {
        var state = Reconnect.Idle.failed(0).reloaded().reloaded()
        state = state.failed(30_000)
        assertEquals(Reconnect.Retrying(0, 2), state)
        assertEquals(4_000L, state.delayMs(30_000))
        assertNull(state.delayMs(RECONNECT_WINDOW_MS))
    }

    @Test fun nextOutageStartsFresh() {
        // After recovering or giving up, the next failure starts at the first step again with its own window.
        assertEquals(Reconnect.Retrying(90_000, 0), Reconnect.GaveUp.failed(90_000))
        assertEquals(Reconnect.Retrying(90_000, 0), Reconnect.Idle.failed(90_000))
    }
}
