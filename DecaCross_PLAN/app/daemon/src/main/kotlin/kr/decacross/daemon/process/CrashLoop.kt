package kr.decacross.daemon.process

import java.time.Duration
import java.time.Instant

/**
 * 크래시 루프 차단 (설계서 §5.3): [window] 안에 [maxCrashes] 회 비정상 종료하면 자동 재시작을 멈추고 진단 모드로.
 */
class CrashLoop(
    private val window: Duration = Duration.ofMinutes(5),
    private val maxCrashes: Int = 3,
) {
    private val crashes = ArrayDeque<Instant>()

    /** 비정상 종료를 기록한다. 루프로 판정되면 true (자동 재시작 중단). */
    @Synchronized
    fun record(at: Instant = Instant.now()): Boolean {
        crashes.addLast(at)
        val cutoff = at.minus(window)
        while (crashes.isNotEmpty() && crashes.first().isBefore(cutoff)) crashes.removeFirst()
        return crashes.size >= maxCrashes
    }

    @Synchronized
    fun reset() = crashes.clear()

    @Synchronized
    fun recentCount(now: Instant = Instant.now()): Int = crashes.count { !it.isBefore(now.minus(window)) }
}
