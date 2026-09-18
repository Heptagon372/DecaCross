package kr.decacross.collector.sources.mojang

import kotlinx.coroutines.test.runTest
import kr.decacross.collector.config.CollectorSettings
import kr.decacross.collector.config.JarMetaMode
import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.RunMode
import kr.decacross.collector.core.SourceStatus
import kr.decacross.collector.http.DigestAlgo
import kr.decacross.collector.http.FailureKind
import kr.decacross.collector.store.CollectorStore
import kr.decacross.collector.store.McInsertResult
import kr.decacross.collector.store.McRow
import kr.decacross.collector.store.NewMcVersion
import kr.decacross.collector.testkit.FakeHttp
import kr.decacross.collector.testkit.FakeResponse
import kr.decacross.collector.testkit.FixedClock
import kr.decacross.collector.testkit.NO_ANALYZER
import kr.decacross.collector.testkit.RecordingStore
import kr.decacross.collector.testkit.deleteTree
import kr.decacross.collector.testkit.newTempDir
import kr.decacross.collector.testkit.testContext
import kr.decacross.collector.testkit.testSettings
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.PackFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** §9.2 MojangSource.collect (FakeHttp + RecordingStore, 작은 매니페스트). */
class MojangSourceTest {
    private val dir = newTempDir()
    private val mini = MiniMojang()

    /** 한 번에 발급한 결과 (old_beta 제외). */
    private val oneShot = mapOf("1.0" to 1000, "s1" to 1001, "s2" to 1002, "1.1" to 1010, "s3" to 1011, "1.1.5 Pre-Release 1" to 1012, "1.2" to 1020)

    @AfterTest
    fun cleanup() = deleteTree(dir)

    private fun settings(jarMeta: JarMetaMode = JarMetaMode.OFF, allowInitialSeed: Boolean = false): CollectorSettings {
        val base = testSettings(dir)
        return base.copy(mojang = base.mojang.copy(manifestUrl = mini.manifestUrl, jarMeta = jarMeta, allowInitialSeed = allowInitialSeed))
    }

    private fun ordinals(store: RecordingStore): Map<String, Int> = store.mc.associate { it.label to it.ordinal.value }

    private fun jarMetaState(store: RecordingStore, label: String): JarMetaState =
        assertNotNull(decodeStateOrNull(JarMetaState.serializer(), store.state[jarMetaStateKey(label)]), "jarmeta state $label")

    private fun jarUrls(label: String): Set<String> = setOf(mini.clientUrl(label), mini.serverUrl(label))

    @Test
    fun once_insertsRows_javaFallback_ordinals() = runTest {
        val http = mini.install(FakeHttp(dir))
        val store = RecordingStore()

        val report = MojangSource().collect(testContext(http, store, settings()))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertEquals(oneShot, ordinals(store))
        assertEquals(7L, report.counters["issue.inserted"])
        val r10 = store.mc.single { it.label == "1.0" }
        assertEquals(8, r10.javaMin, "javaVersion 없음 → 8 (AE-9)")
        assertEquals(8, r10.javaRecommended)
        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), r10.releasedAt)
        val r12 = store.mc.single { it.label == "1.2" }
        assertEquals(21, r12.javaMin)
        assertEquals(21, r12.javaRecommended)
        assertEquals(mini.clientUrl("1.2"), r12.clientJarUrl)
        assertFalse(r12.isSnapshot)
        assertTrue(store.mc.single { it.label == "s1" }.isSnapshot)
        assertTrue(store.mc.none { it.label == "b1.8" })
        assertTrue(http.requests.none { it.url == mini.jsonUrl("b1.8") }, "old_beta 는 받지 않는다")
        assertNull(http.requests.first { it.url == mini.manifestUrl }.conditional, "--once 는 무조건 요청")

        val manifestState = decodeStateOrNull(ManifestState.serializer(), store.state[MANIFEST_STATE_KEY])
        assertEquals("manifest-etag-1", manifestState?.etag)
        val v12 = assertNotNull(decodeStateOrNull(VersionState.serializer(), store.state[versionStateKey("1.2")]))
        assertEquals(mini.serverUrl("1.2"), v12.serverUrl)
        assertEquals(21, v12.javaMajor)
        assertTrue(http.requests.none { it.method == "RANGE" }, "jarMeta OFF")
    }

    @Test
    fun once_emptyNonDevDb_withoutSeedFlag_failed() = runTest {
        val http = mini.install(FakeHttp(dir))
        val store = RecordingStore(isDevDatabase = false)

        val report = MojangSource().collect(testContext(http, store, settings()))

        assertEquals(SourceStatus.FAILED, report.status)
        assertEquals(SEED_REQUIRED_MESSAGE, report.error)
        assertTrue(store.mc.isEmpty())
        assertEquals(listOf(mini.manifestUrl), http.requests.map { it.url })
        assertNull(store.state[MANIFEST_STATE_KEY])

        // --seed-ordinals 를 주면 시딩한다
        val seeded = MojangSource().collect(testContext(http, store, settings(allowInitialSeed = true)))
        assertEquals(SourceStatus.OK, seeded.status)
        assertEquals(oneShot, ordinals(store))
    }

    @Test
    fun once_sha1Mismatch_dropsGapClosedSubset_partial() = runTest {
        val http = mini.install(FakeHttp(dir))
        http.onJson(mini.jsonUrl("s1"), mini.versionJson("s1").replace("java-runtime", "tampered"))
        val store = RecordingStore()

        val report = MojangSource().collect(testContext(http, store, settings()))

        assertEquals(SourceStatus.PARTIAL, report.status)
        // s1 실패 → 같은 구간의 뒤 스냅샷 s2 만 함께 빠진다
        assertEquals(oneShot - "s1" - "s2", ordinals(store))
        assertEquals(1L, report.counters["versionJson.failed"])
        assertEquals(1L, report.counters["versionJson.dropped"])
        assertEquals(2, http.requests.count { it.url == mini.jsonUrl("s1") }, "실패 항목은 한 번 재시도")
        assertTrue(report.warnings.any { it.contains("s1") && it.contains("sha1") }, report.warnings.toString())
        assertNull(store.state[MANIFEST_STATE_KEY], "누락이 있으면 매니페스트 ETag 를 기록하지 않는다")
    }

    @Test
    fun once_transientFailure_retriedOnce_thenOk() = runTest {
        val http = mini.install(FakeHttp(dir))
        val calls = AtomicInteger()
        http.on(mini.jsonUrl("s3")) { if (calls.incrementAndGet() == 1) FakeResponse.Fail(FailureKind.NETWORK) else FakeResponse.Body(mini.versionJson("s3")) }
        val store = RecordingStore()

        val report = MojangSource().collect(testContext(http, store, settings()))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertEquals(2, calls.get())
        assertEquals(1L, report.counters["versionJson.retried"])
        assertNull(report.counters["versionJson.failed"])
        assertEquals(oneShot, ordinals(store))
        assertNotNull(store.state[MANIFEST_STATE_KEY])
    }

    @Test
    fun loop_afterDroppedCycle_unconditionalManifest_issuesRest() = runTest {
        val http = mini.install(FakeHttp(dir))
        http.on(mini.jsonUrl("1.1"), FakeResponse.Status(500))
        val store = RecordingStore()
        val ctx = testContext(http, store, settings(), mode = RunMode.LOOP)

        val first = MojangSource().collect(ctx)
        assertEquals(SourceStatus.PARTIAL, first.status)
        // 릴리스 1.1 실패 → 키 순으로 그 뒤 전부 이번 사이클에서 빠진다
        assertEquals(mapOf("1.0" to 1000, "s1" to 1001, "s2" to 1002), ordinals(store))
        assertNull(store.state[MANIFEST_STATE_KEY])

        http.onJson(mini.jsonUrl("1.1"), mini.versionJson("1.1"))
        val second = MojangSource().collect(ctx)
        assertEquals(SourceStatus.OK, second.status, second.toString())
        assertEquals(oneShot, ordinals(store), "나머지는 한 번에 발급한 서수 그대로")
        val manifestGets = http.requests.filter { it.url == mini.manifestUrl }
        assertEquals(2, manifestGets.size)
        assertTrue(manifestGets.all { it.conditional == null }, "ETag 가 없으니 두 번째도 무조건 요청")
        assertEquals("manifest-etag-1", decodeStateOrNull(ManifestState.serializer(), store.state[MANIFEST_STATE_KEY])?.etag)

        // 세 번째: 이제 조건부 → 304 → 아무것도 받지 않는다
        val n = http.requests.size
        val third = MojangSource().collect(ctx)
        assertEquals(SourceStatus.OK, third.status)
        assertEquals(1L, third.counters["manifest.notModified"])
        val after = http.requests.drop(n)
        assertEquals(listOf(mini.manifestUrl), after.map { it.url })
        assertEquals("manifest-etag-1", after.single().conditional?.etag)
    }

    @Test
    fun jarMetaFailed_backoffState_notRetriedBeforeNextAttempt() = runTest {
        val http = mini.install(FakeHttp(dir))
        http.on(mini.clientUrl("1.2"), FakeResponse.Fail(FailureKind.NETWORK))
        http.on(mini.serverUrl("1.2"), FakeResponse.Fail(FailureKind.NETWORK))
        val store = RecordingStore()
        val t0 = Instant.parse("2026-09-17T00:00:00Z")
        val clock = FixedClock(t0)
        val settings = settings(JarMetaMode.RELEASES)

        val first = MojangSource().collect(testContext(http, store, settings, clock = clock))
        assertEquals(SourceStatus.PARTIAL, first.status)
        assertEquals(1L, first.counters["jarmeta.failed"])
        val s1 = jarMetaState(store, "1.2")
        assertEquals("FAILED", s1.status)
        assertEquals(1, s1.attempts)
        assertEquals(t0 + 15.minutes, Instant.parse(assertNotNull(s1.nextAttemptAt)))

        clock.now = t0 + 5.minutes
        var n = http.requests.size
        val second = MojangSource().collect(testContext(http, store, settings, clock = clock))
        assertEquals(SourceStatus.OK, second.status, second.toString())
        assertEquals(1L, second.counters["jarmeta.backoff"])
        assertTrue(http.requests.drop(n).none { it.url in jarUrls("1.2") }, "백오프 중에는 다시 읽지 않는다")

        clock.now = t0 + 16.minutes
        n = http.requests.size
        val third = MojangSource().collect(testContext(http, store, settings, clock = clock))
        assertEquals(SourceStatus.PARTIAL, third.status)
        assertTrue(http.requests.drop(n).any { it.url in jarUrls("1.2") && it.method == "RANGE" })
        val s3 = jarMetaState(store, "1.2")
        assertEquals(2, s3.attempts)
        assertEquals(t0 + 16.minutes + 30.minutes, Instant.parse(assertNotNull(s3.nextAttemptAt)))
        assertEquals(15.minutes, jarMetaBackoff(1))
        assertEquals(24 * 60, jarMetaBackoff(40).inWholeMinutes.toInt())
    }

    @Test
    fun once_jarMetaReleasesOnly_updatesFacts_writesState() = runTest {
        val http = mini.install(FakeHttp(dir))
        val store = RecordingStore()

        val report = MojangSource().collect(testContext(http, store, settings(JarMetaMode.RELEASES)))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertEquals(listOf("1.2", "1.1", "1.0"), store.mcFactsUpdates.map { it.first }, "서수 내림차순")
        val r12 = store.mc.single { it.label == "1.2" }
        assertEquals(PackFormat(34), r12.rpFormat)
        assertEquals(PackFormat(48), r12.dpFormat)
        assertEquals(767, r12.protocol)
        val r11 = store.mc.single { it.label == "1.1" }
        assertEquals(PackFormat(4), r11.rpFormat)
        assertEquals(PackFormat(4), r11.dpFormat)
        assertNull(r11.protocol)
        val r10 = store.mc.single { it.label == "1.0" }
        assertNull(r10.rpFormat)
        assertNull(r10.dpFormat)
        // 서수는 그대로
        assertEquals(oneShot, ordinals(store))

        assertEquals(2L, report.counters["jarmeta.found"])
        assertEquals(1L, report.counters["jarmeta.none"])
        assertTrue((report.counters["jarmeta.requests"] ?: 0) > 0 && (report.counters["jarmeta.bytes"] ?: 0) > 0)

        val s12 = jarMetaState(store, "1.2")
        assertEquals("FOUND", s12.status)
        assertEquals("server", s12.jar)
        assertEquals("E3", s12.era)
        assertEquals("34", s12.rp)
        assertEquals("48", s12.dp)
        assertEquals(767, s12.protocol)
        assertEquals(FakeHttp.hex(DigestAlgo.SHA1, mini.version("1.2").client), s12.clientSha1)
        assertEquals("client", jarMetaState(store, "1.1").jar)
        assertEquals("NONE", jarMetaState(store, "1.0").status)
        assertTrue(store.state[jarMetaStateKey("1.0")]?.contains("\"bytes\"") == true, "bytes 는 항상 기록")

        val snapshotJars = listOf("s1", "s2", "s3", "1.1.5 Pre-Release 1").flatMap { jarUrls(it) }
        assertTrue(http.requests.none { it.url in snapshotJars }, "RELEASES 모드는 스냅샷 jar 를 읽지 않는다")
    }

    @Test
    fun secondOnce_skipsJarMetaByState_noRangeRequests() = runTest {
        val http = mini.install(FakeHttp(dir))
        val store = RecordingStore()
        val settings = settings(JarMetaMode.RELEASES)
        assertEquals(SourceStatus.OK, MojangSource().collect(testContext(http, store, settings)).status)

        val n = http.requests.size
        val second = MojangSource().collect(testContext(http, store, settings))

        assertEquals(SourceStatus.OK, second.status, second.toString())
        val after = http.requests.drop(n)
        assertEquals(listOf(mini.manifestUrl), after.map { it.url }, "매니페스트 외 요청 없음")
        assertNull(second.counters["issue.inserted"])
        assertEquals(1, store.mcFactsUpdates.count { it.first == "1.2" })
    }

    @Test
    fun loop_manifest304_noIssuance_butJarMetaBacklog() = runTest {
        val http = mini.install(FakeHttp(dir))
        val store = RecordingStore()
        assertEquals(SourceStatus.OK, MojangSource().collect(testContext(http, store, settings(JarMetaMode.OFF))).status)
        assertNotNull(store.state[MANIFEST_STATE_KEY])

        val n = http.requests.size
        val report = MojangSource().collect(testContext(http, store, settings(JarMetaMode.RELEASES), mode = RunMode.LOOP))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertEquals(1L, report.counters["manifest.notModified"])
        assertNull(report.counters["issue.inserted"])
        val after = http.requests.drop(n)
        assertEquals("manifest-etag-1", after.first().conditional?.etag)
        assertTrue(after.none { it.method == "GET" && it.url != mini.manifestUrl }, "per-version JSON 재요청 없음 (버전 상태 사용)")
        assertTrue(after.any { it.method == "RANGE" })
        assertEquals(2L, report.counters["jarmeta.found"])
        assertEquals(1L, report.counters["jarmeta.none"])
        assertEquals(PackFormat(34), store.mc.single { it.label == "1.2" }.rpFormat)
    }

    @Test
    fun insertConflict_failed() = runTest {
        val http = mini.install(FakeHttp(dir))
        val recording = RecordingStore()
        val store = object : CollectorStore by recording {
            override suspend fun insertMcVersions(rows: List<NewMcVersion>): McInsertResult = McInsertResult.Conflict("label 1.0")
        }
        val ctx = CollectContext(RunMode.ONCE, http, store, settings(JarMetaMode.RELEASES), NO_ANALYZER, FixedClock())

        val report = MojangSource().collect(ctx)

        assertEquals(SourceStatus.FAILED, report.status)
        assertTrue(report.error?.contains("label 1.0") == true, report.error)
        assertTrue(recording.mc.isEmpty())
        assertNull(recording.state[MANIFEST_STATE_KEY])
        assertTrue(recording.state.keys.none { it.startsWith("mojang.version.") })
        assertTrue(http.requests.none { it.method == "RANGE" })
    }

    @Test
    fun urlUsedVerbatim_percentEncoded() = runTest {
        val http = mini.install(FakeHttp(dir))
        val store = RecordingStore()

        val report = MojangSource().collect(testContext(http, store, settings()))

        assertEquals(SourceStatus.OK, report.status)
        val encoded = "https://meta.test/v1/packages/1.1.5%20Pre-Release%201.json"
        assertTrue(http.requests.any { it.method == "GET" && it.url == encoded }, http.requests.map { it.url }.toString())
        assertEquals(1012, ordinals(store)["1.1.5 Pre-Release 1"])
    }

    @Test
    fun skips_nonOverflowReasons_warnPerLabel() = runTest {
        val http = mini.install(FakeHttp(dir))
        val store = RecordingStore()
        // s2 가 먼저 발급된 DB → 늦게 온 s1 은 OUT_OF_ORDER (SCP-19: 라벨마다 경고)
        store.mc += McRow(1, "1.0", McOrdinal(1000), Instant.parse("2020-01-01T00:00:00Z"), false, 8, 8, null, null, null, null, null)
        store.mc += McRow(2, "s2", McOrdinal(1001), Instant.parse("2020-01-03T00:00:00Z"), true, 8, 8, null, null, null, null, null)

        val report = MojangSource().collect(testContext(http, store, settings()))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertEquals(1L, report.counters["skip.OUT_OF_ORDER_SNAPSHOT"])
        assertTrue("mojang.skip OUT_OF_ORDER_SNAPSHOT s1" in report.warnings, report.warnings.toString())
        assertNull(ordinals(store)["s1"])
        assertEquals(1010, ordinals(store)["1.1"])
        assertTrue(http.requests.none { it.url == mini.jsonUrl("s1") })
    }

    @Test
    fun lateRelease_issued_inversionWarned_ordinalsUnchanged() = runTest {
        // INV-2: s3(2020-02-02) 가 1.1(2020-02-01) 보다 먼저 발급돼 1.0 칸에 있다 → 1.1 은 1010 으로 발급하고 역전을 경고한다
        val http = mini.install(FakeHttp(dir))
        val store = RecordingStore()
        store.mc += McRow(1, "1.0", McOrdinal(1000), Instant.parse("2020-01-01T00:00:00Z"), false, 8, 8, null, null, null, null, null)
        store.mc += McRow(2, "s3", McOrdinal(1001), Instant.parse("2020-02-02T00:00:00Z"), true, 16, 16, null, null, null, null, null)

        val report = MojangSource().collect(testContext(http, store, settings()))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertEquals(1010, ordinals(store)["1.1"])
        assertEquals(1001, ordinals(store)["s3"], "기존 서수는 그대로")
        assertEquals(1L, report.counters["issue.releaseAfterIssuedSnapshots"])
        assertTrue(report.warnings.any { it.startsWith("mojang.inversion 1.1:") && it.endsWith("s3") }, report.warnings.toString())
    }

    @Test
    fun jarMetaNone_stateMirrorsDbFacts_malformedPackVersionNotE1() = runTest {
        // 1.0 client: protocol 은 있지만 pack_version 이 음수, 정상 pack.mcmeta + data/ → E1 로 추측하지 않고 NONE
        val tweaked = MiniMojang { v ->
            if (v.label != "1.0") {
                v
            } else {
                v.copy(
                    client = MiniMojang.jar(
                        "c10",
                        "version.json" to """{"id":"1.0","protocol_version":5,"world_version":7,"pack_version":-1}""",
                        "pack.mcmeta" to """{"pack":{"pack_format":4}}""",
                        "data/minecraft/a.json" to "{}",
                    ),
                )
            }
        }
        val http = tweaked.install(FakeHttp(dir))
        val store = RecordingStore()
        val base = testSettings(dir)
        val settings = base.copy(mojang = base.mojang.copy(manifestUrl = tweaked.manifestUrl, jarMeta = JarMetaMode.RELEASES))

        val report = MojangSource().collect(testContext(http, store, settings))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertEquals(1L, report.counters["jarmeta.none"])
        val r10 = store.mc.single { it.label == "1.0" }
        assertNull(r10.rpFormat)
        assertNull(r10.dpFormat)
        assertNull(r10.protocol)
        // S7: NONE 상태의 rp/dp/protocol 은 DB 와 같이 null
        val s10 = jarMetaState(store, "1.0")
        assertEquals("NONE", s10.status)
        assertEquals("E0", s10.era)
        assertNull(s10.rp)
        assertNull(s10.dp)
        assertNull(s10.protocol)
        assertEquals(7, s10.worldVersion)
    }

    @Test
    fun loop304_rowWithoutVersionState_notCountedDeferred() = runTest {
        val http = mini.install(FakeHttp(dir))
        val store = RecordingStore()
        assertEquals(SourceStatus.OK, MojangSource().collect(testContext(http, store, settings())).status)
        // 예전 실행이 넣은 행처럼 버전 상태가 없다. 매니페스트가 304 이면 이번 사이클에는 jar 위치를 알 수 없다
        store.state.remove(versionStateKey("1.0"))
        val base = settings(JarMetaMode.RELEASES)
        val budgetTwo = base.copy(mojang = base.mojang.copy(maxJarMetaPerCycle = 2))

        val report = MojangSource().collect(testContext(http, store, budgetTwo, mode = RunMode.LOOP))

        assertEquals(1L, report.counters["manifest.notModified"])
        assertEquals(2L, report.counters["jarmeta.found"])
        assertEquals(1L, report.counters["jarmeta.noVersionState"])
        assertNull(report.counters["jarmeta.deferred"], "처리할 수 없는 label 은 예산 뒤로 미룬 것이 아니다")
        assertEquals(SourceStatus.OK, report.status, report.toString())

        // 예산이 실제로 남긴 대상은 여전히 deferred → PARTIAL
        store.state.keys.removeAll { it.startsWith("mojang.jarmeta.") }
        val budgetOne = base.copy(mojang = base.mojang.copy(maxJarMetaPerCycle = 1))
        val partial = MojangSource().collect(testContext(http, store, budgetOne, mode = RunMode.LOOP))
        assertEquals(1L, partial.counters["jarmeta.deferred"])
        assertEquals(SourceStatus.PARTIAL, partial.status)
    }

    @Test
    fun slotOverflow_countedWithOneReportLineListingExamples() = runTest {
        val http = mini.install(FakeHttp(dir))
        val store = RecordingStore()
        // 1.0 구간의 마지막 칸(1009)이 이미 찼다 → s1, s2 는 SLOT_OVERFLOW
        store.mc += McRow(1, "1.0", McOrdinal(1000), Instant.parse("2020-01-01T00:00:00Z"), false, 8, 8, null, null, null, null, null)
        store.mc += McRow(2, "sx", McOrdinal(1009), Instant.parse("2020-01-01T12:00:00Z"), true, 8, 8, null, null, null, null, null)

        val report = MojangSource().collect(testContext(http, store, settings()))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertEquals(2L, report.counters["skip.SLOT_OVERFLOW"])
        val lines = report.warnings.filter { it.contains("SLOT_OVERFLOW") }
        assertEquals(listOf("mojang.skip SLOT_OVERFLOW 2건 (예: s1, s2)"), lines, "라벨마다가 아니라 한 줄")
        assertNull(ordinals(store)["s1"])
        assertEquals(1010, ordinals(store)["1.1"])
    }

    @Test
    fun drift_warnsOnly_neverUpdatesOrdinal() = runTest {
        val http = mini.install(FakeHttp(dir))
        val store = RecordingStore()
        MojangSource().collect(testContext(http, store, settings()))
        // DB 의 releasedAt 이 매니페스트와 달라진 상황
        val i = store.mc.indexOfFirst { it.label == "1.1" }
        store.mc[i] = store.mc[i].copy(releasedAt = Instant.parse("2020-02-01T00:00:01Z"))

        val report = MojangSource().collect(testContext(http, store, settings()))

        assertEquals(1L, report.counters["drift"])
        assertTrue(report.warnings.any { it.startsWith("mojang.drift 1.1") })
        assertEquals(McOrdinal(1010), store.mc.single { it.label == "1.1" }.ordinal)
        assertEquals(oneShot, ordinals(store))
    }
}
