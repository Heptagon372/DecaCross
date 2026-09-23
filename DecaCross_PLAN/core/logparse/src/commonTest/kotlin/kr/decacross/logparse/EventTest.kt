package kr.decacross.logparse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EventTest {
    private fun event(line: String, ctx: EventContext = EventContext.EMPTY): LogEvent = parseLine(line).toEvent(ctx)

    private fun modern(msg: String, level: String = "INFO", thread: String = "Server thread"): String = "[12:00:00] [$thread/$level]: $msg"

    @Test
    fun `접속 - joined the game 과 logged in (IP 제거)`() {
        assertEquals(LogEvent.Join("Steve"), event(modern("Steve joined the game")))
        assertEquals(
            LogEvent.Join("Steve"),
            event(modern("Steve[/192.168.0.12:51234] logged in with entity id 122 at ([world]120.5, 64.0, -88.5)")),
        )
        assertEquals(LogEvent.Join("철수"), event(modern("철수 joined the game")))
    }

    @Test
    fun `퇴장 - left the game 과 lost connection`() {
        assertEquals(LogEvent.Leave("Steve"), event(modern("Steve left the game")))
        assertEquals(LogEvent.Leave("Steve"), event(modern("Steve lost connection: Disconnected")))
        assertEquals(LogEvent.Leave("Alex"), event(modern("Alex lost connection: Timed out")))
    }

    @Test
    fun `채팅 - 일반과 Not Secure 와 한국어`() {
        assertEquals(LogEvent.Chat("Steve", "hello"), event(modern("<Steve> hello", thread = "Async Chat Thread - #0")))
        assertEquals(LogEvent.Chat("Alex", "hi steve!"), event(modern("[Not Secure] <Alex> hi steve!")))
        assertEquals(LogEvent.Chat("철수", "안녕하세요"), event(modern("<철수> 안녕하세요")))
        assertEquals(LogEvent.Chat("철수", "초록 글씨"), event(modern("<철수> §a초록 §f글씨")))
    }

    @Test
    fun `명령어`() {
        assertEquals(LogEvent.Command("Steve", "/tp Alex"), event(modern("Steve issued server command: /tp Alex")))
        assertEquals(LogEvent.Command("철수", "/spawn"), event(modern("철수 issued server command: /spawn")))
    }

    @Test
    fun `기동 완료 - 초를 ms 로`() {
        assertEquals(LogEvent.Startup(8214), event(modern("Done (8.214s)! For help, type \"help\"")))
        assertEquals(LogEvent.Startup(3712), event("[12:03:48 INFO]: Done (3.712s)! For help, type \"help\" or \"?\""))
    }

    @Test
    fun `랙 - 신형과 구형 메시지`() {
        assertEquals(LogEvent.Lag(2000), event(modern("Can't keep up! Is the server overloaded? Running 2000ms or 40 ticks behind", "WARN")))
        assertEquals(
            LogEvent.Lag(2345),
            event("[12:05:30 WARN]: Can't keep up! Did the system time change, or is the server overloaded? Running 2345ms behind, skipping 46 tick(s)"),
        )
    }

    @Test
    fun `플러그인 로드 성공 - 세 가지 문구`() {
        assertEquals(LogEvent.PluginLoad("LuckPerms", "5.4.141", true), event(modern("[LuckPerms] Loading server plugin LuckPerms v5.4.141")))
        assertEquals(LogEvent.PluginLoad("Vault", "1.7.3-b131", true), event(modern("[Vault] Enabling Vault v1.7.3-b131")))
        assertEquals(LogEvent.PluginLoad("Vault", "1.5.6-b49", true), event("[12:03:41 INFO]: [Vault] Loading Vault v1.5.6-b49"))
        assertEquals(LogEvent.PluginLoad("My Plugin", "1.0", true), event(modern("Enabling My Plugin v1.0")))
    }

    @Test
    fun `플러그인 로드 실패`() {
        assertEquals(
            LogEvent.PluginLoad("ProtocolLib-5.3.0", null, false),
            event(modern("Could not load 'plugins/ProtocolLib-5.3.0.jar' in folder 'plugins'", "ERROR")),
        )
        assertEquals(
            LogEvent.PluginLoad("FancyHolograms-2.4.1", null, false),
            event(modern("Could not load plugin 'FancyHolograms-2.4.1.jar' in folder 'plugins'", "ERROR")),
        )
        assertEquals(
            LogEvent.PluginLoad("ShopGUIPlus", "1.99.0", false),
            event(modern("Error occurred while enabling ShopGUIPlus v1.99.0 (Is it up to date?)", "ERROR")),
        )
        assertEquals(LogEvent.PluginLoad("Foo", null, false), event(modern("Error occurred while enabling Foo", "ERROR")))
    }

    @Test
    fun `ERROR 레벨은 스택트레이스를 품은 Error 이벤트`() {
        val line =
            parseLine(modern("Encountered an unexpected exception", "ERROR"))
                .copy(continuation = listOf("net.minecraft.ReportedException: boom", "\tat a.B(B.java:1)"))
        val ev = line.toEvent()
        assertIs<LogEvent.Error>(ev)
        assertEquals("Encountered an unexpected exception", ev.message)
        assertEquals(2, ev.stackTrace.size)
        assertEquals(LogEvent.Error("fatal", emptyList()), event(modern("fatal", "FATAL")))
    }

    @Test
    fun `WARN 레벨은 Warn 그 외는 Raw`() {
        assertEquals(LogEvent.Warn("hmm"), event(modern("hmm", "WARN")))
        assertEquals(LogEvent.Raw("Preparing level \"world\""), event(modern("Preparing level \"world\"")))
        assertEquals(LogEvent.Raw("Loading libraries, please wait..."), event("Loading libraries, please wait..."))
    }

    @Test
    fun `사망 - 템플릿이 없으면 절대 Death 가 아니다`() {
        assertEquals(LogEvent.Raw("Steve was slain by Zombie"), event(modern("Steve was slain by Zombie")))
        assertEquals(LogEvent.Raw("Steve drowned"), event(modern("Steve drowned")))
    }

    @Test
    fun `사망 - lang 템플릿으로만 매칭하고 긴 템플릿이 우선`() {
        val ctx =
            EventContext(
                listOf(
                    "%1\$s was slain by %2\$s",
                    "%1\$s was slain by %2\$s using %3\$s",
                    "%1\$s drowned",
                    "%1\$s fell from a high place",
                ),
            )
        assertEquals(LogEvent.Death("Steve", "Steve was slain by Zombie"), event(modern("Steve was slain by Zombie"), ctx))
        assertEquals(LogEvent.Death("Steve", "Steve was slain by Zombie using Iron Sword"), event(modern("Steve was slain by Zombie using Iron Sword"), ctx))
        assertEquals(LogEvent.Death("철수", "철수 drowned"), event(modern("철수 drowned"), ctx))
        assertEquals(LogEvent.Raw("Steve tried to swim in lava"), event(modern("Steve tried to swim in lava"), ctx))
        // 템플릿 없는 문장·플러그인 태그가 붙은 줄은 사망이 아니다
        assertEquals(LogEvent.Raw("drowned"), event(modern("[Foo] drowned"), ctx))
    }

    @Test
    fun `사망 - 한국어 템플릿과 자리표시자 순서 뒤집힘`() {
        val ctx = EventContext(listOf("%2\$s에게 %1\$s이(가) 살해당했습니다", "%1\$s이(가) 익사했습니다"))
        assertEquals(LogEvent.Death("철수", "좀비에게 철수이(가) 살해당했습니다"), event(modern("좀비에게 철수이(가) 살해당했습니다"), ctx))
        assertEquals(LogEvent.Death("Steve", "Steve이(가) 익사했습니다"), event(modern("Steve이(가) 익사했습니다"), ctx))
    }

    @Test
    fun `사망 템플릿에 정규식 특수문자가 있어도 안전하다`() {
        val ctx = EventContext(listOf("%1\$s died (again)", "%1\$s [x] %2\$s", "no placeholder", "%1\$s ((("))
        assertEquals(LogEvent.Death("Steve", "Steve died (again)"), event(modern("Steve died (again)"), ctx))
        assertEquals(LogEvent.Death("Steve", "Steve [x] Alex"), event(modern("Steve [x] Alex"), ctx))
        assertTrue(event(modern("no placeholder"), ctx) is LogEvent.Raw)
    }
}
