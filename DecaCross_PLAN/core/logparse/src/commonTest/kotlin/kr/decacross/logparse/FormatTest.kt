package kr.decacross.logparse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormatTest {
    @Test
    fun `detectFormat 은 LEGACY 와 MODERN 을 구분한다`() {
        assertEquals(LogFormat.LEGACY, detectFormat("[12:03:41 INFO]: Starting minecraft server version 1.7.10"))
        assertEquals(LogFormat.MODERN, detectFormat("[12:03:41] [Server thread/INFO]: Starting minecraft server version 1.21.4"))
        assertNull(detectFormat("Loading libraries, please wait..."))
        assertNull(detectFormat("\tat java.base/java.lang.Thread.run(Thread.java:1583)"))
        assertNull(detectFormat(""))
    }

    @Test
    fun `MODERN 파싱 - 시각 스레드 레벨 메시지`() {
        val line = parseLine("[12:03:41] [Server thread/INFO]: Done (8.214s)! For help, type \"help\"")
        assertEquals("12:03:41", line.time)
        assertEquals("Server thread", line.thread)
        assertEquals("INFO", line.level)
        assertNull(line.pluginTag)
        assertEquals("Done (8.214s)! For help, type \"help\"", line.message)
    }

    @Test
    fun `MODERN 파싱 - 공백과 특수문자가 든 스레드 이름`() {
        val scheduler = parseLine("[12:03:41] [Craft Scheduler Thread - 3/WARN]: [LuckPerms] slow query")
        assertEquals("Craft Scheduler Thread - 3", scheduler.thread)
        assertEquals("WARN", scheduler.level)
        assertEquals("LuckPerms", scheduler.pluginTag)
        assertEquals("slow query", scheduler.message)

        val netty = parseLine("[12:03:41] [Netty Epoll Server IO #1/ERROR]: boom")
        assertEquals("Netty Epoll Server IO #1", netty.thread)
        assertEquals("ERROR", netty.level)

        val watchdog = parseLine("[15:03:15] [Paper Watchdog Thread/FATAL]: The server has stopped responding!")
        assertEquals("Paper Watchdog Thread", watchdog.thread)
        assertEquals("FATAL", watchdog.level)
    }

    @Test
    fun `Paper 플러그인 태그는 분리되고 Not Secure 채팅은 태그가 아니다`() {
        val tagged = parseLine("[12:00:04] [Server thread/INFO]: [Vault] Enabling Vault v1.7.3-b131")
        assertEquals("Vault", tagged.pluginTag)
        assertEquals("Enabling Vault v1.7.3-b131", tagged.message)

        val chat = parseLine("[12:00:04] [Async Chat Thread - #0/INFO]: [Not Secure] <Steve> hi")
        assertNull(chat.pluginTag)
        assertEquals("[Not Secure] <Steve> hi", chat.message)
    }

    @Test
    fun `LEGACY 파싱 - 스레드 없음`() {
        val line = parseLine("[12:03:41 WARN]: Can't keep up! Running 2345ms behind")
        assertEquals("12:03:41", line.time)
        assertNull(line.thread)
        assertEquals("WARN", line.level)
        assertEquals("Can't keep up! Running 2345ms behind", line.message)
    }

    @Test
    fun `색코드와 ANSI 는 message 에서 제거되고 raw 에는 남는다`() {
        val raw = "[12:05:40] [Server thread/INFO]: <Steve> §c빨간 [31m글씨[m"
        val line = parseLine(raw)
        assertEquals("<Steve> 빨간 글씨", line.message)
        assertEquals(raw, line.raw)
        assertEquals("plain", stripColorCodes("§aplain§r"))
    }

    @Test
    fun `스택트레이스는 하나의 LogLine 으로 병합된다`() {
        val text =
            """
            [12:10:04] [Server thread/ERROR]: Could not load 'plugins/X.jar' in folder 'plugins'
            org.bukkit.plugin.InvalidPluginException: java.lang.IllegalArgumentException: Unsupported class file major version 65
            ${'\t'}at org.bukkit.plugin.java.JavaPluginLoader.loadPlugin(JavaPluginLoader.java:150)
            ${'\t'}at java.base/java.lang.Thread.run(Thread.java:1583)
            Caused by: java.lang.IllegalArgumentException: Unsupported class file major version 65
            ${'\t'}at org.objectweb.asm.ClassReader.<init>(ClassReader.java:199)
            ${'\t'}... 10 more
            Suppressed: java.io.IOException: closed
            [12:10:04] [Server thread/INFO]: [LuckPerms] Loading server plugin LuckPerms v5.4.141
            """.trimIndent()
        val lines = parseLog(text)
        assertEquals(2, lines.size)
        assertEquals(7, lines[0].continuation.size)
        assertTrue(lines[0].continuation[3].startsWith("Caused by:"))
        assertTrue(lines[0].continuation[6].startsWith("Suppressed:"))
        assertEquals("Loading server plugin LuckPerms v5.4.141", lines[1].message)
        assertTrue(lines[1].continuation.isEmpty())
    }

    @Test
    fun `feed 는 다음 엔트리가 시작될 때 직전 엔트리를 내놓고 flush 가 마지막을 내놓는다`() {
        val parser = LogParser()
        assertEquals(emptyList(), parser.feed("[12:00:00] [Server thread/INFO]: one"))
        assertEquals(emptyList(), parser.feed("\tat a.b.C(C.java:1)"))
        val first = parser.feed("[12:00:01] [Server thread/INFO]: two")
        assertEquals(1, first.size)
        assertEquals("one", first[0].message)
        assertEquals(listOf("\tat a.b.C(C.java:1)"), first[0].continuation)
        val last = parser.flush()
        assertEquals("two", last.single().message)
        assertEquals(emptyList(), parser.flush())
    }

    @Test
    fun `타임스탬프 없는 줄끼리는 각각 독립 엔트리다 (JVM stderr)`() {
        val lines =
            parseLog(
                """
                Error occurred during initialization of VM
                Could not reserve enough space for object heap
                [12:00:00] [Server thread/INFO]: after
                trailing without timestamp
                """.trimIndent(),
            )
        assertEquals(3, lines.size)
        assertEquals("Error occurred during initialization of VM", lines[0].message)
        assertEquals("Could not reserve enough space for object heap", lines[1].message)
        assertEquals(listOf("trailing without timestamp"), lines[2].continuation)
    }

    @Test
    fun `빈 줄과 CRLF 는 무시하거나 정리한다`() {
        val lines = parseLog("[12:00:00] [Server thread/INFO]: a\r\n\r\n[12:00:01] [Server thread/INFO]: b\r\n")
        assertEquals(listOf("a", "b"), lines.map { it.message })
        assertTrue(lines.all { it.continuation.isEmpty() })
    }
}
