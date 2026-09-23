package kr.decacross.ui.console

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandHistoryTest {
    @Test
    fun `up and down walk the history like a shell`() {
        val h = CommandHistory()
        h.push("list")
        h.push("say hi")
        h.push("tps")
        assertEquals("tps", h.up())
        assertEquals("say hi", h.up())
        assertEquals("list", h.up())
        assertEquals("list", h.up()) // 맨 처음에서 더 올라가지 않는다
        assertEquals("say hi", h.down())
        assertEquals("tps", h.down())
        assertEquals("", h.down()) // 입력 중이던 자리
        assertEquals("", h.down())
    }

    @Test
    fun `blank and duplicate consecutive lines are not recorded`() {
        val h = CommandHistory()
        h.push("   ")
        assertNull(h.up())
        h.push("list")
        h.push("list")
        h.push(" list ")
        assertEquals(1, h.size)
        h.push("stop")
        h.push("list")
        assertEquals(listOf("list", "stop", "list"), h.toList())
    }

    @Test
    fun `push resets the cursor to the newest entry`() {
        val h = CommandHistory()
        h.push("a")
        h.push("b")
        h.up()
        h.up()
        h.push("c")
        assertEquals("c", h.up())
    }

    @Test
    fun `reset returns the cursor to the end`() {
        val h = CommandHistory()
        h.push("a")
        h.push("b")
        h.up()
        h.up()
        h.reset()
        assertEquals("b", h.up())
    }

    @Test
    fun `history is bounded`() {
        val h = CommandHistory(max = 3)
        (1..10).forEach { h.push("c$it") }
        assertEquals(listOf("c8", "c9", "c10"), h.toList())
    }
}
