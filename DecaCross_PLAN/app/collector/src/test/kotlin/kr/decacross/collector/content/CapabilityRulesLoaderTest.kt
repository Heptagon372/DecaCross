package kr.decacross.collector.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kr.decacross.analysis.CapabilityRules
import kr.decacross.compat.model.Capability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityRulesLoaderTest {
    @Test
    fun loadsThreeIdentities_noCraftEngine_economyNotStored() {
        val rules = CapabilityRulesLoader.load()

        assertEquals(
            listOf(
                CapabilityRules.Identity("ItemsAdder", "dev/lone/itemsadder/api/", Capability.CustomItemFramework),
                CapabilityRules.Identity("Oraxen", "io/th0rgal/oraxen/", Capability.CustomItemFramework),
                CapabilityRules.Identity("Nexo", "com/nexomc/nexo/", Capability.CustomItemFramework),
            ),
            rules.identities,
        )
        // Q8 미결: CraftEngine·ModelEngine 은 넣지 않는다
        assertTrue(rules.identities.none { it.pluginName.equals("CraftEngine", ignoreCase = true) || it.pluginName.equals("ModelEngine", ignoreCase = true) })
        // SCP-14: Economy 는 기본적으로 CANDIDATE 만
        assertFalse(rules.storeEconomyProviders)
        // Vault 계약 표는 코드 기본값 그대로 (AE-1, AE-2)
        assertEquals(CapabilityRules.DEFAULT_SERVICE_TYPES, rules.serviceTypes)
        assertEquals(CapabilityRules.DEFAULT_EXTERNAL_SUPERTYPES, rules.externalSupertypes)
        assertTrue(rules.identities.none { it.capability in CapabilityRules.NEVER_INFERRED })
    }

    @Test
    fun neverInferredCapability_rejectedAtLoad() {
        // 회귀 (INV-6): anti_cheat·chunk_generator 규칙은 분석기가 버리며 analysis.notes 에 흔적을 남긴다 → 로더가 먼저 거부한다
        fun rules(key: String) = Json.parseToJsonElement(
            """{"identities":[{"pluginName":"Some","definedPackagePrefix":"some/pkg/","capability":"$key"}]}""",
        ).jsonObject
        assertEquals(setOf(Capability.AntiCheat, Capability.ChunkGenerator), CapabilityRules.NEVER_INFERRED)
        for (key in listOf("anti_cheat", "chunk_generator")) {
            val e = assertFailsWith<IllegalStateException> { CapabilityRulesLoader.fromJson(rules(key)) }
            assertTrue(e.message.orEmpty().contains(key), e.message)
        }
        assertEquals(Capability.CustomItemFramework, CapabilityRulesLoader.fromJson(rules("custom_item_framework")).identities.single().capability)
    }
}
