package kr.decacross.collector.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.decacross.analysis.InvalidDescriptor
import kr.decacross.analysis.JarAnalysisResult
import kr.decacross.analysis.JarDescriptors
import kr.decacross.analysis.JarMeta
import kr.decacross.analysis.PackMajorRange
import kr.decacross.analysis.PackMcmeta
import kr.decacross.analysis.PackMcmetaResult
import kr.decacross.collector.CollectorJson
import kr.decacross.compat.model.Capability
import kr.decacross.compat.model.PackFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class AnalysisJsonTest {
    private val version = "0.1.0+rules.1a2b3c4d"
    private val now = Instant.parse("2026-09-17T00:00:00Z")

    private fun obj(text: String): JsonObject = CollectorJson.parseToJsonElement(text).jsonObject

    @Test
    fun roundTrip_keyFields() {
        val analysis = fakeAnalysis(capabilities = listOf(storeEvidence(Capability.PermissionProvider, "impl Perm")))
            .let { it.copy(descriptors = it.descriptors.copy(invalid = listOf(InvalidDescriptor(JarMeta.Descriptor.MODS_TOML, "Phase 2")))) }
        val text = analysisJson(analysis, version)
        val doc = parseAnalysisJson(text)

        assertEquals(version, doc.analyzerVersion)
        assertNull(doc.unreadable)
        val d = doc.descriptors.single()
        assertEquals("PLUGIN_YML", d.descriptor)
        assertEquals("Essentials", d.name)
        assertEquals("2.22.0", d.version)
        assertEquals("1.20", d.apiVersion)
        assertEquals(listOf("Vault"), d.softDepend)
        assertEquals("Vault", d.deps.single().target)
        assertEquals("OPTIONAL", d.deps.single().kind)
        assertEquals(listOf(InvalidDescriptorDoc("MODS_TOML", "Phase 2")), doc.invalidDescriptors)
        val b = assertNotNull(doc.bytecode)
        assertEquals(17, b.requiredJavaFeature)
        assertEquals(61, b.outerMaxMajor)
        assertEquals(mapOf("61" to 3), b.majorHistogram)
        assertFalse(b.nms.binaryRefs)
        assertEquals(listOf(CapabilityDoc("permission_provider", "STORE", "SERVICE_PROVIDER", "impl Perm")), doc.capabilities)
        assertNull(doc.packMeta)

        // majorHistogram 키는 JSON 문자열 키, pack format·Capability 는 문자열
        val root = obj(text)
        assertEquals(JsonPrimitive(3), root["bytecode"]?.jsonObject?.get("majorHistogram")?.jsonObject?.get("61"))
        assertEquals("permission_provider", root["capabilities"]?.jsonArray?.single()?.jsonObject?.get("capability")?.jsonPrimitive?.content)
    }

    @Test
    fun unreadable_shape() {
        val text = unreadableJson("zip END header not found", version)
        val root = obj(text)

        assertEquals(version, root["analyzerVersion"]?.jsonPrimitive?.content)
        assertEquals("zip END header not found", root["unreadable"]?.jsonPrimitive?.content)
        assertEquals(JsonArray(emptyList()), root["descriptors"])
        assertEquals(JsonArray(emptyList()), root["invalidDescriptors"])
        assertEquals(JsonArray(emptyList()), root["capabilities"])
        assertEquals(JsonArray(emptyList()), root["notes"])
        // 나머지는 비어 있거나 없다(null)
        val doc = parseAnalysisJson(text)
        assertNull(doc.bytecode)
        assertNull(doc.packMeta)
        assertEquals(AnalysisDoc(analyzerVersion = version, unreadable = "zip END header not found"), doc)
    }

    @Test
    fun recordMapping_providesStoreOnly_javaMajor_apiVersion() {
        val analysis = fakeAnalysis(
            capabilities = listOf(
                storeEvidence(Capability.PermissionProvider, "a"),
                candidateEvidence(Capability.EconomyProvider, "b"),
                storeEvidence(Capability.PermissionProvider, "c"),
                storeEvidence(Capability.CustomItemFramework, "d"),
            ),
            apiVersion = "1.13",
            javaFeature = 8,
        )
        val ok = analysisRecordOf(JarAnalysisResult.Ok(analysis), "ab".repeat(32), 1234, now, version)

        assertEquals(listOf(Capability.PermissionProvider, Capability.CustomItemFramework), ok.provides)
        assertEquals(8, ok.javaMajor)
        assertEquals("1.13", ok.apiVersion)
        assertNull(ok.packDecl)
        assertEquals("ab".repeat(32), ok.sha256)
        assertEquals(1234, ok.size)
        assertEquals(now, ok.analyzedAt)
        assertEquals(version, ok.analyzerVersion)
        assertEquals(analysisJson(analysis, version), ok.analysisJson)

        // primary descriptor 가 없으면 apiVersion null
        val noDescriptor = analysis.copy(descriptors = JarDescriptors(emptyList()))
        assertNull(analysisRecordOf(JarAnalysisResult.Ok(noDescriptor), "ab".repeat(32), 1, now, version).apiVersion)

        val unreadable = analysisRecordOf(JarAnalysisResult.Unreadable("broken"), "cd".repeat(32), 99, now, version)
        assertNull(unreadable.javaMajor)
        assertNull(unreadable.apiVersion)
        assertEquals(emptyList(), unreadable.provides)
        assertEquals(unreadableJson("broken", version), unreadable.analysisJson)
    }

    @Test
    fun candidateEvidence_neverSerialized() {
        val analysis = fakeAnalysis(
            capabilities = listOf(
                storeEvidence(Capability.PermissionProvider, "store-evidence"),
                candidateEvidence(Capability.EconomyProvider, "zz-candidate-evidence-zz"),
            ),
        )
        val text = analysisJson(analysis, version)

        assertFalse(text.contains("CANDIDATE"), text)
        assertFalse(text.contains("zz-candidate-evidence-zz"), text)
        assertFalse(text.contains("economy_provider"), text)
        assertTrue(text.contains("store-evidence"), text)
        assertEquals(1, parseAnalysisJson(text).capabilities.size)

        // CANDIDATE 만 있으면 capabilities 는 빈 배열
        val onlyCandidates = analysisJson(fakeAnalysis(capabilities = listOf(candidateEvidence(Capability.EconomyProvider))), version)
        assertEquals(JsonArray(emptyList()), obj(onlyCandidates)["capabilities"])
    }

    @Test
    fun packFormats_serializedAsStrings_noDeclCall() {
        // modernDecl()/legacyDecl() 은 이 사본에서 TODO 다 — 부르면 NotImplementedError 로 테스트가 깨진다
        val meta = PackMcmeta(
            packFormat = PackFormat(34),
            supportedFormats = PackMajorRange(PackFormat(16), PackFormat(34, Int.MAX_VALUE)),
            minFormat = PackFormat(88, 1),
            maxFormat = PackFormat(97, Int.MAX_VALUE),
            overlays = listOf(PackMcmeta.Overlay("overlay_1", PackMajorRange(PackFormat(18), PackFormat(19, Int.MAX_VALUE)), null, PackFormat(101, 1))),
            notes = listOf("note"),
        )
        val text = analysisJson(fakeAnalysis(packMeta = PackMcmetaResult.Ok(meta)), version)

        assertTrue(text.contains(""""supportedFormats":{"min":"16","max":"34.2147483647"}"""), text)
        val pack = assertNotNull(obj(text)["packMeta"]?.jsonObject)
        assertEquals(JsonPrimitive(true), pack["ok"])
        assertEquals(JsonPrimitive("34"), pack["packFormat"])
        assertEquals(JsonPrimitive("88.1"), pack["minFormat"])
        assertEquals(JsonPrimitive("97.2147483647"), pack["maxFormat"])
        val overlay = pack["overlays"]?.jsonArray?.single()?.jsonObject
        assertNotNull(overlay)
        assertEquals(JsonPrimitive("overlay_1"), overlay["directory"])
        assertEquals(JsonPrimitive("101.1"), overlay["maxFormat"])
        assertEquals(JsonPrimitive("18"), overlay["formats"]?.jsonObject?.get("min"))
        assertEquals(JsonPrimitive("19.2147483647"), overlay["formats"]?.jsonObject?.get("max"))
        // 어떤 pack format 도 JSON 숫자가 아니다
        for (key in listOf("packFormat", "minFormat", "maxFormat")) {
            val p = pack[key]
            assertIs<JsonPrimitive>(p)
            assertTrue(p.isString, "$key 는 문자열이어야 한다: $p")
        }

        val invalid = analysisJson(fakeAnalysis(packMeta = PackMcmetaResult.Invalid("min > max")), version)
        val bad = assertNotNull(obj(invalid)["packMeta"]?.jsonObject)
        assertEquals(JsonPrimitive(false), bad["ok"])
        assertEquals(JsonPrimitive("min > max"), bad["reason"])
        assertNull(bad["packFormat"])
    }
}
