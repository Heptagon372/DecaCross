package kr.decacross.logparse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 실제 로그 코퍼스 회귀 테스트. 로그 파일은 `src/jvmTest/resources/corpus/` 아래 `.log` 들이다.
 * 파일마다 "최우선 시그니처 키 집합"과 이벤트 개수를 고정한다. 정상 기동 로그는 오탐 0 이어야 한다.
 */
class CorpusTest {
    private val seeds = SeedSignatures.signatures

    private val deathCtx =
        EventContext(
            listOf(
                "%1\$s was slain by %2\$s",
                "%1\$s was shot by %2\$s",
                "%1\$s was shot by %2\$s using %3\$s",
                "%1\$s fell from a high place",
                "%1\$s drowned",
                "%1\$s was blown up by %2\$s",
                "%1\$s tried to swim in lava",
            ),
        )

    private class Parsed(val name: String, val lines: List<LogLine>) {
        fun topKeys(seeds: List<Signature>): Set<String> = lines.mapNotNull { matchSignatures(it, seeds)?.signature?.key }.toSet()

        fun topMatches(seeds: List<Signature>): List<SignatureMatch> = lines.mapNotNull { matchSignatures(it, seeds) }

        fun allKeys(seeds: List<Signature>): Set<String> = lines.flatMap { l -> matchAllSignatures(l, seeds).map { it.signature.key } }.toSet()

        fun events(ctx: EventContext = EventContext.EMPTY): List<LogEvent> = lines.map { it.toEvent(ctx) }

        inline fun <reified T : LogEvent> count(ctx: EventContext = EventContext.EMPTY): Int = events(ctx).count { it is T }
    }

    private fun corpus(name: String): Parsed {
        val stream = checkNotNull(CorpusTest::class.java.getResourceAsStream("/corpus/$name.log")) { "코퍼스 없음: $name" }
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val rawLineCount = text.lines().count { it.isNotBlank() }
        assertTrue(rawLineCount in 40..150, "$name: 코퍼스는 40~150 줄이어야 한다 (지금 $rawLineCount)")
        return Parsed(name, parseLog(text))
    }

    private val allCorpora =
        listOf(
            "paper-normal-startup", "java-mismatch", "missing-dependency", "port-in-use", "oom", "eula",
            "watchdog", "pack-format", "nosuchmethod", "legacy-1.7", "player-events",
        )

    @Test
    fun `corpus - 모든 파일이 예외 없이 파싱되고 이벤트 변환된다`() {
        allCorpora.forEach { name ->
            val p = corpus(name)
            assertTrue(p.lines.isNotEmpty(), name)
            p.events(deathCtx)
            p.allKeys(seeds)
        }
    }

    @Test
    fun `corpus - 정상 기동은 시그니처 오탐 0`() {
        val p = corpus("paper-normal-startup")
        assertEquals(emptySet(), p.allKeys(seeds), "오탐: ${p.topMatches(seeds).map { it.signature.key to it.line.message }}")
        assertEquals(1, p.count<LogEvent.Startup>())
        assertEquals(LogEvent.Startup(8214), p.events().single { it is LogEvent.Startup })
        assertEquals(6, p.count<LogEvent.PluginLoad>())
        assertTrue(p.events().filterIsInstance<LogEvent.PluginLoad>().all { it.ok })
        assertEquals(1, p.count<LogEvent.Warn>())
        assertEquals(0, p.count<LogEvent.Error>())
        assertEquals(0, p.count<LogEvent.Join>())
        assertTrue(p.lines.all { it.continuation.isEmpty() }, "정상 로그에는 멀티라인 엔트리가 없다")
    }

    @Test
    fun `corpus - java-mismatch`() {
        val p = corpus("java-mismatch")
        assertEquals(setOf("unsupported_class_version", "unsupported_class_error"), p.topKeys(seeds))
        assertTrue("plugin_load_failed" in p.allKeys(seeds))
        val matches = p.topMatches(seeds)
        assertEquals(2, matches.size)
        assertTrue(matches.all { it.captured["major"] == "65" }, matches.map { it.captured }.toString())
        assertEquals(21, requiredJavaFromClassMajor(65))

        // 스택트레이스 = LogLine 하나 + 연속 줄 N개
        val first = p.lines.first { it.message.startsWith("Could not load 'plugins/ProtocolLib") }
        assertEquals(20, first.continuation.size)
        assertTrue(first.continuation.any { it.startsWith("Caused by:") })
        assertTrue(first.continuation.last().trim().startsWith("... 10 more"))

        val fails = p.events().filterIsInstance<LogEvent.PluginLoad>().filter { !it.ok }
        assertEquals(listOf("ProtocolLib-5.3.0", "FancyHolograms-2.4.1"), fails.map { it.name })
        assertEquals(1, p.count<LogEvent.Startup>())
    }

    @Test
    fun `corpus - missing-dependency`() {
        val p = corpus("missing-dependency")
        assertEquals(setOf("unknown_dependency"), p.topKeys(seeds))
        assertEquals(listOf("Vault", "Essentials"), p.topMatches(seeds).map { it.captured.getValue("dependency") })
        assertTrue("plugin_load_failed" in p.allKeys(seeds))
        assertEquals(2, p.events().filterIsInstance<LogEvent.PluginLoad>().count { !it.ok })
        assertEquals(4, p.events().filterIsInstance<LogEvent.PluginLoad>().count { it.ok })
        assertEquals(0, p.count<LogEvent.Error>())
    }

    @Test
    fun `corpus - port-in-use`() {
        val p = corpus("port-in-use")
        assertEquals(setOf("port_in_use"), p.topKeys(seeds))
        assertEquals(2, p.topMatches(seeds).size)
        assertEquals(0, p.count<LogEvent.Startup>())
        assertEquals(3, p.count<LogEvent.Warn>())
    }

    @Test
    fun `corpus - oom`() {
        val p = corpus("oom")
        assertEquals(setOf("oom_heap", "cant_keep_up"), p.topKeys(seeds))
        assertEquals(2, p.topMatches(seeds).count { it.signature.key == "oom_heap" })
        assertEquals(listOf("3210", "5120"), p.topMatches(seeds).filter { it.signature.key == "cant_keep_up" }.map { it.captured.getValue("ms") })
        assertEquals(listOf(3210L, 5120L), p.events().filterIsInstance<LogEvent.Lag>().map { it.ms })
        assertEquals(3, p.count<LogEvent.Error>())
        val trace = p.events().filterIsInstance<LogEvent.Error>().first()
        assertTrue(trace.stackTrace.size > 15)
        assertTrue(trace.stackTrace.any { it.contains("com.example.chunkhoarder") })
        assertEquals(2, p.count<LogEvent.Join>())
        assertEquals(2, p.count<LogEvent.Leave>())
        assertEquals(1, p.count<LogEvent.Chat>())
    }

    @Test
    fun `corpus - eula`() {
        val p = corpus("eula")
        assertEquals(setOf("eula_not_agreed"), p.topKeys(seeds))
        assertEquals(1, p.topMatches(seeds).size)
        assertEquals(0, p.count<LogEvent.Startup>())
    }

    @Test
    fun `corpus - watchdog`() {
        val p = corpus("watchdog")
        assertEquals(setOf("watchdog", "cant_keep_up"), p.topKeys(seeds))
        assertTrue(p.topMatches(seeds).count { it.signature.key == "watchdog" } >= 3)
        // 스레드 덤프 안에 플러그인 패키지가 있어 daemon 의 BlamePluginFromStack 이 범인을 지목할 수 있다
        assertTrue(p.lines.any { it.thread == "Paper Watchdog Thread" && it.message.contains("com.example.laggyplugin") })
        assertTrue(p.lines.filter { it.thread == "Paper Watchdog Thread" }.all { it.level == "ERROR" })
        assertEquals(2, p.count<LogEvent.Join>())
        assertEquals(2, p.count<LogEvent.Leave>())
    }

    @Test
    fun `corpus - pack-format`() {
        val p = corpus("pack-format")
        assertEquals(setOf("invalid_pack_format"), p.topKeys(seeds))
        assertEquals(3, p.topMatches(seeds).size)
        assertEquals(1, p.count<LogEvent.Startup>())
        assertEquals(1, p.count<LogEvent.Error>())
    }

    @Test
    fun `corpus - nosuchmethod`() {
        val p = corpus("nosuchmethod")
        assertEquals(setOf("linkage_error"), p.topKeys(seeds))
        val linkage = p.topMatches(seeds)
        assertEquals(listOf("NoSuchMethodError", "IncompatibleClassChangeError"), linkage.map { it.captured.getValue("kind") })
        assertTrue(linkage[0].captured.getValue("member").contains("ItemStack.withType"))
        assertTrue(linkage[0].line.continuation.any { it.startsWith("Caused by: java.lang.NoSuchMethodError") })
        assertTrue("plugin_enable_failed" in p.allKeys(seeds))
        val enableFail = matchAllSignatures(linkage[0].line, seeds).first { it.signature.key == "plugin_enable_failed" }
        assertEquals(mapOf("plugin" to "ShopGUIPlus", "version" to "1.99.0"), enableFail.captured)
        assertTrue(LogEvent.PluginLoad("ShopGUIPlus", "1.99.0", ok = false) in p.events())
        assertEquals(1, p.count<LogEvent.Command>())
    }

    @Test
    fun `corpus - legacy 1_7 포맷`() {
        val p = corpus("legacy-1.7")
        assertTrue(p.lines.all { detectFormat(it.raw) == LogFormat.LEGACY }, "모든 줄이 LEGACY 여야 한다")
        assertTrue(p.lines.all { it.thread == null && it.time != null })
        assertEquals(setOf("cant_keep_up"), p.topKeys(seeds))
        assertEquals(LogEvent.Startup(3712), p.events().single { it is LogEvent.Startup })
        assertEquals(2, p.count<LogEvent.Join>())
        assertEquals(2, p.count<LogEvent.Leave>())
        assertEquals(listOf(LogEvent.Chat("Steve", "hi"), LogEvent.Chat("Steve", "빨간 글씨")), p.events().filterIsInstance<LogEvent.Chat>())
        assertEquals(1, p.count<LogEvent.Command>())
        assertEquals(listOf(LogEvent.Lag(2345)), p.events().filterIsInstance<LogEvent.Lag>())
        assertEquals(8, p.count<LogEvent.PluginLoad>())
        assertEquals(1, p.count<LogEvent.Warn>())
    }

    @Test
    fun `corpus - player-events 채팅 접속 퇴장 명령`() {
        val p = corpus("player-events")
        assertEquals(setOf("cant_keep_up"), p.topKeys(seeds))
        assertEquals(6, p.count<LogEvent.Join>())
        assertEquals(6, p.count<LogEvent.Leave>())
        assertEquals(6, p.count<LogEvent.Command>())
        val chats = p.events().filterIsInstance<LogEvent.Chat>()
        assertEquals(6, chats.size)
        assertTrue(LogEvent.Chat("Alex", "hi steve!") in chats)
        assertTrue(LogEvent.Chat("철수", "안녕하세요") in chats)
        assertTrue(LogEvent.Chat("철수", "초록 글씨 테스트") in chats)
        assertTrue(LogEvent.Chat("철수", "<Steve> 흉내내기") in chats)
        assertEquals(1, p.count<LogEvent.Startup>())
        assertEquals(1, p.count<LogEvent.Lag>())
        assertTrue(LogEvent.Command("Steve", "/say 서버 재시작 5분 전") in p.events())
    }

    @Test
    fun `corpus - 사망은 템플릿이 있을 때만`() {
        val p = corpus("player-events")
        assertEquals(0, p.count<LogEvent.Death>())
        val deaths = p.events(deathCtx).filterIsInstance<LogEvent.Death>()
        assertEquals(6, deaths.size)
        assertEquals(listOf("Steve", "Alex", "철수", "Steve", "Alex", "Steve"), deaths.map { it.player })
        assertNotNull(deaths.find { it.message == "Steve was shot by Skeleton using Bow" })
        // 템플릿이 있어도 채팅·명령·접속은 사망으로 오인하지 않는다
        assertEquals(6, p.count<LogEvent.Chat>(deathCtx))
        assertEquals(6, p.count<LogEvent.Join>(deathCtx))
        assertEquals(6, p.count<LogEvent.Command>(deathCtx))
    }
}
