package kr.decacross.collector.http

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kr.decacross.collector.config.CollectorSettings
import kr.decacross.collector.config.HostPolicy
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 호스트별 요청 간격·동시성 제한 (politeness).
 *
 * # 불변식
 * - [withPermit] 은 **먼저 세마포어를 잡고 나서** 간격을 기다린다. 그래서 대기하던 호출들이 한순간에 함께 깨어나지 않는다.
 * - 간격 대기 중에는 호스트 뮤텍스를 쥐지 않는다. 대기 중에 [penalize] 가 들어오면 즉시 반영되고, 깨어난 호출이 다시 확인한다.
 * - [penalize] 는 이미 걸린 정지를 절대 줄이지 않는다. 정지 길이는 `maxRetryDelayMs` 로 자른다.
 * - 허가는 `proceed` 가 돌아올 때(= 응답 **헤더** 도착) 반환된다. 따라서 `maxConcurrent` 는 동시 요청 수를 묶을 뿐
 *   동시 본문 전송 수를 묶지 않는다. 본문 동시성은 소스가 묶는다 (다운로드는 소스 안에서 순차).
 * - 재시도 플러그인 안쪽에 설치되므로 재시도마다 다시 간격을 지킨다.
 *
 * @param timeSource 간격 계산용 시계 (테스트 이음새). **`delay()` 가 기다린 만큼 적어도 그만큼 흘러야 한다** (운영은 `TimeSource.Monotonic`).
 *   간격 대기 루프는 깨어날 때마다 이 시계로 남은 시간을 다시 재므로, `delay` 와 함께 흐르지 않는 시계
 *   (예: `runTest` 안의 수동 `TestTimeSource`)를 넣고 간격이 남은 호스트에 [withPermit] 을 부르면 끝나지 않는다.
 *   가상 시간 테스트에서는 스케줄러 시계(`testScheduler.timeSource`)를 넘겨라.
 */
class HostPacer(
    private val settings: CollectorSettings,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private class Gate(val policy: HostPolicy, now: ComparableTimeMark) {
        val permits = Semaphore(policy.maxConcurrent)
        val mutex = Mutex()
        var nextAllowed: ComparableTimeMark = now
    }

    private val gates = ConcurrentHashMap<String, Gate>()

    private fun policyOf(host: String): HostPolicy = settings.http.hostPolicies[host] ?: settings.http.defaultHostPolicy

    private fun gate(host: String): Gate = gates.computeIfAbsent(host.lowercase()) { Gate(policyOf(it), timeSource.markNow()) }

    /** 호스트 허가를 얻고 간격을 지킨 뒤 [block] 을 실행한다. */
    suspend fun <T> withPermit(host: String, block: suspend () -> T): T {
        val g = gate(host)
        return g.permits.withPermit {
            awaitSlot(g)
            block()
        }
    }

    /**
     * 보낼 차례가 될 때까지 기다리고 다음 차례를 예약한다.
     * 뮤텍스를 쥔 채 자지 않는다: 자는 동안 [penalize] (다른 요청의 429 응답) 가 막히지 않고,
     * 깨어나면 늘어난 정지를 다시 확인한다.
     */
    private suspend fun awaitSlot(g: Gate) {
        while (true) {
            val wait = g.mutex.withLock {
                val remaining = g.nextAllowed - timeSource.markNow()
                if (!remaining.isPositive()) g.nextAllowed = timeSource.markNow() + g.policy.minIntervalMs.milliseconds
                remaining
            }
            if (!wait.isPositive()) return
            delay(wait)
        }
    }

    /**
     * 응답을 보고 호스트 전체를 멈춘다: 429 이거나 `X-Ratelimit-Remaining: 0` 이면
     * `Retry-After` → `X-Ratelimit-Reset` → 60 초 순서로 정지 길이를 정한다.
     */
    suspend fun observe(host: String, status: Int, header: (String) -> String?) {
        val exhausted = header(RATE_LIMIT_REMAINING)?.trim() == "0"
        if (status != 429 && !exhausted) return
        val pauseMs = header(RETRY_AFTER)?.let { parseRetryAfterMillis(it, Clock.System.now()) }
            ?: header(RATE_LIMIT_RESET)?.let(::parseRateLimitResetMillis)
            ?: DEFAULT_PAUSE.inWholeMilliseconds
        penalize(host, pauseMs.milliseconds)
    }

    /** `nextAllowed = max(nextAllowed, now + min(pause, maxRetryDelayMs))`. */
    suspend fun penalize(host: String, pause: Duration) {
        val g = gate(host)
        val capped = minOf(pause, settings.http.maxRetryDelayMs.milliseconds).coerceAtLeast(Duration.ZERO)
        g.mutex.withLock {
            val until = timeSource.markNow() + capped
            if (until > g.nextAllowed) g.nextAllowed = until
        }
    }

    /** 테스트·로그용: 이 호스트가 다음 요청을 보낼 수 있을 때까지 남은 시간 (0 이상). */
    internal fun remainingPause(host: String): Duration {
        val g = gates[host.lowercase()] ?: return Duration.ZERO
        return (g.nextAllowed - timeSource.markNow()).coerceAtLeast(Duration.ZERO)
    }

    internal companion object {
        const val RETRY_AFTER: String = "Retry-After"
        const val RATE_LIMIT_REMAINING: String = "X-Ratelimit-Remaining"
        const val RATE_LIMIT_RESET: String = "X-Ratelimit-Reset"
        val DEFAULT_PAUSE: Duration = 60.seconds
    }
}
