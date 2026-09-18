package kr.decacross.collector.sources.mojang

import kr.decacross.collector.config.MojangSettings
import kr.decacross.collector.http.Http
import kr.decacross.collector.http.HttpResult
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** jar 메타 읽기 결과 상태. collector_state `mojang.jarmeta.<label>.status` 문자열과 같다. */
internal enum class JarMetaStatus {
    /** version.json / pack.mcmeta 에서 팩 포맷을 얻었다 (E1 이상). */
    FOUND,

    /** 메타 파일이 없거나 E0 (팩 포맷이 없는 시대). 사실 = 전부 null. */
    NONE,

    /** 중앙 디렉터리가 상한보다 커서 읽지 않았다. 모름 ≠ 없음 → 사실을 갱신하지 않는다. */
    TOO_LARGE,

    /** HTTP·zip 구조 실패. 백오프 후 재시도. */
    FAILED,
}

/**
 * @property jar 사실을 읽은 jar (`server` | `client`). FOUND/NONE(메타 파일은 있었음)일 때만.
 * @property bytes Range 응답 본문 바이트 합
 * @property requests Range 요청 수
 */
internal data class JarMetaOutcome(
    val status: JarMetaStatus,
    val jar: String?,
    val facts: JarFacts?,
    val bytes: Long,
    val requests: Int,
    val reason: String? = null,
)

/** Range 요청 수·응답 바이트를 센다 (나머지 메서드는 그대로 위임). */
private class CountingHttp(private val delegate: Http) : Http by delegate {
    val bytes = AtomicLong()
    val requests = AtomicInteger()

    override suspend fun getRange(url: String, offset: Long, length: Int): HttpResult<ByteArray> {
        requests.incrementAndGet()
        val r = delegate.getRange(url, offset, length)
        if (r is HttpResult.Ok) bytes.addAndGet(r.value.size.toLong())
        return r
    }
}

private class Candidate(val name: String, val artifact: VersionJson.Artifact) {
    var eocd: EocdResult? = null
}

private const val VERSION_JSON = "version.json"
private const val PACK_MCMETA = "pack.mcmeta"

/**
 * server/client jar 중앙 디렉터리를 HTTP Range 로 읽어 [JarFacts] 를 얻는다 (D11). jar 전체를 받지 않는다.
 *
 * 순서:
 * 1. server 가 있으면 server EOCD 를 읽는다. 중앙 디렉터리가 꼬리 안에 있으면(번들러 시대) server → client 순.
 * 2. 아니면 client EOCD 도 읽고 **중앙 디렉터리가 작은 jar 부터** 본다 (큰 디렉터리는 필요할 때만 받는다).
 * 3. 각 jar: 디렉터리에 version.json 이나 pack.mcmeta 가 있으면 그것을 읽어 판정하고 끝낸다. 없으면 다음 jar.
 *
 * # 불변식
 * - 요청은 순차다 (호스트 페이서가 동시성을 관리한다).
 * - 어떤 jar 가 실패(HTTP·구조)했고 찾지 못했으면 FAILED, 찾지 못했는데 크기 초과로 못 본 jar 가 있으면 TOO_LARGE,
 *   읽은 jar 모두에 메타 파일이 없으면 NONE.
 */
internal suspend fun readJarMeta(
    http: Http,
    settings: MojangSettings,
    server: VersionJson.Artifact?,
    client: VersionJson.Artifact,
): JarMetaOutcome {
    val counting = CountingHttp(http)
    val failures = ArrayList<String>()
    var tooLarge = 0

    fun outcome(status: JarMetaStatus, jar: String? = null, facts: JarFacts? = null, reason: String? = null) =
        JarMetaOutcome(status, jar, facts, counting.bytes.get(), counting.requests.get(), reason)

    val serverCandidate = server?.let { Candidate("server", it) }
    val clientCandidate = Candidate("client", client)

    suspend fun eocdOf(c: Candidate): EocdResult =
        c.eocd ?: readEocd(counting, c.artifact.url, c.artifact.size, settings.tailBytes).also { c.eocd = it }

    val order: List<Candidate> = if (serverCandidate == null) {
        listOf(clientCandidate)
    } else {
        val se = eocdOf(serverCandidate)
        if (se is EocdResult.Ok && se.eocd.fitsInTail) {
            listOf(serverCandidate, clientCandidate)
        } else {
            val ce = eocdOf(clientCandidate)
            // 크기를 모르는(실패한) 쪽은 뒤로. 같으면 server 먼저
            fun cdSizeOf(r: EocdResult): Long = (r as? EocdResult.Ok)?.eocd?.cdSize ?: Long.MAX_VALUE
            listOf(serverCandidate, clientCandidate).sortedBy { if (it === serverCandidate) cdSizeOf(se) else cdSizeOf(ce) }
        }
    }

    for (c in order) {
        val eocd = when (val e = eocdOf(c)) {
            is EocdResult.Ok -> e.eocd

            is EocdResult.Failed -> {
                failures += "${c.name} EOCD: ${e.reason}"
                continue
            }
        }
        val dir = when (val d = readCentralDirectory(counting, eocd, settings.maxCentralDirectoryBytes)) {
            is ZipDirResult.Ok -> d.dir

            // 모름 ≠ 없음: 다른 jar 를 본다
            is ZipDirResult.TooLarge -> {
                tooLarge++
                continue
            }

            is ZipDirResult.Failed -> {
                failures += "${c.name} central directory: ${d.reason}"
                continue
            }
        }
        val vjRef = dir.entries[VERSION_JSON]
        val pmRef = dir.entries[PACK_MCMETA]
        if (vjRef == null && pmRef == null) continue

        val vj = vjRef?.let { readEntry(counting, c.artifact.url, it) }
        val pm = pmRef?.let { readEntry(counting, c.artifact.url, it) }
        if ((vjRef != null && vj == null) || (pmRef != null && pm == null)) {
            failures += "${c.name} entry read failed"
            continue
        }
        val facts = parseJarFacts(vj?.decodeToString(), pm?.decodeToString(), dir.hasDataDir)
        val status = if (facts.era != ERA_E0) JarMetaStatus.FOUND else JarMetaStatus.NONE
        return outcome(status, c.name, facts)
    }
    return when {
        failures.isNotEmpty() -> outcome(JarMetaStatus.FAILED, reason = failures.joinToString("; "))
        tooLarge > 0 -> outcome(JarMetaStatus.TOO_LARGE, reason = "central directory > ${settings.maxCentralDirectoryBytes} bytes")
        else -> outcome(JarMetaStatus.NONE)
    }
}
