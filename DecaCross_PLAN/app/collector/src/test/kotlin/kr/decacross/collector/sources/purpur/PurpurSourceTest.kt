package kr.decacross.collector.sources.purpur

import kotlinx.coroutines.test.runTest
import kr.decacross.collector.CollectorJson
import kr.decacross.collector.config.CollectorSettings
import kr.decacross.collector.config.MIB
import kr.decacross.collector.config.PurpurHashMode
import kr.decacross.collector.config.PurpurSettings
import kr.decacross.collector.core.SourceStatus
import kr.decacross.collector.http.DigestAlgo
import kr.decacross.collector.http.FailureKind
import kr.decacross.collector.store.CoreBuildRow
import kr.decacross.collector.store.McRow
import kr.decacross.collector.testkit.FakeHttp
import kr.decacross.collector.testkit.FakeResponse
import kr.decacross.collector.testkit.FixedClock
import kr.decacross.collector.testkit.RecordingStore
import kr.decacross.collector.testkit.deleteTree
import kr.decacross.collector.testkit.newTempDir
import kr.decacross.collector.testkit.testContext
import kr.decacross.collector.testkit.testSettings
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.McOrdinal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** §9.4 PurpurSource (D17). */
class PurpurSourceTest {
    private val dir = newTempDir()
    private val base = "https://purpur.test"
    private val t0 = Instant.parse("2026-09-17T00:00:00Z")

    @AfterTest
    fun cleanup() = deleteTree(dir)

    /** 가짜 빌드 정의. [md5] 가 [AUTO] 면 가짜 jar 의 실제 md5. */
    private data class Spec(
        val build: Int,
        val result: String = "SUCCESS",
        val md5: String? = AUTO,
        val timestamp: Long = 1_750_000_000_000L + build,
        val experimental: Boolean = false,
        val size: Int = 100,
    )

    private fun res(path: String): String = checkNotNull(javaClass.getResourceAsStream(path)) { path }.use { it.readBytes().decodeToString() }

    private fun settings(mode: PurpurHashMode, change: (PurpurSettings) -> PurpurSettings = { it }): CollectorSettings =
        testSettings(dir).copy(purpur = change(PurpurSettings(baseUrl = base, hashMode = mode)))

    private fun store(vararg labels: String): RecordingStore = RecordingStore().also { s ->
        labels.forEachIndexed { i, label -> s.mc += McRow(i + 1L, label, McOrdinal(1000 + i * 10), t0, false, 21, 21, null, null, null, null, null) }
    }

    private fun jar(v: String, b: Int, size: Int): ByteArray = ByteArray(size) { i -> ("$v-$b".hashCode() + i).toByte() }

    private fun downloadUrl(v: String, b: Int) = "$base/v2/purpur/$v/$b/download"

    private fun FakeHttp.project(vararg versions: String): FakeHttp =
        onJson("$base/v2/purpur", versions.joinToString(",", """{"project":"purpur","metadata":{"current":"x"},"versions":[""", "]}") { "\"$it\"" })

    /** brief·detailed·다운로드 경로를 등록한다. */
    private fun FakeHttp.version(v: String, vararg specs: Spec): FakeHttp {
        val latest = specs.maxOf { it.build }
        onJson("$base/v2/purpur/$v", """{"project":"purpur","version":"$v","builds":{"latest":"$latest","all":[${specs.sortedBy { it.build }.joinToString(",") { "\"${it.build}\"" }}]}}""")
        val all = specs.sortedBy { it.build }.joinToString(",") { s ->
            val bytes = jar(v, s.build, s.size)
            val md5 = when (s.md5) {
                AUTO -> "\"${FakeHttp.hex(DigestAlgo.MD5, bytes)}\""
                null -> "null"
                else -> "\"${s.md5}\""
            }
            val metadata = if (s.experimental) """{"type":"experimental"}""" else "{}"
            """{"project":"purpur","version":"$v","build":"${s.build}","result":"${s.result}","timestamp":${s.timestamp},"duration":1,""" +
                """"commits":[{"author":"author-1","email":"redacted@example.invalid","description":"x","hash":"h","timestamp":1}],"metadata":$metadata,"md5":$md5}"""
        }
        onJson("$base/v2/purpur/$v?detailed=true", """{"project":"purpur","version":"$v","builds":{"latest":{"build":"$latest"},"all":[$all]}}""")
        for (s in specs) on(downloadUrl(v, s.build), FakeResponse.Body(jar(v, s.build, s.size)))
        return this
    }

    private fun digested(http: FakeHttp): List<String> = http.requests.filter { it.method == "DIGEST" }.map { it.url }

    private fun known(store: RecordingStore, v: String, vararg builds: Int) {
        for (b in builds) {
            store.coreBuilds[Triple(CoreKey.PURPUR, v, b.toString())] =
                CoreBuildRow(v, b.toString(), Channel.STABLE, downloadUrl(v, b), "%064x".format(b), 100, t0)
        }
    }

    @Test
    fun latest_hashesNewestSuccess_newestMcFirst_withinBudget() = runTest {
        val http = FakeHttp(dir)
            .project("1.21.7", "1.21.8", "26.2", "99.9")
            .version("1.21.7", Spec(1), Spec(2))
            .version("1.21.8", Spec(10), Spec(11), Spec(12))
            .version("26.2", Spec(5), Spec(6, size = 300))
        val store = store("1.21.7", "1.21.8", "26.2")

        val report = PurpurSource().collect(testContext(http, store, settings(PurpurHashMode.LATEST), clock = FixedClock(t0)))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertEquals(listOf(downloadUrl("26.2", 6), downloadUrl("1.21.8", 12), downloadUrl("1.21.7", 2)), digested(http))
        assertTrue(http.requests.none { it.url.contains("99.9") }, "mc_versions 에 없는 버전은 보지 않는다")
        val row = assertNotNull(store.coreBuilds[Triple(CoreKey.PURPUR, "26.2", "6")])
        val bytes = jar("26.2", 6, 300)
        assertEquals(FakeHttp.hex(DigestAlgo.SHA256, bytes), row.sha256)
        assertEquals(300L, row.size)
        assertEquals(Channel.STABLE, row.channel)
        assertEquals(downloadUrl("26.2", 6), row.downloadUrl)
        assertEquals(Instant.fromEpochMilliseconds(1_750_000_000_006L), row.publishedAt)
        assertEquals(3, store.coreBuilds.size)
        assertEquals("""{"bytes":500}""", store.state["purpur.budget.2026-09-17"])
        assertEquals(3L, report.counters["inserted"])
    }

    @Test
    fun failureEmptyMd5ZeroTimestamp_excluded() = runTest {
        val specs = arrayOf(Spec(5, result = "FAILURE"), Spec(4, md5 = ""), Spec(3, timestamp = 0), Spec(2), Spec(1))
        val http = FakeHttp(dir).project("26.2").version("26.2", *specs)

        val latest = PurpurSource().collect(testContext(http, store("26.2"), settings(PurpurHashMode.LATEST)))
        assertEquals(SourceStatus.OK, latest.status)
        assertEquals(listOf(downloadUrl("26.2", 2)), digested(http))

        val http2 = FakeHttp(dir).project("26.2").version("26.2", *specs)
        PurpurSource().collect(testContext(http2, store("26.2"), settings(PurpurHashMode.BACKFILL)))
        assertEquals(listOf(downloadUrl("26.2", 2), downloadUrl("26.2", 1)), digested(http2))
    }

    @Test
    fun nullMd5FailureBuild_decodes_andExcluded() = runTest {
        val detailed = CollectorJson.decodeFromString(PurpurVersionDetailed.serializer(), res("/c2/purpur/1.21.8_detailed.json"))
        assertEquals(20, detailed.builds.all.size)
        val nullMd5 = detailed.builds.all[2]
        assertEquals("2480", nullMd5.build)
        assertEquals("FAILURE", nullMd5.result)
        assertNull(nullMd5.md5)

        // 실제 픽스처로 BACKFILL: md5 가 가짜 jar 와 다르므로 전부 md5Mismatch 지만, null md5 빌드는 요청조차 없다
        val http = FakeHttp(dir)
            .project("1.21.8")
            .onJson("$base/v2/purpur/1.21.8", res("/c2/purpur/1.21.8.json"))
            .onJson("$base/v2/purpur/1.21.8?detailed=true", res("/c2/purpur/1.21.8_detailed.json"))
        for (b in detailed.builds.all) http.on(downloadUrl("1.21.8", b.build.toInt()), FakeResponse.Body(jar("1.21.8", b.build.toInt(), 50)))
        // 불일치도 jar 한 개로 세므로 한 사이클에 19개를 다 보려면 jar 상한을 올린다
        val report = PurpurSource().collect(testContext(http, store("1.21.8"), settings(PurpurHashMode.BACKFILL) { it.copy(maxJarsPerCycle = 100) }))

        assertEquals(19L, report.counters["skip.md5Mismatch"])
        assertTrue(http.requests.none { it.url == downloadUrl("1.21.8", 2480) })
    }

    @Test
    fun latest_onlyNewestSixVersions() = runTest {
        val labels = (1..8).map { "1.$it" }
        val http = FakeHttp(dir).project(*labels.toTypedArray())
        for (v in labels) http.version(v, Spec(1))
        val store = store(*labels.toTypedArray())

        val report = PurpurSource().collect(testContext(http, store, settings(PurpurHashMode.LATEST) { it.copy(maxJarsPerCycle = 20) }))

        assertEquals(SourceStatus.OK, report.status)
        val briefs = http.requests.map { it.url }.filter { it.matches(Regex(".*/v2/purpur/1\\.\\d$")) }
        assertEquals(listOf("1.8", "1.7", "1.6", "1.5", "1.4", "1.3").map { "$base/v2/purpur/$it" }, briefs)
        assertEquals(6, digested(http).size)
    }

    @Test
    fun new_onlyBuildsAboveHighestKnown_noBackfill() = runTest {
        val http = FakeHttp(dir)
            .project("1.21.8", "26.2")
            .version("26.2", Spec(3), Spec(4), Spec(5), Spec(6), Spec(7))
            .version("1.21.8", Spec(1), Spec(2), Spec(3))
        val store = store("1.21.8", "26.2")
        known(store, "26.2", 5)

        val report = PurpurSource().collect(testContext(http, store, settings(PurpurHashMode.NEW)))

        assertEquals(SourceStatus.OK, report.status)
        assertEquals(listOf(downloadUrl("26.2", 7), downloadUrl("26.2", 6), downloadUrl("1.21.8", 3)), digested(http))
        assertNull(store.coreBuilds[Triple(CoreKey.PURPUR, "26.2", "4")])
    }

    @Test
    fun backfill_explicit_allUnknown_withinDailyCap() = runTest {
        val http = FakeHttp(dir).project("26.2").version("26.2", Spec(1), Spec(2), Spec(3), Spec(4), Spec(5))
        val store = store("26.2")
        known(store, "26.2", 3)

        val report = PurpurSource().collect(testContext(http, store, settings(PurpurHashMode.BACKFILL) { it.copy(maxJarsPerCycle = 10) }))

        assertEquals(SourceStatus.OK, report.status)
        assertEquals(listOf(5, 4, 2, 1).map { downloadUrl("26.2", it) }, digested(http))

        // 일일 상한 250 바이트 → 두 개만
        val http2 = FakeHttp(dir).project("26.2").version("26.2", Spec(1), Spec(2), Spec(3), Spec(4), Spec(5))
        val capped = PurpurSource().collect(
            testContext(http2, store("26.2"), settings(PurpurHashMode.BACKFILL) { it.copy(maxJarsPerCycle = 10, maxBytesPerDay = 250) }),
        )
        assertEquals(SourceStatus.PARTIAL, capped.status)
        assertEquals(2, digested(http2).size)
        assertEquals(1L, capped.counters["budget.exhausted"])
    }

    @Test
    fun dailyCap_stateAccumulates_acrossRuns() = runTest {
        val http = FakeHttp(dir).project("26.2").version("26.2", Spec(1), Spec(2), Spec(3), Spec(4), Spec(5))
        val store = store("26.2")
        val settings = settings(PurpurHashMode.BACKFILL) { it.copy(maxJarsPerCycle = 10, maxBytesPerDay = 250) }
        val clock = FixedClock(t0)

        PurpurSource().collect(testContext(http, store, settings, clock = clock))
        assertEquals(2, digested(http).size)
        assertEquals("""{"bytes":200}""", store.state["purpur.budget.2026-09-17"])

        val second = PurpurSource().collect(testContext(http, store, settings, clock = clock))
        assertEquals(SourceStatus.PARTIAL, second.status)
        assertEquals(2, digested(http).size, "같은 날은 남은 50 바이트로 100 바이트 jar 를 받지 않는다")

        clock.now = t0 + 1.days
        PurpurSource().collect(testContext(http, store, settings, clock = clock))
        assertEquals(4, digested(http).size)
        assertEquals("""{"bytes":200}""", store.state["purpur.budget.2026-09-18"])
        assertEquals(4, store.coreBuilds.size)
    }

    @Test
    fun secondOnce_noNewBuilds_noDigest() = runTest {
        val http = FakeHttp(dir).project("1.21.8", "26.2").version("1.21.8", Spec(1), Spec(2)).version("26.2", Spec(7))
        val store = store("1.21.8", "26.2")
        PurpurSource().collect(testContext(http, store, settings(PurpurHashMode.LATEST)))
        assertEquals(2, digested(http).size)

        val n = http.requests.size
        val second = PurpurSource().collect(testContext(http, store, settings(PurpurHashMode.LATEST)))

        assertEquals(SourceStatus.OK, second.status)
        val after = http.requests.drop(n)
        assertEquals(listOf("$base/v2/purpur", "$base/v2/purpur/26.2", "$base/v2/purpur/1.21.8"), after.map { it.url }, "1 + 버전 수 만큼의 brief GET 만")
        assertEquals(2L, second.counters["latest.known"])
    }

    @Test
    fun md5Mismatch_skipped() = runTest {
        val http = FakeHttp(dir).project("26.2").version("26.2", Spec(8, md5 = "0123456789abcdef0123456789abcdef"))
        val store = store("26.2")
        val clock = FixedClock(t0)

        val report = PurpurSource().collect(testContext(http, store, settings(PurpurHashMode.NEW), clock = clock))

        assertEquals(SourceStatus.OK, report.status)
        assertEquals(1L, report.counters["skip.md5Mismatch"])
        assertTrue(report.warnings.any { it.contains("md5") })
        assertTrue(store.coreBuilds.isEmpty())
        // 스트리밍한 100 바이트는 예산에 들어가고, 그날 불일치 빌드로 기록된다
        assertEquals("""{"bytes":100,"mismatched":["26.2/8"]}""", store.state["purpur.budget.2026-09-17"])

        // 같은 날 다음 사이클: HEAD·다운로드 없이 건너뛴다 (15분마다 같은 jar 를 다시 받지 않는다)
        val second = PurpurSource().collect(testContext(http, store, settings(PurpurHashMode.NEW), clock = clock))
        assertEquals(SourceStatus.OK, second.status)
        assertEquals(1L, second.counters["skip.md5MismatchToday"])
        assertEquals(1, digested(http).size)
        assertEquals(1, http.requests.count { it.method == "HEAD" })
        assertEquals("""{"bytes":100,"mismatched":["26.2/8"]}""", store.state["purpur.budget.2026-09-17"])

        // 다음 날에는 한 번 다시 시도한다
        clock.now = t0 + 1.days
        val nextDay = PurpurSource().collect(testContext(http, store, settings(PurpurHashMode.NEW), clock = clock))
        assertEquals(1L, nextDay.counters["skip.md5Mismatch"])
        assertEquals(2, digested(http).size)
    }

    @Test
    fun failedDigest_chargesBudget_andCountsAgainstJarCap() = runTest {
        // HEAD 는 정상, 본문은 받는 도중 끊김 → HEAD 길이만큼 예산 소모, jar 상한에도 포함
        val http = FakeHttp(dir).project("26.2").version("26.2", Spec(1), Spec(2), Spec(3))
        for (b in 1..3) {
            http.on(downloadUrl("26.2", b)) { req ->
                if (req.method == "HEAD") FakeResponse.Body(jar("26.2", b, 100)) else FakeResponse.Fail(FailureKind.NETWORK, "reset")
            }
        }
        val store = store("26.2")

        val report = PurpurSource().collect(testContext(http, store, settings(PurpurHashMode.BACKFILL) { it.copy(maxJarsPerCycle = 2) }))

        assertEquals(SourceStatus.PARTIAL, report.status)
        assertEquals(2, digested(http).size, "실패한 스트리밍도 jar 한 개로 센다")
        assertEquals(2L, report.counters["failed"])
        assertEquals(1L, report.counters["budget.exhausted"])
        assertEquals("""{"bytes":200}""", store.state["purpur.budget.2026-09-17"])
        assertTrue(store.coreBuilds.isEmpty())

        // 본문이 HEAD 보다 커서 maxJarBytes 에서 멈춤 → maxJarBytes 만큼 소모
        val http2 = FakeHttp(dir).project("26.2").version("26.2", Spec(1))
        http2.on(downloadUrl("26.2", 1)) { req ->
            if (req.method == "HEAD") FakeResponse.Body(ByteArray(100)) else FakeResponse.Body(ByteArray(2_000))
        }
        val store2 = store("26.2")
        val big = PurpurSource().collect(testContext(http2, store2, settings(PurpurHashMode.LATEST) { it.copy(maxJarBytes = 1_000) }))
        assertEquals(SourceStatus.PARTIAL, big.status)
        assertEquals(1L, big.counters["failed"])
        assertEquals("""{"bytes":1000}""", store2.state["purpur.budget.2026-09-17"])
    }

    @Test
    fun headFailure_countedAsFailed_notTooLarge() = runTest {
        val http = FakeHttp(dir).project("26.2").version("26.2", Spec(1), Spec(2))
        http.on(downloadUrl("26.2", 2)) { req -> if (req.method == "HEAD") FakeResponse.Status(503) else FakeResponse.Body(jar("26.2", 2, 100)) }
        val store = store("26.2")

        val report = PurpurSource().collect(testContext(http, store, settings(PurpurHashMode.BACKFILL)))

        assertEquals(SourceStatus.PARTIAL, report.status)
        assertEquals(1L, report.counters["failed"])
        assertNull(report.counters["skip.tooLarge"])
        assertTrue(report.warnings.any { it.contains("26.2/2") && it.contains("HEAD") && it.contains("503") }, report.warnings.toString())
        assertEquals(listOf(downloadUrl("26.2", 1)), digested(http), "HEAD 실패 빌드는 받지 않고 다음 빌드는 계속")
        assertEquals("""{"bytes":100}""", store.state["purpur.budget.2026-09-17"])
    }

    @Test
    fun budget_maxJars_and_maxBytes() = runTest {
        val specs = arrayOf(Spec(1), Spec(2), Spec(3), Spec(4))
        val http = FakeHttp(dir).project("26.2").version("26.2", *specs)
        val jars = PurpurSource().collect(testContext(http, store("26.2"), settings(PurpurHashMode.BACKFILL) { it.copy(maxJarsPerCycle = 2) }))
        assertEquals(SourceStatus.PARTIAL, jars.status)
        assertEquals(2, digested(http).size)
        assertEquals(1L, jars.counters["budget.exhausted"])

        val http2 = FakeHttp(dir).project("26.2").version("26.2", *specs)
        val bytes = PurpurSource().collect(testContext(http2, store("26.2"), settings(PurpurHashMode.BACKFILL) { it.copy(maxBytesPerCycle = 150) }))
        assertEquals(SourceStatus.PARTIAL, bytes.status)
        assertEquals(1, digested(http2).size)

        // HEAD 크기가 maxJarBytes 초과 → 건너뜀 (다음 빌드는 계속)
        val http3 = FakeHttp(dir).project("26.2").version("26.2", Spec(1), Spec(2, size = 2_000))
        val big = PurpurSource().collect(testContext(http3, store("26.2"), settings(PurpurHashMode.BACKFILL) { it.copy(maxJarBytes = 1_000) }))
        assertEquals(1L, big.counters["skip.tooLarge"])
        assertEquals(listOf(downloadUrl("26.2", 1)), digested(http3))
        assertEquals(512L * MIB, PurpurSettings().maxBytesPerCycle)
    }

    @Test
    fun experimental_folded() = runTest {
        val http = FakeHttp(dir).project("1.21.9").version("1.21.9", Spec(2498, experimental = true), Spec(2499, experimental = true))
        val store = store("1.21.9")

        PurpurSource().collect(testContext(http, store, settings(PurpurHashMode.LATEST)))

        assertEquals(Channel.EXPERIMENTAL, store.coreBuilds[Triple(CoreKey.PURPUR, "1.21.9", "2499")]?.channel)
        // 실제 픽스처의 metadata.type 도 같은 모양이다
        val fixture = CollectorJson.decodeFromString(PurpurVersionDetailed.serializer(), res("/c2/purpur/1.21.9_detailed.json"))
        assertTrue(fixture.builds.all.all { it.metadata["type"] == "experimental" })
    }

    @Test
    fun off_skipped_noRequests() = runTest {
        val http = FakeHttp(dir).project("26.2").version("26.2", Spec(1))

        val report = PurpurSource().collect(testContext(http, store("26.2"), settings(PurpurHashMode.OFF)))

        assertEquals(SourceStatus.SKIPPED, report.status)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun latestAlreadyKnown_noDetailedNoDigest() = runTest {
        val http = FakeHttp(dir).project("26.2").version("26.2", Spec(1), Spec(2))
        val store = store("26.2")
        known(store, "26.2", 2)

        val report = PurpurSource().collect(testContext(http, store, settings(PurpurHashMode.NEW)))

        assertEquals(SourceStatus.OK, report.status)
        assertEquals(listOf("$base/v2/purpur", "$base/v2/purpur/26.2"), http.requests.map { it.url })
        assertEquals(1L, report.counters["latest.known"])
    }

    @Test
    fun fixtureWithScrubbedCommits_parsesWithoutCommitsField() {
        for (path in listOf("/c2/purpur/1.21.8_detailed.json", "/c2/purpur/1.21.9_detailed.json")) {
            val text = res(path)
            assertTrue(text.contains("\"commits\""), "$path 는 commits 를 유지한다")
            val emails = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+").findAll(text).map { it.value }.toSet()
            assertTrue(emails.all { it == "redacted@example.invalid" }, "$path 에 실제 이메일이 없어야 한다: $emails")
            val decoded = CollectorJson.decodeFromString(PurpurVersionDetailed.serializer(), text)
            assertTrue(decoded.builds.all.isNotEmpty())
        }
        assertTrue(PurpurBuild::class.java.declaredFields.none { it.name == "commits" }, "DTO 에 commits 필드가 없어야 한다")
        val project = CollectorJson.decodeFromString(PurpurProject.serializer(), res("/c2/purpur/purpur.json"))
        assertTrue("1.21.8" in project.versions)
        val brief = CollectorJson.decodeFromString(PurpurVersion.serializer(), res("/c2/purpur/1.21.8.json"))
        assertEquals("2497", brief.builds.latest)
    }

    private companion object {
        const val AUTO = "<auto>"
    }
}
