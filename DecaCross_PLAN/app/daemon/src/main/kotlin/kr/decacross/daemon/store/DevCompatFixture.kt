package kr.decacross.daemon.store

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
 */
object DevCompatFixture {
    private const val RESOURCE = "/dev-compat.json"

    fun load(): CompatFixture {
        val text = DevCompatFixture::class.java.getResourceAsStream(RESOURCE)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: error("데몬 리소스 $RESOURCE 를 찾을 수 없습니다")
        return CompatFixture.fromJson(text)
    }

    fun db(): InMemoryCompatDb = load().toDb()
}
