package kr.decacross.daemon.process

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashLoopTest {
    @Test
    fun tripsOnThirdCrashWithinWindow() {
        val loop = CrashLoop(Duration.ofMinutes(5), 3)
        val t0 = Instant.parse("2026-09-24T00:00:00Z")
        assertFalse(loop.record(t0))
        assertFalse(loop.record(t0.plusSeconds(60)))
        assertTrue(loop.record(t0.plusSeconds(120)), "5분 내 3회 → 차단")
    }

    @Test
    fun oldCrashesExpire() {
        val loop = CrashLoop(Duration.ofMinutes(5), 3)
        val t0 = Instant.parse("2026-09-24T00:00:00Z")
        loop.record(t0)
        loop.record(t0.plusSeconds(10))
        assertFalse(loop.record(t0.plusSeconds(6 * 60)), "첫 두 번은 윈도우 밖으로 나갔다")
    }
}
