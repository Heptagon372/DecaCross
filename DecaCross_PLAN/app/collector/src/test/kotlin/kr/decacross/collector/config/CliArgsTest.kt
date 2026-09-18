package kr.decacross.collector.config

import kr.decacross.collector.core.RunMode
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.store.DbTarget
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class CliArgsTest {
    private val localAppData = "C:\\Users\\tester\\AppData\\Local"
    private val winEnv = mapOf("LOCALAPPDATA" to localAppData)

    private fun ok(vararg args: String, env: Map<String, String> = winEnv, os: String = "Windows 11"): CliOptions {
        val parsed = parseCli(arrayOf(*args), env, os)
        assertIs<CliParse.Ok>(parsed, "기대: Ok, 실제: $parsed")
        return parsed.options
    }

    private fun error(vararg args: String, env: Map<String, String> = winEnv, os: String = "Windows 11"): String {
        val parsed = parseCli(arrayOf(*args), env, os)
        assertIs<CliParse.Error>(parsed, "기대: Error, 실제: $parsed")
        return parsed.message
    }

    @Test
    fun once_defaults() {
        val o = ok("--once")
        assertEquals(RunMode.ONCE, o.mode)
        assertEquals(SourceId.entries.toSet(), o.sources)
        assertEquals(DbTarget.EmbeddedDev(Path.of(localAppData, "DecaCross", "devdb"), 54329), o.db)
        assertFalse(o.migrate)
        assertFalse(o.check)
        assertFalse(o.sanity)
        assertFalse(o.sanityOnly)
        assertNull(o.sanityExpect)
        assertEquals(54329, o.devDbPort)
        val s = o.settings
        assertEquals(Path.of(localAppData, "DecaCross", "collector-tmp"), s.tempDir)
        assertEquals(JarMetaMode.RELEASES, s.mojang.jarMeta)
        assertTrue(s.mojang.includeSnapshots)
        assertFalse(s.mojang.allowInitialSeed)
        assertEquals(120, s.mojang.maxJarMetaPerCycle)
        assertEquals(PurpurHashMode.LATEST, s.purpur.hashMode)
        assertEquals(6, s.purpur.latestVersions)
        assertEquals(6, s.purpur.maxJarsPerCycle)
        assertEquals(512L * MIB, s.purpur.maxBytesPerCycle)
        assertEquals(1024L * MIB, s.purpur.maxBytesPerDay)
        assertEquals(200, s.content.modrinthCandidates)
        assertEquals(100, s.content.modrinthTop)
        assertEquals(100, s.content.hangarTop)
        assertEquals(100, s.content.versionsPerProject)
        assertEquals(1, s.content.analyzePerProject)
        assertEquals(700L * MIB, s.content.maxDownloadBytesPerCycle)
        assertEquals(60.minutes, s.sourceTimeout)
        assertEquals(buildUserAgent(), s.userAgent)
    }

    @Test
    fun loop_defaults() {
        val o = ok("--loop")
        assertEquals(RunMode.LOOP, o.mode)
        assertEquals(JarMetaMode.ALL, o.settings.mojang.jarMeta)
        assertEquals(PurpurHashMode.NEW, o.settings.purpur.hashMode)
        assertEquals(1, o.settings.content.analyzePerProject)
    }

    @Test
    fun quick_preset_thenExplicitOverride() {
        val quick = ok("--once", "--quick")
        assertEquals(JarMetaMode.OFF, quick.settings.mojang.jarMeta)
        assertEquals(PurpurHashMode.OFF, quick.settings.purpur.hashMode)
        assertEquals(0, quick.settings.content.analyzePerProject)

        val overridden = ok("--once", "--quick", "--mojang-jarmeta=all", "--content-analyze=2")
        assertEquals(JarMetaMode.ALL, overridden.settings.mojang.jarMeta)
        assertEquals(PurpurHashMode.OFF, overridden.settings.purpur.hashMode)
        assertEquals(2, overridden.settings.content.analyzePerProject)

        // --quick 앞에 준 플래그는 --quick 이 덮는다
        val before = ok("--once", "--mojang-jarmeta=all", "--quick")
        assertEquals(JarMetaMode.OFF, before.settings.mojang.jarMeta)
    }

    @Test
    fun onceAndLoop_bothOrNeither_error() {
        assertTrue(error("--once", "--loop").contains("--once"))
        assertTrue(error("--sources=mojang").contains("--once"))
        assertTrue(error().contains("--once"))
    }

    @Test
    fun unknownFlag_error() {
        assertTrue(error("--once", "--bogus").contains("--bogus"))
        assertTrue(error("--once", "positional").contains("positional"))
        error("--once", "--check=yes")
        error("--once", "--sources")
        assertIs<CliParse.Help>(parseCli(arrayOf("--help"), winEnv, "Windows 11"))
        assertIs<CliParse.Help>(parseCli(arrayOf("--once", "--help"), winEnv, "Windows 11"))
    }

    @Test
    fun unknownSource_error() {
        assertTrue(error("--once", "--sources=mojang,spigot").contains("spigot"))
        val o = ok("--once", "--sources=Mojang, adoptium")
        assertEquals(setOf(SourceId.MOJANG, SourceId.ADOPTIUM), o.sources)
    }

    @Test
    fun postgresUri_toJdbc_withDecodedCredentials() {
        val o = ok("--once", "--db-url=postgresql://coll%40ector:p%40ss%3Aw+rd@db.example.test:5432/postgres?sslmode=require")
        assertEquals(DbTarget.Url("jdbc:postgresql://db.example.test:5432/postgres?sslmode=require", "coll@ector", "p@ss:w+rd"), o.db)

        val short = ok("--once", "--db-url=postgres://u@localhost/db")
        assertEquals(DbTarget.Url("jdbc:postgresql://localhost/db", "u", null), short.db)

        val jdbc = ok("--once", "--db-url=jdbc:postgresql://h:5433/x", "--db-user=admin", "--db-password=secret")
        assertEquals(DbTarget.Url("jdbc:postgresql://h:5433/x", "admin", "secret"), jdbc.db)

        // 명시 플래그 > 환경변수 > URL 안 자격 증명
        val env = winEnv + mapOf("DECACROSS_DB_USER" to "envuser")
        val flags = ok("--once", "--db-url=postgresql://urluser:urlpass@h/db", "--db-password=flagpass", env = env)
        assertEquals(DbTarget.Url("jdbc:postgresql://h/db", "envuser", "flagpass"), flags.db)

        assertFalse(DbTarget.Url("jdbc:postgresql://h/db", "u", "secret").toString().contains("secret"))
        error("--once", "--db-url=mysql://h/db")
        error("--once", "--db-url=postgresql://u:%zz@h/db")
    }

    @Test
    fun envDbUrl_used() {
        val env = winEnv + mapOf(
            "DECACROSS_DB_URL" to "postgresql://a:b@envhost:6543/postgres",
            "DECACROSS_DB_PASSWORD" to "fromenv",
        )
        val o = ok("--once", env = env)
        assertEquals(DbTarget.Url("jdbc:postgresql://envhost:6543/postgres", "a", "fromenv"), o.db)
        // --db-url 이 환경변수보다 우선
        val flag = ok("--once", "--db-url=jdbc:postgresql://flaghost/db", env = env)
        assertEquals("jdbc:postgresql://flaghost/db", (flag.db as DbTarget.Url).jdbcUrl)
    }

    @Test
    fun requireDbUrl_blocksEmbedded() {
        val env = winEnv + mapOf("DECACROSS_REQUIRE_DB_URL" to "true")
        assertEquals("DB URL 필요 (DECACROSS_REQUIRE_DB_URL)", error("--once", env = env))
        assertIs<DbTarget.Url>(ok("--once", "--db-url=jdbc:postgresql://h/db", env = env).db)
    }

    @Test
    fun uaContact_env_override_andRejectsParens() {
        val o = ok("--once", env = winEnv + mapOf("DECACROSS_UA_CONTACT" to "+https://example.test/contact"))
        assertEquals("DecaCross/0.1 (+https://example.test/contact)", o.settings.userAgent)
        assertTrue(error("--once", env = winEnv + mapOf("DECACROSS_UA_CONTACT" to "bad (contact)")).contains("DECACROSS_UA_CONTACT"))
        error("--once", env = winEnv + mapOf("DECACROSS_UA_CONTACT" to "   "))
    }

    @Test
    fun windowsPaths_useLocalAppData_nonWindows_useXdg() {
        val win = ok("--once")
        assertEquals(Path.of(localAppData, "DecaCross", "collector-tmp"), win.settings.tempDir)
        assertEquals(Path.of(localAppData, "DecaCross", "devdb"), win.devDbDir)

        val xdgEnv = mapOf("XDG_CACHE_HOME" to "/xdg/cache", "XDG_DATA_HOME" to "/xdg/data", "HOME" to "/home/tester")
        val xdg = ok("--once", env = xdgEnv, os = "Linux")
        assertEquals(Path.of("/xdg/cache", "decacross", "collector-tmp"), xdg.settings.tempDir)
        assertEquals(Path.of("/xdg/data", "decacross", "devdb"), xdg.devDbDir)

        val home = ok("--once", env = mapOf("HOME" to "/home/tester"), os = "Mac OS X")
        assertEquals(Path.of("/home/tester", ".cache", "decacross", "collector-tmp"), home.settings.tempDir)
        assertEquals(Path.of("/home/tester", ".local", "share", "decacross", "devdb"), home.devDbDir)
    }

    @Test
    fun oneDrivePaths_refused_unlessAllowed() {
        // 경로 문자열에 OneDrive
        assertTrue(error("--once", "--temp-dir=C:\\Users\\tester\\OneDrive\\tmp").contains("OneDrive"))
        assertTrue(error("--once", "--dev-db-dir=C:\\Users\\tester\\onedrive - corp\\db").contains("OneDrive"))
        val allowed = ok("--once", "--temp-dir=C:\\Users\\tester\\OneDrive\\tmp", "--allow-onedrive-temp")
        assertEquals(Path.of("C:\\Users\\tester\\OneDrive\\tmp"), allowed.settings.tempDir)

        // 환경변수 OneDrive* 디렉터리 아래 (이름에 OneDrive 가 없어도)
        val env = winEnv + mapOf("OneDriveCommercial" to "D:\\CorpSync")
        assertTrue(error("--once", "--temp-dir=D:\\CorpSync\\collector", env = env).contains("OneDrive"))
        ok("--once", "--temp-dir=D:\\CorpSyncOther\\collector", env = env)
        ok("--once", "--temp-dir=D:\\CorpSync\\collector", "--allow-onedrive-temp", env = env)

        // 기본 경로가 OneDrive 아래로 풀리면 그것도 거부
        val badLocal = mapOf("LOCALAPPDATA" to "C:\\Users\\tester\\OneDrive\\Local")
        error("--once", env = badLocal)
    }

    @Test
    fun devDbPort_flag() {
        val o = ok("--once", "--dev-db-port=54349", "--dev-db-dir=D:\\devdb")
        assertEquals(54349, o.devDbPort)
        assertEquals(DbTarget.EmbeddedDev(Path.of("D:\\devdb"), 54349), o.db)
        error("--once", "--dev-db-port=0")
        error("--once", "--dev-db-port=70000")
        error("--once", "--dev-db-port=abc")
    }

    @Test
    fun purpurBackfill_explicitOnly_neverDefault() {
        assertEquals(PurpurHashMode.LATEST, ok("--once").settings.purpur.hashMode)
        assertEquals(PurpurHashMode.NEW, ok("--loop").settings.purpur.hashMode)
        assertEquals(PurpurHashMode.OFF, ok("--loop", "--quick").settings.purpur.hashMode)
        assertEquals(PurpurHashMode.BACKFILL, ok("--loop", "--purpur-hash=backfill").settings.purpur.hashMode)
        assertEquals(PurpurHashMode.BACKFILL, ok("--once", "--purpur-hash=BACKFILL").settings.purpur.hashMode)
        error("--once", "--purpur-hash=everything")
        val budgets = ok("--once", "--purpur-latest-versions=3", "--purpur-max-jars=2", "--purpur-max-mb=100", "--purpur-max-mb-per-day=200")
        assertEquals(3, budgets.settings.purpur.latestVersions)
        assertEquals(2, budgets.settings.purpur.maxJarsPerCycle)
        assertEquals(100L * MIB, budgets.settings.purpur.maxBytesPerCycle)
        assertEquals(200L * MIB, budgets.settings.purpur.maxBytesPerDay)
    }

    @Test
    fun sanityExpect_path() {
        val o = ok("--once", "--sanity-only", "--check", "--sanity-expect=src/test/resources/c1/sanity-expect-2026-09-17.json")
        assertEquals(Path.of("src/test/resources/c1/sanity-expect-2026-09-17.json"), o.sanityExpect)
        assertTrue(o.sanityOnly)
        assertTrue(o.sanity, "--sanity-only 면 sanity 도 true")
        assertTrue(o.check)
        error("--once", "--sanity-expect=")
    }

    @Test
    fun migrate_seedOrdinals_and_numericFlags() {
        val o = ok(
            "--once", "--migrate", "--seed-ordinals", "--mojang-snapshots=false", "--mojang-jarmeta-max=7",
            "--modrinth-candidates=10", "--modrinth-top=5", "--hangar-top=25", "--content-versions=3", "--content-max-mb=50",
            "--source-timeout-min=5", "--sanity",
        )
        assertTrue(o.migrate)
        assertTrue(o.sanity)
        assertTrue(o.settings.mojang.allowInitialSeed)
        assertFalse(o.settings.mojang.includeSnapshots)
        assertEquals(7, o.settings.mojang.maxJarMetaPerCycle)
        assertEquals(10, o.settings.content.modrinthCandidates)
        assertEquals(5, o.settings.content.modrinthTop)
        assertEquals(25, o.settings.content.hangarTop)
        assertEquals(3, o.settings.content.versionsPerProject)
        assertEquals(50L * MIB, o.settings.content.maxDownloadBytesPerCycle)
        assertEquals(5.minutes, o.settings.sourceTimeout)
        error("--once", "--mojang-snapshots=maybe")
        error("--once", "--source-timeout-min=0")
    }
}
