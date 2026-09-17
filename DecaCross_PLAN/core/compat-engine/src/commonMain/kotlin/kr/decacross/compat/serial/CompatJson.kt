package kr.decacross.compat.serial

import kotlinx.serialization.json.Json

/** 엔진 타입 직렬화 공통 설정. 픽스처·데몬 API·웹 API 가 같은 규칙을 쓴다. */
public val CompatJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    prettyPrint = false
}
