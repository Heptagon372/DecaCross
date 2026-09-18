package kr.decacross.collector.content

import kotlinx.coroutines.test.runTest
import kr.decacross.analysis.JarAnalysisResult
import kr.decacross.collector.core.JarAnalyzer
import kr.decacross.collector.core.ReportBuilder
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.core.SourceStatus
import kr.decacross.collector.http.DigestAlgo
import kr.decacross.collector.store.AnalysisRecord
import kr.decacross.collector.store.DepRow
import kr.decacross.collector.testkit.FakeHttp
import kr.decacross.collector.testkit.FakeResponse
import kr.decacross.collector.testkit.FixedClock
import kr.decacross.collector.testkit.NO_ANALYZER
import kr.decacross.collector.testkit.RecordingStore
import kr.decacross.collector.testkit.countFiles
import kr.decacross.collector.testkit.deleteTree
import kr.decacross.collector.testkit.newTempDir
import kr.decacross.collector.testkit.testContext
import kr.decacross.collector.testkit.testSettings
import kr.decacross.compat.model.Capability
import kr.decacross.compat.model.DepKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ContentPipelineTest {
    private val dir = newTempDir()
    private val effective = "0.1.0+rules.1a2b3c4d"
    private val url = "https://cdn.modrinth.com/data/AAAA/versions/BBBB/Plugin-1.0.jar"
    private val jar = "PK-fake-jar-bytes-for-the-pipeline".encodeToByteArray()
    private val jarSha1 = FakeHttp.hex(DigestAlgo.SHA1, jar)
    private val jarSha256 = FakeHttp.hex(DigestAlgo.SHA256, jar)
    private val clock = FixedClock(Instant.parse("2026-09-17T12:00:00Z"))

    @AfterTest
    fun cleanup() = deleteTree(dir)

    private class Fixture(val http: FakeHttp, val store: RecordingStore, val report: ReportBuilder, val pipeline: ContentPipeline)

    private fun fixture(
        analyzer: JarAnalyzer = NO_ANALYZER,
        budget: DownloadBudget = DownloadBudget(bytesLeft = 700L * 1024 * 1024, maxJarBytes = 64L * 1024 * 1024),
    ): Fixture {
        val http = FakeHttp(dir).on(url, FakeResponse.Body(jar))
        val store = RecordingStore()
        val report = ReportBuilder(SourceId.MODRINTH)
        val ctx = testContext(http, store, testSettings(dir), analyzer = analyzer, clock = clock)
        return Fixture(http, store, report, ContentPipeline(ctx, report, budget, effective))
    }

    private fun target(
        id: Long = 7,
        stored: String? = null,
        size: Long? = jar.size.toLong(),
        knownSha256: String? = null,
        expected: Map<DigestAlgo, String> = mapOf(DigestAlgo.SHA1 to jarSha1),
    ) = AnalysisTarget(id, stored, url, size, knownSha256, expected)

    private fun Fixture.counter(key: String): Long = report.count(key)

    private fun Fixture.downloads(): Int = http.requests.count { it.method == "DOWNLOAD" }

    @Test
    fun upToDate_noRequest() = runTest {
        val f = fixture()
        f.pipeline.analyze(target(stored = effective))

        assertEquals(emptyList(), f.http.requests)
        assertEquals(1L, f.counter("analysis.upToDate"))
        assertTrue(f.store.analyses.isEmpty())
    }

    @Test
    fun olderRulesFingerprint_reanalysed() = runTest {
        val f = fixture(analyzer = { JarAnalysisResult.Ok(fakeAnalysis()) })
        f.pipeline.analyze(target(stored = "0.1.0+rules.00000000"))

        assertEquals(1, f.downloads())
        val record = assertNotNull(f.store.analyses[7])
        assertEquals(effective, record.analyzerVersion)
        assertEquals(1L, f.counter("analysis.recorded"))
        assertEquals(0, countFiles(dir))
    }

    @Test
    fun dedupeBySha256_copies_noDownload() = runTest {
        val f = fixture()
        val existing = AnalysisRecord(
            sha256 = jarSha256,
            size = jar.size.toLong(),
            javaMajor = 17,
            apiVersion = "1.20",
            packDecl = null,
            analyzedAt = Instant.parse("2026-01-01T00:00:00Z"),
            analyzerVersion = effective,
            analysisJson = analysisJson(fakeAnalysis(), effective),
            provides = listOf(Capability.PermissionProvider),
        )
        f.store.recordAnalysis(1, existing)
        // 다른 분석기 버전의 같은 sha 는 복사 대상이 아니다
        f.store.recordAnalysis(2, existing.copy(analyzerVersion = "0.0.9+rules.ffffffff", javaMajor = 8))

        f.pipeline.analyze(target(id = 9, knownSha256 = jarSha256.uppercase(), expected = mapOf(DigestAlgo.SHA256 to jarSha256)))

        assertEquals(emptyList(), f.http.requests)
        assertEquals(existing.copy(analyzedAt = clock.now), f.store.analyses[9])
        assertEquals(listOf(DepRow(DepKind.PROVIDES, null, Capability.PermissionProvider)), f.store.deps[9]?.toList())
        assertEquals(1L, f.counter("analysis.dedupedBySha256"))
    }

    @Test
    fun budget_tooLarge_and_exhausted() = runTest {
        val size = jar.size.toLong()
        val budget = DownloadBudget(bytesLeft = size + size / 2, maxJarBytes = size)
        val f = fixture(analyzer = { JarAnalysisResult.Ok(fakeAnalysis()) }, budget = budget)

        f.pipeline.analyze(target(id = 1, size = null)) // 크기 모름 → 받지 않는다
        f.pipeline.analyze(target(id = 2, size = size + 1)) // 파일 하나 상한 초과
        assertEquals(2L, f.counter("analysis.tooLarge"))
        assertEquals(0, f.downloads())

        f.pipeline.analyze(target(id = 3, size = size)) // 통과 → 예산 차감
        assertEquals(size / 2, budget.bytesLeft)
        f.pipeline.analyze(target(id = 4, size = size)) // 남은 예산 부족
        assertEquals(1L, f.counter("analysis.budgetExhausted"))
        assertEquals(size / 2, budget.bytesLeft)

        assertEquals(1, f.downloads())
        assertEquals(setOf(3L), f.store.analyses.keys)
    }

    @Test
    fun downloadDigestMismatch_counted_noRecord() = runTest {
        val f = fixture()
        f.pipeline.analyze(target(expected = mapOf(DigestAlgo.SHA1 to "0".repeat(40))))

        assertEquals(1, f.downloads())
        assertEquals(1L, f.counter("analysis.downloadFailed"))
        assertTrue(f.store.analyses.isEmpty())
        assertEquals(0, countFiles(dir))

        // HTTP 실패도 같은 카운터
        f.http.on(url, FakeResponse.Status(503))
        f.pipeline.analyze(target())
        assertEquals(2L, f.counter("analysis.downloadFailed"))
        assertTrue(f.store.analyses.isEmpty())
    }

    @Test
    fun unreadable_recorded() = runTest {
        val f = fixture(analyzer = { JarAnalysisResult.Unreadable("zip END header not found") })
        f.pipeline.analyze(target())

        val record = assertNotNull(f.store.analyses[7])
        assertEquals(jarSha256, record.sha256)
        assertEquals(jar.size.toLong(), record.size)
        assertNull(record.javaMajor)
        assertNull(record.apiVersion)
        assertEquals(emptyList(), record.provides)
        assertEquals(unreadableJson("zip END header not found", effective), record.analysisJson)
        assertEquals(effective, record.analyzerVersion)
        assertEquals(1L, f.counter("analysis.recorded"))
        assertEquals(0, countFiles(dir))

        // 크기로만 검증된 전송(플랫폼 해시 없음)도 기록한다
        f.pipeline.analyze(target(id = 8, expected = emptyMap()))
        assertNotNull(f.store.analyses[8])
    }

    @Test
    fun sizeMismatch_noRecord_jarDeleted() = runTest {
        var analyzed = false
        val f = fixture(analyzer = {
            analyzed = true
            JarAnalysisResult.Unreadable("truncated")
        })
        f.pipeline.analyze(target(size = jar.size + 10L, expected = emptyMap()))

        assertEquals(1, f.downloads())
        assertEquals(1L, f.counter("analysis.sizeMismatch"))
        assertFalse(analyzed)
        assertTrue(f.store.analyses.isEmpty())
        assertEquals(0, countFiles(dir))
        assertTrue(f.report.build(SourceStatus.OK).warnings.any { "크기 불일치" in it })
    }

    @Test
    fun unreadable_noDigestNoSize_notRecorded() = runTest {
        // 해시도 크기도 없으면 조용히 잘린 전송을 가려낼 수 없다 → "읽을 수 없음"을 영구 기록하지 않는다
        assertFalse(transferVerified(emptyMap(), expectedSize = null, actualSize = 10))
        assertFalse(transferVerified(emptyMap(), expectedSize = 11, actualSize = 10))
        assertTrue(transferVerified(emptyMap(), expectedSize = 10, actualSize = 10))
        assertTrue(transferVerified(mapOf(DigestAlgo.SHA1 to jarSha1), expectedSize = null, actualSize = 10))

        // 파이프라인에서는 크기를 모르는 jar 가 예산 단계에서 이미 걸러져 다운로드조차 하지 않는다
        val f = fixture(analyzer = { JarAnalysisResult.Unreadable("truncated") })
        f.pipeline.analyze(target(size = null, expected = emptyMap()))
        assertEquals(0, f.downloads())
        assertTrue(f.store.analyses.isEmpty())
        assertEquals(0L, f.counter("analysis.recorded"))
    }

    @Test
    fun analyzerStackOverflow_recordedUnreadable_jarDeleted() = runTest {
        val f = fixture(analyzer = { throw StackOverflowError("deep class hierarchy") })
        f.pipeline.analyze(target())

        val record = assertNotNull(f.store.analyses[7])
        assertContains(record.analysisJson, "analyzer crashed")
        assertNull(record.javaMajor)
        assertEquals(emptyList(), record.provides)
        assertEquals(1L, f.counter("analysis.crashed"))
        assertEquals(1L, f.counter("analysis.recorded"))
        assertEquals(0, countFiles(dir))

        val linkage = fixture(analyzer = { throw NoClassDefFoundError("x/Y") })
        linkage.pipeline.analyze(target(id = 8))
        assertNotNull(linkage.store.analyses[8])
        assertEquals(1L, linkage.counter("analysis.crashed"))
        assertEquals(0, countFiles(dir))
    }

    @Test
    fun ok_recordsProvidesStoreOnly_andDeletesJar() = runTest {
        val analysis = fakeAnalysis(
            capabilities = listOf(
                storeEvidence(Capability.PermissionProvider),
                candidateEvidence(Capability.EconomyProvider),
                storeEvidence(Capability.PermissionProvider, "second registration"),
            ),
        )
        val f = fixture(analyzer = { JarAnalysisResult.Ok(analysis) })
        f.pipeline.analyze(target())

        val record = assertNotNull(f.store.analyses[7])
        assertEquals(listOf(Capability.PermissionProvider), record.provides)
        assertEquals(jarSha256, record.sha256)
        assertEquals(jar.size.toLong(), record.size)
        assertEquals(17, record.javaMajor)
        assertEquals("1.20", record.apiVersion)
        assertNull(record.packDecl)
        assertEquals(clock.now, record.analyzedAt)
        assertEquals(analysisJson(analysis, effective), record.analysisJson)
        assertEquals(listOf(DepRow(DepKind.PROVIDES, null, Capability.PermissionProvider)), f.store.deps[7]?.toList())
        assertEquals(0, countFiles(dir))
    }

    @Test
    fun analyzer_seesExistingFile_beforeDelete() = runTest {
        var seen: Path? = null
        val f = fixture(analyzer = { path ->
            assertTrue(Files.isRegularFile(path), "분석 시점에 jar 가 있어야 한다: $path")
            assertTrue(jar.contentEquals(Files.readAllBytes(path)))
            seen = path
            JarAnalysisResult.Ok(fakeAnalysis())
        })
        f.pipeline.analyze(target())

        val path = assertNotNull(seen)
        assertEquals(dir, path.parent)
        assertTrue(path.fileName.toString().startsWith("dl-"))
        assertFalse(Files.exists(path))
        assertNotNull(f.store.analyses[7])
    }

    @Test
    fun candidate_loggedNotStored() = runTest {
        val analysis = fakeAnalysis(
            capabilities = listOf(candidateEvidence(Capability.EconomyProvider, "failed: C3,C5 (TokenManager-style)")),
        )
        val f = fixture(analyzer = { JarAnalysisResult.Ok(analysis) })
        LogCapture(ContentPipeline::class.java).use { logs ->
            f.pipeline.analyze(target())
            assertContains(logs.messages, "capability 후보(저장 안 함): economy_provider failed: C3,C5 (TokenManager-style)")
        }

        val record = assertNotNull(f.store.analyses[7])
        assertEquals(emptyList(), record.provides)
        assertFalse(record.analysisJson.contains("CANDIDATE"))
        assertFalse(record.analysisJson.contains("economy_provider"))
        assertEquals(emptyList<DepRow>(), f.store.deps[7]?.toList())
    }
}
