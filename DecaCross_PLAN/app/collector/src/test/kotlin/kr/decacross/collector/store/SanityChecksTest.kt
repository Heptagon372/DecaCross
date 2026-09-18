package kr.decacross.collector.store

import kotlinx.coroutines.runBlocking
import kr.decacross.collector.config.CompletionThresholds
import kr.decacross.collector.testkit.deleteTree
import kr.decacross.collector.testkit.newTempDir
import kr.decacross.compat.model.Capability
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.ContentKind
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.LoaderFamily
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.PackFormat
import kr.decacross.compat.model.Source
import kr.decacross.compat.version.ORDINAL_STEP
import kr.decacross.compat.version.seedOrdinals
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class SanityChecksTest {
    private var opened: PgCollectorStore? = null
    private val store: PgCollectorStore get() = requireNotNull(opened) { "@BeforeTest 가 저장소를 열지 않았다" }
    private val tempDir = newTempDir("decacross-sanity-")
    private val t0 = Instant.parse("2011-11-18T00:00:00Z")
    private val low = CompletionThresholds(mcVersions = 1, coreBuilds = 1, content = 1)

    @BeforeTest
    fun setUp() {
        TestPg.resetAndMigrate()
        opened = TestPg.openStore()
    }

    @AfterTest
    fun tearDown() {
        opened?.close()
        opened = null
        deleteTree(tempDir)
    }

    private fun List<SanityResult>.check(id: String): SanityResult =
        firstOrNull { it.name.startsWith("$id ") } ?: throw AssertionError("$id 결과 없음: $this")

    private val enabledPolicy = RedistributionPolicy(enabled = true, allowlist = setOf("MIT", "Apache-2.0"))

    private suspend fun sanity(expect: SanityExpect? = null, policy: RedistributionPolicy? = enabledPolicy) =
        runSanity(store, policy, tempDir, expect, low)

    private fun newMc(label: String, ordinal: Int, at: Instant, snapshot: Boolean = false, java: Int = 21) =
        NewMcVersion(label, McOrdinal(ordinal), at, snapshot, java, java, null, null)

    /** OrdinalIssuer 와 같은 규칙으로 만든 작은 발급 결과: 릴리스 = seedOrdinals, 스냅샷 = 직전 릴리스 + 1.. */
    private suspend fun seedIssuerLikeData() {
        val releases = listOf("1.0" to t0, "1.1" to t0 + 50.days, "1.4.6" to t0 + 300.days, "1.4.5" to t0 + 300.days, "1.5" to t0 + 400.days)
        val seeded = seedOrdinals(releases).toMap()
        val times = releases.toMap()
        val rows = releases.map { (label, _) -> newMc(label, seeded.getValue(label).value, times.getValue(label)) } +
            listOf(
                newMc("12w01a", seeded.getValue("1.0").value + 1, t0 + 10.days, snapshot = true),
                newMc("12w02a", seeded.getValue("1.0").value + 2, t0 + 20.days, snapshot = true),
                newMc("13w01a", seeded.getValue("1.4.6").value + 1, t0 + 350.days, snapshot = true),
            )
        assertEquals(McInsertResult.Inserted(rows.size), store.insertMcVersions(rows))
        assertTrue(store.updateMcFacts("1.5", McFacts(PackFormat(1), null, 60)))
        store.putState("mojang.jarmeta.1.5", """{"clientSha1":"aa","status":"FOUND","era":"E0x","rp":"1","protocol":60,"bytes":10,"requests":2}""")
        store.putState("mojang.jarmeta.1.0", """{"clientSha1":"bb","status":"NONE","bytes":10,"requests":2}""")
        store.putState("mojang.jarmeta.1.1", """{"clientSha1":"cc","status":"FAILED","attempts":1,"bytes":0,"requests":1}""")
        store.putState("mojang.manifest", """{"etag":"0x8DF"}""")
        store.upsertCoreBuilds(CoreKey.PAPER, listOf(CoreBuildRow("1.5", "1", Channel.STABLE, "https://fill.test/1.jar", "0f".repeat(32), 10, t0)))
        val cid = (store.upsertContent(ContentRow(Source.MODRINTH, "P1", "alpha", "Alpha", ContentKind.PLUGIN, "MIT", true, null, 1, null, null, null)) as ContentUpsertResult.Stored).id
        val vid = store.upsertContentVersionsMeta(
            cid,
            listOf(VersionMeta(ContentVersionRow("1", null, "release", "https://cdn.modrinth.com/1.jar", null, null, setOf(LoaderFamily.BUKKIT), null, null, t0), emptyList())),
        ).getValue("1")
        store.recordAnalysis(vid, AnalysisRecord("ee".repeat(32), 5, 17, "1.13", null, t0, "0.1.0+rules.12345678", """{"capabilities":[{"confidence":"STORE"}]}""", listOf(Capability.PermissionProvider)))
    }

    @Test
    fun selfConsistency_passesOnIssuerOutput(): Unit = runBlocking {
        seedIssuerLikeData()
        val results = sanity()
        val notPass = results.filter { it.level != SanityLevel.PASS }
        assertEquals(emptyList(), notPass, "모두 PASS 여야 한다: $results")
        assertEquals(listOf("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "S10", "S11", "S12", "S13", "S15", "S16"), results.map { it.name.substringBefore(' ') })
        assertTrue(results.check("S7").detail.contains("2"), "FOUND/NONE 두 상태를 비교했다: ${results.check("S7")}")

        // 허용 목록을 못 읽으면 S9 는 WARN, 완료 기준 미달은 S1 FAIL
        val noPolicy = runSanity(store, null, tempDir, null, CompletionThresholds())
        assertEquals(SanityLevel.WARN, noPolicy.check("S9").level)
        assertEquals(SanityLevel.FAIL, noPolicy.check("S1").level)
    }

    @Test
    fun s9_switchOff_anyRedistributableRowFails_evenOnAllowlist(): Unit = runBlocking {
        // 회귀 (INV-4): Phase 1 스위치가 꺼져 있으면 허용 목록 라이선스(MIT)라도 redistributable=true 는 FAIL
        val off = RedistributionPolicy(enabled = false, allowlist = setOf("MIT", "Apache-2.0"))
        store.upsertContent(ContentRow(Source.MODRINTH, "OFF1", "safe", "Safe", ContentKind.PLUGIN, "MIT", false, null, null, null, null, null))
        assertEquals(SanityLevel.PASS, sanity(policy = off).check("S9").level)
        store.upsertContent(ContentRow(Source.MODRINTH, "OFF2", "mit", "Mit", ContentKind.PLUGIN, "MIT", true, null, null, null, null, null))
        assertEquals(SanityLevel.PASS, sanity(policy = enabledPolicy).check("S9").level, "스위치가 켜져 있으면 허용 목록 MIT 는 통과")
        val fail = sanity(policy = off).check("S9")
        assertEquals(SanityLevel.FAIL, fail.level, fail.detail)
        assertTrue(fail.detail.contains("redistributableEnabled"), fail.detail)
    }

    @Test
    fun expectFile_utf8Bom_parsed() {
        // 회귀 (V-1): BOM 붙은 --sanity-expect 파일
        val expect = SanityExpect.parse(Char(0xFEFF).toString() + """{"ordinals": {"1.21": 1860}}""")
        assertEquals(mapOf("1.21" to 1860), expect.ordinals)
    }

    @Test
    fun s16_snapshotReleasedAfterLaterOrdinalRelease_warns(): Unit = runBlocking {
        // 회귀 (INV-2): r2 가 늦게 도착해 s-after(더 늦게 나옴)보다 큰 서수를 받은 상태. S3/S5 는 잡지 못한다
        store.insertMcVersions(listOf(newMc("r1", 1000, t0), newMc("s-before", 1001, t0 + 1.days, snapshot = true), newMc("s-after", 1002, t0 + 3.days, snapshot = true)))
        assertEquals(SanityLevel.PASS, sanity().check("S16").level)
        store.insertMcVersions(listOf(newMc("r2", 1010, t0 + 2.days)))
        val results = sanity()
        assertEquals(SanityLevel.PASS, results.check("S3").level)
        assertEquals(SanityLevel.PASS, results.check("S5").level)
        val s16 = results.check("S16")
        assertEquals(SanityLevel.WARN, s16.level, s16.detail)
        assertTrue(s16.detail.contains(" 1 "), s16.detail)
    }

    @Test
    fun s6_detectsNonSeedOrdinal(): Unit = runBlocking {
        store.insertMcVersions(listOf(newMc("1.0", 1000, t0), newMc("1.1", 1000 + 2 * ORDINAL_STEP, t0 + 1.days)))
        val results = sanity()
        assertEquals(SanityLevel.PASS, results.check("S3").level)
        assertEquals(SanityLevel.PASS, results.check("S4").level)
        val s6 = results.check("S6")
        assertEquals(SanityLevel.FAIL, s6.level)
        assertTrue(s6.detail.contains("1.1"), s6.detail)
    }

    @Test
    fun s3_s4_s5_detectStructuralViolations(): Unit = runBlocking {
        // 시간 역순 릴리스(S3), 10 의 배수 아닌 릴리스(S4), 슬롯 밖 스냅샷(S5)
        store.insertMcVersions(
            listOf(
                newMc("1.0", 1010, t0),
                newMc("1.1", 1000, t0 + 1.days),
                newMc("1.2", 1025, t0 + 2.days),
                newMc("99w99a", 990, t0 - 1.days, snapshot = true),
            ),
        )
        val results = sanity()
        assertEquals(SanityLevel.FAIL, results.check("S3").level)
        assertEquals(SanityLevel.FAIL, results.check("S4").level)
        assertEquals(SanityLevel.FAIL, results.check("S5").level)
        assertEquals(SanityLevel.PASS, results.check("S2").level)
    }

    @Test
    fun s7_detectsFactMismatchWithState(): Unit = runBlocking {
        store.insertMcVersions(listOf(newMc("1.21", 1000, t0), newMc("1.21.1", 1010, t0 + 1.days)))
        store.updateMcFacts("1.21", McFacts(PackFormat(35), PackFormat(48), 767))
        store.putState("mojang.jarmeta.1.21", """{"clientSha1":"x","status":"FOUND","rp":"34","dp":"48","protocol":767,"bytes":1,"requests":1}""")
        val mismatch = sanity().check("S7")
        assertEquals(SanityLevel.FAIL, mismatch.level)
        assertTrue(mismatch.detail.contains("1.21"), mismatch.detail)

        store.updateMcFacts("1.21", McFacts(PackFormat(34), PackFormat(48), 767))
        assertEquals(SanityLevel.PASS, sanity().check("S7").level)

        // NONE 인데 DB 에 값이 있으면 FAIL
        store.updateMcFacts("1.21.1", McFacts(PackFormat(34), null, null))
        store.putState("mojang.jarmeta.1.21.1", """{"clientSha1":"y","status":"NONE","bytes":1,"requests":1}""")
        assertEquals(SanityLevel.FAIL, sanity().check("S7").level)
    }

    @Test
    fun s10_java7Warns_zeroFails(): Unit = runBlocking {
        val cid = (store.upsertContent(ContentRow(Source.HANGAR, "9", "legacy", "Legacy", ContentKind.PLUGIN, null, false, null, null, null, null, null)) as ContentUpsertResult.Stored).id
        val ids = store.upsertContentVersionsMeta(
            cid,
            listOf("1", "2").map { v -> VersionMeta(ContentVersionRow(v, null, null, null, null, null, emptySet(), null, null, null), emptyList()) },
        )
        store.recordAnalysis(ids.getValue("1"), AnalysisRecord("11".repeat(32), 1, 7, null, null, t0, "0.1.0+rules.0", "{}", emptyList()))
        val warn = sanity().check("S10")
        assertEquals(SanityLevel.WARN, warn.level, warn.detail)

        store.recordAnalysis(ids.getValue("2"), AnalysisRecord("22".repeat(32), 1, 0, null, null, t0, "0.1.0+rules.0", "{}", emptyList()))
        val fail = sanity().check("S10")
        assertEquals(SanityLevel.FAIL, fail.level, fail.detail)
    }

    @Test
    fun s14_expectFile_mismatchFails_nullWarns(): Unit = runBlocking {
        val text = requireNotNull(SanityChecksTest::class.java.getResourceAsStream("/c1/sanity-expect-2026-09-17.json")) { "c1 리소스 없음" }
            .use { it.readBytes().decodeToString() }
        val expect = SanityExpect.parse(text)
        assertEquals(mapOf("1.0" to 1000, "1.21" to 1860, "26.3" to 2020), expect.ordinals)
        assertEquals(SanityExpect.ExpectedFacts("97.1", "121"), expect.facts["26.3"])
        assertEquals(mapOf("1.21.8" to 21, "26.3" to 25), expect.javaMin)

        store.insertMcVersions(
            listOf(
                newMc("1.0", 1000, t0),
                newMc("1.21", 1860, t0 + 1.days),
                newMc("1.21.8", 1940, t0 + 2.days, java = 21),
                newMc("26.3", 2020, t0 + 3.days, java = 25),
            ),
        )
        store.updateMcFacts("1.21", McFacts(PackFormat(34), PackFormat(48), null))
        // 26.3 사실이 아직 없음 (jar 메타 꺼짐) → WARN
        val warn = sanity(expect).check("S14")
        assertEquals(SanityLevel.WARN, warn.level, warn.detail)
        assertTrue(warn.detail.contains("26.3"), warn.detail)

        store.updateMcFacts("26.3", McFacts(PackFormat(97, 1), PackFormat(121), null))
        assertEquals(SanityLevel.PASS, sanity(expect).check("S14").level)

        store.updateMcFacts("1.21", McFacts(PackFormat(34, 0), PackFormat(49), null))
        val fail = sanity(expect).check("S14")
        assertEquals(SanityLevel.FAIL, fail.level)
        assertTrue(fail.detail.contains("1.21 dp"), fail.detail)

        val ordinalMismatch = sanity(expect.copy(ordinals = mapOf("1.21" to 1870), facts = emptyMap())).check("S14")
        assertEquals(SanityLevel.FAIL, ordinalMismatch.level)
        assertTrue(sanity(null).none { it.name.startsWith("S14") }, "기대값 파일이 없으면 S14 없음")
    }

    @Test
    fun s15_candidateInAnalysisFails(): Unit = runBlocking {
        val cid = (store.upsertContent(ContentRow(Source.MODRINTH, "X", "x", "X", ContentKind.PLUGIN, null, false, null, null, null, null, null)) as ContentUpsertResult.Stored).id
        val ids = store.upsertContentVersionsMeta(
            cid,
            listOf(VersionMeta(ContentVersionRow("1", null, null, "https://cdn.modrinth.com/x.jar", null, null, emptySet(), null, null, null), emptyList())),
        )
        store.recordAnalysis(ids.getValue("1"), AnalysisRecord("33".repeat(32), 1, 17, null, null, t0, "v", """{"capabilities":[{"cap":"economy_provider","confidence":"STORE"}]}""", emptyList()))
        assertEquals(SanityLevel.PASS, sanity().check("S15").level)

        store.recordAnalysis(ids.getValue("1"), AnalysisRecord("33".repeat(32), 1, 17, null, null, t0, "v", """{"capabilities":[{"cap":"economy_provider","confidence":"CANDIDATE"}]}""", emptyList()))
        assertEquals(SanityLevel.FAIL, sanity().check("S15").level)

        store.recordAnalysis(ids.getValue("1"), AnalysisRecord("33".repeat(32), 1, 17, null, null, t0, "v", "{}", emptyList()))
        store.upsertContentVersionsMeta(
            cid,
            listOf(VersionMeta(ContentVersionRow("2", null, null, "https://github.com/x/releases", null, null, emptySet(), null, null, null), emptyList())),
        )
        assertEquals(SanityLevel.PASS, sanity().check("S15").level)
        store.upsertContentVersionsMeta(
            cid,
            listOf(VersionMeta(ContentVersionRow("3", null, null, "http://insecure.test/x.jar", null, null, emptySet(), null, null, null), emptyList())),
        )
        assertEquals(SanityLevel.FAIL, sanity().check("S15").level)
    }

    @Test
    fun s8_s9_s11_s12_s13_violations(): Unit = runBlocking {
        store.insertMcVersions(listOf(newMc("1.0", 1000, t0)))
        store.upsertCoreBuilds(CoreKey.PURPUR, listOf(CoreBuildRow("1.0", "1", Channel.STABLE, "https://x.test/1.jar", "ABC", 0, null)))
        store.upsertContent(ContentRow(Source.HANGAR, "1", "gpl", "Gpl", ContentKind.PLUGIN, "GPL-3.0", true, null, null, null, null, null))
        val results = sanity()
        assertEquals(SanityLevel.FAIL, results.check("S8").level)
        assertEquals(SanityLevel.FAIL, results.check("S9").level)
        assertEquals(SanityLevel.PASS, results.check("S11").level)
        assertEquals(SanityLevel.PASS, results.check("S12").level)
        assertEquals(SanityLevel.WARN, results.check("S13").level)

        Files.createDirectories(tempDir)
        Files.write(tempDir.resolve("dl-123.part"), byteArrayOf(1))
        assertEquals(SanityLevel.FAIL, sanity().check("S12").level)
    }
}
