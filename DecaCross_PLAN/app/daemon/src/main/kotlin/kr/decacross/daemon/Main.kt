package kr.decacross.daemon

import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kr.decacross.daemon.api.uiRoutes
import kr.decacross.daemon.paths.DecaPaths
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/** 데몬 포트 범위 (설계서 §6.1). 첫 빈 포트를 쓴다. */
val DAEMON_PORTS: IntRange = 27565..27575

val daemonJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}

fun Application.daemonModule(d: Daemon) {
    install(ContentNegotiation) { json(daemonJson) }
    install(WebSockets) {
        pingPeriod = 15.seconds
        contentConverter = KotlinxWebsocketSerializationConverter(daemonJson)
    }
    routing {
        uiRoutes(d)
        // 08: bridgeRoutes(d) — 별도 트리. UI 핸들러 재사용 금지.
    }
}

fun pickPort(range: IntRange = DAEMON_PORTS): Int? =
    range.firstOrNull { p -> runCatching { ServerSocket(p, 1, java.net.InetAddress.getLoopbackAddress()).close() }.isSuccess }

fun startDaemon(paths: DecaPaths = DecaPaths.detect(), port: Int? = null): Pair<Daemon, EmbeddedServer<*, *>> {
    val log = LoggerFactory.getLogger("decacross.daemon")
    val d = Daemon.create(paths)
    val chosen = port ?: pickPort() ?: error("데몬 포트($DAEMON_PORTS)를 하나도 열 수 없습니다")
    val server = embeddedServer(CIO, host = "127.0.0.1", port = chosen) { daemonModule(d) }
    server.start(wait = false)
    Files.createDirectories(paths.appData)
    Files.writeString(
        paths.appData.resolve("daemon.json"),
        daemonJson.encodeToString(DaemonInfo.serializer(), DaemonInfo(chosen, ProcessHandle.current().pid(), Daemon.VERSION)),
    )
    log.info("DecaCross 데몬 {} — http://127.0.0.1:{}  (서버 폴더: {})", Daemon.VERSION, chosen, paths.serversRoot)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking { d.shutdown() }
            runCatching { Files.deleteIfExists(paths.appData.resolve("daemon.json")) }
        },
    )
    return d to server
}

/**
 * 데몬 진입점. UI 를 닫아도 살아있고, 마크 서버를 감독한다. 종료는 Ctrl+C / 시그널 → 모든 서버 정상 종료.
 */
fun main() {
    val (_, server) = startDaemon()
    Thread.currentThread().join()
    server.stop()
}
