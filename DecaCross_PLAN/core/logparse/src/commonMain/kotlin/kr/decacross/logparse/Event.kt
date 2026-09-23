package kr.decacross.logparse

/** 구조화된 로그 이벤트. 사망 메시지는 하드코딩하지 않는다 — 수집된 lang 템플릿 전이면 [Raw]. */
public sealed interface LogEvent {
    /** 접속. `X joined the game` 또는 `X[/ip:port] logged in with entity id ...` (IP 는 버린다) */
    public data class Join(val player: String) : LogEvent

    /** 퇴장. `X left the game` 또는 `X lost connection: reason` */
    public data class Leave(val player: String) : LogEvent

    /** 채팅. `<name> msg`, Paper 1.19+ 의 `[Not Secure] <name> msg` */
    public data class Chat(val player: String, val message: String) : LogEvent

    /** 사망. [EventContext.deathTemplates] 로만 판정된다. */
    public data class Death(val player: String, val message: String) : LogEvent

    /** `X issued server command: /cmd` */
    public data class Command(val issuer: String, val command: String) : LogEvent

    /** `Done (8.214s)! For help, type "help"` → 8214ms */
    public data class Startup(val tookMs: Long) : LogEvent

    public data class Warn(val message: String) : LogEvent

    /** ERROR/FATAL 레벨. [stackTrace] 는 병합된 연속 줄 그대로. */
    public data class Error(val message: String, val stackTrace: List<String> = emptyList()) : LogEvent

    /** 플러그인 로드/활성화. 실패(`Could not load`, `Error occurred while enabling`)면 `ok = false`. */
    public data class PluginLoad(val name: String, val version: String?, val ok: Boolean) : LogEvent

    /** `Can't keep up! ... Running 2000ms or 40 ticks behind` → 2000 */
    public data class Lag(val ms: Long) : LogEvent

    public data class Raw(val line: String) : LogEvent
}

/**
 * 파싱된 로그 한 엔트리. 스택트레이스처럼 여러 줄에 걸친 출력은 [continuation] 에 병합된다.
 *
 * @property time `HH:MM:SS`. 타임스탬프 없는 줄이면 `null`
 * @property thread `Server thread` 등. LEGACY 포맷에는 없다
 * @property level `INFO`/`WARN`/`ERROR`/`FATAL` (대문자 정규화)
 * @property pluginTag Paper 플러그인 로거 접두 `[PluginName]`. 메시지에서는 제거된다
 * @property message 색코드·ANSI 제거, 플러그인 태그 제거 후의 본문
 * @property continuation 이어붙은 줄들 (`\tat ...`, `Caused by: ...`)
 * @property raw 첫 줄 원문
 */
public data class LogLine(
    val time: String?,
    val thread: String?,
    val level: String?,
    val pluginTag: String?,
    val message: String,
    val raw: String,
    val continuation: List<String> = emptyList(),
) {
    /** 시그니처 매칭·검색용 전체 텍스트: 본문 + 연속 줄. `Caused by:` 가 진짜 원인을 담는다. */
    public fun fullText(): String = if (continuation.isEmpty()) message else (listOf(message) + continuation).joinToString("\n")
}

/**
 * 이벤트 변환에 필요한 외부 데이터. 순수 함수 모듈이므로 전부 주입받는다.
 *
 * @property deathTemplates client.jar 의 lang(`en_us.json`/`ko_kr.json`)에서 collector 가 추출한 `death.*` 템플릿.
 *   `%1$s was slain by %2$s` 처럼 `%N$s` 자리표시자를 가진다. `%1$s` 가 희생자. 비어 있으면 사망은 절대 판정하지 않는다.
 */
public class EventContext(public val deathTemplates: List<String> = emptyList()) {
    internal val deathPatterns: List<DeathPattern> = compileDeathTemplates(deathTemplates)

    public companion object {
        /** 템플릿 없음 — 사망 메시지는 전부 [LogEvent.Raw] */
        public val EMPTY: EventContext = EventContext()
    }
}

/** 컴파일된 사망 템플릿. [victimGroup] 은 `%1$s` 에 해당하는 캡처 그룹 번호. */
internal data class DeathPattern(val template: String, val regex: Regex, val victimGroup: Int)

private val PLACEHOLDER_RE = Regex("""%(\d+)?\$?s""")

/**
 * lang 템플릿을 정규식으로 컴파일한다. 리터럴 부분은 이스케이프, `%1$s` 는 `(\S+)`(플레이어명은 공백 없음), 나머지는 `(.+?)`.
 * 리터럴이 긴 템플릿을 먼저 시도해 `%1$s was slain by %2$s using %3$s` 가 `... by %2$s` 보다 우선하게 한다.
 */
internal fun compileDeathTemplates(templates: List<String>): List<DeathPattern> =
    templates
        .distinct()
        .mapNotNull { template ->
            val placeholders = PLACEHOLDER_RE.findAll(template).toList()
            if (placeholders.isEmpty()) return@mapNotNull null
            val sb = StringBuilder("^")
            var cursor = 0
            var victimGroup = 1
            placeholders.forEachIndexed { index, ph ->
                sb.append(Regex.escape(template.substring(cursor, ph.range.first)))
                val number = ph.groupValues[1].toIntOrNull() ?: (index + 1)
                if (number == 1) {
                    victimGroup = index + 1
                    sb.append("""(\S+)""")
                } else {
                    sb.append("(.+?)")
                }
                cursor = ph.range.last + 1
            }
            sb.append(Regex.escape(template.substring(cursor))).append("$")
            val literalLength = template.length - placeholders.sumOf { it.value.length }
            runCatching { DeathPattern(template, Regex(sb.toString()), victimGroup) to literalLength }.getOrNull()
        }
        .sortedByDescending { it.second }
        .map { it.first }

private val JOIN_RE = Regex("""^(\S+) joined the game$""")
private val LOGIN_RE = Regex("""^(\S+?)\[/[^\]]*\] logged in with entity id \d+""")
private val LEAVE_RE = Regex("""^(\S+) left the game$""")
private val LOST_CONNECTION_RE = Regex("""^(\S+) lost connection: ?(.*)$""")
private val CHAT_RE = Regex("""^(?:\[Not Secure\] )?<([^>]+)> (.*)$""")
private val COMMAND_RE = Regex("""^(\S+) issued server command: (/.*)$""")
private val STARTUP_RE = Regex("""^Done \(([\d.,]+)s\)!""")
private val LAG_RE = Regex("""^Can't keep up!.*?Running (\d+)ms or (\d+) ticks behind""")
private val LAG_FALLBACK_RE = Regex("""^Can't keep up!.*?(\d+)ms""")
private val PLUGIN_LOADING_RE = Regex("""^Loading server plugin (.+?) v(\S+)$""")
private val PLUGIN_ENABLING_RE = Regex("""^Enabling (.+?) v(\S+)$""")
private val PLUGIN_LOADING_LEGACY_RE = Regex("""^Loading (.+?) v(\S+)$""")
private val PLUGIN_LOAD_FAILED_RE = Regex("""^Could not load (?:plugin )?'([^']+)'""")
private val PLUGIN_ENABLE_FAILED_RE = Regex("""^Error occurred while enabling (\S+?)(?: v(\S+))?(?: \(Is it up to date\?\))?$""")

/**
 * 로그 엔트리를 이벤트로 바꾼다. 구체 패턴(접속·채팅·명령·기동·랙·플러그인·사망)을 먼저 보고,
 * 그다음 레벨(ERROR/FATAL → [LogEvent.Error], WARN → [LogEvent.Warn]), 나머지는 [LogEvent.Raw].
 * 예외를 던지지 않는다.
 */
public fun LogLine.toEvent(ctx: EventContext = EventContext.EMPTY): LogEvent {
    JOIN_RE.matchEntire(message)?.let { return LogEvent.Join(it.groupValues[1]) }
    LOGIN_RE.find(message)?.let { return LogEvent.Join(it.groupValues[1]) }
    LEAVE_RE.matchEntire(message)?.let { return LogEvent.Leave(it.groupValues[1]) }
    LOST_CONNECTION_RE.matchEntire(message)?.let { return LogEvent.Leave(it.groupValues[1]) }
    CHAT_RE.matchEntire(message)?.let { return LogEvent.Chat(it.groupValues[1], it.groupValues[2]) }
    COMMAND_RE.matchEntire(message)?.let { return LogEvent.Command(it.groupValues[1], it.groupValues[2]) }
    STARTUP_RE.find(message)?.let { m ->
        val seconds = m.groupValues[1].replace(",", "").toDoubleOrNull()
        if (seconds != null) return LogEvent.Startup((seconds * 1000).toLong())
    }
    (LAG_RE.find(message) ?: LAG_FALLBACK_RE.find(message))?.let { m ->
        m.groupValues[1].toLongOrNull()?.let { return LogEvent.Lag(it) }
    }
    PLUGIN_LOADING_RE.matchEntire(message)?.let { return LogEvent.PluginLoad(it.groupValues[1], it.groupValues[2], ok = true) }
    PLUGIN_ENABLING_RE.matchEntire(message)?.let { return LogEvent.PluginLoad(it.groupValues[1], it.groupValues[2], ok = true) }
    PLUGIN_LOADING_LEGACY_RE.matchEntire(message)?.let { return LogEvent.PluginLoad(it.groupValues[1], it.groupValues[2], ok = true) }
    PLUGIN_LOAD_FAILED_RE.find(message)?.let { return LogEvent.PluginLoad(pluginNameFromPath(it.groupValues[1]), null, ok = false) }
    PLUGIN_ENABLE_FAILED_RE.matchEntire(message)?.let {
        return LogEvent.PluginLoad(it.groupValues[1], it.groupValues[2].ifEmpty { null }, ok = false)
    }
    if (pluginTag == null) {
        for (death in ctx.deathPatterns) {
            val m = death.regex.matchEntire(message) ?: continue
            return LogEvent.Death(m.groupValues[death.victimGroup], message)
        }
    }
    return when (level) {
        "ERROR", "FATAL", "SEVERE" -> LogEvent.Error(message, continuation)
        "WARN", "WARNING" -> LogEvent.Warn(message)
        else -> LogEvent.Raw(message)
    }
}

/** `plugins/ProtocolLib-5.3.0.jar` → `ProtocolLib-5.3.0` */
private fun pluginNameFromPath(path: String): String =
    path.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".jar")
