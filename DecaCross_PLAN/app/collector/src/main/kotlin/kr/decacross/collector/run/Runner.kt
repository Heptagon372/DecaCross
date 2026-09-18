package kr.decacross.collector.run

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kr.decacross.analysis.JarAnalysisResult
import kr.decacross.collector.config.CliOptions
import kr.decacross.collector.config.CollectorSettings
import kr.decacross.collector.content.CapabilityRulesLoader
import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.CollectorSource
import kr.decacross.collector.core.JarAnalyzer
import kr.decacross.collector.core.JvmJarAnalyzer
import kr.decacross.collector.core.RunMode
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.core.SourceReport
import kr.decacross.collector.core.SourceStatus
import kr.decacross.collector.core.allSources
import kr.decacross.collector.http.Http
import kr.decacross.collector.http.KtorHttp
import kr.decacross.collector.loadRedistributionPolicy
import kr.decacross.collector.store.CollectorStore
import kr.decacross.collector.store.DbTarget
import kr.decacross.collector.store.DevDatabase
import kr.decacross.collector.store.MigrationResult
import kr.decacross.collector.store.MigrationRunner
import kr.decacross.collector.store.PgCollectorStore
import kr.decacross.collector.store.RedistributionPolicy
import kr.decacross.collector.store.SanityExpect
import kr.decacross.collector.store.SanityLevel
import kr.decacross.collector.store.TableCounts
import kr.decacross.collector.store.runSanity
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/** 수집기 종료 코드. */
object ExitCode {
    const val OK: Int = 0

    /** 설정·DB 치명 오류 (소스의 OutOfMemoryError 포함) */
    const val FATAL: Int = 1

    /** `required` 소스가 FAILED */
    const val REQUIRED_FAILED: Int = 2

    /** `--check` 인데 02 완료 기준 미달 */
    const val CHECK_UNMET: Int = 3

    /** 다른 수집기가 실행 락을 쥐고 있음 */
    const val RUN_LOCKED: Int = 4

    /** `--sanity` FAIL */
    const val SANITY_FAILED: Int = 5
}

/** [Runner.runOnce] 결과. */
internal data class RunOutcome(
    val reports: List<SourceReport>,
    val durations: Map<SourceId, Duration>,
    val finishedAt: Map<SourceId, Instant>,
    val counts: TableCounts,
    val exitCode: Int,
    /** OutOfMemoryError 로 실행을 멈췄다 */
    val fatal: Boolean,
    /** 마지막 단계 뒤 실행 디렉터리에서 치운 `dl-*` 파일 수 (`tempdir.leftover`) */
    val leftoverTempFiles: Int,
)

/**
 * 첫 `analyze` 호출 때 실제 분석기를 만든다.
 * `CapabilityRulesLoader.load()` 를 기동 시점에 부르지 않는다 — jar 를 받지 않는 소스만 돌 때 필요 없고, 실패해도 그 소스만 실패한다.
 */
internal class LazyJarAnalyzer(factory: () -> JarAnalyzer) : JarAnalyzer {
    private val delegate: JarAnalyzer by lazy(factory)

    override fun analyze(jar: Path): JarAnalysisResult = delegate.analyze(jar)
}

/**
 * 수집 실행기: DB 준비(개발 DB·마이그레이션·스키마 확인) → 실행 락 → 임시 디렉터리 → 소스 실행 → 보고서.
 *
 * # 불변식
 * - 한 소스의 실패(예외·`Error`·타임아웃)는 다른 소스를 취소하지 않는다 (단계마다 `supervisorScope`).
 * - 실행 자체가 취소될 때만 [CancellationException] 을 다시 던진다. 소스 안의 `withTimeout` 이 낸 취소 예외는 FAILED 로 보고한다.
 * - `OutOfMemoryError` 는 FAILED + 치명: 현재 단계가 끝나면 종료 코드 1.
 * - 소스·HTTP 에는 실행별 임시 디렉터리 `run-<pid>` 로 바꾼 설정을 넘긴다.
 * - 마지막 보고 줄은 `종료 코드 <n>` 이다 (`gradlew run` 은 0 이 아닌 코드를 모두 1 로 바꾸므로).
 */
class Runner(
    private val options: CliOptions,
    private val sourcesFactory: () -> List<CollectorSource> = ::allSources,
    private val httpFactory: (CollectorSettings) -> Http = { s -> KtorHttp(s) },
    private val analyzerFactory: () -> JarAnalyzer = { JvmJarAnalyzer(CapabilityRulesLoader.load()) },
    private val out: PrintStream = System.out,
    private val policyLoader: () -> RedistributionPolicy? = { loadRedistributionPolicy() },
) {
    /** 실행 중 연 자원. 종료 훅과 정상 종료가 함께 닫아도 한 번만 닫힌다. */
    private class Resources : AutoCloseable {
        private val closed = AtomicBoolean(false)

        @Volatile var devDb: DevDatabase? = null

        @Volatile var store: PgCollectorStore? = null

        @Volatile var http: Http? = null

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            (http as? AutoCloseable)?.let { closeQuietly("HTTP", it) }
            store?.let { closeQuietly("DB 연결", it) }
            devDb?.let { closeQuietly("개발 DB", it) }
        }

        private fun closeQuietly(what: String, c: AutoCloseable) {
            try {
                c.close()
            } catch (e: Exception) {
                log.warn("{} 닫기 실패: {}", what, e.toString())
            }
        }
    }

    /** 전체 실행. 종료 코드를 돌려준다 ([ExitCode]). */
    suspend fun run(): Int {
        val resources = Resources()
        val code = try {
            runWith(resources)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLException) {
            out.println("DB 오류: ${e.message}")
            ExitCode.FATAL
        } catch (e: IOException) {
            out.println("I/O 오류: $e")
            ExitCode.FATAL
        } catch (e: IllegalStateException) {
            out.println("실행 불가: ${e.message}")
            ExitCode.FATAL
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { resources.close() }
        }
        out.println("종료 코드 $code")
        return code
    }

    private suspend fun runWith(resources: Resources): Int {
        val embedded = options.db is DbTarget.EmbeddedDev
        val url = when (val db = options.db) {
            is DbTarget.Url -> db

            is DbTarget.EmbeddedDev -> {
                log.warn("DB URL 이 없어 임베디드 개발 DB 를 쓴다: {} (port {})", db.root, db.port)
                val dev = withContext(Dispatchers.IO) { DevDatabase.start(db.root, db.port) }
                resources.devDb = dev
                DbTarget.Url(dev.jdbcUrl, null, null)
            }
        }

        if (!options.sanityOnly && (embedded || options.migrate)) {
            val dir = MigrationRunner.locate()
            if (dir == null) {
                out.println("마이그레이션 디렉터리를 찾을 수 없다 (-D${MigrationRunner.SYSTEM_PROPERTY} 또는 ${MigrationRunner.ENV_VAR})")
                return ExitCode.FATAL
            }
            val result = withContext(Dispatchers.IO) {
                PgCollectorStore.connect(url).use { MigrationRunner(dir).migrate(it, allowOutOfOrder = embedded) }
            }
            when (result) {
                is MigrationResult.Failed -> {
                    out.println("마이그레이션 실패: ${result.reason}")
                    return ExitCode.FATAL
                }

                is MigrationResult.Applied -> if (result.files.isNotEmpty()) log.info("마이그레이션 적용 {}개: {}", result.files.size, result.files)
            }
        }

        val store = withContext(Dispatchers.IO) { PgCollectorStore.open(url, isDevDatabase = embedded) }
        resources.store = store

        if (options.sanityOnly) return sanityOnly(store)

        if (!store.tryAcquireRunLock()) {
            out.println("다른 수집기가 실행 중이다 (실행 락 보유 중)")
            return ExitCode.RUN_LOCKED
        }

        val prepared = withContext(Dispatchers.IO) { TempDirs.prepare(options.settings.tempDir) }
        val runDir = prepared.runDir
        val base = options.settings
        val settings = base.copy(
            tempDir = runDir,
            mojang = if (embedded) base.mojang.copy(allowInitialSeed = true) else base.mojang,
        )
        val http = httpFactory(settings)
        resources.http = http
        val analyzer = LazyJarAnalyzer(analyzerFactory)
        val sources = sourcesFactory().filter { it.id in options.sources }

        return when (options.mode) {
            RunMode.ONCE -> {
                val hook = Thread({ TempDirs.cleanupRunDir(runDir) }, "decacross-collector-tempdir")
                val registered = addShutdownHook(hook)
                try {
                    val outcome = runOnce(store, http, analyzer, sources, settings, RunMode.ONCE)
                    out.println(CompletionReport.render(outcome.reports, outcome.counts, settings.thresholds, outcome.durations))
                    var code = outcome.exitCode
                    if (options.sanity) {
                        val sanityCode = sanity(store) ?: return ExitCode.FATAL
                        if (code == ExitCode.OK) code = sanityCode
                    }
                    code
                } finally {
                    if (registered) removeShutdownHook(hook)
                    TempDirs.cleanupRunDir(runDir)
                }
            }

            RunMode.LOOP -> runLoop(resources, store, http, analyzer, sources, settings, runDir)
        }
    }

    private suspend fun sanityOnly(store: PgCollectorStore): Int {
        val counts = store.counts()
        out.println("== 테이블 ==")
        out.println(CompletionReport.tablesLine(counts))
        out.println("== 02 완료 판정 ==")
        CompletionReport.thresholdLines(counts, options.settings.thresholds).forEach(out::println)
        val sanityCode = sanity(store) ?: return ExitCode.FATAL
        return when {
            options.check && !CompletionReport.thresholdsMet(counts, options.settings.thresholds) -> ExitCode.CHECK_UNMET
            else -> sanityCode
        }
    }

    /** 정합성 검사를 출력하고 FAIL 이 있으면 5, 없으면 0. 기대값 파일을 읽지 못하면 null (치명). */
    private suspend fun sanity(store: PgCollectorStore): Int? {
        val expect = options.sanityExpect?.let { path ->
            try {
                SanityExpect.parse(withContext(Dispatchers.IO) { Files.readString(path) })
            } catch (e: IOException) {
                out.println("--sanity-expect 파일을 읽을 수 없다: $path ($e)")
                return null
            } catch (e: IllegalArgumentException) {
                out.println("--sanity-expect 형식 오류: $path (${e.message})")
                return null
            }
        }
        val results = runSanity(store, policyLoader(), options.settings.tempDir, expect, options.settings.thresholds)
        out.println(CompletionReport.renderSanity(results))
        return if (results.any { it.level == SanityLevel.FAIL }) ExitCode.SANITY_FAILED else ExitCode.OK
    }

    // ── --once ─────────────────────────────────────────────────────────────

    /**
     * 선택된 소스를 `dependsOn` 위상 단계로 실행한다 (단계는 순차, 단계 안은 병렬). 테스트 이음새.
     * DB 준비·락·임시 디렉터리는 [run] 이 이미 했다고 가정한다 ([settings] 의 tempDir 은 실행 디렉터리).
     */
    internal suspend fun runOnce(
        store: CollectorStore,
        http: Http,
        analyzer: JarAnalyzer,
        sources: List<CollectorSource>,
        settings: CollectorSettings,
        mode: RunMode,
    ): RunOutcome {
        val fatal = AtomicBoolean(false)
        val reports = ArrayList<SourceReport>()
        val durations = LinkedHashMap<SourceId, Duration>()
        val finishedAt = LinkedHashMap<SourceId, Instant>()
        for (level in levels(sources)) {
            val results = supervisorScope {
                level.map { source ->
                    async {
                        val mark = TimeSource.Monotonic.markNow()
                        val ctx = CollectContext(mode, http, store, settings, analyzer)
                        val report = runSourceSafely(source, ctx, fatal)
                        Triple(report, mark.elapsedNow(), Clock.System.now())
                    }
                }.awaitAll()
            }
            for ((report, took, at) in results) {
                reports += report
                durations[report.source] = took
                finishedAt[report.source] = at
            }
            if (fatal.get()) {
                log.error("OutOfMemoryError — 남은 단계를 실행하지 않는다")
                break
            }
        }
        val leftover = withContext(Dispatchers.IO) { TempDirs.sweepPartFiles(settings.tempDir) }
        if (leftover > 0) log.warn("tempdir.leftover={} — 실행 디렉터리에 남은 임시 파일을 지웠다 ({})", leftover, settings.tempDir)
        val counts = store.counts()
        val requiredIds = sources.filter { it.required }.mapTo(HashSet()) { it.id }
        val code = when {
            fatal.get() -> ExitCode.FATAL
            reports.any { it.status == SourceStatus.FAILED && it.source in requiredIds } -> ExitCode.REQUIRED_FAILED
            options.check && !CompletionReport.thresholdsMet(counts, settings.thresholds) -> ExitCode.CHECK_UNMET
            else -> ExitCode.OK
        }
        return RunOutcome(reports, durations, finishedAt, counts, code, fatal.get(), leftover)
    }

    /** 소스 하나를 타임아웃·실패 격리 안에서 실행한다. 절대 형제 소스를 취소시키지 않는다. */
    private suspend fun runSourceSafely(source: CollectorSource, ctx: CollectContext, fatal: AtomicBoolean): SourceReport {
        val report = try {
            withTimeoutOrNull(ctx.settings.sourceTimeout) { source.collect(ctx) }
                ?: failed(source.id, "timeout")
        } catch (e: CancellationException) {
            // 실행 자체가 취소됐으면 전파, 소스 자신의 withTimeout 등이면 FAILED
            if (!currentCoroutineContext().isActive) throw e
            failed(source.id, e.toString())
        } catch (e: OutOfMemoryError) {
            fatal.set(true)
            failed(source.id, e.toString())
        } catch (e: Exception) {
            failed(source.id, e.toString())
        } catch (e: Error) {
            // NotImplementedError·LinkageError·StackOverflowError·AssertionError 등 OOM 외의 Error 도 이 소스만 FAILED.
            // 새어 나가면 --once 는 형제 소스가 취소되고 보고서가 없으며, --loop 는 due 가 갱신되지 않아 매 틱 재실행된다.
            failed(source.id, e.toString())
        }
        if (report.status == SourceStatus.FAILED) log.warn("{} FAILED: {}", source.id, report.error)
        return if (report.source == source.id) report else report.copy(source = source.id)
    }

    private fun failed(id: SourceId, error: String): SourceReport = SourceReport(id, SourceStatus.FAILED, emptyMap(), emptyList(), error)

    // ── --loop ─────────────────────────────────────────────────────────────

    private suspend fun runLoop(
        resources: Resources,
        store: CollectorStore,
        http: Http,
        analyzer: JarAnalyzer,
        sources: List<CollectorSource>,
        settings: CollectorSettings,
        runDir: Path,
    ): Int = coroutineScope {
        val exit = AtomicInteger(ExitCode.OK)
        val loop = launch { exit.set(loopBody(store, http, analyzer, sources, settings)) }
        // SIGINT: 루프 취소 → 최대 30 초 대기 → 실행 디렉터리 청소 → DB·개발 DB 닫기
        val hook = Thread({
            loop.cancel(CancellationException("종료 신호"))
            runBlocking { withTimeoutOrNull(SHUTDOWN_WAIT) { loop.join() } }
            TempDirs.cleanupRunDir(runDir)
            resources.close()
        }, "decacross-collector-shutdown")
        val registered = addShutdownHook(hook)
        try {
            loop.join()
        } finally {
            if (registered) removeShutdownHook(hook)
            TempDirs.cleanupRunDir(runDir)
        }
        exit.get()
    }

    /** 첫 사이클은 [runOnce] 와 같고(`dependsOn` 존중), 이후 10 초마다 기한이 된 소스를 띄운다. OOM 이면 1 을 돌려준다. */
    internal suspend fun loopBody(
        store: CollectorStore,
        http: Http,
        analyzer: JarAnalyzer,
        sources: List<CollectorSource>,
        settings: CollectorSettings,
        clock: Clock = Clock.System,
        tick: Duration = LOOP_TICK,
    ): Int {
        val first = runOnce(store, http, analyzer, sources, settings, RunMode.LOOP)
        out.println(CompletionReport.render(first.reports, first.counts, settings.thresholds, first.durations))
        if (first.fatal) return ExitCode.FATAL
        val byId = sources.associateBy { it.id }
        val due = ConcurrentHashMap<SourceId, Instant>()
        for (r in first.reports) {
            val source = byId[r.source] ?: continue
            due[r.source] = nextDue(first.finishedAt[r.source] ?: clock.now(), source.interval, r.status)
        }
        val running = ConcurrentHashMap.newKeySet<SourceId>()
        val fatal = AtomicBoolean(false)
        supervisorScope {
            while (currentCoroutineContext().isActive && !fatal.get()) {
                val now = clock.now()
                val launchable = sources.filter { it.id !in running && now >= (due[it.id] ?: now) }
                if (launchable.isNotEmpty()) {
                    // 실행 중인 소스가 없을 때만 청소한다: 진행 중 다운로드의 임시 파일을 지우지 않게
                    if (running.isEmpty()) {
                        val swept = withContext(Dispatchers.IO) { TempDirs.sweepPartFiles(settings.tempDir) }
                        if (swept > 0) log.warn("사이클 시작 청소: 남은 임시 파일 {}개 삭제", swept)
                    }
                    for (source in launchable) {
                        running += source.id
                        launch {
                            try {
                                val mark = TimeSource.Monotonic.markNow()
                                val ctx = CollectContext(RunMode.LOOP, http, store, settings, analyzer)
                                val report = runSourceSafely(source, ctx, fatal)
                                out.println(CompletionReport.sourceLine(report, mark.elapsedNow()))
                                due[source.id] = nextDue(clock.now(), source.interval, report.status)
                            } finally {
                                running -= source.id
                            }
                        }
                    }
                }
                delay(tick)
            }
        }
        return if (fatal.get()) ExitCode.FATAL else ExitCode.OK
    }

    internal companion object {
        private val log = LoggerFactory.getLogger(Runner::class.java)

        val LOOP_TICK: Duration = 10.seconds
        val FAILED_RETRY_CAP: Duration = 10.minutes
        private val SHUTDOWN_WAIT: Duration = 30.seconds

        /** 다음 실행 시각: 성공(OK/PARTIAL/SKIPPED) → `end + interval`, FAILED → `end + min(interval, 10분)`. */
        fun nextDue(end: Instant, interval: Duration, status: SourceStatus): Instant =
            if (status == SourceStatus.FAILED) end + minOf(interval, FAILED_RETRY_CAP) else end + interval

        /** 선택된 소스만의 `dependsOn` 위상 단계. 선택되지 않은 의존은 무시한다. 순서는 입력 순서를 유지한다. */
        fun levels(sources: List<CollectorSource>): List<List<CollectorSource>> {
            val selected = sources.mapTo(HashSet()) { it.id }
            val remaining = sources.toMutableList()
            val done = HashSet<SourceId>()
            val out = ArrayList<List<CollectorSource>>()
            while (remaining.isNotEmpty()) {
                val ready = remaining.filter { s -> s.dependsOn.all { it !in selected || it in done } }
                if (ready.isEmpty()) {
                    log.warn("dependsOn 순환: {} — 한 단계로 실행한다", remaining.map { it.id })
                    out += remaining.toList()
                    break
                }
                out += ready
                ready.forEach { done += it.id }
                remaining.removeAll(ready)
            }
            return out
        }

        private fun addShutdownHook(hook: Thread): Boolean = try {
            Runtime.getRuntime().addShutdownHook(hook)
            true
        } catch (e: IllegalStateException) {
            false
        }

        private fun removeShutdownHook(hook: Thread) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook)
            } catch (e: IllegalStateException) {
                // 이미 종료 중: 훅이 스스로 정리한다
            }
        }
    }
}
