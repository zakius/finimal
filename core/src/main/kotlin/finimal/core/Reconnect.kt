package finimal.core

/** How long playback keeps reconnecting after a network failure before it shows the error. */
const val RECONNECT_WINDOW_MS = 60_000L

/**
 * Delay before reconnect attempt [attempt], counting from 0: 1, 2, 4 and 8 s, then every 10 s. Returns
 * null once [RECONNECT_WINDOW_MS] has passed since [firstFailureMs], when it is time to tell the user.
 */
fun reconnectDelayMs(attempt: Int, firstFailureMs: Long, nowMs: Long): Long? =
    if (nowMs - firstFailureMs >= RECONNECT_WINDOW_MS) null else longArrayOf(1_000, 2_000, 4_000, 8_000, 10_000)[minOf(attempt, 4)]
