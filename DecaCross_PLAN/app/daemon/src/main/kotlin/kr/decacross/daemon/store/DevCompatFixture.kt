package kr.decacross.daemon.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.decacross.compat.db.CompatFixture
import kr.decacross.compat.db.InMemoryCompatDb

/**
 * 개발용 호환성 스냅샷 (`dev-compat.json`, 데몬 리소스).
 *
 * Mojang `version_manifest_v2.json`(릴리스 103개, 서수는 §2.1 시딩 규칙으로 발급)과
 * PaperMC Fill v3 의 버전별 최신 빌드로 만들었다. pack_format 은 문서에 명시된 값만 있고
 * 나머지는 null — 02 단계 수집기가 client.jar 에서 실측해 채운다.
 *
 * 04 단계에서 SQLDelight 기반 `SqlDelightCompatDb` 로 대체된다. 그때까지 CLI/데몬의 유일한 데이터 소스.
 *
 * # 한계 (docs/04_설계결정_03설치.md D-03-1)
 * - ★ Paper 빌드만 들어 있다. `--core purpur|folia` 는 [kr.decacross.daemon.install.INSTALLABLE_CORES] 에는 있지만
 *   이 스냅샷으로는 언제나 `NoBuildCollected` 다. CLI 도움말이 그렇게 말한다.
 * - ★ 스냅샷은 시점이 박혀 있다. 그 시점을 **코드가 읽을 수 있게** [snapshotDate] 로 내보낸다 —
 *   자기 나이를 모르는 임시 데이터는 조용히 낡는다. CLI 는 `create` 마다 이 날짜를 한 줄 알린다.
 */
object DevCompatFixture {
    private const val RESOURCE = "/dev-compat.json"

    private val metaJson = Json { ignoreUnknownKeys = true }

    /** `dev-compat.json` 의 메타 필드만 (본문은 [CompatFixture] 가 읽는다). */
    @Serializable
    private data class SnapshotMeta(val snapshotDate: String? = null, val snapshotSources: List<String> = emptyList())

    fun load(): CompatFixture = CompatFixture.fromJson(readResource())

    fun db(): InMemoryCompatDb = load().toDb()

    /** 스냅샷을 만든 날 (`YYYY-MM-DD`). 리소스에 없으면 null. */
    fun snapshotDate(): String? = metaJson.decodeFromString(SnapshotMeta.serializer(), readResource()).snapshotDate

    /** 스냅샷을 만든 출처 URL. */
    fun snapshotSources(): List<String> =
        metaJson.decodeFromString(SnapshotMeta.serializer(), readResource()).snapshotSources

    /** CLI·UI 가 그대로 한 줄 보여 주는 안내 (임시 데이터라는 사실을 매번 드러낸다). */
    fun snapshotNoticeKo(): String {
        val date = snapshotDate() ?: "날짜 미상"
        return "호환성 데이터는 $date 스냅샷입니다 (Paper 만 수집됨 — 04 단계에서 실제 DB 로 대체됩니다)"
    }

    private fun readResource(): String =
        DevCompatFixture::class.java.getResourceAsStream(RESOURCE)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: error("데몬 리소스 $RESOURCE 를 찾을 수 없습니다")
}
