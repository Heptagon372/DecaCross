package kr.decacross.collector

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/** URL 접두/포함 문자열로 라우팅하는 MockEngine. 매칭이 없으면 404. */
fun routedEngine(routes: Map<String, String>, status: (String) -> HttpStatusCode = { HttpStatusCode.OK }): MockEngine =
    MockEngine { request ->
        val url = request.url.toString()
        val hit = routes.entries.firstOrNull { url.contains(it.key) }
        if (hit == null) respond("not found: $url", HttpStatusCode.NotFound) else json(hit.value, status(url))
    }

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

/** 대기 시간을 기록만 하는 sleeper. 테스트에서 실제로 기다리지 않는다. */
class RecordingSleeper {
    val sleeps = ArrayList<Long>()
    var now: Long = 0L
    val sleeper: suspend (Long) -> Unit = { ms ->
        sleeps += ms
        now += ms
    }
    val clock: () -> Long = { now }
}

fun testHttp(engine: MockEngine, sleeper: RecordingSleeper = RecordingSleeper()): CollectorHttp =
    CollectorHttp(engine = engine, userAgent = "DecaCross-test/0 (+test)", sleeper = sleeper.sleeper, clock = sleeper.clock)
