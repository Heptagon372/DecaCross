package kr.decacross.logparse

/** 구조화된 로그 이벤트. 사망 메시지는 하드코딩하지 않는다 — 수집된 lang 템플릿 전이면 [Raw]. */
public sealed interface LogEvent {
    public data class Join(val player: String) : LogEvent

    public data class Leave(val player: String) : LogEvent

    public data class Chat(val player: String, val message: String) : LogEvent

    public data class Death(val player: String, val message: String) : LogEvent

    public data class Command(val issuer: String, val command: String) : LogEvent

    public data class Startup(val tookMs: Long) : LogEvent

    public data class Warn(val message: String) : LogEvent

    public data class Error(val message: String, val stackTrace: List<String> = emptyList()) : LogEvent

    public data class PluginLoad(val name: String, val version: String?, val ok: Boolean) : LogEvent

    public data class Lag(val ms: Long) : LogEvent

    public data class Raw(val line: String) : LogEvent
}

public data class LogLine(
    val time: String?,
    val thread: String?,
    val level: String?,
    val message: String,
    val raw: String,
)
