package kr.decacross.collector.sources.mojang

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.decacross.compat.model.PackFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** §9.2 VersionJsonParser (AE-8). */
class VersionJsonParserTest {
    @Test
    fun releases_fixture_allMatchExpected() {
        val releases = MojangTestSupport.jarMetaReleases
        assertEquals(52, releases.size)
        for ((label, el) in releases) {
            val o = el.jsonObject
            val vj = o["versionJson"]?.takeIf { it != JsonNull }?.toString()
            val pm = o["packMcmeta"]?.takeIf { it != JsonNull }?.toString()
            val expected = o.getValue("expected").jsonObject
            val facts = parseJarFacts(vj, pm, hasDataDir = true)
            assertEquals(expected.string("era"), facts.era, "$label era")
            assertEquals(expected.string("rp"), facts.rp?.toString(), "$label rp")
            assertEquals(expected.string("dp"), facts.dp?.toString(), "$label dp")
            assertEquals(expected["protocol"]?.jsonPrimitive?.intOrNull, facts.protocol, "$label protocol")
        }
    }

    @Test
    fun e4_minorPreserved() {
        val o = MojangTestSupport.jarMetaReleases.getValue("26.3").jsonObject
        val facts = parseJarFacts(o.getValue("versionJson").toString(), null, hasDataDir = false)
        assertEquals("E4", facts.era)
        assertEquals("97.1", facts.rp.toString())
        assertEquals("121", facts.dp.toString())
        assertEquals(PackFormat(97, 1), facts.rp)
        assertEquals(777, facts.protocol)
        assertEquals(5023, facts.worldVersion)
        assertEquals("main", facts.seriesId)
    }

    @Test
    fun e2_intAppliesToBoth() {
        val facts = parseJarFacts("""{"id":"1.16.5","protocol_version":754,"pack_version":6}""", """{"pack":{"pack_format":99}}""", hasDataDir = true)
        assertEquals("E2", facts.era)
        assertEquals(PackFormat(6), facts.rp)
        assertEquals(PackFormat(6), facts.dp)
        assertEquals(754, facts.protocol)
    }

    @Test
    fun e3_objectResourceData() {
        val facts = parseJarFacts("""{"pack_version":{"resource":34,"data":48},"protocol_version":767}""", null, hasDataDir = true)
        assertEquals("E3", facts.era)
        assertEquals(PackFormat(34), facts.rp)
        assertEquals(PackFormat(48), facts.dp)
    }

    @Test
    fun e1_packMcmetaOnly() {
        val facts = parseJarFacts(null, """{"pack":{"description":"The default data for Minecraft","pack_format":4}}""", hasDataDir = true)
        assertEquals("E1", facts.era)
        assertEquals(PackFormat(4), facts.rp)
        assertEquals(PackFormat(4), facts.dp)
        assertNull(facts.protocol)
    }

    @Test
    fun e0_1_6_4_packMcmetaWithoutDataDir() {
        val facts = parseJarFacts(null, """{"pack":{"pack_format":1,"description":"The default look of Minecraft"}}""", hasDataDir = false)
        assertEquals("E0", facts.era)
        assertNull(facts.rp)
        assertNull(facts.dp)
        assertNull(facts.protocol)
    }

    @Test
    fun e0_nulls() {
        val facts = parseJarFacts(null, null, hasDataDir = true)
        assertEquals(JarFacts("E0", null, null, null, null, null), facts)
        // version.json 은 있는데 pack_version 이 없고 pack.mcmeta 도 없음
        assertEquals("E0", parseJarFacts("""{"id":"x","protocol_version":5}""", null, hasDataDir = true).era)
    }

    @Test
    fun malformed_nullsNoThrow() {
        val cases = listOf(
            "not json" to null,
            "[1,2,3]" to "{}",
            """{"pack_version":-1}""" to """{"pack":{"pack_format":-1}}""",
            """{"pack_version":{"resource_major":1.5,"resource_minor":0,"data_major":2,"data_minor":0}}""" to null,
            """{"pack_version":{"resource_major":3,"resource_minor":-1,"data_major":2,"data_minor":0}}""" to null,
            """{"pack_version":{"resource":"7","data":7}}""" to """{"pack":{"pack_format":"4"}}""",
            """{"pack_version":"5","protocol_version":"x","world_version":-3,"series_id":7}""" to """{"pack":[]}""",
            """{"pack_version":99999999999999}""" to """{"pack":{"pack_format":1e3}}""",
            """{"pack_version":{}}""" to "{",
        )
        for ((vj, pm) in cases) {
            val facts = parseJarFacts(vj, pm, hasDataDir = true)
            assertEquals("E0", facts.era, "$vj / $pm")
            assertNull(facts.rp, vj)
            assertNull(facts.dp, vj)
        }
        // E4/E3/E2 모양인데 값이 틀리면, 정상 pack.mcmeta + data/ 가 있어도 E1 로 내려가 추측하지 않는다
        val validMcmeta = """{"pack":{"pack_format":4}}"""
        val malformedClaims = listOf(
            """{"pack_version":-1}""",
            """{"pack_version":1.5}""",
            """{"pack_version":99999999999999}""",
            """{"pack_version":{"resource_major":1.5,"resource_minor":0,"data_major":2,"data_minor":0}}""",
            """{"pack_version":{"resource_major":3,"resource_minor":0}}""",
            """{"pack_version":{"resource":-7,"data":7}}""",
        )
        for (vj in malformedClaims) {
            assertEquals(JarFacts("E0", null, null, null, null, null), parseJarFacts(vj, validMcmeta, hasDataDir = true), vj)
        }
        // 규칙 모양이 아닌 pack_version(문자열·null·키 없는 객체)은 없는 것과 같다 → E1
        for (vj in listOf("""{"pack_version":"5"}""", """{"pack_version":null}""", """{"pack_version":{}}""")) {
            val facts = parseJarFacts(vj, validMcmeta, hasDataDir = true)
            assertEquals("E1", facts.era, vj)
            assertEquals(PackFormat(4), facts.rp, vj)
        }

        val weird = parseJarFacts("""{"pack_version":"5","protocol_version":"x","world_version":-3,"series_id":7}""", null, hasDataDir = true)
        assertNull(weird.protocol)
        assertNull(weird.worldVersion)
        assertNull(weird.seriesId)
    }

    private fun JsonObject.string(key: String): String? = this[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
}
