package kr.decacross.logparse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignatureTest {
    private val seeds = SeedSignatures.signatures

    private fun line(msg: String, vararg continuation: String, level: String = "ERROR", thread: String = "Server thread"): LogLine =
        parseLine("[12:00:00] [$thread/$level]: $msg").copy(continuation = continuation.toList())

    @Test
    fun `시드는 18종이고 키가 유일하며 fix 가 1개 이상이다`() {
        assertEquals(18, SeedSignatures.all.size)
        assertEquals(18, SeedSignatures.all.map { it.key }.toSet().size)
        SeedSignatures.all.forEach { assertTrue(it.fixes.isNotEmpty(), "${it.key} 에 fix 가 없다") }
        SeedSignatures.all.forEach { assertTrue(it.fixes.any { f -> f.recommended }, "${it.key} 에 recommended fix 가 없다") }
    }

    @Test
    fun `캡처는 이름으로 매핑된다`() {
        val m = matchSignatures(line("Could not load 'plugins/X.jar'", "Caused by: java.lang.IllegalArgumentException: Unsupported class file major version 65"), seeds)
        assertNotNull(m)
        assertEquals("unsupported_class_version", m.signature.key)
        assertEquals(mapOf("major" to "65"), m.captured)
        assertEquals(21, requiredJavaFromClassMajor(m.captured.getValue("major").toInt()))
    }

    @Test
    fun `우선순위가 높은 시그니처가 이긴다 - Caused by 가 진짜 원인`() {
        val l =
            line(
                "Error occurred while enabling ShopGUIPlus v1.99.0 (Is it up to date?)",
                "java.lang.RuntimeException: init",
                "\tat a.B(B.java:1)",
                "Caused by: java.lang.NoSuchMethodError: 'void a.B.c()'",
            )
        val all = matchAllSignatures(l, seeds)
        assertEquals(listOf("linkage_error", "plugin_enable_failed"), all.map { it.signature.key })
        assertEquals(mapOf("kind" to "NoSuchMethodError", "member" to "'void a.B.c()'"), all[0].captured)
        assertEquals(mapOf("plugin" to "ShopGUIPlus", "version" to "1.99.0"), all[1].captured)
    }

    @Test
    fun `매칭 안 된 옵션 그룹은 캡처 맵에서 빠진다`() {
        val m = matchSignatures(line("Error occurred while enabling Foo"), seeds)
        assertNotNull(m)
        assertEquals(mapOf("plugin" to "Foo"), m.captured)
    }

    @Test
    fun `Unknown dependency 는 끝의 마침표를 캡처하지 않는다`() {
        val m =
            matchSignatures(
                line(
                    "Could not load 'plugins/EssentialsXChat-2.21.0.jar' in folder 'plugins'",
                    "org.bukkit.plugin.UnknownDependencyException: Unknown dependency Vault. Please download and install Vault to run this plugin.",
                ),
                seeds,
            )
        assertNotNull(m)
        assertEquals("unknown_dependency", m.signature.key)
        assertEquals(mapOf("dependency" to "Vault"), m.captured)
    }

    @Test
    fun `watchdog 은 스레드 이름의 Watchdog 으로 잡는다`() {
        val m = matchSignatures(line("Server thread dump (Look for plugins here before reporting to Paper!):", thread = "Paper Watchdog Thread"), seeds)
        assertEquals("watchdog", m?.signature?.key)
        assertNull(matchSignatures(line("Server thread dump"), seeds))
    }

    @Test
    fun `각 시드가 대표 문장에 맞는다`() {
        val samples =
            mapOf(
                "eula_not_agreed" to "You need to agree to the EULA in order to run the server. Go to eula.txt for more info.",
                "port_in_use" to "**** FAILED TO BIND TO PORT!",
                "heap_reserve_failed" to "Could not reserve enough space for object heap",
                "oom_heap" to "java.lang.OutOfMemoryError: Java heap space",
                "nms_missing" to "java.lang.NoClassDefFoundError: net/minecraft/server/v1_20_R3/MinecraftServer",
                "cant_keep_up" to "Can't keep up! Is the server overloaded? Running 2000ms or 40 ticks behind",
                "invalid_pack_format" to "Pack \"file/x.zip\" has an unsupported pack_format 15",
                "library_download_failed" to "Failed to download net.fabricmc:fabric-loader:0.16.0 library from https://maven.fabricmc.net",
                "incompatible_server_version" to "This server is running Paper 1.21.4 which is not compatible with this plugin (requires 1.20.x)",
                "java_not_found" to "'java' is not recognized as an internal or external command,",
                "world_version_mismatch" to "The world 'world' was saved with a NEWER version of Minecraft (3953 > 3700)",
            )
        samples.forEach { (key, text) ->
            val m = matchSignatures(parseLine(text), seeds)
            assertEquals(key, m?.signature?.key, "'$text' 는 $key 에 맞아야 한다")
        }
        assertEquals(mapOf("nmsVersion" to "v1_20_R3"), matchSignatures(parseLine(samples.getValue("nms_missing")), seeds)?.captured)
        assertEquals(mapOf("ms" to "2000"), matchSignatures(parseLine(samples.getValue("cant_keep_up")), seeds)?.captured)
    }

    @Test
    fun `정상 문장은 아무 시드에도 맞지 않는다`() {
        listOf(
            "This server is running Paper version 1.21.4-100-main@a1b2c3d (Implementing API version 1.21.4-R0.1-SNAPSHOT)",
            "Preparing level \"world\"",
            "Loading libraries, please wait...",
            "[Paper Watchdog] Stopping server",
            "Steve joined the game",
            "Saving chunks for level 'ServerLevel[world]'/minecraft:overworld",
        ).forEach { assertNull(matchSignatures(parseLine("[12:00:00] [Server thread/INFO]: $it"), seeds), it) }
    }

    @Test
    fun `같은 우선순위면 목록 앞이 이기고 빈 목록이면 null`() {
        val a = Signature("a", Regex("x"), "t", priority = 1)
        val b = Signature("b", Regex("x"), "t", priority = 1)
        assertEquals("a", matchSignatures(line("x"), listOf(a, b))?.signature?.key)
        assertEquals("b", matchSignatures(line("x"), listOf(b, a))?.signature?.key)
        assertNull(matchSignatures(line("x"), emptyList()))
    }
}
