package kr.decacross.ui.console

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleBufferTest {
    @Test
    fun `keeps exactly the last N lines`() {
        val buf = ConsoleBuffer(capacity = 1000, chunkSize = 64)
        repeat(37) { b -> buf.append(List(100) { "l${b * 100 + it}" }) } // 3700 줄
        val snap = buf.snapshot()
        assertEquals(1000, snap.size)
        assertEquals("l2700", snap.first())
        assertEquals("l3699", snap.last())
        // 무작위 인덱스도 순서대로
        assertEquals("l3200", snap[500])
    }

    @Test
    fun `snapshot is immutable after further appends and trims`() {
        val buf = ConsoleBuffer(capacity = 300, chunkSize = 16)
        buf.append(List(200) { "a$it" })
        val snap = buf.snapshot()
        val copy = snap.toList()
        buf.append(List(500) { "b$it" }) // 앞 청크가 통째로 버려지고 새 청크가 붙는다
        buf.clear()
        buf.append(List(10) { "c$it" })
        assertEquals(copy, snap.toList())
        assertEquals(200, snap.size)
    }

    @Test
    fun `batch append cost does not scale with buffer size`() {
        // 10만 줄이 찬 뒤 200줄 배치를 1,000번 넣어도 매번 전체 복사(10만) 가 없으면 빠르다.
        val buf = ConsoleBuffer()
        buf.append(List(ConsoleBuffer.DEFAULT_CAPACITY) { "x$it" })
        val t0 = System.nanoTime()
        repeat(1000) { b -> buf.append(List(200) { "y${b}_$it" }) }
        val snapshot = buf.snapshot()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(ConsoleBuffer.DEFAULT_CAPACITY, snapshot.size)
        assertEquals("y999_199", snapshot.last())
        assertTrue(ms < 2_000, "1000 batches took ${ms}ms — append must be O(batch), not O(buffer)")
    }

    @Test
    fun `generation changes on append and clear`() {
        val buf = ConsoleBuffer(capacity = 10, chunkSize = 4)
        val g0 = buf.snapshot().generation
        buf.append(listOf("a"))
        val g1 = buf.snapshot().generation
        buf.append(emptyList())
        val g2 = buf.snapshot().generation
        buf.clear()
        val g3 = buf.snapshot().generation
        assertTrue(g1 > g0)
        assertEquals(g1, g2) // 빈 배치는 변화 없음
        assertTrue(g3 > g2)
        assertEquals(0, buf.size)
    }

    @Test
    fun `level detection`() {
        assertEquals(LogLevel.WARN, LogLevel.of("[12:03:41] [Server thread/WARN]: Can't keep up!"))
        assertEquals(LogLevel.ERROR, LogLevel.of("[12:03:41] [Server thread/ERROR]: boom"))
        assertEquals(LogLevel.ERROR, LogLevel.of("\tat kr.x.Y.z(Y.kt:1)"))
        assertEquals(LogLevel.WARN, LogLevel.of("[12:03:41 WARN]: old format"))
        assertEquals(LogLevel.INFO, LogLevel.of("[12:03:41] [Server thread/INFO]: Done (8.2s)! For help, type \"help\""))
        assertEquals(LogLevel.INFO, LogLevel.of("<player> WARNING this is chat"))
    }
}
