package kr.decacross.daemon

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing

/** 데몬 브리지 포트 범위 (설계서 §6.1). 첫 빈 포트를 쓴다. */
val DAEMON_PORTS: IntRange = 27565..27575

/**
 * 데몬 진입점. UI 를 닫아도 살아있고, 마크 서버를 감독한다.
 * 라우팅(UiRoutes / BridgeRoutes)은 05·08 단계에서 붙는다.
 */
fun main() {
    embeddedServer(CIO, host = "127.0.0.1", port = DAEMON_PORTS.first) {
        routing { /* 05: UiRoutes, 08: BridgeRoutes — 서로 핸들러를 공유하지 않는다 */ }
    }.start(wait = true)
}
