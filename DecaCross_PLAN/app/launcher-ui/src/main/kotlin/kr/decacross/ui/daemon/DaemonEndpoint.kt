package kr.decacross.ui.daemon

/** 발견된 데몬 주소 + UI 토큰. `daemon.json` 과 `ui.token` 에서 읽는다 (명세 §7.1). */
data class DaemonEndpoint(val port: Int, val pid: Long, val token: String) {
    val httpBase: String get() = "http://127.0.0.1:$port"
    val wsBase: String get() = "ws://127.0.0.1:$port"
}
