package kr.decacross.collector

import kotlinx.coroutines.test.runTest
import kr.decacross.collector.sources.HangarSource
import kr.decacross.collector.sources.ModrinthSource
import kr.decacross.collector.sources.depKindOf
import kr.decacross.collector.sources.loaderFamilyOf
import kr.decacross.collector.sources.mcRangeOf
import kr.decacross.compat.model.Dep
import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.DepTarget
import kr.decacross.compat.model.LoaderFamily
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentTest {
    @Test
    fun loaderMapping() {
        listOf("paper", "spigot", "purpur", "bukkit", "folia", "Paper").forEach { assertEquals(LoaderFamily.BUKKIT, loaderFamilyOf(it), it) }
        listOf("fabric", "quilt").forEach { assertEquals(LoaderFamily.FABRIC, loaderFamilyOf(it), it) }
        listOf("forge", "neoforge").forEach { assertEquals(LoaderFamily.FORGE, loaderFamilyOf(it), it) }
        listOf("velocity", "bungeecord", "waterfall", "datapack", "").forEach { assertNull(loaderFamilyOf(it), it) }
    }

    @Test
    fun dependencyMapping_noConflictRepresentation() {
        assertEquals(DepKind.REQUIRE, depKindOf("required"))
        assertEquals(DepKind.OPTIONAL, depKindOf("optional"))
        assertNull(depKindOf("incompatible"))
        assertNull(depKindOf("embedded"))
    }

    @Test
    fun mcRange_usesOnlyKnownLabelsAndOrdinals() {
        val mc = mapOf("1.21" to McOrdinal(1900), "1.21.1" to McOrdinal(1910), "26.3" to McOrdinal(2020))
        // "1.21.1" 과 "26.3" 문자열 비교는 뒤집히지만 서수는 맞다
        assertEquals(McOrdinal(1900) to McOrdinal(2020), mcRangeOf(listOf("26.3", "1.21", "1.21.1", "1.7.10"), mc))
        assertEquals(null to null, mcRangeOf(listOf("1.7.10"), mc))
    }

    // ── Modrinth 실측 형태 (2026-09) ────────────────────────────────────────

    private val search = """{"hits":[
      {"project_id":"Vebnzrzj","project_type":"mod","slug":"luckperms","author":"Luck","title":"LuckPerms","description":"perms","categories":["bukkit","paper"],"versions":["1.21.8","26.3"],"downloads":2746190,"icon_url":"https://cdn/x.webp","license":"MIT"},
      {"project_id":"AAAA1111","project_type":"mod","slug":"closed-plugin","author":"x","title":"Closed","description":"","categories":["paper"],"versions":["1.21.8"],"downloads":5,"icon_url":null,"license":"ARR"}
    ],"offset":0,"limit":100,"total_hits":2}"""

    private val lpVersions = """[
      {"id":"b0mk8uS6","project_id":"Vebnzrzj","version_number":"v5.5.71-bukkit","game_versions":["1.8.9","1.21.8","26.3"],"loaders":["bukkit","folia","paper","spigot"],
       "dependencies":[{"project_id":"P7dR8mSH","version_id":null,"dependency_type":"required"},{"project_id":"AAAA1111","version_id":null,"dependency_type":"optional"},{"project_id":"ZZZZ9999","version_id":null,"dependency_type":"incompatible"}],
       "date_published":"2026-08-06T19:40:10.637955Z","version_type":"release",
       "files":[{"hashes":{"sha512":"188a","sha1":"fc8b"},"url":"https://cdn.modrinth.com/data/Vebnzrzj/versions/b0mk8uS6/LuckPerms-Bukkit-5.5.71.jar","filename":"LuckPerms-Bukkit-5.5.71.jar","primary":true,"size":1501521,"file_type":null}]},
      {"id":"old1","project_id":"Vebnzrzj","version_number":"v5.4.0","game_versions":["1.21.8"],"loaders":["velocity"],"dependencies":[],"files":[]}
    ]"""

    private val closedVersions = """[{"id":"c1","project_id":"AAAA1111","version_number":"1.0","game_versions":["1.21.8"],"loaders":["paper"],"dependencies":[],"files":[{"url":"https://x/c.jar","filename":"c.jar","primary":true,"size":1,"hashes":{}}]}]"""

    @Test
    fun modrinth_parsesSearchVersionsDepsAndLicense() = runTest {
        val engine = routedEngine(
            mapOf(
                "/v2/search" to search,
                "/v2/project/Vebnzrzj/version" to lpVersions,
                "/v2/project/AAAA1111/version" to closedVersions,
                "/v2/projects?ids=" to """[{"id":"P7dR8mSH","slug":"fabric-api"}]""",
            ),
        )
        val mc = mapOf("1.21.8" to McOrdinal(1900), "26.3" to McOrdinal(2020))
        val c = testHttp(engine).use { ModrinthSource(it).collect(mc, maxContent = 100, maxVersionsPerContent = 5) }

        assertEquals(2, c.contents.size)
        val lp = c.contents.first { it.content.slug == "luckperms" }
        assertEquals(Source.MODRINTH, lp.content.source)
        assertEquals("MIT", lp.content.license)
        assertTrue(lp.content.redistributable)
        assertEquals("Vebnzrzj", lp.sourceId)
        assertEquals("https://modrinth.com/plugin/luckperms", lp.pageUrl)
        assertFalse(c.contents.first { it.content.slug == "closed-plugin" }.content.redistributable)

        val v = c.versions.first { it.slug == "luckperms" && it.version == "v5.5.71-bukkit" }
        assertEquals(setOf(LoaderFamily.BUKKIT), v.loaders)
        assertEquals(McOrdinal(1900), v.mcMin)
        assertEquals(McOrdinal(2020), v.mcMax)
        assertNull(v.sha256, "Modrinth 는 sha256 을 주지 않는다")
        assertEquals(1501521L, v.size)
        assertEquals("https://cdn.modrinth.com/data/Vebnzrzj/versions/b0mk8uS6/LuckPerms-Bukkit-5.5.71.jar", v.fileUrl)
        assertEquals(
            listOf(Dep(DepKind.REQUIRE, DepTarget.Slug("fabric-api")), Dep(DepKind.OPTIONAL, DepTarget.Slug("closed-plugin"))),
            v.deps,
        )
        assertTrue(c.notes.any { it.contains("incompatible") })
        // velocity 전용 버전은 loaders 가 비지만 버전 자체는 남는다 (캡 안)
        assertTrue(c.versions.any { it.version == "v5.4.0" && it.loaders.isEmpty() })
    }

    @Test
    fun modrinth_capsVersionsPerContent() = runTest {
        val engine = routedEngine(mapOf("/v2/search" to search, "/v2/project/Vebnzrzj/version" to lpVersions, "/v2/project/AAAA1111/version" to closedVersions, "/v2/projects?ids=" to "[]"))
        val c = testHttp(engine).use { ModrinthSource(it).collect(emptyMap(), maxContent = 1, maxVersionsPerContent = 1) }
        assertEquals(1, c.contents.size)
        assertEquals(1, c.versions.size)
    }

    // ── Hangar 실측 형태 (2026-09) ──────────────────────────────────────────

    private val hangarProjects = """{"pagination":{"count":1,"limit":25,"offset":0},"result":[
      {"id":31,"name":"ViaVersion","namespace":{"owner":"ViaVersion","slug":"ViaVersion"},"stats":{"downloads":495347,"stars":468},"description":"Allow newer",
       "settings":{"license":{"name":"GPL","url":"https://x","type":"GPL"}},"avatarUrl":"https://a/x.png"}]}"""
    private val hangarVersions = """{"pagination":{"count":2,"limit":25,"offset":0},"result":[
      {"id":30566,"name":"5.12.1-SNAPSHOT+1069","createdAt":"2026-09-20T12:56:41Z",
       "downloads":{"PAPER":{"fileInfo":{"name":"ViaVersion-5.12.1-SNAPSHOT.jar","sizeBytes":6504194,"sha256Hash":"d3bd99c2"},"externalUrl":null,"downloadUrl":"https://hangarcdn.papermc.io/x.jar"},
                    "VELOCITY":{"fileInfo":{"name":"v.jar","sizeBytes":1,"sha256Hash":"ee"},"externalUrl":null,"downloadUrl":"https://hangarcdn.papermc.io/v.jar"}},
       "pluginDependencies":{"PAPER":[{"name":"ViaBackwards","projectId":12,"required":false,"externalUrl":null,"platform":"PAPER"},{"name":"Vault","projectId":null,"required":true}]},
       "platformDependencies":{"PAPER":["1.21.8","26.3"],"VELOCITY":["3.3"]}},
      {"id":1,"name":"velocity-only","downloads":{"VELOCITY":{"fileInfo":null,"externalUrl":"https://ext/v.jar","downloadUrl":null}},"pluginDependencies":{},"platformDependencies":{"VELOCITY":["3.3"]}}
    ]}"""

    @Test
    fun hangar_parsesProjectsAndPaperVersions() = runTest {
        val engine = routedEngine(mapOf("/projects/ViaVersion/versions" to hangarVersions, "/projects?" to hangarProjects))
        val mc = mapOf("1.21.8" to McOrdinal(1900), "26.3" to McOrdinal(2020))
        val c = testHttp(engine).use { HangarSource(it).collect(mc, maxContent = 25, maxVersionsPerContent = 5) }
        assertEquals(1, c.contents.size)
        val p = c.contents.single()
        assertEquals("viaversion", p.content.slug)
        assertEquals(Source.HANGAR, p.content.source)
        assertEquals("GPL", p.content.license)
        assertTrue(p.content.redistributable)
        assertEquals("https://hangar.papermc.io/ViaVersion/ViaVersion", p.pageUrl)

        assertEquals(1, c.versions.size, "PAPER 다운로드 없는 버전은 생략")
        val v = c.versions.single()
        assertEquals("5.12.1-SNAPSHOT+1069", v.version)
        assertEquals("https://hangarcdn.papermc.io/x.jar", v.fileUrl)
        assertEquals("d3bd99c2", v.sha256)
        assertEquals(6504194L, v.size)
        assertEquals(McOrdinal(1900) to McOrdinal(2020), v.mcMin to v.mcMax)
        assertEquals(setOf(LoaderFamily.BUKKIT), v.loaders)
        assertEquals(
            listOf(Dep(DepKind.OPTIONAL, DepTarget.Slug("viabackwards")), Dep(DepKind.REQUIRE, DepTarget.Slug("vault"))),
            v.deps,
        )
        assertTrue(c.notes.any { it.contains("velocity-only") })
    }
}
