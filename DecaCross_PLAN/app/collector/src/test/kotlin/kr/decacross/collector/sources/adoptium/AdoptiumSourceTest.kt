package kr.decacross.collector.sources.adoptium

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.decacross.collector.config.AdoptiumSettings
import kr.decacross.collector.config.CollectorSettings
import kr.decacross.collector.core.SourceStatus
import kr.decacross.collector.store.McRow
import kr.decacross.collector.testkit.FakeHttp
import kr.decacross.collector.testkit.RecordingStore
import kr.decacross.collector.testkit.deleteTree
import kr.decacross.collector.testkit.newTempDir
import kr.decacross.collector.testkit.testContext
import kr.decacross.collector.testkit.testSettings
import kr.decacross.compat.model.Arch
import kr.decacross.compat.model.ImageType
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.Os
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** §9.5 AdoptiumSource (D23). */
class AdoptiumSourceTest {
    private val dir = newTempDir()
    private val base = "https://adoptium.test"
    private val settings: CollectorSettings = testSettings(dir).copy(adoptium = AdoptiumSettings(baseUrl = base))

    @AfterTest
    fun cleanup() = deleteTree(dir)

    private fun res(path: String): String = checkNotNull(javaClass.getResourceAsStream(path)) { path }.use { it.readBytes().decodeToString() }

    private fun latestUrl(f: Int) = "$base/v3/assets/latest/$f/hotspot?vendor=eclipse"

    private fun store(vararg javaMins: Int): RecordingStore = RecordingStore().also { s ->
        javaMins.forEachIndexed { i, j ->
            s.mc += McRow(i + 1L, "v$i", McOrdinal(1000 + i * 10), Instant.parse("2026-01-01T00:00:00Z"), false, j, j, null, null, null, null, null)
        }
    }

    private fun FakeHttp.available(): FakeHttp = onJson("$base/v3/info/available_releases", res("/c2/adoptium/available_releases.json"))

    private fun rows(store: RecordingStore, feature: Int) = store.javaRuntimes.values.filter { it.feature == feature }

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

    @Test
    fun features_fromDb_intersectAvailable_warnUnavailable() = runTest {
        val http = FakeHttp(dir).available()
            .onJson(latestUrl(8), res("/c2/adoptium/latest_8.json"))
            .onJson(latestUrl(16), res("/c2/adoptium/latest_16.json"))
            .onJson(latestUrl(21), res("/c2/adoptium/latest_21.json"))
        val store = store(8, 16, 21, 21, 99)

        val report = AdoptiumSource().collect(testContext(http, store, settings))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        val latest = http.requests.map { it.url }.filter { it.contains("/assets/latest/") }
        assertEquals(listOf(latestUrl(8), latestUrl(16), latestUrl(21)), latest)
        assertTrue(report.warnings.any { it.contains("99") }, report.warnings.toString())
        assertEquals(1L, report.counters["features.unavailable"])
        assertEquals(3L, report.counters["features"])
    }

    @Test
    fun filters_osArchImageHeap_lowercasesSha() = runTest {
        val fixture = Json.parseToJsonElement(res("/c2/adoptium/latest_21.json")).jsonArray.map { it.jsonObject }.toMutableList()
        fun binary(o: JsonObject) = o.getValue("binary").jsonObject
        fun withBinary(o: JsonObject, f: (JsonObject) -> JsonObject) = o.with("binary", f(binary(o)))
        val linuxX64Jre = fixture.indexOfFirst {
            val b = binary(it)
            b.str("os") == "linux" && b.str("architecture") == "x64" && b.str("image_type") == "jre"
        }
        val template = fixture[linuxX64Jre]
        val pkg = binary(template).getValue("package").jsonObject
        val sha = pkg.str("checksum")
        fixture[linuxX64Jre] = withBinary(template) { it.with("package", pkg.with("checksum", JsonPrimitive(sha.uppercase()))) }
        // 제외돼야 하는 변형들 (release_name 을 바꿔 upsert 키 중복으로 가려지지 않게)
        fun variant(name: String, f: (JsonObject) -> JsonObject) = withBinary(template.with("release_name", JsonPrimitive(name)), f)
        fixture += variant("heap-large") { it.with("heap_size", JsonPrimitive("large")) }
        fixture += variant("openj9") { it.with("jvm_impl", JsonPrimitive("openj9")) }
        fixture += variant("no-package") { it.with("package", JsonNull) }
        fixture += variant("bad-sha") { it.with("package", pkg.with("checksum", JsonPrimitive("abc"))) }
        fixture += variant("aix") { it.with("os", JsonPrimitive("aix")) }
        fixture += variant("ppc") { it.with("architecture", JsonPrimitive("ppc64le")) }
        fixture += variant("testimage") { it.with("image_type", JsonPrimitive("testimage")) }

        val http = FakeHttp(dir).available().onJson(latestUrl(21), JsonArray(fixture).toString(), "e21")
        val store = store(21)

        val report = AdoptiumSource().collect(testContext(http, store, settings))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        val stored = rows(store, 21)
        assertEquals(12, stored.size, "alpine-linux, staticlibs 와 변형 7개는 제외")
        assertEquals(9L, report.counters["assets.excluded"])
        assertEquals(12L, report.counters["inserted"])
        assertEquals(0L, report.counters["coverage.missing"] ?: 0L)
        assertTrue(stored.all { it.releaseName == "jdk-21.0.12.1+1" && it.openjdkVersion == "21.0.12.1+1-LTS" })
        assertTrue(stored.all { Regex("^[0-9a-f]{64}$").matches(it.sha256) })
        val jre = stored.single { it.os == Os.LINUX && it.arch == Arch.X64 && it.imageType == ImageType.JRE }
        assertEquals(sha.lowercase(), jre.sha256)
        assertEquals(pkg.str("link"), jre.downloadUrl)
        assertEquals(pkg.str("name"), jre.packageName)
        assertEquals(Instant.parse("2026-08-19T17:12:57Z"), jre.publishedAt)
        assertEquals("""{"etag":"e21"}""", store.state[adoptiumLatestStateKey(21)])
    }

    @Test
    fun java16_noJre_coverageMissing() = runTest {
        val http = FakeHttp(dir).available().onJson(latestUrl(16), res("/c2/adoptium/latest_16.json"))
        val store = store(16)

        val report = AdoptiumSource().collect(testContext(http, store, settings))

        assertEquals(SourceStatus.OK, report.status)
        val stored = rows(store, 16)
        assertEquals(4, stored.size)
        assertTrue(stored.all { it.imageType == ImageType.JDK })
        assertEquals(8L, report.counters["coverage.missing"])
        assertTrue("JRE 16 windows/x64 없음" in report.warnings, report.warnings.toString())
        assertTrue("JDK 16 mac/aarch64 없음" in report.warnings)
    }

    @Test
    fun java8_versionWithoutPatchOptional_parses() = runTest {
        val text = res("/c2/adoptium/latest_8.json")
        val version = Json.parseToJsonElement(text).jsonArray.single().jsonObject.getValue("version").jsonObject
        assertTrue("patch" !in version && "optional" !in version)
        val http = FakeHttp(dir).available().onJson(latestUrl(8), text)
        val store = store(8)

        val report = AdoptiumSource().collect(testContext(http, store, settings))

        assertEquals(SourceStatus.OK, report.status)
        val row = rows(store, 8).single()
        assertEquals(Os.WINDOWS, row.os)
        assertEquals(Arch.X64, row.arch)
        assertEquals(ImageType.JRE, row.imageType)
        assertEquals("jdk8u504-b01", row.releaseName)
        assertEquals("1.8.0_504-b01", row.openjdkVersion)
        assertEquals(40_104_826L, row.size)
        assertEquals(11L, report.counters["coverage.missing"])
    }

    @Test
    fun etag304_skips() = runTest {
        val http = FakeHttp(dir).available().onJson(latestUrl(21), res("/c2/adoptium/latest_21.json"), "e21")
        val store = store(21)
        store.state[adoptiumLatestStateKey(21)] = """{"etag":"e21"}"""

        val report = AdoptiumSource().collect(testContext(http, store, settings))

        assertEquals(SourceStatus.OK, report.status)
        assertEquals(1L, report.counters["latest.notModified"])
        assertEquals("e21", http.requests.single { it.url == latestUrl(21) }.conditional?.etag)
        assertTrue(store.javaRuntimes.isEmpty())
    }

    @Test
    fun emptyDb_ltsFallback_warns() = runTest {
        val http = FakeHttp(dir).available()
            .onJson(latestUrl(8), res("/c2/adoptium/latest_8.json"))
            .onJson(latestUrl(11), "[]")
            .onJson(latestUrl(17), "[]")
            .onJson(latestUrl(21), res("/c2/adoptium/latest_21.json"))
            .onJson(latestUrl(25), "[]")
        val store = RecordingStore()

        val report = AdoptiumSource().collect(testContext(http, store, settings))

        assertEquals(SourceStatus.OK, report.status, report.toString())
        assertTrue(report.warnings.any { it.contains("LTS") }, report.warnings.toString())
        val latest = http.requests.map { it.url }.filter { it.contains("/assets/latest/") }
        assertEquals(listOf(8, 11, 17, 21, 25).map { latestUrl(it) }, latest)
        assertEquals(13, store.javaRuntimes.size)
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
}
