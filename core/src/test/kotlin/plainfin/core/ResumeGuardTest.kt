package plainfin.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ResumeGuardTest {
    private val runtime = 40 * 60 * TICKS_PER_SECOND
    private val resumed = 20 * 60 * TICKS_PER_SECOND

    @Test fun reportAtZeroRightAfterResumeKeepsResumePoint() =
        assertEquals(resumed, ResumeGuard.position(resumed, 0, runtime, 5_000, seeked = false))

    @Test fun seekToStartPassesThrough() =
        assertEquals(0, ResumeGuard.position(resumed, 0, runtime, 5_000, seeked = true))

    @Test fun afterGuardWindowPassesThrough() =
        assertEquals(0, ResumeGuard.position(resumed, 0, runtime, 31_000, seeked = false))

    @Test fun playFromStartIsNotGuarded() =
        assertEquals(TICKS_PER_SECOND, ResumeGuard.position(0, TICKS_PER_SECOND, runtime, 5_000, seeked = false))

    @Test fun normalProgressPassesThrough() =
        assertEquals(resumed + TICKS_PER_SECOND, ResumeGuard.position(resumed, resumed + TICKS_PER_SECOND, runtime, 5_000, seeked = false))
}
