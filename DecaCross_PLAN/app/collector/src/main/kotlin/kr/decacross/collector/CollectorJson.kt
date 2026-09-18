package kr.decacross.collector

import kotlinx.serialization.json.Json

/**
 * 외부 API 응답 파싱 공통 설정.
 * 모르는 필드는 무시한다 — 상대 API 가 필드를 추가해도 수집기가 죽지 않게.
 * enum 은 문자열로 받아서 소스 코드가 직접 접는다 (새 값이 와도 역직렬화가 실패하지 않게).
 *
 * # 불변식
 * - JSON `null` 이 기본값 있는 non-null 필드에 오면 기본값을 쓴다 ([coerceInputValues]).
 *   예: Purpur 1.20+ 의 FAILURE 빌드는 `"md5": null` 이다 — DTO 하나가 소스 전체를 죽이면 안 된다.
 *   (`explicitNulls = false` 만으로는 JSON null 이 기본값으로 바뀌지 않는다.)
 */
val CollectorJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = false
}

/** UTF-8 BOM (U+FEFF). kotlinx JSON 파서는 이것을 문법 오류로 본다. */
private val UTF8_BOM: String = Char(0xFEFF).toString()

/**
 * 운영자가 Windows 편집기로 저장한 설정·기대값 JSON 앞의 UTF-8 BOM 을 벗긴다.
 * 로컬 파일·리소스를 읽는 모든 경로가 이것을 거친다 (C3-R3, V-1).
 */
internal fun stripUtf8Bom(text: String): String = text.removePrefix(UTF8_BOM)
