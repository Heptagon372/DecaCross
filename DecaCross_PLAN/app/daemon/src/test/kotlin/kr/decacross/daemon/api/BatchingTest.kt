package kr.decacross.daemon.api

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class BatchingTest {
    /** 불변식 14: 라인 1,000개가 순식간에 들어오면 프레임 수는 라인 수보다 훨씬 적어야 한다. */
    @Test
    fun burst_isCollapsedIntoFewFrames() = runBlocking {
        val frames = flow { repeat(1000) { emit("line $it") } }.chunkedTimeout(50.milliseconds, 200).toList()
        assertEquals(1000, frames.sumOf { it.size }, "라인은 하나도 잃지 않는다")
        assertTrue(frames.size <= 5, "1000 라인 → 최대 5 프레임(maxSize 200), 실제 ${frames.size}")
        assertEquals((0 until 1000).map { "line $it" }, frames.flatten(), "순서 보존")
    }

    @Test
    fun slowTrickle_stillFlushesEachLineWithinWindow() = runBlocking {
        val frames = flow {
            repeat(3) {
                emit("l$it")
                kotlinx.coroutines.delay(120)
            }
        }.chunkedTimeout(50.milliseconds, 200).toList()
        assertEquals(3, frames.size)
        assertTrue(frames.all { it.size == 1 })
    }

    @Test
    fun emptyFlow_emitsNothing() = runBlocking {
        assertEquals(emptyList(), flow<String> {}.chunkedTimeout(50.milliseconds, 10).toList())
    }
}
