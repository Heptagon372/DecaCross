package kr.decacross.cli.testkit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import kr.decacross.cli.ConsoleIo
import kr.decacross.cli.ShutdownControl
import kr.decacross.cli.ShutdownHookRegistrar
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.CoreBuild
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.McVersion
import kr.decacross.daemon.install.InstallManifest
import kr.decacross.daemon.install.InstallTarget
import kr.decacross.daemon.install.LaunchSpec
import kr.decacross.daemon.process.CompiledConsolePatterns
import kr.decacross.daemon.process.InterruptResult
import kr.decacross.daemon.process.ReadyState
import kr.decacross.daemon.process.SendResult
import kr.decacross.daemon.process.ServerProcess
import kr.decacross.daemon.process.ShutdownOutcome
import kr.decacross.daemon.process.ShutdownReport
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Instant

/** 미리 정해진 입력을 주고 출력을 모으는 [ConsoleIo]. 실제 `System.in`·`System.out` 을 건드리지 않는다. */
class RecordingIo(input: String = "") {
    val lines: MutableList<String> = CopyOnWriteArrayList()

    val io: ConsoleIo = ConsoleIo({ ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)) }, Charsets.UTF_8) { line ->
        lines.add(line)
    }

    fun text(): String = lines.joinToString("\n")
}

/** 테스트가 원하는 시점에 줄을 넣거나 입력을 끝낼 수 있는 [ConsoleIo] (프롬프트 순서 검증용). */
class PipedIo : AutoCloseable {
    val lines: MutableList<String> = CopyOnWriteArrayList()
    private val outputStream = PipedOutputStream()
    private val inputStream = PipedInputStream(outputStream, 8192)

    val io: ConsoleIo = ConsoleIo({ inputStream }, Charsets.UTF_8) { line -> lines.add(line) }

    fun writeLine(line: String) {
        outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
        outputStream.flush()
    }

    fun endInput() {
        outputStream.close()
    }

    override fun close() {
        try {
            outputStream.close()
        } catch (e: IOException) {
            // 이미 닫혔다
        }
        try {
            inputStream.close()
        } catch (e: IOException) {
            // 이미 닫혔다
        }
    }
}

/** 셧다운 훅을 실제로 등록하지 않고 붙잡아 둔다 (테스트가 직접 돌려 본다). */
class FakeHooks : ShutdownHookRegistrar {
    val added: MutableList<Thread> = CopyOnWriteArrayList()
    val removed: MutableList<Thread> = CopyOnWriteArrayList()

    override fun add(hook: Thread) {
        added.add(hook)
    }

    override fun remove(hook: Thread) {
        removed.add(hook)
    }
}

/** 프로세스를 띄우지 않는 [ServerProcess]. */
class FakeServerProcess(
    override val pid: Long = 4242L,
    private val readyState: ReadyState = ReadyState.READY,
    /** null 이 아니면 [awaitReady] 가 이 값이 차를 때까지 기다린다 (준비 순서 검증용). */
    private val readySignal: CompletableDeferred<ReadyState>? = null,
) : ServerProcess {
    val sent: MutableList<String> = CopyOnWriteArrayList()
    private val exit = CompletableDeferred<Int>()
    private val alive = AtomicBoolean(true)

    /** true 면 [send] 가 `STDIN_CLOSED` 를 돌려준다. */
    val stdinClosed = AtomicBoolean(false)

    override val isAlive: Boolean get() = alive.get()

    override suspend fun send(line: String, expect: Regex?, timeout: Duration): SendResult {
        if (stdinClosed.get()) return SendResult.STDIN_CLOSED
        sent.add(line)
        return SendResult.SENT
    }

    override suspend fun awaitExit(timeout: Duration): Int? = withTimeoutOrNull(timeout) { exit.await() }

    override suspend fun interrupt(): InterruptResult = InterruptResult.Sent

    override fun kill() {
        finish(137)
    }

    override suspend fun awaitReady(timeout: Duration?): ReadyState = readySignal?.await() ?: readyState

    /** 서버가 [code] 로 끝났다고 알린다. */
    fun finish(code: Int) {
        alive.set(false)
        exit.complete(code)
    }
}

/**
 * 종료 프로토콜을 돌리지 않고 호출만 기록하는 [ShutdownControl].
 *
 * [manualRelease] 가 true 면 [release] 를 부를 때까지 결과가 나오지 않는다 — 호출자가 `await()` 로 **정말 기다리는지**
 * 보려면 이게 필요하다. 바로 완성해 주면 `startUrgent()` 만 부르고 마는 (프로토콜 도중에 JVM 이 죽는) 구현과 구분되지 않는다.
 */
class FakeShutdownControl(
    private val report: ShutdownReport = ShutdownReport(emptyList(), 0, ShutdownOutcome.STOPPED),
    private val manualRelease: Boolean = false,
    /** null 이 아니면 프로토콜이 이 예외로 끝난다 (정리 경로 검사용). */
    private val failure: Throwable? = null,
) : ShutdownControl {
    val calls: MutableList<String> = CopyOnWriteArrayList()
    private val startedBy = AtomicReference<String?>(null)
    private val deferred = CompletableDeferred<ShutdownReport>()

    override val isStarted: Boolean get() = startedBy.get() != null

    /** 프로토콜을 실제로 시작시킨 호출 (`start` 또는 `urgent`). 두 번째 호출부터는 바뀌지 않는다. */
    val startedByKind: String? get() = startedBy.get()

    /** [manualRelease] 일 때 결과를 내놓는다. */
    fun release() {
        deferred.complete(report)
    }

    override fun start(): Deferred<ShutdownReport> = record("start")

    override fun startUrgent(): Deferred<ShutdownReport> = record("urgent")

    private fun record(kind: String): Deferred<ShutdownReport> {
        calls.add(kind)
        startedBy.compareAndSet(null, kind)
        when {
            failure != null -> deferred.completeExceptionally(failure)
            !manualRelease -> deferred.complete(report)
        }
        return deferred
    }
}

/** 테스트 고정값 모음. */
object Fixtures {
    val patterns: CompiledConsolePatterns =
        CompiledConsolePatterns(Regex("""Done \([0-9.]+s\)! For help"""), Regex("Saved the game"))

    val spec: LaunchSpec = LaunchSpec(
        javaPath = "/opt/java/bin/java",
        javaFeature = 21,
        xmsMb = 1024,
        xmxMb = 1024,
        flagProfileId = "aikar-base",
        jvmFlags = listOf("-XX:+UseG1GC", "-XX:+AlwaysPreTouch"),
        jarFileName = "paper-1.21.8.jar",
    )

    val mc: McVersion = McVersion(
        ordinal = McOrdinal(1940),
        label = "1.21.8",
        releasedAt = Instant.parse("2025-07-17T00:00:00Z"),
        javaMin = 21,
        javaRecommended = 21,
        rpFormat = null,
        dpFormat = null,
    )

    val build: CoreBuild = CoreBuild(
        core = CoreKey.PAPER,
        mc = McOrdinal(1940),
        build = "60",
        channel = Channel.STABLE,
        downloadUrl = "https://example.invalid/paper-1.21.8-60.jar",
        sha256 = "0".repeat(64),
        size = 55_312_384L,
    )

    val target: InstallTarget = InstallTarget(mc, build)

    fun manifest(serverName: String = "paper-1.21.8"): InstallManifest = InstallManifest(
        installId = "t-1",
        serverName = serverName,
        createdAt = "2026-09-17T00:00:00Z",
        mcLabel = "1.21.8",
        mcOrdinal = 1940,
        core = "PAPER",
        coreBuild = "60",
        coreChannel = "STABLE",
        files = emptyList(),
    )
}
