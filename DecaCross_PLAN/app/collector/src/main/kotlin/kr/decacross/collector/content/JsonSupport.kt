package kr.decacross.collector.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kr.decacross.collector.CollectorJson
import kr.decacross.collector.http.Http
import kr.decacross.collector.http.HttpResult
import kr.decacross.collector.stripUtf8Bom

// ── 콘텐츠 소스(Modrinth·Hangar) 공용 JSON 도우미 ─────────────────────────────

/**
 * JSON 을 받아 DTO 로 해석한 결과. 예외 대신 sealed.
 *
 * # 불변식
 * - [Failed.status] 는 HTTP 상태 코드가 있을 때만 non-null 이다 (404 = 확정적 부재 판정에 쓴다).
 *   네트워크 실패·본문 해석 실패는 null.
 */
internal sealed interface Fetched<out T> {
    data class Ok<out T>(val value: T) : Fetched<T>

    data class Failed(val status: Int?, val detail: String) : Fetched<Nothing>
}

/**
 * GET → [CollectorJson] 으로 [T] 해석. 취소 외의 예외를 던지지 않는다 (Http 가 이미 sealed 결과를 준다).
 */
internal suspend inline fun <reified T> Http.getJson(url: String, maxBytes: Int = Http.DEFAULT_MAX_BODY_BYTES): Fetched<T> =
    when (val r = get(url, conditional = null, maxBytes = maxBytes)) {
        is HttpResult.Ok -> decodeJson<T>(r.value, url)
        is HttpResult.NotModified -> Fetched.Failed(r.meta.status, "예상치 못한 304: $url")
        is HttpResult.Status -> Fetched.Failed(r.meta.status, "HTTP ${r.meta.status}: $url ${r.bodySnippet.take(200)}")
        is HttpResult.Failure -> Fetched.Failed(null, "${r.kind}: $url ${r.message}")
    }

/** 본문 해석. 스키마가 어긋나면 [Fetched.Failed] (status null). */
internal inline fun <reified T> decodeJson(bytes: ByteArray, url: String): Fetched<T> =
    try {
        Fetched.Ok(CollectorJson.decodeFromString<T>(bytes.decodeToString()))
    } catch (e: IllegalArgumentException) {
        // SerializationException 도 IllegalArgumentException 이다
        Fetched.Failed(null, "JSON 해석 실패: $url ${e.message?.take(200)}")
    }

/**
 * 클래스패스 JSON 리소스의 루트 객체. 없거나 객체가 아니면 [IllegalStateException] —
 * 규칙 리소스가 깨진 채로 조용히 돌면 안 된다 (기동 시 즉시 드러나야 한다).
 * 운영자가 Windows 편집기로 저장해 붙은 UTF-8 BOM 은 벗긴다 (SCP-14·SCP-20 스위치를 손으로 바꾸는 파일; 마이그레이션 D45 와 같은 이유).
 */
internal fun readJsonResource(path: String): JsonObject {
    val stream = CapabilityRulesLoader::class.java.getResourceAsStream(path)
        ?: throw IllegalStateException("리소스 없음: $path")
    val text = stripUtf8Bom(stream.use { it.readBytes().decodeToString() })
    val root = try {
        CollectorJson.parseToJsonElement(text)
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException("리소스 JSON 해석 실패: $path", e)
    }
    return root as? JsonObject ?: throw IllegalStateException("리소스 루트가 객체가 아니다: $path")
}

/** 문자열 원소만 모은다. 문자열이 아닌 원소가 있으면 [IllegalStateException]. */
internal fun JsonElement?.stringList(what: String): List<String> {
    if (this == null) return emptyList()
    val array = this as? JsonArray ?: throw IllegalStateException("$what 는 배열이어야 한다")
    return array.map { element -> element.stringValue(what) }
}

/** 문자열 스칼라. JSON 문자열이 아니면 [IllegalStateException]. */
internal fun JsonElement.stringValue(what: String): String {
    val p = this as? JsonPrimitive
    if (p == null || !p.isString) throw IllegalStateException("$what 는 문자열이어야 한다: $this")
    return p.content
}

/** 불리언 스칼라. 키가 없으면 [default]. 불리언이 아니면 [IllegalStateException]. */
internal fun JsonElement?.booleanValue(what: String, default: Boolean): Boolean {
    if (this == null) return default
    val p = this as? JsonPrimitive
    if (p == null || p.isString) throw IllegalStateException("$what 는 불리언이어야 한다: $this")
    return when (p.content) {
        "true" -> true
        "false" -> false
        else -> throw IllegalStateException("$what 는 불리언이어야 한다: $this")
    }
}
