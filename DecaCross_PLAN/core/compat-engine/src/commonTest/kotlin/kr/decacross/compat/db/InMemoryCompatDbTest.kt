package kr.decacross.compat.db

import kr.decacross.compat.Confidence
import kr.decacross.compat.model.Capability
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.PackFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemoryCompatDbTest {
    private val fixtureJson =
        """
        {
          "mcVersions": [
            {"ordinal": 1000, "label": "1.20.4", "releasedAt": "2023-12-07T12:00:00Z", "javaMin": 17, "javaRecommended": 17, "rpFormat": "22", "dpFormat": "26"},
            {"ordinal": 1010, "label": "1.21",   "releasedAt": "2024-06-13T12:00:00Z", "javaMin": 21, "javaRecommended": 21, "rpFormat": "34", "dpFormat": "48"},
            {"ordinal": 1020, "label": "1.21.8", "releasedAt": "2025-07-17T12:00:00Z", "javaMin": 21, "javaRecommended": 21, "rpFormat": null, "dpFormat": "81"},
            {"ordinal": 1025, "label": "26.3-pre1", "releasedAt": "2026-08-01T12:00:00Z", "isSnapshot": true, "javaMin": 25, "javaRecommended": 25, "rpFormat": null, "dpFormat": null},
            {"ordinal": 1030, "label": "26.3",   "releasedAt": "2026-09-01T12:00:00Z", "javaMin": 25, "javaRecommended": 25, "rpFormat": null, "dpFormat": "121.0"},
            {"ordinal": 1040, "label": "26.4-pre1", "releasedAt": "2026-09-10T12:00:00Z", "isSnapshot": true, "javaMin": 25, "javaRecommended": 25, "rpFormat": null, "dpFormat": null}
          ],
          "coreBuilds": [
            {"core": "PAPER", "mc": 1020, "build": "9",  "channel": "STABLE",       "downloadUrl": "u9",  "sha256": "a", "size": 1},
            {"core": "PAPER", "mc": 1020, "build": "60", "channel": "STABLE",       "downloadUrl": "u60", "sha256": "b", "size": 1},
            {"core": "PAPER", "mc": 1020, "build": "61", "channel": "EXPERIMENTAL", "downloadUrl": "u61", "sha256": "c", "size": 1}
          ],
          "content": [
            {"slug": "itemsadder", "name": "ItemsAdder", "kind": "PLUGIN", "source": "SPIGOT"},
            {"slug": "oraxen", "name": "Oraxen", "kind": "PLUGIN", "source": "MODRINTH", "license": "MIT", "redistributable": true}
          ],
          "contentVersions": [
            {"slug": "itemsadder", "version": "4.0.0", "deps": [{"kind": "PROVIDES", "target": {"type": "cap", "value": {"type": "custom_item_framework"}}}]},
            {"slug": "oraxen", "version": "1.190.0", "deps": [{"kind": "PROVIDES", "target": {"type": "cap", "value": {"type": "custom_item_framework"}}}, {"kind": "REQUIRE", "target": {"type": "slug", "value": "protocollib"}, "range": ">=5.0.0"}]}
          ],
          "confidence": [
            {"slug": "oraxen", "version": "1.190.0", "mc": 1020, "core": "PAPER", "confidence": "GREEN"}
          ]
        }
        """.trimIndent()

    private val db = CompatFixture.fromJson(fixtureJson).toDb()

    @Test
    fun mcByLabel_andPackFormatsAreSeparate() {
        val v = db.mcByLabel("1.21") ?: error("없음")
        assertEquals(PackFormat(34), v.rpFormat)
        assertEquals(PackFormat(48), v.dpFormat)
        assertEquals(PackFormat(121, 0), db.mcByLabel("26.3")?.dpFormat)
        assertNull(db.mcByLabel("1.21.8")?.rpFormat)
    }

    @Test
    fun mcLatest_skipsSnapshotsByDefault() {
        assertEquals("26.3", db.mcLatest()?.label)
        assertEquals("26.4-pre1", db.mcLatest(allowSnapshot = true)?.label)
    }

    @Test
    fun mcInFamily_matchesPrefixSegment_notSubstring() {
        assertEquals(listOf("1.21", "1.21.8"), db.mcInFamily("1.21").map { it.label })
        assertEquals(emptyList(), db.mcInFamily("1.2").map { it.label }, "'1.2' 는 '1.20.4' 나 '1.21' 을 포함하지 않는다")
        assertEquals(listOf("26.3-pre1", "26.3"), db.mcInFamily("26.3").map { it.label })
    }

    @Test
    fun coreBuilds_stableOnlyByDefault_andNewestFirst() {
        val stable = db.coreBuilds(CoreKey.PAPER, McOrdinal(1020))
        assertEquals(listOf("60", "9"), stable.map { it.build }, "숫자 기준 내림차순 (문자열이면 '9' > '60')")
        val all = db.coreBuilds(CoreKey.PAPER, McOrdinal(1020), stableOnly = false)
        assertEquals(listOf("61", "60", "9"), all.map { it.build })
        assertEquals(Channel.EXPERIMENTAL, all.first().channel)
        assertEquals(emptyList(), db.coreBuilds(CoreKey.PURPUR, McOrdinal(1020)))
    }

    @Test
    fun providersOf_isDerivedFromProvidesDeps() {
        assertEquals(listOf("itemsadder", "oraxen"), db.providersOf(Capability.CustomItemFramework))
        assertEquals(emptyList(), db.providersOf(Capability.EconomyProvider))
    }

    @Test
    fun confidence_defaultsToYellow_whenUnknown() {
        assertEquals(Confidence.GREEN, db.confidenceOf("oraxen", "1.190.0", McOrdinal(1020), CoreKey.PAPER))
        assertEquals(Confidence.YELLOW, db.confidenceOf("oraxen", "1.190.0", McOrdinal(1030), CoreKey.PAPER))
        assertEquals(Confidence.YELLOW, db.confidenceOf("itemsadder", "4.0.0", McOrdinal(1020), CoreKey.PAPER))
    }

    @Test
    fun redistributable_defaultsToFalse() {
        assertEquals(false, db.content("itemsadder")?.redistributable)
        assertEquals(true, db.content("oraxen")?.redistributable)
    }

    @Test
    fun fixture_roundTripsThroughJson() {
        val f = CompatFixture.fromJson(fixtureJson)
        val again = CompatFixture.fromJson(f.toJson())
        assertEquals(f, again)
    }
}
