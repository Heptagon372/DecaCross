package kr.decacross.collector.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kr.decacross.analysis.CapabilityRules
import kr.decacross.collector.store.capabilityFromDbKey

/**
 * 클래스패스 리소스 [RESOURCE] → [CapabilityRules].
 * 정체성 규칙(플러그인 이름·패키지)은 수집 데이터라 코드에 하드코딩하지 않는다 (CLAUDE.md 코딩 규칙).
 */
object CapabilityRulesLoader {
    const val RESOURCE: String = "/capability-identities.json"

    /** 리소스가 없거나 깨졌거나 추론 금지 Capability 를 가리키면 예외 — 수집기 기동 시 즉시 드러나야 한다. */
    fun load(): CapabilityRules = fromJson(readJsonResource(RESOURCE))

    /** 규칙 JSON 루트 객체 → 규칙. 형식이 어긋나면 [IllegalStateException]. */
    internal fun fromJson(root: JsonObject): CapabilityRules {
        val storeEconomy = root["storeEconomyProviders"].booleanValue("storeEconomyProviders", default = false)
        val identities = root["identities"]?.let { element ->
            val array = element as? JsonArray ?: throw IllegalStateException("$RESOURCE: identities 는 배열이어야 한다")
            array.mapIndexed { i, item ->
                val obj = item as? JsonObject ?: throw IllegalStateException("$RESOURCE: identities[$i] 는 객체여야 한다")
                identityOf(obj, "identities[$i]")
            }
        }.orEmpty()
        // serviceTypes·externalSupertypes 는 Vault API 계약이라 코드 기본값을 그대로 쓴다 (AE-1, AE-2)
        return CapabilityRules(identities = identities, storeEconomyProviders = storeEconomy)
    }

    private fun identityOf(obj: JsonObject, where: String): CapabilityRules.Identity {
        fun field(name: String): String =
            (obj[name] ?: throw IllegalStateException("$RESOURCE: $where.$name 없음")).stringValue("$where.$name")

        val pluginName = field("pluginName")
        val prefix = field("definedPackagePrefix")
        val key = field("capability")
        check(pluginName.isNotBlank() && prefix.isNotBlank()) { "$RESOURCE: $where 의 pluginName/definedPackagePrefix 가 비었다" }
        val capability = capabilityFromDbKey(key)
            ?: throw IllegalStateException("$RESOURCE: $where 의 알 수 없는 capability '$key'")
        // 추론 금지 Capability(anti_cheat·chunk_generator)는 규칙으로 받지 않는다 — 분석기가 조용히 버리고 note 를 남기는 대신 기동 시 드러낸다 (D34, INV-6)
        check(capability !in CapabilityRules.NEVER_INFERRED) { "$RESOURCE: $where 의 capability '$key' 는 추론 금지 대상이다" }
        return CapabilityRules.Identity(pluginName = pluginName, definedPackagePrefix = prefix, capability = capability)
    }
}
