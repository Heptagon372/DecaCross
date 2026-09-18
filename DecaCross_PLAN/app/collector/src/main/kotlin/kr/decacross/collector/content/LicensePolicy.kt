package kr.decacross.collector.content

import kotlinx.serialization.json.JsonObject

/**
 * 라이선스 → `content.license` / `content.redistributable` 결정 (D26, SCP-20).
 *
 * # 불변식 (CLAUDE.md 불변식 5)
 * - `redistributable = enabled && <규칙>`. 리소스가 `redistributableEnabled: false` 로 배포되므로 Phase 1 은 전부 false.
 * - 허용 목록 비교는 **정확·대소문자 구분** 일치만 (`"mit"` ✗, `"LicenseRef-MIT-Non-Distribution"` ✗).
 * - 라이선스 원문(정규화한 SPDX id 또는 원문 텍스트)은 스위치와 무관하게 항상 돌려준다 → 스위치를 켜면 재계산만으로 끝난다.
 * - 버전을 지어내지 않는다 (Hangar `GPL` 은 `GPL` 그대로).
 */
internal class LicensePolicy(
    val enabled: Boolean,
    val allow: Set<String>,
    val hangarTypeToSpdx: Map<String, String>,
    val versionless: Set<String>,
    val unspecified: Set<String>,
) {
    data class Decision(val license: String?, val redistributable: Boolean)

    /** Modrinth 검색 결과의 `license` (SPDX id 문자열). */
    fun modrinth(spdxId: String?): Decision {
        val license = spdxId?.trim()?.takeIf { it.isNotEmpty() }
        return Decision(license, enabled && license != null && license in allow)
    }

    /** Hangar `settings.license` 의 `type` / `name`. */
    fun hangar(type: String?, name: String?): Decision {
        if (type == null || type in unspecified) return Decision(null, false)
        hangarTypeToSpdx[type]?.let { mapped -> return Decision(mapped, enabled && mapped in allow) }
        return when {
            type in versionless -> Decision(type, false)
            type == OTHER -> Decision(name?.trim()?.ifEmpty { null }, false)
            else -> Decision(type, false)
        }
    }

    companion object {
        const val RESOURCE: String = "/license-policy.json"
        private const val OTHER = "Other"

        /** 리소스 [RESOURCE] 에서 읽는다. `redistributableEnabled` 키가 없으면 false. 리소스가 없거나 깨졌으면 예외. */
        fun load(): LicensePolicy = fromJson(readJsonResource(RESOURCE))

        /** 정책 JSON 루트 객체 → 정책. 형식이 어긋나면 [IllegalStateException]. */
        internal fun fromJson(root: JsonObject): LicensePolicy {
            val mapping = root["hangarTypeToSpdx"]?.let { element ->
                val obj = element as? JsonObject ?: throw IllegalStateException("$RESOURCE: hangarTypeToSpdx 는 객체여야 한다")
                obj.mapValues { (k, v) -> v.stringValue("hangarTypeToSpdx.$k") }
            }.orEmpty()
            return LicensePolicy(
                enabled = root["redistributableEnabled"].booleanValue("redistributableEnabled", default = false),
                allow = root["redistributableSpdx"].stringList("redistributableSpdx").toSet(),
                hangarTypeToSpdx = mapping,
                versionless = root["hangarVersionlessTypes"].stringList("hangarVersionlessTypes").toSet(),
                unspecified = root["hangarUnspecifiedTypes"].stringList("hangarUnspecifiedTypes").toSet(),
            )
        }
    }
}
