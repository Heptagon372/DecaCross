package kr.decacross.collector.run

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kr.decacross.analysis.JarAnalysisResult
import kr.decacross.collector.config.CliOptions
import kr.decacross.collector.config.CliParse
import kr.decacross.collector.config.CollectorSettings
import kr.decacross.collector.config.CompletionThresholds
import kr.decacross.collector.config.parseCli
import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.CollectorSource
import kr.decacross.collector.core.JarAnalyzer
import kr.decacross.collector.core.RunMode
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.core.SourceReport
import kr.decacross.collector.core.SourceStatus
import kr.decacross.collector.http.Http
import kr.decacross.collector.loadRedistributionPolicy
import kr.decacross.collector.store.McInsertResult
import kr.decacross.collector.store.NewMcVersion
import kr.decacross.collector.store.RedistributionPolicy
import kr.decacross.collector.store.TableCounts
import kr.decacross.collector.store.TestPg
import kr.decacross.collector.testkit.FakeHttp
import kr.decacross.collector.testkit.NO_ANALYZER
import kr.decacross.collector.testkit.RecordingStore
import kr.decacross.collector.testkit.countFiles
import kr.decacross.collector.testkit.deleteTree
import kr.decacross.collector.testkit.newTempDir
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.McOrdinal
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

class RunnerTest {
    private val tmp = newTempDir("decacross-runner-")
    private val output = ByteArrayOutputStream()
    private val out = PrintStream(output, true, Charsets.UTF_8)

    @AfterTest
    fun cleanup() = deleteTree(tmp)

    private class FakeSource(
        override val id: SourceId,
        override val dependsOn: Set<SourceId> = emptySet(),
        override val required: Boolean = false,
        override val interval: Duration = 5.minutes,
        val body: suspend (CollectContext) -> SourceReport = { SourceReport(id, SourceStatus.OK, mapOf("n" to 1), emptyList()) },
    ) : CollectorSource {
        override suspend fun collect(ctx: CollectContext): SourceReport = body(ctx)
    }

    private fun options(vararg extra: String): CliOptions {
        val parsed = parseCli(arrayOf("--once", "--temp-dir=${tmp.resolve("collector-tmp")}", *extra), mapOf("LOCALAPPDATA" to tmp.toString()), "Windows 11")
        return assertIs<CliParse.Ok>(parsed, parsed.toString()).options
    }

    private fun loopOptions(vararg extra: String): CliOptions {
        val parsed = parseCli(arrayOf("--loop", "--temp-dir=${tmp.resolve("collector-tmp")}", *extra), mapOf("LOCALAPPDATA" to tmp.toString()), "Windows 11")
        return assertIs<CliParse.Ok>(parsed, parsed.toString()).options
    }

    /** 닫혔는지 관찰할 수 있는 [Http] (Runner 는 AutoCloseable 인 HTTP 만 닫는다). */
    private class ClosingHttp(inner: FakeHttp) :
        Http by inner,
        AutoCloseable {
        val closed = AtomicBoolean(false)

        override fun close() {
            closed.set(true)
        }
    }

    private fun runner(options: CliOptions = options()): Runner = Runner(options, out = out, policyLoader = { null })

    private fun settings(runDir: Path = Files.createDirectories(tmp.resolve("run-test"))): CollectorSettings =
        options().settings.copy(tempDir = runDir, thresholds = CompletionThresholds(0, 0, 0))

    private suspend fun runOnce(sources: List<CollectorSource>, settings: CollectorSettings = settings(), store: RecordingStore = RecordingStore(), runner: Runner = runner()): RunOutcome =
        runner.runOnce(store, FakeHttp(settings.tempDir), NO_ANALYZER, sources, settings, RunMode.ONCE)

    private fun ok(id: SourceId) = SourceReport(id, SourceStatus.OK, emptyMap(), emptyList())

    @Test
    fun once_levels_mojangFirst_thenParallel(): Unit = runBlocking {
        val starts = ConcurrentHashMap<SourceId, ComparableTimeMark>()
        val ends = ConcurrentHashMap<SourceId, ComparableTimeMark>()
        fun timed(id: SourceId, dependsOn: Set<SourceId>, millis: Long) = FakeSource(id, dependsOn) {
            starts[id] = TimeSource.Monotonic.markNow()
            delay(millis)
            ends[id] = TimeSource.Monotonic.markNow()
            ok(id)
        }
        val sources = listOf(
            timed(SourceId.PAPER, setOf(SourceId.MOJANG), 300),
            timed(SourceId.MOJANG, emptySet(), 150),
            timed(SourceId.FOLIA, setOf(SourceId.MOJANG), 300),
        )
        val outcome = runOnce(sources)
        assertEquals(SourceId.MOJANG, outcome.reports.first().source, "Mojang 단계가 먼저 끝난다")
        assertEquals(ExitCode.OK, outcome.exitCode)
        val mojangEnd = ends.getValue(SourceId.MOJANG)
        assertTrue(starts.getValue(SourceId.PAPER) >= mojangEnd && starts.getValue(SourceId.FOLIA) >= mojangEnd, "의존 소스는 Mojang 뒤에 시작")
        assertTrue(starts.getValue(SourceId.FOLIA) < ends.getValue(SourceId.PAPER) && starts.getValue(SourceId.PAPER) < ends.getValue(SourceId.FOLIA), "같은 단계는 병렬")
        assertEquals(setOf(SourceId.MOJANG, SourceId.PAPER, SourceId.FOLIA), outcome.durations.keys)

        // 선택되지 않은 의존은 무시한다
        val levels = Runner.levels(listOf(FakeSource(SourceId.MODRINTH, setOf(SourceId.MOJANG)), FakeSource(SourceId.HANGAR, setOf(SourceId.MOJANG))))
        assertEquals(listOf(listOf(SourceId.MODRINTH, SourceId.HANGAR)), levels.map { l -> l.map { it.id } })
    }

    @Test
    fun once_requiredFailed_exit2(): Unit = runBlocking {
        val required = FakeSource(SourceId.MOJANG, required = true) { SourceReport(SourceId.MOJANG, SourceStatus.FAILED, emptyMap(), emptyList(), "manifest 실패") }
        assertEquals(ExitCode.REQUIRED_FAILED, runOnce(listOf(required)).exitCode)

        val optional = FakeSource(SourceId.PURPUR, required = false) { SourceReport(SourceId.PURPUR, SourceStatus.FAILED, emptyMap(), emptyList(), "x") }
        assertEquals(ExitCode.OK, runOnce(listOf(optional)).exitCode, "필수가 아닌 소스 실패는 종료 코드에 영향 없음")

        val partial = FakeSource(SourceId.PAPER, required = true) { SourceReport(SourceId.PAPER, SourceStatus.PARTIAL, emptyMap(), emptyList()) }
        assertEquals(ExitCode.OK, runOnce(listOf(partial)).exitCode)
    }

    @Test
    fun once_check_unmet_exit3(): Unit = runBlocking {
        val checking = runner(options("--check"))
        val store = RecordingStore()
        val thresholds = settings().copy(thresholds = CompletionThresholds(mcVersions = 1, coreBuilds = 0, content = 0))
        assertEquals(ExitCode.CHECK_UNMET, runOnce(listOf(FakeSource(SourceId.ADOPTIUM)), thresholds, store, checking).exitCode)

        val inserted = store.insertMcVersions(listOf(NewMcVersion("1.21", McOrdinal(1860), Instant.parse("2024-06-13T08:24:03Z"), false, 21, 21, null, null)))
        assertIs<McInsertResult.Inserted>(inserted)
        assertEquals(ExitCode.OK, runOnce(listOf(FakeSource(SourceId.ADOPTIUM)), thresholds, store, checking).exitCode)
        // --check 가 없으면 미달이어도 0
        assertEquals(ExitCode.OK, runOnce(listOf(FakeSource(SourceId.ADOPTIUM)), thresholds.copy(thresholds = CompletionThresholds(999, 999, 999)), store).exitCode)
    }

    @Test
    fun once_sourceThrows_reportedFailed_othersRun(): Unit = runBlocking {
        val sources = listOf(
            FakeSource(SourceId.PAPER) { throw IllegalStateException("터짐 (테스트)") },
            FakeSource(SourceId.FOLIA) {
                delay(100)
                ok(SourceId.FOLIA)
            },
            FakeSource(SourceId.HANGAR) { TODO("WP-C3") },
        )
        val outcome = runOnce(sources)
        val byId = outcome.reports.associateBy { it.source }
        assertEquals(SourceStatus.FAILED, byId.getValue(SourceId.PAPER).status)
        assertTrue(byId.getValue(SourceId.PAPER).error.orEmpty().contains("터짐"))
        assertEquals(SourceStatus.OK, byId.getValue(SourceId.FOLIA).status)
        assertEquals(SourceStatus.FAILED, byId.getValue(SourceId.HANGAR).status)
        assertTrue(byId.getValue(SourceId.HANGAR).error.orEmpty().contains("NotImplementedError"))
        assertEquals(ExitCode.OK, outcome.exitCode)
    }

    @Test
    fun once_sourceStackOverflow_othersComplete(): Unit = runBlocking {
        val sources = listOf(
            FakeSource(SourceId.MODRINTH) {
                delay(30)
                throw StackOverflowError("분석기 폭주 (테스트)")
            },
            FakeSource(SourceId.PURPUR) {
                delay(30)
                throw NoClassDefFoundError("some/Missing")
            },
            FakeSource(SourceId.HANGAR) {
                delay(300)
                ok(SourceId.HANGAR)
            },
        )
        val outcome = runOnce(sources)
        val byId = outcome.reports.associateBy { it.source }
        assertEquals(SourceStatus.FAILED, byId.getValue(SourceId.MODRINTH).status)
        assertEquals(SourceStatus.FAILED, byId.getValue(SourceId.PURPUR).status)
        assertEquals(SourceStatus.OK, byId.getValue(SourceId.HANGAR).status, "형제 소스는 취소되지 않는다")
        assertFalse(outcome.fatal)
    }

    @Test
    fun once_sourceAssertionError_isolated_othersComplete(): Unit = runBlocking {
        // 회귀 (C1-R2): Exception·LinkageError·StackOverflowError 외의 Error 가 새면 형제 소스가 취소되고 보고서가 없다
        val sources = listOf(
            FakeSource(SourceId.MODRINTH) {
                delay(30)
                throw AssertionError("검증 실패 (테스트)")
            },
            FakeSource(SourceId.HANGAR) {
                delay(300)
                ok(SourceId.HANGAR)
            },
        )
        val outcome = runOnce(sources)
        val byId = outcome.reports.associateBy { it.source }
        assertEquals(SourceStatus.FAILED, byId.getValue(SourceId.MODRINTH).status)
        assertTrue(byId.getValue(SourceId.MODRINTH).error.orEmpty().contains("AssertionError"), byId.getValue(SourceId.MODRINTH).error)
        assertEquals(SourceStatus.OK, byId.getValue(SourceId.HANGAR).status, "형제 소스는 취소되지 않는다")
        assertFalse(outcome.fatal)
        assertEquals(ExitCode.OK, outcome.exitCode)
    }

    @Test
    fun loop_sourceAssertionError_failedDueRespected_notRelaunchedEveryTick(): Unit = runBlocking {
        // 회귀 (C1-R2): --loop 에서 Error 가 새면 due 가 갱신되지 않아 매 틱 다시 띄워진다 (FAILED 재시도 규칙 우회)
        val clock = object : Clock {
            @Volatile
            var now: Instant = Clock.System.now()

            override fun now(): Instant = now
        }
        val runs = AtomicInteger()
        val source = FakeSource(SourceId.PURPUR, interval = 1.hours) {
            if (runs.incrementAndGet() == 1) ok(SourceId.PURPUR) else throw AssertionError("두 번째 실행 실패 (테스트)")
        }
        val s = settings()
        val job = launch(Dispatchers.Default) { runner().loopBody(RecordingStore(), FakeHttp(s.tempDir), NO_ANALYZER, listOf(source), s, clock, tick = 20.milliseconds) }
        withTimeout(10.seconds) { while (runs.get() < 1) delay(10) }
        // 주기가 지나 두 번째 실행 → AssertionError → FAILED 기한(end + 10분) 전에는 다시 띄우지 않는다
        clock.now += 2.hours
        withTimeout(10.seconds) { while (runs.get() < 2) delay(10) }
        delay(300)
        job.cancelAndJoin()
        assertEquals(2, runs.get(), "FAILED 소스가 틱마다 다시 실행됐다")
    }

    @Test
    fun once_outOfMemory_fatal_stopsAfterLevel(): Unit = runBlocking {
        val ran = AtomicInteger()
        val sources = listOf(
            FakeSource(SourceId.MOJANG) { throw OutOfMemoryError("테스트") },
            FakeSource(SourceId.ADOPTIUM) {
                ran.incrementAndGet()
                ok(SourceId.ADOPTIUM)
            },
            FakeSource(SourceId.PAPER, setOf(SourceId.MOJANG)) {
                ran.incrementAndGet()
                ok(SourceId.PAPER)
            },
        )
        val outcome = runOnce(sources)
        assertTrue(outcome.fatal)
        assertEquals(ExitCode.FATAL, outcome.exitCode)
        assertEquals(1, ran.get(), "같은 단계는 끝까지, 다음 단계는 실행하지 않는다")
    }

    @Test
    fun once_sourceOwnWithTimeout_reportedFailed_notPropagated(): Unit = runBlocking {
        val sources = listOf(
            FakeSource(SourceId.FOLIA) {
                withTimeout(20.milliseconds) { delay(10.seconds) }
                ok(SourceId.FOLIA)
            },
            FakeSource(SourceId.PAPER) {
                delay(100)
                ok(SourceId.PAPER)
            },
        )
        val outcome = runOnce(sources)
        val byId = outcome.reports.associateBy { it.source }
        assertEquals(SourceStatus.FAILED, byId.getValue(SourceId.FOLIA).status)
        assertTrue(byId.getValue(SourceId.FOLIA).error.orEmpty().contains("Timed out"), byId.getValue(SourceId.FOLIA).error)
        assertEquals(SourceStatus.OK, byId.getValue(SourceId.PAPER).status)
    }

    @Test
    fun once_timeout_failed(): Unit = runBlocking {
        val s = settings().copy(sourceTimeout = 200.milliseconds)
        val started = TimeSource.Monotonic.markNow()
        val hanging = FakeSource(SourceId.ADOPTIUM) {
            delay(1.hours)
            ok(SourceId.ADOPTIUM)
        }
        val outcome = runOnce(listOf(hanging, FakeSource(SourceId.PURPUR)), s)
        val byId = outcome.reports.associateBy { it.source }
        assertEquals(SourceStatus.FAILED, byId.getValue(SourceId.ADOPTIUM).status)
        assertEquals("timeout", byId.getValue(SourceId.ADOPTIUM).error)
        assertEquals(SourceStatus.OK, byId.getValue(SourceId.PURPUR).status)
        assertTrue(started.elapsedNow() < 10.seconds)
    }

    @Test
    fun loop_nextDue_rules() {
        val end = Instant.parse("2026-09-17T10:00:00Z")
        assertEquals(end + 5.minutes, Runner.nextDue(end, 5.minutes, SourceStatus.OK))
        assertEquals(end + 6.hours, Runner.nextDue(end, 6.hours, SourceStatus.PARTIAL))
        assertEquals(end + 6.hours, Runner.nextDue(end, 6.hours, SourceStatus.SKIPPED))
        assertEquals(end + 10.minutes, Runner.nextDue(end, 6.hours, SourceStatus.FAILED), "실패는 최대 10분 뒤 재시도")
        assertEquals(end + 5.minutes, Runner.nextDue(end, 5.minutes, SourceStatus.FAILED), "주기가 더 짧으면 주기대로")
    }

    @Test
    fun loop_launchesDueSources_neverConcurrently(): Unit = runBlocking {
        val runs = AtomicInteger()
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val mojangRuns = AtomicInteger()
        val source = FakeSource(SourceId.PAPER, setOf(SourceId.MOJANG), interval = 50.milliseconds) {
            val now = running.incrementAndGet()
            maxRunning.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            runs.incrementAndGet()
            delay(120)
            running.decrementAndGet()
            ok(SourceId.PAPER)
        }
        val mojang = FakeSource(SourceId.MOJANG, interval = 1.hours) {
            mojangRuns.incrementAndGet()
            ok(SourceId.MOJANG)
        }
        val s = settings()
        Files.write(s.tempDir.resolve("dl-stale.part"), byteArrayOf(1))
        val r = runner()
        val job = launch(Dispatchers.Default) { r.loopBody(RecordingStore(), FakeHttp(s.tempDir), NO_ANALYZER, listOf(mojang, source), s, tick = 20.milliseconds) }
        withTimeout(10.seconds) { while (runs.get() < 4) delay(20) }
        job.cancelAndJoin()
        assertEquals(1, maxRunning.get(), "같은 소스를 동시에 두 번 띄우지 않는다")
        assertEquals(1, mojangRuns.get(), "주기가 안 된 소스는 다시 돌지 않는다")
        assertEquals(0, countFiles(s.tempDir), "사이클 시작 청소")
        assertTrue(output.toString(Charsets.UTF_8).contains("PAPER"))
    }

    @Test
    fun tempDirs_deadRunDirsRemoved_liveKept() {
        val root = Files.createDirectories(tmp.resolve("collector-tmp"))
        fun runDir(pid: Long, vararg files: String): Path {
            val d = Files.createDirectories(root.resolve("run-$pid"))
            files.forEach { Files.write(d.resolve(it), byteArrayOf(1, 2, 3)) }
            return d
        }
        val dead = runDir(300, "dl-1.part", "dl-2.part")
        val alive = runDir(200, "dl-3.part")
        val deadWithOther = runDir(400, "dl-4.part", "notes.txt")
        Files.write(root.resolve("dl-orphan.part"), byteArrayOf(1))
        val unrelated = Files.createDirectories(root.resolve("run-abc"))

        assertEquals(listOf("dl-orphan.part", "dl-1.part", "dl-2.part", "dl-4.part").sorted(), TempDirs.leftoverFiles(root, pid = 100, isAlive = { it == 200L }).map { it.fileName.toString() }.sorted())

        val prepared = TempDirs.prepare(root, pid = 100, isAlive = { it == 200L })
        assertEquals(root.resolve("run-100"), prepared.runDir)
        assertTrue(Files.isDirectory(prepared.runDir))
        assertFalse(Files.exists(dead), "죽은 실행 디렉터리는 지운다")
        assertTrue(Files.exists(alive.resolve("dl-3.part")), "살아 있는 실행은 건드리지 않는다")
        assertFalse(Files.exists(deadWithOther.resolve("dl-4.part")))
        assertTrue(Files.exists(deadWithOther.resolve("notes.txt")), "dl-* 가 아닌 파일은 남긴다")
        assertFalse(Files.exists(root.resolve("dl-orphan.part")))
        assertTrue(Files.isDirectory(unrelated))
        assertEquals(1, prepared.removedDeadRunDirs)
        assertEquals(4, prepared.removedFiles)
        assertEquals(emptyList(), TempDirs.leftoverFiles(root, pid = 100, isAlive = { it == 200L }))

        TempDirs.cleanupRunDir(prepared.runDir)
        assertFalse(Files.exists(prepared.runDir))
        assertTrue(TempDirs.isProcessAlive(ProcessHandle.current().pid()))
    }

    @Test
    fun runDir_sweptAfterRun(): Unit = runBlocking {
        val s = settings()
        val leaky = FakeSource(SourceId.MODRINTH) { ctx ->
            Files.write(ctx.settings.tempDir.resolve("dl-leaked.part"), byteArrayOf(9))
            ok(SourceId.MODRINTH)
        }
        val outcome = runOnce(listOf(leaky), s)
        assertEquals(1, outcome.leftoverTempFiles)
        assertEquals(0, countFiles(s.tempDir))
    }

    @Test
    fun redistributionPolicy_loadedFromResource_missingOrBrokenIsNull() {
        assertEquals(RedistributionPolicy(enabled = true, allowlist = setOf("MIT", "Apache-2.0")), loadRedistributionPolicy("/c1/license-policy-enabled.json"))
        // 수집기(LicensePolicy.load)와 같은 해석: 문자열이 아닌 원소가 있으면 수집기도 못 쓰는 파일 → null (S9 WARN)
        assertEquals(null, loadRedistributionPolicy("/c1/license-policy-sample.json"))
        assertEquals(null, loadRedistributionPolicy("/c1/no-such-policy.json"))
        assertEquals(null, loadRedistributionPolicy("/c1/sanity-expect-2026-09-17.json"), "키가 없으면 null")
        // 운영 리소스: Phase 1 스위치는 꺼져 있다 (SCP-20)
        val shipped = assertNotNull(loadRedistributionPolicy())
        assertFalse(shipped.enabled)
        assertTrue("MIT" in shipped.allowlist)
    }

    @Test
    fun redistributionPolicy_utf8Bom_stillLoaded() {
        // 회귀 (V-1): BOM 붙은 license-policy.json 이 S9 를 조용히 WARN 으로 떨어뜨리면 안 된다. redistributableEnabled 없음 = false
        val raw = checkNotNull(RunnerTest::class.java.getResourceAsStream("/c1/license-policy-bom.json")) { "테스트 리소스 없음" }.use { it.readBytes() }
        assertTrue(raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte(), "리소스가 BOM 으로 시작해야 한다")
        assertEquals(RedistributionPolicy(enabled = false, allowlist = setOf("MIT")), loadRedistributionPolicy("/c1/license-policy-bom.json"))
    }

    @Test
    fun lazyAnalyzer_notBuiltUntilUsed() {
        val built = AtomicInteger()
        val lazy = LazyJarAnalyzer {
            built.incrementAndGet()
            JarAnalyzer { JarAnalysisResult.Unreadable("fake") }
        }
        assertEquals(0, built.get())
        assertIs<JarAnalysisResult.Unreadable>(lazy.analyze(tmp.resolve("x.jar")))
        lazy.analyze(tmp.resolve("y.jar"))
        assertEquals(1, built.get())
    }

    @Test
    fun completionReport_render() {
        val counts = TableCounts(539, 103, 436, 5727, mapOf(CoreKey.FOLIA to 122L, CoreKey.PAPER to 5599L, CoreKey.PURPUR to 6L), 200, 7412, 181, 900, 46)
        val reports = listOf(
            SourceReport(SourceId.MOJANG, SourceStatus.OK, linkedMapOf("issue.inserted" to 539L, "skip.SLOT_OVERFLOW" to 315L), emptyList()),
            SourceReport(SourceId.ADOPTIUM, SourceStatus.FAILED, emptyMap(), listOf("경고 하나"), "kotlin.NotImplementedError: WP-C2"),
        )
        val text = CompletionReport.render(reports, counts, CompletionThresholds(), mapOf(SourceId.MOJANG to 12.minutes + 3.seconds))
        val lines = text.lines()
        assertEquals("== 수집 결과 ==", lines[0])
        assertEquals("MOJANG    OK       12m03s  issue.inserted=539 skip.SLOT_OVERFLOW=315", lines[1])
        assertTrue(lines[2].startsWith("ADOPTIUM  FAILED        -  "), lines[2])
        assertTrue(text.contains("오류: kotlin.NotImplementedError: WP-C2"))
        assertTrue(text.contains("경고: 경고 하나"))
        assertTrue(text.contains("mc_versions 539 (release 103, snapshot 436) · core_builds 5727 (PAPER 5599, PURPUR 6, FOLIA 122) · content 200"))
        assertTrue(text.contains("mc_versions   539 ≥ 200  OK"), text)
        assertTrue(text.contains("core_builds  5727 ≥ 500  OK"), text)
        assertTrue(text.contains("content       200 ≥ 100  OK"), text)
        val unmet = CompletionReport.render(emptyList(), counts.copy(content = 3), CompletionThresholds(), emptyMap())
        assertTrue(unmet.contains("content         3 < 100  미달"), unmet)
        assertEquals("5s", CompletionReport.formatDuration(5.seconds))
        assertEquals("1m10s", CompletionReport.formatDuration(70.seconds))
        assertEquals("1h02m", CompletionReport.formatDuration(62.minutes))
    }

    @Test
    fun run_loop_cancelled_sourceCancelled_resourcesClosed_runDirCleaned_noRelaunch(): Unit = runBlocking {
        // SC-3: 실제 --loop 진입점(Runner.run → runLoop)을 취소했을 때. 첫 사이클은 끝나고, 틱 루프가 다시 띄운 실행 도중 취소한다
        TestPg.resetSchema()
        val url = TestPg.target.jdbcUrl
        val launches = AtomicInteger()
        val inFlightCancelled = CompletableDeferred<Unit>()
        val contexts = CopyOnWriteArrayList<CollectContext>()
        val source = FakeSource(SourceId.ADOPTIUM, interval = 1.milliseconds) { ctx ->
            contexts += ctx
            if (launches.incrementAndGet() == 1) {
                ok(SourceId.ADOPTIUM)
            } else {
                Files.write(ctx.settings.tempDir.resolve("dl-inflight.part"), byteArrayOf(1))
                try {
                    awaitCancellation()
                } finally {
                    inFlightCancelled.complete(Unit)
                }
            }
        }
        val https = CopyOnWriteArrayList<ClosingHttp>()
        val opts = loopOptions("--db-url=$url", "--migrate", "--sources=adoptium")
        val runner = Runner(opts, sourcesFactory = { listOf(source) }, httpFactory = { s -> ClosingHttp(FakeHttp(s.tempDir)).also { https += it } }, analyzerFactory = { NO_ANALYZER }, out = out, policyLoader = { null })
        val job = launch(Dispatchers.Default) { runner.run() }

        withTimeout(60.seconds) { while (launches.get() < 2 || contexts.size < 2) delay(20) }
        val runDir = contexts.last().settings.tempDir
        assertEquals(RunMode.LOOP, contexts.last().mode)
        assertTrue(Files.exists(runDir.resolve("dl-inflight.part")), "진행 중 실행의 임시 파일")
        assertTrue(output.toString(Charsets.UTF_8).contains("== 수집 결과 =="), "첫 사이클 보고서")

        withTimeout(30.seconds) { job.cancelAndJoin() }
        assertTrue(inFlightCancelled.isCompleted, "진행 중인 소스가 취소된다")
        assertFalse(Files.exists(runDir), "실행 디렉터리(임시 파일 포함)를 치운다")
        assertTrue(https.single().closed.get(), "HTTP 클라이언트를 닫는다")
        // 저장소를 닫았다: 소스가 받은 저장소는 더 쓸 수 없고, 실행 락이 풀려 다른 수집기가 잡을 수 있다
        assertFailsWith<IllegalStateException> { contexts.last().store.counts() }
        // 연결 종료 후 서버가 세션 락을 푸는 것은 비동기라 잠깐 기다린다
        val relocked = TestPg.openStore(isDevDatabase = false).use { other ->
            withTimeoutOrNull(10.seconds) {
                while (!other.tryAcquireRunLock()) delay(50)
                true
            }
        }
        assertEquals(true, relocked, "실행 락이 풀렸다")
        delay(300)
        assertEquals(2, launches.get(), "취소 뒤에는 소스를 다시 띄우지 않는다")
        assertFalse(output.toString(Charsets.UTF_8).contains("종료 코드"), "취소는 종료 코드 줄 없이 호출자에게 전파된다")
    }

    @Test
    fun run_externalDb_migrate_lock_report_exitCodes(): Unit = runBlocking {
        TestPg.resetSchema()
        val url = TestPg.target.jdbcUrl
        val calls = CopyOnWriteArrayList<CollectContext>()
        val source = FakeSource(SourceId.ADOPTIUM) { ctx ->
            calls += ctx
            ok(SourceId.ADOPTIUM)
        }
        val fake = { s: CollectorSettings -> FakeHttp(s.tempDir) }
        val opts = options("--db-url=$url", "--migrate", "--sources=adoptium,mojang")
        val code = Runner(opts, sourcesFactory = { listOf(source) }, httpFactory = fake, analyzerFactory = { NO_ANALYZER }, out = out, policyLoader = { null }).run()
        assertEquals(ExitCode.OK, code, output.toString(Charsets.UTF_8))
        val ctx = calls.single()
        assertEquals(RunMode.ONCE, ctx.mode)
        assertFalse(ctx.store.isDevDatabase)
        assertFalse(ctx.settings.mojang.allowInitialSeed, "외부 DB 는 --seed-ordinals 없이 시딩 허용 안 함")
        assertTrue(ctx.settings.tempDir.fileName.toString().startsWith("run-"), "소스는 실행 디렉터리를 받는다: ${ctx.settings.tempDir}")
        assertFalse(Files.exists(ctx.settings.tempDir), "끝나면 실행 디렉터리를 치운다")
        val text = output.toString(Charsets.UTF_8)
        assertTrue(text.contains("== 수집 결과 =="), text)
        assertTrue(text.trimEnd().endsWith("종료 코드 0"), text)

        // 다른 수집기가 락을 쥐고 있으면 4
        TestPg.openStore(isDevDatabase = false).use { holder ->
            assertTrue(holder.tryAcquireRunLock())
            val locked = Runner(options("--db-url=$url"), sourcesFactory = { listOf(source) }, httpFactory = fake, out = out, policyLoader = { null }).run()
            assertEquals(ExitCode.RUN_LOCKED, locked)
        }

        // --sanity: 완료 기준 미달(S1) → 5, --sanity-only --check → 3
        val sanity = Runner(options("--db-url=$url", "--sanity"), sourcesFactory = { listOf(source) }, httpFactory = fake, out = out, policyLoader = { null }).run()
        assertEquals(ExitCode.SANITY_FAILED, sanity)
        assertTrue(output.toString(Charsets.UTF_8).contains("== 정합성 검사 =="))
        val sanityOnly = Runner(options("--db-url=$url", "--sanity-only", "--check"), sourcesFactory = { error("수집하면 안 된다") }, httpFactory = fake, out = out, policyLoader = { null }).run()
        assertEquals(ExitCode.CHECK_UNMET, sanityOnly)

        // 스키마가 없는 DB + --migrate 없음 → 1
        TestPg.resetSchema()
        val noSchema = Runner(options("--db-url=$url"), sourcesFactory = { listOf(source) }, httpFactory = fake, out = out, policyLoader = { null }).run()
        assertEquals(ExitCode.FATAL, noSchema)
        assertNotNull(output.toString(Charsets.UTF_8).lines().firstOrNull { it.contains("마이그레이션 미적용") })
    }
}
