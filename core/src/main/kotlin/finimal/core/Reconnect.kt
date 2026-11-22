package finimal.core

const val RECONNECT_WINDOW_MS = 60_000L

/**
 * Delay before reconnect attempt [attempt], counting from 0: 1, 2, 4 and 8 s, then every 10 s. Returns
 * null once [RECONNECT_WINDOW_MS] has passed since [firstFailureMs], when it is time to tell the user.
 */
fun reconnectDelayMs(attempt: Int, firstFailureMs: Long, nowMs: Long): Long? {
    if (nowMs - firstFailureMs >= RECONNECT_WINDOW_MS) return null
    val delays = longArrayOf(1_000, 2_000, 4_000, 8_000, 10_000)
    return delays[minOf(attempt, 4)]
}

/**
 * Where a player stands with a dropped connection.
 *
 * Idle → (network failure) → Retrying → (loaded with the server answering) → Idle
 *                                     → (window ran out) → GaveUp → (Retry, then loaded) → Idle
 */
sealed interface Reconnect {
    /** Not reconnecting: playing, or stopped for a reason other than the network. */
    data object Idle : Reconnect

    /** Reloading at the same position since [sinceMs], after [attempt] reloads. The player shows "Reconnecting…". */
    data class Retrying(val sinceMs: Long, val attempt: Int) : Reconnect {
        /** How long to wait before the next reload, or null once the window has run out. */
        fun delayMs(nowMs: Long): Long? = reconnectDelayMs(attempt, sinceMs, nowMs)

        fun reloaded() = Retrying(sinceMs, attempt + 1)
    }

    /** The window ran out. The error shows until the user presses Retry. */
    data object GaveUp : Reconnect

    /** A network failure at [nowMs]. Starts retrying, or keeps the retry under way, so its window counts from the first failure. */
    fun failed(nowMs: Long): Retrying = this as? Retrying ?: Retrying(nowMs, 0)
}
