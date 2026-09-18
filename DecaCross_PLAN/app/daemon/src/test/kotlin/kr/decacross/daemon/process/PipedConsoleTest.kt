package kr.decacross.daemon.process

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [PipedServerProcess] 의 불변식 "기대 패턴 대기를 **쓰기 전에** 건다" (ServerConsole.kt) 를 순서를 고정해 확인한다.
 *
 * 가짜 프로세스는 stdin 에 줄이 들어오면 답 줄과 sentinel 줄을 stdout 으로 밀어 넣고, **펌프가 sentinel 까지 처리한 뒤에야**
 * 쓰기를 끝낸다. 즉 쓰기가 끝난 시점에 답 줄은 이미 흘러갔다 — 쓰고 나서 구독하는 구현이라면 그 줄을 놓쳐 `TIMED_OUT` 이 된다.
 */
class PipedConsoleTest {
    @Test
    fun sendArmsTheWaitBeforeWriting() {
        val fake = EchoProcess()
        val seen = CopyOnWriteArrayList<String>()
        val process: ServerProcess = PipedServerProcess(
            fake,
            ServerFixture.patterns,
            outputLine = { line ->
                seen.add(line)
                if (line.endsWith(EchoProcess.SENTINEL)) fake.sentinelProcessed()
            },
            interrupter = ProcessInterrupter { InterruptResult.Sent },
        )
        try {
            runBlocking {
                assertEquals(
                    SendResult.MATCHED,
                    process.send("say 안녕", Regex("""\[Server] 안녕"""), 10.seconds),
                    "쓰기 전에 대기를 걸었으므로 쓰자마자 나온 줄을 놓치지 않는다",
                )
            }
        } finally {
            fake.destroy()
        }
        assertTrue(seen.any { it.endsWith(EchoProcess.SENTINEL) }, "펌프가 sentinel 까지 읽었다: $seen")
    }
}

/**
 * stdin 에 들어온 줄에 즉시 답하는 가짜 프로세스. 답을 밀어 넣은 뒤 sentinel 이 펌프를 통과할 때까지 쓰기를 붙잡는다.
 * (진짜 프로세스로는 "답이 구독보다 먼저 흘러가는" 순간을 고정할 수 없다.)
 */
private class EchoProcess : Process() {
    private val stdout = LineQueueStream()
    private val pending = ByteArrayOutputStream()
    private val sentinelSignals = LinkedBlockingQueue<Unit>()
    private val ended = CountDownLatch(1)

    private val stdin = object : OutputStream() {
        override fun write(b: Int) {
            if (b == '\n'.code) {
                val line = pending.toString(StandardCharsets.UTF_8)
                pending.reset()
                respond(line)
            } else {
                pending.write(b)
            }
        }
    }

    /** 펌프가 sentinel 줄을 처리했다 (= 그 앞의 답 줄은 이미 방출됐다). */
    fun sentinelProcessed() {
        sentinelSignals.offer(Unit)
    }

    private fun respond(line: String) {
        val text = line.trim()
        if (text.startsWith("say ")) stdout.push("[12:00:00 INFO]: [Server] ${text.substring(4)}")
        stdout.push("[12:00:00 INFO]: $SENTINEL")
        sentinelSignals.poll(10, TimeUnit.SECONDS)
    }

    override fun getOutputStream(): OutputStream = stdin

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int {
        ended.await()
        return 0
    }

    override fun exitValue(): Int = if (ended.count > 0L) throw IllegalThreadStateException() else 0

    override fun isAlive(): Boolean = ended.count > 0L

    override fun pid(): Long = FAKE_PID

    override fun destroy() {
        ended.countDown()
        stdout.end()
    }

    companion object {
        const val SENTINEL: String = "dcx-sentinel"
        private const val FAKE_PID = 4242L
    }
}

/** 줄 단위로 밀어 넣는 입력 스트림. `InputStream` 기본 벌크 읽기는 len 바이트를 다 채울 때까지 막히므로 직접 구현한다. */
private class LineQueueStream : InputStream() {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var current = ByteArray(0)
    private var index = 0

    @Volatile
    private var closed = false

    fun push(line: String) {
        queue.put((line + "\n").toByteArray(StandardCharsets.UTF_8))
    }

    fun end() {
        closed = true
        queue.put(ByteArray(0))
    }

    override fun read(): Int {
        while (index >= current.size) {
            if (closed && queue.isEmpty()) return -1
            current = try {
                queue.take()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
            index = 0
            if (current.isEmpty()) return -1
        }
        return current[index++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val first = read()
        if (first < 0) return -1
        b[off] = first.toByte()
        var n = 1
        while (n < len && index < current.size) {
            b[off + n] = current[index++]
            n++
        }
        return n
    }

    override fun available(): Int = current.size - index
}
