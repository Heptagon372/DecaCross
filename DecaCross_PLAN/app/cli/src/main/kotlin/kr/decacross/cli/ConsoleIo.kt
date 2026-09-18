package kr.decacross.cli

import kotlinx.coroutines.channels.Channel
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * CLI 표준 입출력 (DESIGN2 §2.15 `ConsoleIo`).
 *
 * 입력은 데몬 스레드 `dcx-stdin` 하나가 읽어 무제한 [Channel] 로 넘긴다. EULA 프롬프트와 서버 콘솔 전달이 이 채널 하나를 나눠 쓴다.
 *
 * # 불변식
 * - ★ [readLine] 은 코루틴 취소에 즉시 응답한다 (`withContext(IO) { readLine() }` 로 막으면 Ctrl+C 때 롤백이 멈춘다, critique windows #8).
 * - 스레드는 처음 입력이 필요할 때만 뜬다. 입력이 끝나면(EOF·IOException) 채널을 닫는다 — [readLine] 은 그때부터 null.
 * - [drainBuffered] 는 이미 버퍼에 있는 줄만 버린다. 채널의 닫힘(EOF) 상태는 건드리지 않는다 (critique m7).
 * - ★ 줄마다 **읽힌 순번**([StampedLine.sequence])을 찍는다. 질문을 출력한 **뒤** [inputMark] 를 찍어 두면,
 *   그보다 작거나 같은 순번의 줄은 질문 전에 들어온 입력이다 — 그것을 답(동의)으로 보면 안 된다.
 * - [out] 은 MS949 콘솔에서 `?` 로 깨질 문자를 바꿔서 내보낸다 ([consoleSafe], critique windows #7). [printRaw] 는 서버 출력이라 그대로 둔다.
 */
class ConsoleIo(
    private val source: () -> InputStream = { System.`in` },
    private val charset: Charset = defaultStdinCharset(),
    private val sink: (String) -> Unit = ::printFlushed,
) {
    private val lines = Channel<StampedLine>(Channel.UNLIMITED)
    private val readerStarted = AtomicBoolean(false)
    private val inputEnded = CountDownLatch(1)
    private val readCount = AtomicLong(0)

    /** 런처가 사용자에게 하는 말 한 줄. */
    fun out(line: String) {
        sink(consoleSafe(line))
    }

    /** 서버가 내보낸 줄 그대로 (가공 금지 — 로그를 왜곡하지 않는다). */
    fun printRaw(line: String) {
        sink(line)
    }

    /** 다음 입력 줄. 입력이 끝났으면 null. 취소 가능하다. */
    suspend fun readLine(): String? = readLineStamped()?.text

    /** 다음 입력 줄과 그 줄이 읽힌 순번. 입력이 끝났으면 null. 취소 가능하다. */
    suspend fun readLineStamped(): StampedLine? {
        ensureReaderStarted()
        return lines.receiveCatching().getOrNull()
    }

    /**
     * 지금까지 읽어 들인 줄 수.
     *
     * # 불변식
     * - ★ 질문을 출력한 **직후에** 찍는다. 순번이 이 값보다 크지 않은 줄은 질문 전에 들어온 입력이다.
     */
    fun inputMark(): Long {
        ensureReaderStarted()
        return readCount.get()
    }

    /** 이미 들어와 있던 줄을 버리고 몇 줄 버렸는지 돌려준다 (질문 전에 친 줄이 동의가 되면 안 된다). */
    fun drainBuffered(): Int {
        ensureReaderStarted()
        var discarded = 0
        while (lines.tryReceive().getOrNull() != null) discarded++
        return discarded
    }

    /** 더 이상 입력을 기다리지 않는다 (남은 [readLine] 은 null). */
    fun close() {
        lines.close()
    }

    /** 테스트용: 입력이 끝날 때까지(EOF) 기다린다. */
    internal fun awaitInputEnd(timeoutMs: Long): Boolean {
        ensureReaderStarted()
        return inputEnded.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun ensureReaderStarted() {
        if (!readerStarted.compareAndSet(false, true)) return
        val thread = Thread({ pump() }, "dcx-stdin")
        thread.isDaemon = true
        thread.start()
    }

    private fun pump() {
        try {
            BufferedReader(InputStreamReader(source(), charset)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    // 순번은 채널에 넣기 **전에** 올린다 — [inputMark] 가 "이미 읽은 줄" 을 놓치지 않게
                    lines.trySend(StampedLine(readCount.incrementAndGet(), line))
                }
            }
        } catch (e: IOException) {
            // 입력이 끊긴 것과 같게 본다 — 아래 finally 가 채널을 닫아 EOF 로 흘려보낸다
        } finally {
            lines.close()
            inputEnded.countDown()
        }
    }
}

/** 표준입력 한 줄과 그 줄이 읽힌 순번 ([ConsoleIo.inputMark] 와 비교해 "질문 전 입력" 을 가려낸다). */
data class StampedLine(val sequence: Long, val text: String)

/** `println` + flush (Gradle `run` 은 출력이 파이프라 줄 단위로 밀어야 프롬프트가 제때 보인다). */
private fun printFlushed(line: String) {
    println(line)
    System.out.flush()
}

/**
 * stdin 문자 집합: `stdin.encoding` → `native.encoding` → UTF-8.
 * Gradle `run` 은 `-Dstdin.encoding=UTF-8` 을 넣고(빌드 스크립트), `bin\cli.bat` 은 949 콘솔의 `native.encoding` 을 쓴다.
 */
internal fun defaultStdinCharset(): Charset {
    val name = System.getProperty("stdin.encoding") ?: System.getProperty("native.encoding") ?: "UTF-8"
    return try {
        Charset.forName(name)
    } catch (e: IllegalCharsetNameException) {
        Charsets.UTF_8
    } catch (e: UnsupportedCharsetException) {
        Charsets.UTF_8
    }
}

/**
 * MS949(코드페이지 949) 콘솔이 인코딩할 수 없는 문자를 바꾼다 (D-I44, critique windows #7).
 * 지금 필요한 것은 em dash 하나지만(WP0 실패 문구), 다른 작업 패키지가 쓸 만한 문장 부호를 미리 막아 둔다:
 * 일반 구두점 영역의 **모든 대시**(U+2010‥U+2015)와 **모든 공백**(U+2000‥U+200B)을 ASCII 로 내린다.
 * 표·범위에 없는 문자가 새로 들어오면 `ConsoleIoTest`·`EventRendererTest` 의 MS949 검사가 잡는다.
 */
internal fun consoleSafe(line: String): String {
    if (line.none { it.needsConsoleFallback() }) return line
    val sb = StringBuilder(line.length)
    for (c in line) sb.append(consoleFallback(c) ?: c)
    return sb.toString()
}

/** 바꿔야 하는 문자면 대체 문자열, 아니면 null. */
private fun consoleFallback(c: Char): String? =
    when {
        CONSOLE_UNSAFE.containsKey(c) -> CONSOLE_UNSAFE[c]
        c in DASH_RANGE -> "-"
        c in SPACE_RANGE -> " "
        c in ZERO_WIDTH_RANGE -> ""
        else -> null
    }

private fun Char.needsConsoleFallback(): Boolean = consoleFallback(this) != null

/** U+2010 HYPHEN ‥ U+2015 HORIZONTAL BAR — 일부만 KS X 1001 에 있어서 전부 `-` 로 내린다. */
private val DASH_RANGE: CharRange = '‐'..'―'

/** U+2000 EN QUAD ‥ U+200A HAIR SPACE — MS949 에 없는 폭 있는 공백들. */
private val SPACE_RANGE: CharRange = ' '..' '

/** U+200B ZERO WIDTH SPACE ‥ U+200F RLM — 폭이 없으니 공백이 아니라 아예 뺀다. */
private val ZERO_WIDTH_RANGE: CharRange = '​'..'‏'

private val CONSOLE_UNSAFE: Map<Char, String> = mapOf(
    '•' to "*", // bullet
    '‣' to "*",
    '✔' to "[v]",
    '✘' to "[x]",
    '⚠' to "[!]",
    '↩' to "<-",
)
