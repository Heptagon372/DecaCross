package kr.decacross.logparse

/** 버전별 로그 포맷. 자동 감지 후 파서 선택. */
public enum class LogFormat {
    /** 1.7 이하 `[12:03:41 INFO]: msg` */
    LEGACY,

    /** 1.8+ `[12:03:41] [Server thread/INFO]: msg` (Paper 는 뒤에 `[PluginName]` 접두) */
    MODERN,
}

/** 1.7 이하: `[HH:MM:SS LEVEL]: message` */
private val LEGACY_RE = Regex("""^\[(\d{2}:\d{2}:\d{2})\s+([A-Za-z]+)\]:?\s?(.*)$""")

/**
 * 1.8+: `[HH:MM:SS] [thread/LEVEL]: message`.
 * 스레드 이름에는 공백·`#`·`-` 가 올 수 있다 (`Craft Scheduler Thread - 3`, `Netty Epoll Server IO #1`).
 * log4j 설정에 따라 `[thread/LEVEL] [logger]: message` 처럼 로거 이름이 끼는 변형도 허용한다.
 */
private val MODERN_RE =
    Regex("""^\[(\d{2}:\d{2}:\d{2})\]\s+\[([^\]]*?)/([A-Za-z]+)\](?:\s+\[[^\]]*\])?:?\s?(.*)$""")

/** 타임스탬프로 시작하는 줄인지(= 새 엔트리의 시작인지) 빠르게 판정 */
private val TIMESTAMP_PREFIX_RE = Regex("""^\[\d{2}:\d{2}:\d{2}[\] ]""")

/** Paper 플러그인 로거 접두 `[PluginName] `. 공백 없는 이름만 태그로 본다 — `[Not Secure] <name> msg` 는 채팅이다. */
private val PLUGIN_TAG_RE = Regex("""^\[([A-Za-z0-9_.\-]+)\]\s(.*)$""")

/** `§x` 색코드 + ANSI 이스케이프(CSI 시퀀스) */
private val COLOR_RE = Regex("""§.|\[[0-9;?]*[ -/]*[@-~]""")

/** 스택트레이스 등 명시적 연속 줄 접두 */
private val CONTINUATION_PREFIXES = listOf("\t", " ", "at ", "Caused by:", "Suppressed:", "...")

/**
 * 한 줄만 보고 포맷을 감지한다. 타임스탬프가 없는 줄(JVM stderr, 스택트레이스 등)은 `null`.
 */
public fun detectFormat(line: String): LogFormat? =
    when {
        MODERN_RE.matches(line) -> LogFormat.MODERN
        LEGACY_RE.matches(line) -> LogFormat.LEGACY
        else -> null
    }

/** 색코드(`§x`)와 ANSI 이스케이프를 제거한다. */
public fun stripColorCodes(text: String): String = COLOR_RE.replace(text, "")

/**
 * 한 줄을 [LogLine] 으로 파싱한다. 멀티라인 병합은 하지 않는다 — 그건 [LogParser] 의 몫.
 * 어떤 입력이 와도 예외를 던지지 않는다. 포맷을 모르면 시각·스레드·레벨이 `null` 인 라인을 돌려준다.
 *
 * @param format 지정하지 않으면 [detectFormat] 으로 자동 감지
 */
public fun parseLine(line: String, format: LogFormat? = detectFormat(line)): LogLine {
    val clean = line.trimEnd('\r', '\n')
    val match =
        when (format) {
            LogFormat.MODERN -> MODERN_RE.matchEntire(clean)
            LogFormat.LEGACY -> LEGACY_RE.matchEntire(clean)
            null -> null
        }
    if (match == null) {
        return LogLine(time = null, thread = null, level = null, pluginTag = null, message = stripColorCodes(clean), raw = clean)
    }
    val (time, thread, level, body) =
        when (format) {
            LogFormat.MODERN -> Quad(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
            else -> Quad(match.groupValues[1], null, match.groupValues[2], match.groupValues[3])
        }
    val stripped = stripColorCodes(body)
    val tagMatch = PLUGIN_TAG_RE.matchEntire(stripped)
    return LogLine(
        time = time,
        thread = thread,
        level = level.uppercase(),
        pluginTag = tagMatch?.groupValues?.get(1),
        message = tagMatch?.groupValues?.get(2) ?: stripped,
        raw = clean,
    )
}

private data class Quad(val time: String, val thread: String?, val level: String, val body: String)

/**
 * 상태를 가진 스트리밍 파서. 줄 단위로 [feed] 하고, 멀티라인 엔트리(스택트레이스·스레드 덤프)를 하나의 [LogLine] 으로 병합한다.
 *
 * 연속 줄 판정:
 * - `\tat `, `Caused by:`, `\t... N more`, `Suppressed:` 및 공백/탭으로 시작하는 줄 → 직전 엔트리의 [LogLine.continuation]
 * - 타임스탬프 없는 줄이 타임스탬프 있는 엔트리 뒤에 오면 → 연속 줄 (log4j 가 예외를 접두 없이 출력하는 형태)
 * - 타임스탬프 없는 줄이 타임스탬프 없는 엔트리 뒤에 오면 → 새 엔트리 (JVM stderr 처럼 줄마다 독립인 출력)
 *
 * 마지막 엔트리는 다음 엔트리가 시작될 때까지 보류되므로, 입력이 끝나면 반드시 [flush] 를 호출한다.
 * 어떤 입력에도 예외를 던지지 않는다.
 */
public class LogParser {
    private var head: LogLine? = null
    private val buffer: MutableList<String> = mutableListOf()

    /**
     * 한 줄을 넣는다. 새 엔트리가 시작되어 직전 엔트리가 확정되면 그것을 돌려준다 (0 또는 1개).
     * 빈 줄은 무시한다.
     */
    public fun feed(rawLine: String): List<LogLine> {
        val line = rawLine.trimEnd('\r', '\n')
        if (line.isBlank()) return emptyList()
        val current = head
        if (current != null && isContinuation(line, current)) {
            buffer += stripColorCodes(line)
            return emptyList()
        }
        val emitted = drain()
        head = parseLine(line)
        return emitted
    }

    /** 여러 줄을 순서대로 [feed] 한다. */
    public fun feedAll(lines: Iterable<String>): List<LogLine> = lines.flatMap { feed(it) }

    /** 보류 중인 마지막 엔트리를 확정해 돌려준다. */
    public fun flush(): List<LogLine> = drain()

    private fun drain(): List<LogLine> {
        val current = head ?: return emptyList()
        val done = if (buffer.isEmpty()) current else current.copy(continuation = buffer.toList())
        head = null
        buffer.clear()
        return listOf(done)
    }

    private fun isContinuation(line: String, pending: LogLine): Boolean {
        if (CONTINUATION_PREFIXES.any { line.startsWith(it) }) return true
        return pending.time != null && !TIMESTAMP_PREFIX_RE.containsMatchIn(line)
    }
}

/** 전체 텍스트를 한 번에 파싱한다. 줄 구분은 `\n` (앞의 `\r` 은 제거). */
public fun parseLog(text: String): List<LogLine> {
    val parser = LogParser()
    return parser.feedAll(text.lineSequence().asIterable()) + parser.flush()
}
