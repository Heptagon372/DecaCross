package kr.decacross.collector.content

import kotlinx.serialization.json.JsonObject
import kr.decacross.collector.CollectorJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class LicensePolicyTest {
    /** 배포 리소스의 목록 그대로, 스위치만 켠 정책. */
    private val switchedOn: LicensePolicy = LicensePolicy.load().let {
        LicensePolicy(enabled = true, allow = it.allow, hangarTypeToSpdx = it.hangarTypeToSpdx, versionless = it.versionless, unspecified = it.unspecified)
    }

    private fun decision(license: String?, redistributable: Boolean) = LicensePolicy.Decision(license, redistributable)

    @Test
    fun modrinth_exactAllowlistOnly() {
        assertEquals(decision("MIT", true), switchedOn.modrinth("MIT"))
        assertEquals(decision("MIT", true), switchedOn.modrinth("  MIT "))
        assertEquals(decision("mit", false), switchedOn.modrinth("mit"))
        assertEquals(decision("LicenseRef-MIT-Non-Distribution", false), switchedOn.modrinth("LicenseRef-MIT-Non-Distribution"))
        assertEquals(decision("GPL-3.0-only", false), switchedOn.modrinth("GPL-3.0-only"))
        assertEquals(decision("LicenseRef-All-Rights-Reserved", false), switchedOn.modrinth("LicenseRef-All-Rights-Reserved"))
        assertEquals(decision(null, false), switchedOn.modrinth(null))
        assertEquals(decision(null, false), switchedOn.modrinth("   "))
    }

    @Test
    fun hangar_mapping_table() {
        assertEquals(decision("MIT", true), switchedOn.hangar("MIT", "MIT"))
        assertEquals(decision("Apache-2.0", true), switchedOn.hangar("Apache 2.0", "Apache 2.0"))
        // 버전을 지어내지 않는다
        assertEquals(decision("GPL", false), switchedOn.hangar("GPL", "GPL"))
        assertEquals(decision("LGPL", false), switchedOn.hangar("LGPL", null))
        assertEquals(decision("AGPL", false), switchedOn.hangar("AGPL", "AGPL"))
        // Other → 자유 텍스트 이름 (허용 목록 비교 없음)
        assertEquals(decision("BSD 3-Clause", false), switchedOn.hangar("Other", "BSD 3-Clause"))
        assertEquals(decision(null, false), switchedOn.hangar("Other", "   "))
        assertEquals(decision(null, false), switchedOn.hangar("Other", null))
        assertEquals(decision(null, false), switchedOn.hangar("Unspecified", "Unspecified"))
        assertEquals(decision(null, false), switchedOn.hangar(null, null))
        // 모르는 type → 원문, 재배포 불가
        assertEquals(decision("WTFPL", false), switchedOn.hangar("WTFPL", "WTFPL"))
    }

    @Test
    fun loadsFromResource_switchOff_allFalse_licenseKept() {
        val shipped = LicensePolicy.load()
        assertFalse(shipped.enabled)
        assertEquals(
            setOf("MIT", "MIT-0", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "0BSD", "ISC", "Zlib", "CC0-1.0", "Unlicense"),
            shipped.allow,
        )
        assertEquals(decision("MIT", false), shipped.modrinth("MIT"))
        assertEquals(decision("Apache-2.0", false), shipped.modrinth("Apache-2.0"))
        assertEquals(decision("MIT", false), shipped.hangar("MIT", "MIT"))
        assertEquals(decision("Apache-2.0", false), shipped.hangar("Apache 2.0", null))
        assertEquals(decision("GPL", false), shipped.hangar("GPL", "GPL"))

        // 키가 없으면 스위치는 꺼진 것으로 본다
        val root = CollectorJson.parseToJsonElement("""{"redistributableSpdx":["MIT"],"hangarTypeToSpdx":{"MIT":"MIT"}}""")
        assertIs<JsonObject>(root)
        val noSwitch = LicensePolicy.fromJson(root)
        assertFalse(noSwitch.enabled)
        assertEquals(decision("MIT", false), noSwitch.modrinth("MIT"))
    }
}
