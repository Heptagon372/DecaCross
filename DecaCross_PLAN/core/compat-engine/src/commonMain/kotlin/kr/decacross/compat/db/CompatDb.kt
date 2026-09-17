package kr.decacross.compat.db

import kr.decacross.compat.Confidence
import kr.decacross.compat.model.Capability
import kr.decacross.compat.model.Content
import kr.decacross.compat.model.ContentVersion
import kr.decacross.compat.model.CoreBuild
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.McVersion

/**
 * 엔진이 보는 데이터의 전부. 조회 전용. 네트워크·파일 I/O 는 전부 이 뒤에 숨는다.
 *
 * 구현체: 테스트용 [InMemoryCompatDb], 데몬의 SqlDelightCompatDb(04), 서버측 Postgres(08).
 */
public interface CompatDb {
    public fun mcByLabel(label: String): McVersion?

    public fun mcLatest(allowSnapshot: Boolean = false): McVersion?

    /** "1.21" → [1.21, 1.21.1, …]. 라벨 접두사 매칭이되 정렬은 ordinal. */
    public fun mcInFamily(prefix: String): List<McVersion>

    public fun coreBuilds(core: CoreKey, mc: McOrdinal, stableOnly: Boolean = true): List<CoreBuild>

    public fun content(slug: String): Content?

    public fun contentVersions(slug: String): List<ContentVersion>

    /** 이 Capability 를 PROVIDES 하는 콘텐츠 slug 목록. */
    public fun providersOf(cap: Capability): List<String>

    public fun confidenceOf(slug: String, version: String, mc: McOrdinal, core: CoreKey): Confidence
}
