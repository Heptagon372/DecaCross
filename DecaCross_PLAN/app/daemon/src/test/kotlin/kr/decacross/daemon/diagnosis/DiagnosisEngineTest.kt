package kr.decacross.daemon.diagnosis

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiagnosisEngineTest {
    private fun engine() = DiagnosisEngine(Files.createTempDirectory("srv"), 25565, portOwnerLookup = { "javaw.exe (pid 4242)" })

    @Test
    fun javaMismatch_yieldsExecutableChangeJava() {
        val e = engine()
        val ds = e.analyze(
            listOf(
                "[12:00:00] [Server thread/ERROR]: Could not load plugin 'ProtocolLib.jar' in folder 'plugins'",
                "org.bukkit.plugin.InvalidPluginException: java.lang.UnsupportedClassVersionError: com/comphenix/protocol/ProtocolLib has been compiled by a more recent version of the Java Runtime (class file version 65.0), this version of the Java Runtime only recognizes class file versions up to 61.0",
                "\tat org.bukkit.plugin.java.JavaPluginLoader.loadPlugin(JavaPluginLoader.java:100)",
                "Caused by: java.lang.UnsupportedClassVersionError: Unsupported class file major version 65",
                "[12:00:01] [Server thread/INFO]: Done (3.0s)! For help, type \"help\"",
            ),
        )
        val d = assertNotNull(ds.firstOrNull { it.signatureKey == "unsupported_class_version" }, "ds=$ds")
        val fix = d.fixes.first { it.action.type == "ChangeJava" }
        assertEquals("21", fix.action.arg, "65 − 44 = 21")
        assertTrue(fix.action.executable)
        assertTrue(d.causeKo.contains("Java 21"))
        assertTrue(d.fixes.isNotEmpty())
    }

    @Test
    fun portInUse_namesOwnerAndSuggestsNextPort() {
        val e = engine()
        val ds = e.analyze(
            listOf(
                "[12:00:00] [Server thread/WARN]: **** FAILED TO BIND TO PORT!",
                "[12:00:00] [Server thread/WARN]: The exception was: io.netty.channel.unix.Errors\$NativeIoException: bind(..) failed: Address already in use",
            ),
        )
        val d = assertNotNull(ds.firstOrNull { it.signatureKey == "port_in_use" })
        assertTrue(d.causeKo.contains("javaw.exe"), d.causeKo)
        val port = d.fixes.first { it.action.type == "SuggestPort" }
        assertEquals("25566", port.action.arg)
    }

    @Test
    fun watchdog_blamesPluginPackage() {
        val e = engine()
        val ds = e.analyze(
            listOf(
                "[12:00:00] [Paper Watchdog Thread/ERROR]: The server has not responded for 10 seconds! Creating thread dump",
                "[12:00:00] [Paper Watchdog Thread/ERROR]: Server thread dump (Look for plugins here before reporting to Paper!):",
                "\tat net.minecraft.server.MinecraftServer.tick(MinecraftServer.java:1)",
                "\tat com.evil.laggy.LagTask.run(LagTask.java:10)",
                "\tat com.evil.laggy.LagTask.spin(LagTask.java:20)",
                "\tat org.bukkit.craftbukkit.scheduler.CraftTask.run(CraftTask.java:1)",
            ),
        )
        val d = assertNotNull(ds.firstOrNull { it.signatureKey == "watchdog" }, "ds=$ds")
        assertTrue(d.causeKo.contains("com.evil.laggy"), d.causeKo)
        assertTrue(d.fixes.any { it.action.type == "DisablePlugin" && it.action.arg == "com.evil.laggy" })
    }

    @Test
    fun repeatedError_updatesOccurrences_notNewCard() {
        val e = engine()
        e.analyze(listOf("[12:00:00] [Server thread/INFO]: Can't keep up! Is the server overloaded? Running 2000ms or 40 ticks behind"))
        e.analyze(listOf("[12:00:05] [Server thread/INFO]: Can't keep up! Is the server overloaded? Running 3000ms or 60 ticks behind"))
        assertEquals(1, e.all().size)
        assertEquals(2, e.all().first().occurrences)
    }

    @Test
    fun normalLog_producesNothing() {
        val e = engine()
        assertEquals(
            emptyList(),
            e.analyze(
                listOf(
                    "[12:00:00] [Server thread/INFO]: Starting minecraft server version 1.21.8",
                    "[12:00:01] [Server thread/INFO]: Preparing spawn area: 0%",
                    "[12:00:02] [Server thread/INFO]: Done (2.0s)! For help, type \"help\"",
                    "[12:00:03] [Server thread/INFO]: heptagon joined the game",
                ),
            ),
        )
    }
}
