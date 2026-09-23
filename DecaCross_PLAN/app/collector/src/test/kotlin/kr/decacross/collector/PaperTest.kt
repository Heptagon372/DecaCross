package kr.decacross.collector

import kotlinx.coroutines.test.runTest
import kr.decacross.collector.sources.PaperSource
import kr.decacross.collector.sources.PurpurSource
import kr.decacross.collector.sources.fillChannel
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.McOrdinal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaperTest {
    @Test
    fun channelMapping_onlyStableIsStable() {
        assertEquals(Channel.STABLE, fillChannel("STABLE"))
        assertEquals(Channel.STABLE, fillChannel("stable"))
        assertEquals(Channel.EXPERIMENTAL, fillChannel("ALPHA"))
        assertEquals(Channel.EXPERIMENTAL, fillChannel("BETA"))
        assertEquals(Channel.EXPERIMENTAL, fillChannel("whatever"))
    }

    // Fill v3 실측 형태 (2026-09)
    private val project = """{"project":{"id":"paper","name":"Paper"},"versions":{"26.3":["26.3","26.3-rc-3"],"1.21":["1.21.8"]}}"""
    private val builds1218 = """[
      {"id":60,"time":"2025-09-06T21:50:11.982Z","channel":"STABLE","commits":[],
       "downloads":{"server:default":{"name":"paper-1.21.8-60.jar","checksums":{"sha256":"8de7c52c3b02403503d16fac58003f1efef7dd7a0256786843927fa92ee57f1e"},"size":52811717,"url":"https://fill-data.papermc.io/v1/objects/8de7/paper-1.21.8-60.jar"}}},
      {"id":59,"time":"2025-09-01T00:00:00.000Z","channel":"ALPHA","downloads":{"server:default":{"name":"paper-1.21.8-59.jar","checksums":{"sha256":"aa"},"size":1,"url":"https://x/59.jar"}}}
    ]"""
    private val builds263 = """[{"id":6,"time":"2026-09-16T00:00:00Z","channel":"BETA","downloads":{"server:default":{"name":"paper-26.3-6.jar","checksums":{"sha256":"bb"},"size":2,"url":"https://x/6.jar"}}}]"""

    @Test
    fun paper_collectsAllBuildsAndSkipsUnknownLabels() = runTest {
        val engine = routedEngine(
            mapOf(
                "/projects/paper/versions/1.21.8/builds" to builds1218,
                "/projects/paper/versions/26.3/builds" to builds263,
                "/projects/paper" to project,
            ),
        )
        val mc = mapOf("1.21.8" to McOrdinal(1900), "26.3" to McOrdinal(2020))
        val skipped = ArrayList<String>()
        val builds = testHttp(engine).use { PaperSource(it).collect(mc, skipped) }
        assertEquals(3, builds.size)
        assertEquals(listOf("paper:26.3-rc-3"), skipped)
        val b60 = builds.first { it.build == "60" }
        assertEquals(CoreKey.PAPER, b60.core)
        assertEquals(McOrdinal(1900), b60.mc)
        assertEquals(Channel.STABLE, b60.channel)
        assertEquals("8de7c52c3b02403503d16fac58003f1efef7dd7a0256786843927fa92ee57f1e", b60.sha256)
        assertEquals(52811717L, b60.size)
        assertEquals(Channel.EXPERIMENTAL, builds.first { it.build == "59" }.channel)
        assertEquals(Channel.EXPERIMENTAL, builds.first { it.build == "6" }.channel)
    }

    @Test
    fun purpur_noSha256_isEmptyString() = runTest {
        val engine = routedEngine(
            mapOf(
                "/v2/purpur/1.21.8" to """{"project":"purpur","version":"1.21.8","builds":{"latest":"2497","all":["2496","2497"]}}""",
                "/v2/purpur" to """{"project":"purpur","metadata":{"current":"26.2"},"versions":["1.21.8","9.9.9"]}""",
            ),
        )
        val skipped = ArrayList<String>()
        val builds = testHttp(engine).use { PurpurSource(it).collect(mapOf("1.21.8" to McOrdinal(1900)), skipped) }
        assertEquals(2, builds.size)
        assertEquals(listOf("purpur:9.9.9"), skipped)
        assertTrue(builds.all { it.core == CoreKey.PURPUR && it.sha256 == "" && it.size == 0L })
        assertEquals("https://api.purpurmc.org/v2/purpur/1.21.8/2497/download", builds.first { it.build == "2497" }.downloadUrl)
    }
}
