package kr.decacross.collector.sources

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kr.decacross.analysis.parsePackMcmeta
import kr.decacross.collector.CollectorHttp
import kr.decacross.collector.HttpOutcome
import kr.decacross.collector.McRaw
import kr.decacross.collector.VersionStub
import kr.decacross.compat.model.PackDecl
import kr.decacross.compat.model.PackFormat
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.time.Instant

const val MOJANG_MANIFEST_URL: String = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

// ── 응답 스키마 (실제 응답 2026-09 확인) ─────────────────────────────────────

@Serializable
data class VersionManifest(val latest: ManifestLatest = ManifestLatest(), val versions: List<ManifestEntry> = emptyList())

@Serializable
data class ManifestLatest(val release: String? = null, val snapshot: String? = null)

/** `type` 은 release | snapshot | old_beta | old_alpha. old_* 는 수집하지 않는다. */
@Serializable
data class ManifestEntry(
    val id: String,
    val type: String,
    val url: String,
    val releaseTime: String,
    val sha1: String? = null,
)

/** 버전별 JSON. `javaVersion` 은 아주 오래된 버전(1.6 이전 일부)에 없다. */
@Serializable
data class VersionDetail(
    val id: String,
    val releaseTime: String? = null,
    val javaVersion: JavaVersionRef? = null,
    val downloads: Map<String, DownloadRef> = emptyMap(),
)

@Serializable
data class JavaVersionRef(val component: String? = null, val majorVersion: Int)

@Serializable
data class DownloadRef(val url: String, val sha1: String? = null, val size: Long? = null)

// ── client.jar 안의 version.json / pack.mcmeta ────────────────────────────────

/** client.jar 에서 읽은 포맷. ★ rp / dp 별개 (불변식 4). [notes] 는 읽지 못한 부분. */
data class ClientPackFormats(
    val rp: PackFormat?,
    val dp: PackFormat?,
    val protocol: Int? = null,
    val notes: List<String> = emptyList(),
)

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * `version.json` 의 `pack_version` 파싱. 관측된 형태 전부를 받는다:
 * - `4` (1.13~1.14, int 하나 → rp = dp)
 * - `{"resource": 64, "data": 81}` (1.15 ~ 1.21.8, int)
 * - `{"resource": "88.0", "data": "88.0"}` / `{"resource": {"major": 88, "minor": 0}, ...}` (방어적)
 * - `{"resource_major": 97, "resource_minor": 1, "data_major": 121, "data_minor": 0}` (26.3 실측)
 * `protocol_version` 도 같이 읽는다. `pack_version` 이 없으면 null.
 */
fun parseClientVersionJson(text: String): ClientPackFormats? {
    val root = runCatching { lenientJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    val protocol = (root["protocol_version"] as? JsonPrimitive)?.intOrNull
    val notes = ArrayList<String>()
    val pv = root["pack_version"] ?: return ClientPackFormats(null, null, protocol, listOf("pack_version 없음"))
    val (rp, dp) = when (pv) {
        is JsonPrimitive -> {
            val f = packFormatOf(pv, notes)
            f to f
        }

        is JsonObject -> {
            val rp = pv["resource"]?.let { packFormatOf(it, notes) }
                ?: pairFormat(pv["resource_major"], pv["resource_minor"])
            val dp = pv["data"]?.let { packFormatOf(it, notes) }
                ?: pairFormat(pv["data_major"], pv["data_minor"])
            if (rp == null) notes += "resource 포맷을 읽지 못함: $pv"
            if (dp == null) notes += "data 포맷을 읽지 못함: $pv"
            rp to dp
        }

        else -> {
            notes += "pack_version 형태를 읽지 못함: $pv"
            null to null
        }
    }
    return ClientPackFormats(rp, dp, protocol, notes)
}

private fun pairFormat(major: JsonElement?, minor: JsonElement?): PackFormat? {
    val ma = (major as? JsonPrimitive)?.intOrNull ?: return null
    val mi = (minor as? JsonPrimitive)?.intOrNull ?: 0
    return if (ma >= 0 && mi >= 0) PackFormat(ma, mi) else null
}

/** int | "88.0" 문자열 | 88.0 소수 | {major, minor} 객체 → [PackFormat]. */
private fun packFormatOf(v: JsonElement, notes: MutableList<String>): PackFormat? = when (v) {
    is JsonPrimitive -> v.contentOrNull?.let { PackFormat.parse(it) }.also { if (it == null) notes += "포맷 값을 읽지 못함: $v" }

    is JsonObject -> pairFormat(v["major"], v["minor"]).also { if (it == null) notes += "포맷 객체를 읽지 못함: $v" }

    else -> {
        notes += "포맷 형태를 읽지 못함: $v"
        null
    }
}

/**
 * client.jar 루트의 `version.json` → 없거나 `pack_version` 이 없으면 루트 `pack.mcmeta` 의 `pack.pack_format` 으로 대체.
 * pack.mcmeta 는 단일 포맷이라 rp 와 dp 에 같은 값을 넣는다 (그 시절엔 구분이 없었다).
 * jar 를 열 수 없으면 null. 호출자가 jar 를 지운다 — 여기서는 읽기만.
 */
fun readClientPackFormats(jar: Path): ClientPackFormats? {
    val zip = runCatching { ZipFile(jar.toFile()) }.getOrNull() ?: return null
    return zip.use { z ->
        val fromVersionJson = z.getEntry("version.json")?.let { e ->
            runCatching { z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        }?.let(::parseClientVersionJson)
        if (fromVersionJson != null && (fromVersionJson.rp != null || fromVersionJson.dp != null)) return@use fromVersionJson
        val mcmeta = z.getEntry("pack.mcmeta")?.let { e ->
            runCatching { z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        }?.let(::parsePackMcmeta)
        val single = (mcmeta?.decl as? PackDecl.Single)?.format
        ClientPackFormats(
            rp = single,
            dp = single,
            protocol = fromVersionJson?.protocol,
            notes = (fromVersionJson?.notes.orEmpty()) + if (single != null) listOf("pack.mcmeta 단일 포맷으로 대체") else listOf("version.json / pack.mcmeta 둘 다 없음"),
        )
    }
}

// ── 소스 ──────────────────────────────────────────────────────────────────────

/**
 * Mojang piston-meta. manifest → (release | snapshot 만) → 버전별 JSON → [McRaw].
 * pack 포맷은 client.jar 를 임시 디렉터리에 받아 읽고 `finally` 에서 지운다.
 */
class MojangSource(private val http: CollectorHttp, private val manifestUrl: String = MOJANG_MANIFEST_URL) {
    suspend fun manifest(): HttpOutcome<VersionManifest> = http.getJson(manifestUrl)

    suspend fun detail(entry: ManifestEntry): HttpOutcome<VersionDetail> = http.getJson(entry.url)

    /** 버전별 JSON 을 읽어 [McRaw] 로. `javaVersion` 이 없으면 null 로 두고 호출자가 8 로 대체한다. */
    suspend fun collect(entry: ManifestEntry): McRaw? {
        val stub = entry.toStub() ?: return null
        val detail = detail(entry).let { r ->
            when (r) {
                is HttpOutcome.Ok -> r.value

                is HttpOutcome.Failed -> {
                    log.warn("버전 JSON 실패 {}: {}", entry.id, r.message)
                    return McRaw(stub.label, stub.isSnapshot, stub.releasedAt, javaMajor = null)
                }
            }
        }
        val client = detail.downloads["client"]
        return McRaw(
            label = stub.label,
            isSnapshot = stub.isSnapshot,
            releasedAt = stub.releasedAt,
            javaMajor = detail.javaVersion?.majorVersion,
            clientUrl = client?.url,
            clientSha1 = client?.sha1,
            clientSize = client?.size,
        )
    }

    /**
     * pack 포맷 채우기. [candidates] 중 최신순으로 최대 [max] 개의 client.jar 를 받아 읽는다.
     * 반환은 라벨 → 읽은 포맷. jar 는 성공·실패와 무관하게 `finally` 에서 삭제된다.
     */
    suspend fun fillPackFormats(candidates: List<McRaw>, tmpDir: Path, max: Int): Map<String, ClientPackFormats> {
        val out = LinkedHashMap<String, ClientPackFormats>()
        val targets = candidates.filter { it.clientUrl != null }.sortedByDescending { it.releasedAt }.take(max)
        Files.createDirectories(tmpDir)
        for (raw in targets) {
            val url = raw.clientUrl ?: continue
            val jar = tmpDir.resolve("${UUID.randomUUID()}.jar")
            try {
                when (val d = http.download(url, jar)) {
                    is HttpOutcome.Failed -> log.warn("client.jar 다운로드 실패 {}: {}", raw.label, d.message)

                    is HttpOutcome.Ok -> {
                        val formats = readClientPackFormats(jar)
                        if (formats == null) {
                            log.warn("client.jar 를 열지 못함 {}", raw.label)
                        } else {
                            out[raw.label] = formats
                            log.info("pack 포맷 {} → rp={} dp={} protocol={} {}", raw.label, formats.rp, formats.dp, formats.protocol, formats.notes)
                        }
                    }
                }
            } finally {
                runCatching { Files.deleteIfExists(jar) }
            }
        }
        return out
    }

    private companion object {
        val log = LoggerFactory.getLogger(MojangSource::class.java)
    }
}

/** release / snapshot 만 [VersionStub] 으로. old_alpha / old_beta 는 null. 시각을 못 읽어도 null + 로그. */
fun ManifestEntry.toStub(): VersionStub? {
    val isSnapshot = when (type) {
        "release" -> false
        "snapshot" -> true
        else -> return null
    }
    val at = runCatching { Instant.parse(releaseTime) }.getOrNull()
    if (at == null) {
        LoggerFactory.getLogger(MojangSource::class.java).warn("releaseTime 을 읽지 못함 {}: {}", id, releaseTime)
        return null
    }
    return VersionStub(id, isSnapshot, at)
}
