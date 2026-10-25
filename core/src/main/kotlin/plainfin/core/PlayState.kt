package plainfin.core

import kotlinx.serialization.Serializable

const val TICKS_PER_SECOND = 10_000_000L
const val TICKS_PER_MS = TICKS_PER_SECOND / 1000

/** What the server stores per user and item. */
@Serializable
data class PlayState(
    val positionTicks: Long,
    val played: Boolean,
    /** Epoch millis; null when the item was never played. */
    val lastPlayedMs: Long?,
)

/**
 * Mirror of the server's `UserDataManager.UpdatePlayState` with its default limits, for plays the
 * server never saw (offline). Online stops go through `/Sessions/Playing/Stopped`, which runs the
 * real thing.
 */
object ResumeRules {
    const val MIN_RESUME_PCT = 5
    const val MAX_RESUME_PCT = 90
    const val MIN_RESUME_DURATION_SECONDS = 300

    fun onStop(previous: PlayState?, positionTicks: Long, runtimeTicks: Long?, nowMs: Long): PlayState {
        var played = previous?.played ?: false
        var position = positionTicks
        if (runtimeTicks == null || runtimeTicks <= 0) {
            played = true
            position = 0
        } else if (position > 0) {
            val pct = position * 100.0 / runtimeTicks
            when {
                pct < MIN_RESUME_PCT -> position = 0
                pct > MAX_RESUME_PCT || position >= runtimeTicks - TICKS_PER_SECOND -> {
                    position = 0
                    played = true
                }
                runtimeTicks / TICKS_PER_SECOND < MIN_RESUME_DURATION_SECONDS -> {
                    position = 0
                    played = true
                }
            }
        }
        return PlayState(position, played, nowMs)
    }
}

/** Newer LastPlayedDate wins. A server state without a date never beats a local play. */
fun shouldPush(local: PlayState, server: PlayState?): Boolean {
    val serverMs = server?.lastPlayedMs ?: return true
    val localMs = local.lastPlayedMs ?: return false
    return localMs >= serverMs
}

/**
 * The position to report: [positionTicks], unless it would move a resume point back under the
 * resume minimum within [GUARD_MS] of starting, with no seek. No viewer does that; a player that
 * reports before it has loaded, or a restored "start over", does, and the series restarts.
 */
object ResumeGuard {
    const val GUARD_MS = 30_000L

    fun position(startTicks: Long, positionTicks: Long, runtimeTicks: Long?, sinceStartMs: Long, seeked: Boolean): Long {
        if (seeked || runtimeTicks == null || runtimeTicks <= 0 || sinceStartMs > GUARD_MS) return positionTicks
        val min = runtimeTicks * ResumeRules.MIN_RESUME_PCT / 100
        return if (startTicks > min && positionTicks < min) startTicks else positionTicks
    }
}
