package kr.decacross.collector.content

import kr.decacross.analysis.ANALYZER_VERSION
import kr.decacross.analysis.CapabilityRules
import kr.decacross.compat.model.Capability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AnalyzerFingerprintTest {
    private val itemsAdder = CapabilityRules.Identity("ItemsAdder", "dev/lone/itemsadder/api/", Capability.CustomItemFramework)
    private val oraxen = CapabilityRules.Identity("Oraxen", "io/th0rgal/oraxen/", Capability.CustomItemFramework)
    private val nexo = CapabilityRules.Identity("Nexo", "com/nexomc/nexo/", Capability.CustomItemFramework)

    @Test
    fun stable_orderIndependent_changesWithRules() {
        val rules = CapabilityRules(identities = listOf(itemsAdder, oraxen, nexo))
        val v = effectiveAnalyzerVersion(rules)

        // 형식: "<ANALYZER_VERSION>+rules.<8 hex>"
        assertTrue(Regex("""^${Regex.escape(ANALYZER_VERSION)}\+rules\.[0-9a-f]{8}$""").matches(v), v)

        // 같은 규칙 → 같은 값
        assertEquals(v, effectiveAnalyzerVersion(CapabilityRules(identities = listOf(itemsAdder, oraxen, nexo))))

        // 순서만 다름 → 같은 값 (identities, serviceTypes, externalSupertypes 의 값 집합)
        assertEquals(v, effectiveAnalyzerVersion(CapabilityRules(identities = listOf(nexo, itemsAdder, oraxen))))
        val reversedServices = LinkedHashMap<String, Capability>().apply {
            CapabilityRules.DEFAULT_SERVICE_TYPES.entries.reversed().forEach { (k, c) -> put(k, c) }
        }
        val reorderedExternal = CapabilityRules.DEFAULT_EXTERNAL_SUPERTYPES.mapValues { (_, s) -> LinkedHashSet(s.reversed()) }
        assertEquals(
            v,
            effectiveAnalyzerVersion(
                CapabilityRules(identities = listOf(oraxen, nexo, itemsAdder), serviceTypes = reversedServices, externalSupertypes = reorderedExternal),
            ),
        )

        // 접두사 하나가 바뀜 → 다른 값
        val changedPrefix = CapabilityRules(identities = listOf(itemsAdder, oraxen.copy(definedPackagePrefix = "io/th0rgal/oraxen/api/"), nexo))
        assertNotEquals(v, effectiveAnalyzerVersion(changedPrefix))

        // 식별자 추가·경제 스위치·서비스 표 변경 → 다른 값
        val craftEngine = CapabilityRules.Identity("CraftEngine", "net/momirealms/craftengine/", Capability.CustomItemFramework)
        assertNotEquals(v, effectiveAnalyzerVersion(CapabilityRules(identities = listOf(itemsAdder, oraxen, nexo, craftEngine))))
        assertNotEquals(v, effectiveAnalyzerVersion(rules.copy(storeEconomyProviders = true)))
        assertNotEquals(v, effectiveAnalyzerVersion(rules.copy(serviceTypes = rules.serviceTypes - "net/milkbowl/vault/economy/Economy")))
        assertNotEquals(v, effectiveAnalyzerVersion(rules.copy(externalSupertypes = emptyMap())))

        // 리소스에서 두 번 읽어도 같은 값
        assertEquals(effectiveAnalyzerVersion(CapabilityRulesLoader.load()), effectiveAnalyzerVersion(CapabilityRulesLoader.load()))
    }
}
