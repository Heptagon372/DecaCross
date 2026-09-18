package kr.decacross.collector.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonSupportTest {
    @Test
    fun readJsonResource_stripsUtf8Bom() {
        // 회귀 (C3-R3): Windows 편집기가 붙인 BOM 때문에 license-policy.json·capability-identities.json 로딩이 실패하면 안 된다
        val raw = checkNotNull(JsonSupportTest::class.java.getResourceAsStream(BOM_RESOURCE)) { "테스트 리소스 없음" }.use { it.readBytes() }
        assertTrue(raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte(), "리소스가 BOM 으로 시작해야 한다")

        val root = readJsonResource(BOM_RESOURCE)
        assertEquals(true, root["storeEconomyProviders"].booleanValue("storeEconomyProviders", default = false))
        assertEquals(listOf("a"), root["list"].stringList("list"))
    }

    @Test
    fun readJsonResource_missing_throws() {
        assertFailsWith<IllegalStateException> { readJsonResource("/c3/does-not-exist.json") }
    }

    private companion object {
        const val BOM_RESOURCE = "/c3/bom-resource.json"
    }
}
