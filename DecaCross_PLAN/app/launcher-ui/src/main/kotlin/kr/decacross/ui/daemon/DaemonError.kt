package kr.decacross.ui.daemon

/**
 * 데몬 API 호출 실패. HTTP 오류 응답(`ErrorDto`)은 [DaemonException.Api] 로, 연결 자체가 안 되는 경우는 [DaemonException.Unreachable] 로 매핑한다.
 * UI 계층은 [messageKo] 만 보여주면 된다.
 */
sealed class DaemonException(val messageKo: String, cause: Throwable? = null) : Exception(messageKo, cause) {
    /** 데몬이 `ErrorDto` 로 거절함 (409/404/401 등). */
    class Api(val status: Int, messageKo: String) : DaemonException(messageKo)

    /** 데몬에 도달하지 못함 (연결 거부·타임아웃·프로세스 없음). */
    class Unreachable(messageKo: String, cause: Throwable? = null) : DaemonException(messageKo, cause)
}
