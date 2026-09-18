package kr.decacross.collector.sources.fill

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.decacross.collector.config.CollectorSettings
import kr.decacross.collector.config.FillSettings
import kr.decacross.collector.core.RunMode
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.core.SourceStatus
import kr.decacross.collector.store.CoreBuildRow
import kr.decacross.collector.store.McRow
import kr.decacross.collector.testkit.FakeHttp
import kr.decacross.collector.testkit.FakeResponse
import kr.decacross.collector.testkit.FixedClock
import kr.decacross.collector.testkit.RecordingStore
import kr.decacross.collector.testkit.deleteTree
import kr.decacross.collector.testkit.newTempDir
import kr.decacross.collector.testkit.testContext
import kr.decacross.collector.testkit.testSettings
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.McOrdinal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** §9.3 FillSource (paper, folia). */
class FillSourceTest {
    private val dir = newTempDir()
    private val base = "https://fill.test"
    private val t0 = Instant.parse("2026-09-17T00:00:00Z")
    private val settings: CollectorSettings = testSettings(dir).copy(fill = FillSettings(baseUrl = base))

    @AfterTest
    fun cleanup() = deleteTree(dir)

    private fun res(path: String): String = checkNotNull(javaClass.getResourceAsStream(path)) { path }.use { it.readBytes().decodeToString() }

    private fun versionsUrl(project: String = "paper") = "$base/v3/projects/$project/versions"

    private fun buildsUrl(id: String, project: String = "paper") = "$base/v3/projects/$project/versions/$id/builds"

    private fun store(vararg labels: String): RecordingStore = RecordingStore().also { s ->
        labels.forEachIndexed { i, label ->
            s.mc += McRow(i + 1L, label, McOrdinal(1000 + i * 10), t0, false, 21, 21, null, null, null, null, null)
        }
    }

    private fun known(store: RecordingStore, label: String, vararg builds: Int, core: CoreKey = CoreKey.PAPER, channel: Channel = Channel.STABLE) {
        for (b in builds) store.coreBuilds[Triple(core, label, b.toString())] = row(label, b, channel)
    }

    private fun row(label: String, build: Int, channel: Channel = Channel.STABLE) =
        CoreBuildRow(label, build.toString(), channel, "https://fill-data.test/$build.jar", sha(build), 1000L + build, t0)

    private fun sha(n: Int) = "%064x".format(n)

    private fun build(
        id: Int,
        channel: String = "STABLE",
        time: String = "2026-09-16T19:27:27Z",
        sha256: String? = sha(id),
        size: Long = 1000L + id,
        key: String = "server:default",
    ): String {
        val checksums = sha256?.let { """"sha256":"$it"""" } ?: ""
        return """{"id":$id,"time":"$time","channel":"$channel","commits":[],"downloads":{"$key":{"name":"paper-$id.jar","checksums":{$checksums},"size":$size,"url":"https://fill-data.test/$id.jar"}}}"""
    }

    private fun builds(vararg b: String) = b.joinToString(",", "[", "]")

    private fun versions(vararg v: Triple<String, String, List<Int>>) =
        v.joinToString(",", """{"versions":[""", "]}") { (id, status, builds) -> """{"version":{"id":"$id","support":{"status":"$status"}},"builds":$builds}""" }

    /** --loop 에서 전체 순회가 아직 필요 없는 상태. */
    private fun recentSweep(store: RecordingStore, project: String = "paper") {
        store.state[fillSweepStateKey(project)] = """{"at":"$t0"}"""
    }

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

    @Test
    fun once_serverDefaultOnly_badShaSkipped_unknownLabelSkipped_noEtagStored() = runTest {
        val fixture = Json.parseToJsonElement(res("/c2/fill/paper_1.21.8_builds.json")).jsonArray.map { it.jsonObject }.toMutableList()
        fun edit(i: Int, f: (JsonObject) -> JsonObject) {
            val dl = fixture[i].getValue("downloads").jsonObject
            fixture[i] = fixture[i].with("downloads", dl.with("server:default", f(dl.getValue("server:default").jsonObject)))
        }
        // build 60: server:mojang 만 있음
        val d60 = fixture[0].getValue("downloads").jsonObject.getValue("server:default")
        fixture[0] = fixture[0].with("downloads", JsonObject(mapOf("server:mojang" to d60)))
        // build 59: sha256 형식 오류, build 58: 대문자 sha256 (소문자로 받아들임), build 57: size 0
        edit(1) { it.with("checksums", JsonObject(mapOf("sha256" to JsonPrimitive("not-a-sha")))) }
        val sha58 = fixture[2].getValue("downloads").jsonObject.getValue("server:default").jsonObject
            .getValue("checksums").jsonObject.getValue("sha256").jsonPrimitive.content
        edit(2) { it.with("checksums", JsonObject(mapOf("sha256" to JsonPrimitive(sha58.uppercase())))) }
        edit(3) { it.with("size", JsonPrimitive(0)) }

        val http = FakeHttp(dir)
            .onJson(versionsUrl(), res("/c2/fill/paper_versions.json"), "versions-etag")
            .onJson(buildsUrl("1.21.8"), JsonArray(fixture).toString(), "b-1.21.8")
            .onJson(buildsUrl("26.2"), builds(build(10), build(11, channel = "ALPHA")), "b-26.2")
        val store = store("1.21.8", "26.2")

        val report = FillSource(FillProject.PAPER).collect(testContext(http, store, settings))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertEquals(2L, report.counters["skip.noMcVersion"], "26.3, 26.3-rc-3 는 mc_versions 에 없다")
        assertEquals(1L, report.counters["skip.noServerDefault"])
        assertEquals(2L, report.counters["skip.badChecksum"])
        assertEquals(9L, report.counters["inserted"])
        assertTrue(http.requests.none { it.url == buildsUrl("26.3") || it.url == buildsUrl("26.3-rc-3") })
        assertNull(store.coreBuilds[Triple(CoreKey.PAPER, "1.21.8", "60")])
        assertNull(store.coreBuilds[Triple(CoreKey.PAPER, "1.21.8", "59")])
        assertNull(store.coreBuilds[Triple(CoreKey.PAPER, "1.21.8", "57")])
        assertEquals(sha58.lowercase(), store.coreBuilds[Triple(CoreKey.PAPER, "1.21.8", "58")]?.sha256)
        val b56 = assertNotNull(store.coreBuilds[Triple(CoreKey.PAPER, "1.21.8", "56")])
        assertEquals(Channel.STABLE, b56.channel)
        assertTrue(b56.downloadUrl.startsWith("https://fill-data.papermc.io/"))
        assertEquals(Channel.EXPERIMENTAL, store.coreBuilds[Triple(CoreKey.PAPER, "26.2", "11")]?.channel)

        assertNotNull(store.state[fillBuildsStateKey("paper", "1.21.8")])
        assertNotNull(store.state[fillBuildsStateKey("paper", "26.2")])
        assertNull(store.state[fillBuildsStateKey("paper", "26.3")], "mc label 이 없으면 ETag 를 저장하지 않는다")
        assertNull(store.state[fillBuildsStateKey("paper", "26.3-rc-3")])
        assertTrue(http.requests.all { it.conditional == null })
    }

    @Test
    fun timeFormats_bothParse() = runTest {
        val http = FakeHttp(dir)
            .onJson(versionsUrl(), versions(Triple("26.2", "SUPPORTED", listOf(1, 2, 3))))
            .onJson(buildsUrl("26.2"), builds(build(1, time = "2025-09-06T21:50:11.982Z"), build(2, time = "2026-09-16T19:27:27Z"), build(3, time = "yesterday")))
        val store = store("26.2")

        val report = FillSource(FillProject.PAPER).collect(testContext(http, store, settings))

        assertEquals(SourceStatus.OK, report.status)
        assertEquals(Instant.parse("2025-09-06T21:50:11.982Z"), store.coreBuilds[Triple(CoreKey.PAPER, "26.2", "1")]?.publishedAt)
        assertEquals(Instant.parse("2026-09-16T19:27:27Z"), store.coreBuilds[Triple(CoreKey.PAPER, "26.2", "2")]?.publishedAt)
        val b3 = assertNotNull(store.coreBuilds[Triple(CoreKey.PAPER, "26.2", "3")])
        assertNull(b3.publishedAt)
        assertEquals(1L, report.counters["publishedAt.unparsed"])
    }

    @Test
    fun unsortedBuildIds_treatedAsSet() = runTest {
        val http = FakeHttp(dir)
            .onJson(versionsUrl(), versions(Triple("26.2", "UNSUPPORTED", listOf(105, 104, 103)), Triple("1.21.8", "UNSUPPORTED", listOf(3, 1, 2))))
            .onJson(buildsUrl("26.2"), builds(build(103), build(104), build(105)))
            .onJson(buildsUrl("1.21.8"), builds(build(1), build(2), build(3)))
        val store = store("26.2", "1.21.8")
        known(store, "26.2", 103, 104, 105)
        known(store, "1.21.8", 1, 2)
        recentSweep(store)

        val report = FillSource(FillProject.PAPER).collect(testContext(http, store, settings, mode = RunMode.LOOP, clock = FixedClock(t0 + 1.hours)))

        assertEquals(SourceStatus.OK, report.status)
        assertTrue(http.requests.none { it.url == buildsUrl("26.2") }, "순서만 다른 같은 빌드 집합")
        assertTrue(http.requests.any { it.url == buildsUrl("1.21.8") })
        assertEquals(1L, report.counters["inserted"])
    }

    @Test
    fun etag304_withKnownBuilds_skips() = runTest {
        val http = FakeHttp(dir)
            .onJson(versionsUrl(), versions(Triple("26.2", "SUPPORTED", listOf(10, 11))))
            .onJson(buildsUrl("26.2"), builds(build(10), build(11)), "b1")
        val store = store("26.2")
        known(store, "26.2", 10)
        store.state[fillBuildsStateKey("paper", "26.2")] = """{"etag":"b1"}"""

        val report = FillSource(FillProject.PAPER).collect(testContext(http, store, settings))

        assertEquals(SourceStatus.OK, report.status)
        assertEquals(1L, report.counters["builds.notModified"])
        val req = http.requests.filter { it.url == buildsUrl("26.2") }
        assertEquals(1, req.size)
        assertEquals("b1", req.single().conditional?.etag)
        assertNull(store.coreBuilds[Triple(CoreKey.PAPER, "26.2", "11")])
    }

    @Test
    fun loop_versions304_usesSupportedIdsFromState() = runTest {
        val http = FakeHttp(dir)
            .onJson(versionsUrl(), res("/c2/fill/paper_versions.json"), "v1")
            .onJson(buildsUrl("26.2"), builds(build(10)))
            .onJson(buildsUrl("1.21.8"), builds(build(1)))
        val store = store("26.2", "1.21.8")
        store.state[fillVersionsStateKey("paper")] = """{"etag":"v1","supported":["26.2"]}"""
        recentSweep(store)

        val report = FillSource(FillProject.PAPER).collect(testContext(http, store, settings, mode = RunMode.LOOP, clock = FixedClock(t0 + 30.minutes)))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertEquals(1L, report.counters["versions.notModified"])
        assertEquals("v1", http.requests.first { it.url == versionsUrl() }.conditional?.etag)
        assertEquals(listOf(versionsUrl(), buildsUrl("26.2")), http.requests.map { it.url })
        assertNotNull(store.coreBuilds[Triple(CoreKey.PAPER, "26.2", "10")])
    }

    @Test
    fun etag304_withoutKnownBuilds_refetches() = runTest {
        val http = FakeHttp(dir)
            .onJson(versionsUrl(), versions(Triple("26.2", "SUPPORTED", listOf(10))))
            .onJson(buildsUrl("26.2"), builds(build(10)), "b1")
        val store = store("26.2")
        store.state[fillBuildsStateKey("paper", "26.2")] = """{"etag":"b1"}"""

        val report = FillSource(FillProject.PAPER).collect(testContext(http, store, settings))

        assertEquals(SourceStatus.OK, report.status)
        val req = http.requests.filter { it.url == buildsUrl("26.2") }
        assertEquals(listOf("b1", null), req.map { it.conditional?.etag })
        assertEquals(1L, report.counters["inserted"])
    }

    @Test
    fun loop_selectsChangedOrSupported_only() = runTest {
        val http = FakeHttp(dir)
            .onJson(
                versionsUrl(),
                versions(
                    Triple("1.20.1", "UNSUPPORTED", listOf(1, 2)),
                    Triple("1.21.8", "UNSUPPORTED", listOf(1, 2, 3)),
                    Triple("26.2", "SUPPORTED", listOf(1)),
                ),
            )
            .onJson(buildsUrl("1.20.1"), builds(build(1), build(2)))
            .onJson(buildsUrl("1.21.8"), builds(build(1), build(2), build(3)))
            .onJson(buildsUrl("26.2"), builds(build(1)))
        val store = store("1.20.1", "1.21.8", "26.2")
        known(store, "1.20.1", 1, 2)
        known(store, "1.21.8", 1, 2)
        known(store, "26.2", 1)
        recentSweep(store)

        val report = FillSource(FillProject.PAPER).collect(testContext(http, store, settings, mode = RunMode.LOOP, clock = FixedClock(t0 + 2.hours)))

        assertEquals(SourceStatus.OK, report.status)
        val buildRequests = http.requests.map { it.url }.filter { it.endsWith("/builds") }
        assertEquals(listOf(buildsUrl("1.21.8"), buildsUrl("26.2")), buildRequests)
        assertEquals(2L, report.counters["versions.candidates"])

        // 전체 순회 시각이 24시간 지나면 전부 본다 (versions 도 무조건 요청)
        val n = http.requests.size
        val sweep = FillSource(FillProject.PAPER).collect(testContext(http, store, settings, mode = RunMode.LOOP, clock = FixedClock(t0 + 25.hours)))
        assertEquals(3L, sweep.counters["versions.candidates"])
        assertNull(http.requests[n].conditional)
        assertEquals((t0 + 25.hours).toString(), Json.parseToJsonElement(store.state.getValue(fillSweepStateKey("paper"))).jsonObject.getValue("at").jsonPrimitive.content)
    }

    @Test
    fun channelPromotion_updatesRow() = runTest {
        val http = FakeHttp(dir)
            .onJson(versionsUrl(), versions(Triple("26.2", "SUPPORTED", listOf(10, 11))))
            .onJson(buildsUrl("26.2"), builds(build(10, channel = "STABLE"), build(11, channel = "STABLE")))
        val store = store("26.2")
        known(store, "26.2", 10, channel = Channel.EXPERIMENTAL)
        known(store, "26.2", 11, channel = Channel.STABLE)

        val report = FillSource(FillProject.PAPER).collect(testContext(http, store, settings))

        assertEquals(1L, report.counters["updated"])
        assertEquals(1L, report.counters["unchanged"])
        assertEquals(Channel.STABLE, store.coreBuilds[Triple(CoreKey.PAPER, "26.2", "10")]?.channel)
    }

    @Test
    fun folia_betaOnlyFixture_foldedExperimental() = runTest {
        val http = FakeHttp(dir)
            .onJson(versionsUrl("folia"), versions(Triple("26.2", "SUPPORTED", listOf(1, 3, 4, 5, 6, 7))))
            .onJson(buildsUrl("26.2", "folia"), res("/c2/fill/folia_26.2_builds.json"))
        val store = store("26.2")

        val report = FillSource(FillProject.FOLIA).collect(testContext(http, store, settings))

        assertEquals(SourceStatus.OK, report.status)
        val folia = store.coreBuilds.filterKeys { it.first == CoreKey.FOLIA }.values
        assertEquals(6, folia.size)
        assertTrue(folia.all { it.channel == Channel.EXPERIMENTAL })
        assertTrue(report.warnings.isEmpty(), "BETA 는 알려진 채널")
        assertTrue(store.coreBuilds.keys.none { it.first == CoreKey.PAPER })
    }

    @Test
    fun versionsFailure_failed_buildsFailure_partial() = runTest {
        val failing = FakeHttp(dir).on(versionsUrl(), FakeResponse.Status(500))
        val store1 = store("26.2")
        val failed = FillSource(FillProject.PAPER).collect(testContext(failing, store1, settings))
        assertEquals(SourceStatus.FAILED, failed.status)
        assertTrue(store1.state.isEmpty())

        val http = FakeHttp(dir)
            .onJson(versionsUrl(), versions(Triple("26.2", "SUPPORTED", listOf(10)), Triple("1.21.8", "UNSUPPORTED", listOf(1))), "v-etag")
            .on(buildsUrl("26.2"), FakeResponse.Status(503))
            .onJson(buildsUrl("1.21.8"), builds(build(1)))
        val store2 = store("26.2", "1.21.8")
        val partial = FillSource(FillProject.PAPER).collect(testContext(http, store2, settings))
        assertEquals(SourceStatus.PARTIAL, partial.status)
        assertEquals(1L, partial.counters["builds.failed"])
        assertNotNull(store2.coreBuilds[Triple(CoreKey.PAPER, "1.21.8", "1")])
        assertNull(store2.state[fillVersionsStateKey("paper")], "builds 실패가 있으면 versions ETag 를 남기지 않는다")
    }

    @Test
    fun folia_required_false_paper_required_true() {
        val paper = FillSource(FillProject.PAPER)
        val folia = FillSource(FillProject.FOLIA)
        assertTrue(paper.required)
        assertFalse(folia.required)
        assertEquals(SourceId.PAPER, paper.id)
        assertEquals(SourceId.FOLIA, folia.id)
        assertEquals(setOf(SourceId.MOJANG), paper.dependsOn)
        assertEquals(setOf(SourceId.MOJANG), folia.dependsOn)
        assertEquals(15.minutes, paper.interval)
        assertEquals("1.14.2%20Pre-Release%204", fillPathSegment("1.14.2 Pre-Release 4"))
    }
}
