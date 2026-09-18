package kr.decacross.collector.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kr.decacross.collector.config.CollectorSettings
import kr.decacross.collector.config.HostPolicy
import kr.decacross.collector.config.HttpSettings
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

class HostPacerTest {
    private fun settings(policies: Map<String, HostPolicy>, maxRetryDelayMs: Long = 300_000): CollectorSettings =
        CollectorSettings(
            tempDir = Path.of("unused"),
            http = HttpSettings(hostPolicies = policies, defaultHostPolicy = HostPolicy(0, 8), maxRetryDelayMs = maxRetryDelayMs),
        )

    private data class Span(val host: String, val start: ComparableTimeMark, val end: ComparableTimeMark)

    @Test
    fun hostPacer_minInterval_perHost_parallelAcrossHosts(): Unit = runBlocking {
        val pacer = HostPacer(settings(mapOf("a.test" to HostPolicy(100, 1), "b.test" to HostPolicy(100, 1))))
        val spans = CopyOnWriteArrayList<Span>()
        (1..3).flatMap { listOf("a.test", "b.test") }.map { host ->
            async(Dispatchers.Default) {
                pacer.withPermit(host) {
                    val start = TimeSource.Monotonic.markNow()
                    delay(20)
                    spans += Span(host, start, TimeSource.Monotonic.markNow())
                }
            }
        }.awaitAll()
        for (host in listOf("a.test", "b.test")) {
            val starts = spans.filter { it.host == host }.map { it.start }.sorted()
            assertEquals(3, starts.size)
            for (i in 1 until starts.size) {
                val gap = starts[i] - starts[i - 1]
                assertTrue(gap >= 90.milliseconds, "$host 연속 요청 간격이 너무 짧다: $gap")
            }
        }
        // 호스트끼리는 서로 막지 않는다: b 의 첫 요청이 a 의 마지막 요청보다 먼저 시작
        val aLast = spans.filter { it.host == "a.test" }.maxOf { it.start }
        val bFirst = spans.filter { it.host == "b.test" }.minOf { it.start }
        assertTrue(bFirst < aLast, "두 호스트의 요청이 겹쳐야 한다")
    }

    @Test
    fun virtualTime_schedulerTimeSource_pacesAndTerminates(): Unit = runTest {
        // V-2: 가상 시간에서는 delay 와 함께 흐르는 스케줄러 시계를 넘기면 간격 대기가 끝나고, 간격도 정확히 지켜진다
        val clock = testScheduler.timeSource
        val pacer = HostPacer(settings(mapOf("v.test" to HostPolicy(100, 1))), clock)
        val origin = clock.markNow()
        val starts = ArrayList<Duration>()
        repeat(3) { pacer.withPermit("v.test") { starts += origin.elapsedNow() } }
        assertEquals(listOf(Duration.ZERO, 100.milliseconds, 200.milliseconds), starts)
        pacer.penalize("v.test", 1.seconds)
        val before = clock.markNow()
        pacer.withPermit("v.test") { }
        assertEquals(1.seconds, before.elapsedNow(), "정지가 끝날 때까지 기다린 뒤 보낸다")
    }

    @Test
    fun penalize_neverShortens(): Unit = runTest {
        val time = TestTimeSource()
        val pacer = HostPacer(settings(emptyMap(), maxRetryDelayMs = 5_000), time)
        pacer.penalize("api.test", 2.seconds)
        assertEquals(2.seconds, pacer.remainingPause("api.test"))
        pacer.penalize("api.test", 100.milliseconds)
        assertEquals(2.seconds, pacer.remainingPause("api.test"), "짧은 정지가 긴 정지를 줄이면 안 된다")
        time += 500.milliseconds
        assertEquals(1500.milliseconds, pacer.remainingPause("api.test"))
        pacer.penalize("api.test", 1.seconds + 600.milliseconds)
        assertEquals(1600.milliseconds, pacer.remainingPause("api.test"), "더 긴 정지는 늘린다")
        pacer.penalize("api.test", 1_000.seconds)
        assertEquals(5.seconds, pacer.remainingPause("api.test"), "maxRetryDelayMs 로 자른다")
        assertEquals(Duration.ZERO, pacer.remainingPause("other.test"))

        // observe: 429 + Retry-After / Remaining: 0 + Reset / 기본 60 초(상한으로 잘림)
        val p2 = HostPacer(settings(emptyMap(), maxRetryDelayMs = 120_000), time)
        p2.observe("r.test", 429) { if (it == "Retry-After") "7" else null }
        assertEquals(7.seconds, p2.remainingPause("r.test"))
        p2.observe("m.test", 200) { mapOf("X-Ratelimit-Remaining" to "0", "X-Ratelimit-Reset" to "12")[it] }
        assertEquals(12.seconds, p2.remainingPause("m.test"))
        p2.observe("d.test", 429) { null }
        assertEquals(60.seconds, p2.remainingPause("d.test"))
        p2.observe("n.test", 200) { mapOf("X-Ratelimit-Remaining" to "5", "X-Ratelimit-Reset" to "12")[it] }
        assertEquals(Duration.ZERO, p2.remainingPause("n.test"))
    }

    @Test
    fun penalize_whileWaiterSleeps_returnsImmediately_andExtendsWait(): Unit = runBlocking {
        // 회귀 (C1-R1): 간격을 기다리는 호출이 호스트 뮤텍스를 쥔 채 자면, 진행 중인 다른 응답의 429 반영(penalize)이
        // 그만큼 막히고, 깨어난 호출은 그 사이 늘어난 정지를 무시하고 보낸다.
        val pacer = HostPacer(settings(mapOf("multi.test" to HostPolicy(0, 4))))
        val origin = TimeSource.Monotonic.markNow()
        pacer.penalize("multi.test", 300.milliseconds)
        val sent = async(Dispatchers.Default) {
            pacer.withPermit("multi.test") { origin.elapsedNow() }
        }
        delay(50)
        val penalizeStart = TimeSource.Monotonic.markNow()
        pacer.penalize("multi.test", 600.milliseconds)
        val penalizeTook = penalizeStart.elapsedNow()
        assertTrue(penalizeTook < 150.milliseconds, "penalize 가 자는 대기자에게 막혔다: $penalizeTook")
        val sentAt = sent.await()
        assertTrue(sentAt >= 600.milliseconds, "깨어난 호출이 늘어난 정지(≈650 ms)를 무시하고 보냈다: $sentAt")
    }

    @Test
    fun semaphoreBeforePacing(): Unit = runBlocking {
        // maxConcurrent=1, 간격 100 ms. 첫 호출이 300 ms 동안 허가를 쥔다.
        // 세마포어를 먼저 잡으면 세 번째 호출은 두 번째 호출 시작 후 간격만큼 더 기다린다.
        // (간격을 먼저 기다리면 둘 다 대기 중에 간격을 소진해 허가가 풀리는 순간 거의 동시에 시작한다.)
        val pacer = HostPacer(settings(mapOf("one.test" to HostPolicy(100, 1))))
        val starts = CopyOnWriteArrayList<Pair<Int, ComparableTimeMark>>()
        val first = async(Dispatchers.Default) {
            pacer.withPermit("one.test") {
                starts += 1 to TimeSource.Monotonic.markNow()
                delay(300)
            }
        }
        delay(30)
        val others = (2..3).map { i ->
            async(Dispatchers.Default) {
                delay((i - 2) * 10L)
                pacer.withPermit("one.test") { starts += i to TimeSource.Monotonic.markNow() }
            }
        }
        (others + first).awaitAll()
        val ordered = starts.sortedBy { it.second }.map { it.second }
        assertEquals(3, ordered.size)
        assertTrue(ordered[1] - ordered[0] >= 280.milliseconds, "두 번째는 첫 허가가 풀린 뒤: ${ordered[1] - ordered[0]}")
        assertTrue(ordered[2] - ordered[1] >= 90.milliseconds, "세 번째도 간격을 지킨다: ${ordered[2] - ordered[1]}")
    }
}
