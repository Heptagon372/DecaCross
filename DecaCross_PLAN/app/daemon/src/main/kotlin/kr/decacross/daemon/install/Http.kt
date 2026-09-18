package kr.decacross.daemon.install

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent

/** User-Agent 에 들어가는 데몬 버전. */
const val DAEMON_UA_VERSION: String = "0.1"

/** 기본 연락처. ★ 이메일을 코드에 넣지 마라. 운영자는 [UA_CONTACT_ENV] 로 교체한다 (불변식 17). */
const val DEFAULT_UA_CONTACT: String = "+https://github.com/Heptagon372/DecaCross"

/** 수집기와 같은 환경변수 이름 (D19). */
const val UA_CONTACT_ENV: String = "DECACROSS_UA_CONTACT"

/**
 * `DecaCross/0.1 (+https://github.com/Heptagon372/DecaCross)` 형태의 UA. 연락처가 비었거나 괄호·제어문자가 있으면 null
 * (generic UA 로 조용히 내려가지 않게 호출자가 실패 처리한다).
 */
fun buildDaemonUserAgent(contact: String = DEFAULT_UA_CONTACT): String? {
    val c = contact.trim()
    if (c.isEmpty() || c.any { it == '(' || it == ')' || it.isISOControl() }) return null
    return "DecaCross/$DAEMON_UA_VERSION ($c)"
}

/**
 * 설치 다운로드용 HttpClient (research fetch-verify §2.2, Ktor 3.4.3 소스·프로브로 확인).
 *
 * - CIO 엔진 `requestTimeout = 0`: 기본 15 s 가 본문 스트리밍까지 죽인다 (F3).
 * - `HttpTimeout.requestTimeoutMillis = null`: 요청 타임아웃이 `execute {}` 본문 읽기까지 포함한다 (F2).
 * - `socketTimeoutMillis = 30 s`: CIO 기본은 무한이라 멈춘 서버를 영원히 기다린다 (F5).
 * - `HttpRequestRetry` 미설치 (재시도마다 Range 오프셋이 달라진다), `ContentEncoding` 미설치 (Range 는 identity 바이트 기준).
 * - `expectSuccess = false`: 상태 코드는 [Fetcher] 가 직접 해석한다.
 *
 * @param engine 테스트용 엔진(MockEngine 등). null 이면 CIO 를 만들고 클라이언트가 닫힐 때 같이 닫는다.
 */
fun installHttpClient(userAgent: String, engine: HttpClientEngine? = null): HttpClient {
    val common: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        install(UserAgent) { agent = userAgent }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
            requestTimeoutMillis = null
        }
    }
    return if (engine == null) {
        HttpClient(CIO) {
            engine { requestTimeout = 0 }
            common()
        }
    } else {
        HttpClient(engine) { common() }
    }
}
