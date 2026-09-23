package kr.decacross.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kr.decacross.daemon.install.Config
import kr.decacross.daemon.install.InstalledServer
import kr.decacross.daemon.process.ServerProcess
import kr.decacross.daemon.process.ShutdownResult
import kr.decacross.daemon.process.shutdown
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CLI 콘솔: 서버 stdout 을 그대로 흘리고, 사용자가 친 줄을 stdin 으로 넘긴다.
 * Ctrl+C → save-all → stop 순서로 정리한다 (불변식 13). 05 단계에서 데몬 Supervisor 가 이 역할을 넘겨받는다.
 */
object ConsoleRunner {
    fun run(server: InstalledServer, stopAfterDone: Boolean) = runBlocking {
        val dir = Paths.get(server.dir)
        val javaExe = Paths.get(server.javaExe)
        println("▶ 기동: ${server.name} (${server.mcLabel} ${server.core.name.lowercase()}-${server.build}, Java $javaExe)")
        val stopping = AtomicBoolean(false)
        coroutineScope {
            val sp = ServerProcess.start(this, dir, javaExe, Config.jvmArgs(server.ramMb), server.coreJar)
            val done = CompletableDeferred<Unit>()

            val hook = Thread {
                if (stopping.compareAndSet(false, true) && sp.isAlive) {
                    println("\n■ 종료 중 (save-all → stop)…")
                    runBlocking { sp.shutdown { println("  · $it") } }
                }
            }
            Runtime.getRuntime().addShutdownHook(hook)

            val printer = launch {
                sp.lines.collect { line ->
                    println(line)
                    if (line.contains("Done (") && line.contains("For help")) done.complete(Unit)
                }
            }
            // 사용자 입력 → 서버 stdin
            val input = launch(Dispatchers.IO) {
                while (sp.isAlive) {
                    val line = readlnOrNull() ?: break
                    if (line.isNotBlank()) runCatching { sp.send(line) }
                }
            }

            if (stopAfterDone) {
                done.await()
                println("✔ 'Done (' 확인 — 정상 종료를 시작합니다")
                stopping.set(true)
                when (val r = sp.shutdown { println("  · $it") }) {
                    is ShutdownResult.Graceful -> println("■ 정상 종료 (exit=${r.exitCode})")
                    is ShutdownResult.Terminated -> println("■ 강제 종료(TERM) (exit=${r.exitCode})")
                    is ShutdownResult.Killed -> println("■ ⚠ 강제 종료(KILL) — 월드 손상 가능")
                }
            } else {
                sp.awaitExit()
                println("■ 서버 종료 (exit=${sp.exitCode})")
            }
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            printer.cancel()
            input.cancel()
        }
    }
}
