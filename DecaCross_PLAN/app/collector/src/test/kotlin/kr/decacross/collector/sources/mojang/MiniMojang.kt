package kr.decacross.collector.sources.mojang

import kr.decacross.collector.http.DigestAlgo
import kr.decacross.collector.testkit.FakeHttp
import kr.decacross.collector.testkit.FakeResponse

/**
 * MojangSourceTest 용 작은 매니페스트: 릴리스 3, old_beta 1, 스냅샷 4 (+ 작은 per-version JSON·메모리 zip jar).
 *
 * 한 번에 발급하면: 1.0=1000, s1=1001, s2=1002, 1.1=1010, s3=1011, `1.1.5 Pre-Release 1`=1012, 1.2=1020.
 *
 * [tweak] 으로 버전 하나(예: jar 내용)를 바꿀 수 있다. 매니페스트·per-version JSON 의 sha1/size 는 바뀐 값으로 계산된다.
 */
internal class MiniMojang(tweak: (Version) -> Version = { it }) {
    data class Version(
        val label: String,
        val type: String,
        val releaseTime: String,
        val javaMajor: Int?,
        val client: ByteArray,
        val server: ByteArray?,
    )

    val manifestUrl = "https://meta.test/mc/game/version_manifest_v2.json"

    val versions: List<Version> = listOf(
        Version("b1.8", "old_beta", "2011-09-15T00:00:00+00:00", null, jar("beta"), null),
        // 1.6.x 모양: client 에 pack.mcmeta 가 있지만 data/ 가 없다 → NONE. server 없음
        Version("1.0", "release", "2020-01-01T00:00:00+00:00", null, jar("c10", "pack.mcmeta" to """{"pack":{"pack_format":1}}"""), null),
        Version("s1", "snapshot", "2020-01-02T00:00:00+00:00", 8, jar("s1"), null),
        Version("s2", "snapshot", "2020-01-03T00:00:00+00:00", 8, jar("s2"), null),
        // server 는 꼬리에 들어가지만 메타가 없다 → client 의 pack.mcmeta + data/ → E1
        Version(
            "1.1",
            "release",
            "2020-02-01T00:00:00+00:00",
            16,
            jar("c11", "pack.mcmeta" to """{"pack":{"pack_format":4}}""", "data/minecraft/a.json" to "{}"),
            jar("s11"),
        ),
        Version("s3", "snapshot", "2020-02-02T00:00:00+00:00", 16, jar("s3"), null),
        Version("1.1.5 Pre-Release 1", "snapshot", "2020-02-03T00:00:00+00:00", 16, jar("pre"), null),
        // 번들러 server: version.json E3
        Version(
            "1.2",
            "release",
            "2020-03-01T00:00:00+00:00",
            21,
            jar("c12"),
            jar("s12", "version.json" to """{"id":"1.2","protocol_version":767,"world_version":3953,"pack_version":{"resource":34,"data":48}}"""),
        ),
    ).map(tweak)

    fun version(label: String): Version = versions.single { it.label == label }

    /** 공백은 `%20` 으로 이미 인코딩된 URL (소스는 그대로 써야 한다). */
    fun jsonUrl(label: String): String = "https://meta.test/v1/packages/${label.replace(" ", "%20")}.json"

    fun clientUrl(label: String): String = "https://data.test/${label.replace(" ", "%20")}/client.jar"

    fun serverUrl(label: String): String = "https://data.test/${label.replace(" ", "%20")}/server.jar"

    fun versionJson(label: String): String {
        val v = version(label)
        val java = v.javaMajor?.let { """"javaVersion":{"component":"java-runtime","majorVersion":$it},""" } ?: ""
        val server = v.server?.let { ""","server":${artifact(serverUrl(label), it)}""" } ?: ""
        return """{"id":"${v.label}",$java"downloads":{"client":${artifact(clientUrl(label), v.client)}$server},"type":"${v.type}"}"""
    }

    fun sha1(text: String): String = FakeHttp.hex(DigestAlgo.SHA1, text.encodeToByteArray())

    fun manifestJson(): String {
        val entries = versions.reversed().joinToString(",") { v ->
            """{"id":"${v.label}","type":"${v.type}","url":"${jsonUrl(v.label)}","time":"2026-01-01T00:00:00+00:00","releaseTime":"${v.releaseTime}","sha1":"${sha1(versionJson(v.label))}","complianceLevel":1}"""
        }
        return """{"latest":{"release":"1.2","snapshot":"1.1.5 Pre-Release 1"},"versions":[$entries]}"""
    }

    /** 매니페스트·per-version JSON·jar 경로를 전부 등록한다. */
    fun install(http: FakeHttp, etag: String? = "manifest-etag-1"): FakeHttp {
        http.onJson(manifestUrl, manifestJson(), etag)
        for (v in versions) {
            http.onJson(jsonUrl(v.label), versionJson(v.label))
            http.on(clientUrl(v.label), FakeResponse.Body(v.client))
            v.server?.let { http.on(serverUrl(v.label), FakeResponse.Body(it)) }
        }
        return http
    }

    private fun artifact(url: String, bytes: ByteArray): String =
        """{"sha1":"${FakeHttp.hex(DigestAlgo.SHA1, bytes)}","size":${bytes.size},"url":"$url"}"""

    companion object {
        /** 표식 엔트리 하나 + [files] 로 된 메모리 zip jar. */
        fun jar(marker: String, vararg files: Pair<String, String>): ByteArray =
            buildZip(listOf(ZipItem("marker/$marker.class", marker.encodeToByteArray())) + files.map { ZipItem(it.first, it.second.encodeToByteArray()) })
    }
}
