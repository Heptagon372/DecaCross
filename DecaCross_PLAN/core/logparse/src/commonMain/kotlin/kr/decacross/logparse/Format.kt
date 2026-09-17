package kr.decacross.logparse

/** 버전별 로그 포맷. 자동 감지 후 파서 선택. */
public enum class LogFormat {
    /** 1.7 이하 `[12:03:41 INFO]: msg` */
    LEGACY,

    /** 1.8+ `[12:03:41] [Server thread/INFO]: msg` (Paper 는 뒤에 `[PluginName]` 접두) */
    MODERN,
}

public fun detectFormat(line: String): LogFormat? = TODO("06")

public fun parseLine(line: String, format: LogFormat): LogLine = TODO("06")
