package kr.decacross.collector.http

import kr.decacross.collector.config.CollectorSettings
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * 호스트별 회로 차단기.
 *
 * # 불변식
 * - 실패는 **논리 호출 1회당 한 번** 센다 (`get`/`getRange`/`head`/`download`/`digest`, 재시도·다운로드 시도가 모두 끝난 뒤).
 *   실패 = 네트워크·타임아웃 실패, 또는 최종 5xx/429 상태.
 * - 연속 실패가 `circuitBreakerFailures` 에 닿으면 `circuitOpenMs` 동안 OPEN: 그 호스트 호출은 즉시 [Admission.Rejected].
 * - OPEN 시간이 지나면 정확히 **하나**의 호출만 반개방(half-open) 탐침으로 들여보낸다. 동시에 온 호출은 계속 즉시 실패.
 *   탐침 성공 → CLOSED(0), 탐침 실패 → 다시 OPEN.
 * - 2xx/206/304 는 카운터를 0 으로 되돌린다.
 * - 상태 전이는 호스트마다 원자적이다 (호스트 상태 객체 단위 `synchronized`).
 */
class CircuitBreaker(
    private val settings: CollectorSettings,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    /** 호출 허용 여부. */
    enum class Admission {
        /** 평상시 (CLOSED) */
        ALLOWED,

        /** 반개방 탐침 (이 호출 하나만) */
        PROBE,

        /** OPEN — 요청을 보내지 않고 즉시 실패 */
        REJECTED,
    }

    /** 논리 호출 결과 분류. */
    enum class Outcome {
        /** 2xx / 206 / 304 */
        SUCCESS,

        /** 네트워크·타임아웃 실패, 최종 5xx/429 */
        FAILURE,

        /** 서버는 응답했지만 성공도 장애도 아님 (404, 416, 해시 불일치, 너무 큼 …) */
        NEUTRAL,
    }

    private sealed interface State {
        data class Closed(val failures: Int) : State

        data class Open(val until: ComparableTimeMark) : State

        data object HalfOpen : State
    }

    private class HostState {
        var state: State = State.Closed(0)
    }

    private val hosts = ConcurrentHashMap<String, HostState>()

    private fun hostState(host: String): HostState = hosts.computeIfAbsent(host.lowercase()) { HostState() }

    /** 호출 전에 부른다. [Admission.REJECTED] 면 요청을 보내지 마라. */
    fun acquire(host: String): Admission {
        val h = hostState(host)
        synchronized(h) {
            return when (val s = h.state) {
                is State.Closed -> Admission.ALLOWED

                is State.Open -> if (timeSource.markNow() >= s.until) {
                    h.state = State.HalfOpen
                    Admission.PROBE
                } else {
                    Admission.REJECTED
                }

                State.HalfOpen -> Admission.REJECTED
            }
        }
    }

    /**
     * 호출이 끝난 뒤 부른다. [outcome] 이 null 이면 결과 없이 끝난 호출(취소)이다:
     * 탐침이었다면 다음 호출이 다시 탐침이 되도록 OPEN(지금 만료)으로 되돌린다.
     */
    fun record(host: String, admission: Admission, outcome: Outcome?) {
        if (admission == Admission.REJECTED) return
        val h = hostState(host)
        synchronized(h) {
            val probe = admission == Admission.PROBE
            val s = h.state
            when (outcome) {
                null -> if (probe && s == State.HalfOpen) h.state = State.Open(timeSource.markNow())

                Outcome.SUCCESS -> h.state = State.Closed(0)

                Outcome.NEUTRAL -> if (probe && s == State.HalfOpen) h.state = State.Closed(0)

                Outcome.FAILURE -> when {
                    probe && s == State.HalfOpen -> h.state = openNow()

                    s is State.Closed -> {
                        val failures = s.failures + 1
                        h.state = if (failures >= settings.http.circuitBreakerFailures) openNow() else State.Closed(failures)
                    }

                    // 이미 OPEN/반개방인데 차단 전에 출발한 호출이 늦게 실패한 경우: 상태를 늘리거나 바꾸지 않는다
                    else -> Unit
                }
            }
        }
    }

    private fun openNow(): State = State.Open(timeSource.markNow() + settings.http.circuitOpenMs.milliseconds)

    /** 테스트·보고용: 현재 OPEN(반개방 포함)인가. */
    internal fun isOpen(host: String): Boolean {
        val h = hosts[host.lowercase()] ?: return false
        synchronized(h) { return h.state !is State.Closed }
    }

    internal companion object {
        /** [HttpResult] → 회로 차단기 결과. */
        fun classify(result: HttpResult<*>): Outcome = when (result) {
            is HttpResult.Ok -> Outcome.SUCCESS

            is HttpResult.NotModified -> Outcome.SUCCESS

            is HttpResult.Status -> if (result.meta.status == 429 || result.meta.status in 500..599) Outcome.FAILURE else Outcome.NEUTRAL

            is HttpResult.Failure -> when (result.kind) {
                FailureKind.NETWORK, FailureKind.TIMEOUT -> Outcome.FAILURE
                else -> Outcome.NEUTRAL
            }
        }
    }
}
