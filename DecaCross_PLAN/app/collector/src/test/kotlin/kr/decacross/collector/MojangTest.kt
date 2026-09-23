package kr.decacross.collector

import kotlinx.coroutines.test.runTest
import kr.decacross.collector.sources.MojangSource
import kr.decacross.collector.sources.parseClientVersionJson
import kr.decacross.collector.sources.readClientPackFormats
import kr.decacross.compat.db.CompatFixture
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.PackFormat
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MojangTest {
    // ── version.json pack_version 변형 ──────────────────────────────────────

    @Test
    fun packVersion_singleInt_appliesToBoth() {
        val f = assertNotNull(parseClientVersionJson("""{"id":"1.14.4","pack_version":4,"protocol_version":498}"""))
        assertEquals(PackFormat(4), f.rp)
        assertEquals(PackFormat(4), f.dp)
        assertEquals(498, f.protocol)
    }

    @Test
    fun packVersion_resourceDataInts_1_21_8() {
        // 1.21.8 client.jar 실측
        val f = assertNotNull(parseClientVersionJson("""{"id":"1.21.8","protocol_version":772,"pack_version":{"resource":64,"data":81}}"""))
        assertEquals(PackFormat(64), f.rp)
        assertEquals(PackFormat(81), f.dp)
        assertEquals(772, f.protocol)
    }

    @Test
    fun packVersion_majorMinorSplitFields_26_3() {
        // 26.3 client.jar 실측
        val f = assertNotNull(
            parseClientVersionJson("""{"id":"26.3","protocol_version":777,"pack_version":{"resource_major":97,"resource_minor":1,"data_major":121,"data_minor":0}}"""),
        )
        assertEquals(PackFormat(97, 1), f.rp)
        assertEquals(PackFormat(121, 0), f.dp)
        assertEquals("97.1", f.rp.toString())
        assertEquals("121", f.dp.toString())
    }

    @Test
    fun packVersion_stringsAndObjects() {
        val s = assertNotNull(parseClientVersionJson("""{"pack_version":{"resource":"88.0","data":"101.1"}}"""))
        assertEquals(PackFormat(88, 0), s.rp)
        assertEquals(PackFormat(101, 1), s.dp)
        val o = assertNotNull(parseClientVersionJson("""{"pack_version":{"resource":{"major":88,"minor":2},"data":{"major":90,"minor":0}}}"""))
        assertEquals(PackFormat(88, 2), o.rp)
        assertEquals(PackFormat(90, 0), o.dp)
    }

    @Test
    fun packVersion_missingOrGarbage() {
        val none = assertNotNull(parseClientVersionJson("""{"id":"1.12.2"}"""))
        assertNull(none.rp)
        assertNull(none.dp)
        assertNull(parseClientVersionJson("not json"))
        val bad = assertNotNull(parseClientVersionJson("""{"pack_version":{"resource":"x","data":-1}}"""))
        assertNull(bad.rp)
        assertNull(bad.dp)
        assertTrue(bad.notes.isNotEmpty())
    }

    @Test
    fun readClientPackFormats_fallsBackToPackMcmeta() {
        val jar = Files.createTempFile("client", ".jar")
        try {
            ZipOutputStream(Files.newOutputStream(jar)).use { z ->
                z.putNextEntry(ZipEntry("pack.mcmeta"))
                z.write("""{"pack":{"pack_format":5,"description":"x"}}""".toByteArray())
                z.closeEntry()
            }
            val f = assertNotNull(readClientPackFormats(jar))
            assertEquals(PackFormat(5), f.rp)
            assertEquals(PackFormat(5), f.dp)
            assertTrue(f.notes.any { it.contains("pack.mcmeta") })
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    // ── manifest 샘플 → 파이프라인: 3 releases + 2 snapshots → 5행 ─────────────

    private val manifest = """
        {"latest":{"release":"1.21.1","snapshot":"24w34a"},"versions":[
          {"id":"24w34a","type":"snapshot","url":"https://meta.test/24w34a.json","time":"x","releaseTime":"2024-08-22T12:00:00+00:00"},
          {"id":"24w33a","type":"snapshot","url":"https://meta.test/24w33a.json","time":"x","releaseTime":"2024-08-15T12:00:00+00:00"},
          {"id":"1.21.1","type":"release","url":"https://meta.test/1.21.1.json","time":"x","releaseTime":"2024-08-08T12:00:00+00:00"},
          {"id":"1.21","type":"release","url":"https://meta.test/1.21.json","time":"x","releaseTime":"2024-06-13T12:00:00+00:00"},
          {"id":"1.20.4","type":"release","url":"https://meta.test/1.20.4.json","time":"x","releaseTime":"2023-12-07T12:00:00+00:00"},
          {"id":"b1.7.3","type":"old_beta","url":"https://meta.test/b1.7.3.json","time":"x","releaseTime":"2011-07-11T22:00:00+00:00"}
        ]}
    """.trimIndent()

    private fun detail(id: String, java: Int?) = buildString {
        append("""{"id":"$id","type":"release","releaseTime":"2024-01-01T00:00:00+00:00",""")
        if (java != null) append(""""javaVersion":{"component":"x","majorVersion":$java},""")
        append(""""downloads":{"client":{"sha1":"abc","size":10,"url":"https://data.test/$id/client.jar"}}}""")
    }

    @Test
    fun pipeline_manifestSample_fiveRowsWithSnapshotOrdinals() = runTest {
        val engine = routedEngine(
            mapOf(
                "version_manifest_v2.json" to manifest,
                "/24w34a.json" to detail("24w34a", 21),
                "/24w33a.json" to detail("24w33a", 21),
                "/1.21.1.json" to detail("1.21.1", 21),
                "/1.21.json" to detail("1.21", 21),
                "/1.20.4.json" to detail("1.20.4", null),
            ),
        )
        val dir = Files.createTempDirectory("collector-test")
        val out = dir.resolve("snapshot.json")
        val opts = CollectorOptions(sources = setOf(SourceName.MOJANG), out = out, input = null, maxClientJars = 0, tmpDir = dir.resolve("tmp"), databaseUrl = null)
        val report = testHttp(engine).use { http ->
            CollectorPipeline(opts, http, mojang = MojangSource(http, "https://meta.test/version_manifest_v2.json")).runOnce()
        }
        assertEquals(5, report.counts.mcVersions)
        assertEquals(3, report.counts.mcReleases)
        assertEquals(2, report.counts.mcSnapshots)
        assertEquals(listOf("1.20.4"), report.javaMinFallback)

        val fx = CompatFixture.fromJson(Files.readString(out))
        val byLabel = fx.mcVersions.associateBy { it.label }
        assertEquals(McOrdinal(1000), byLabel.getValue("1.20.4").ordinal)
        assertEquals(McOrdinal(1010), byLabel.getValue("1.21").ordinal)
        assertEquals(McOrdinal(1020), byLabel.getValue("1.21.1").ordinal)
        assertEquals(McOrdinal(1021), byLabel.getValue("24w33a").ordinal)
        assertEquals(McOrdinal(1022), byLabel.getValue("24w34a").ordinal)
        assertTrue(byLabel.getValue("24w34a").isSnapshot)
        assertFalse(byLabel.getValue("1.21.1").isSnapshot)
        assertEquals(8, byLabel.getValue("1.20.4").javaMin)
        assertEquals(21, byLabel.getValue("1.21.1").javaMin)
        assertNull(byLabel["b1.7.3"])
        assertTrue(Files.isRegularFile(dir.resolve("collector-report.json")))

        // 두 번째 실행 (--in = 이전 출력): 서수 불변, 상세 재요청 없음
        val second = testHttp(engine).use { http ->
            CollectorPipeline(opts, http, mojang = MojangSource(http, "https://meta.test/version_manifest_v2.json")).runOnce()
        }
        assertEquals(5, second.counts.mcVersions)
        val fx2 = CompatFixture.fromJson(Files.readString(out))
        assertEquals(fx.mcVersions.map { it.label to it.ordinal }, fx2.mcVersions.map { it.label to it.ordinal })
    }
}
