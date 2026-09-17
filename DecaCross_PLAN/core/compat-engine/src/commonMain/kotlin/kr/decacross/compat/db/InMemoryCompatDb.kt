package kr.decacross.compat.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.decacross.compat.Confidence
import kr.decacross.compat.model.Capability
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.Content
import kr.decacross.compat.model.ContentVersion
import kr.decacross.compat.model.CoreBuild
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.DepTarget
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.McVersion
import kr.decacross.compat.serial.CompatJson

/**
 * 테스트·개발용 인메모리 [CompatDb]. 데이터는 생성자 인자나 [CompatFixture] JSON 으로 주입된다.
 * 이 클래스 자체는 아무것도 읽지 않는다 (core 모듈 I/O 금지 — 파일을 읽는 쪽은 app 계층).
 */
public class InMemoryCompatDb(
    mcVersions: List<McVersion>,
    coreBuilds: List<CoreBuild> = emptyList(),
    content: List<Content> = emptyList(),
    contentVersions: List<ContentVersion> = emptyList(),
    confidence: List<ConfidenceEntry> = emptyList(),
) : CompatDb {
    private val mcByLabel: Map<String, McVersion> = mcVersions.associateBy { it.label }
    private val mcSorted: List<McVersion> = mcVersions.sortedBy { it.ordinal }
    private val builds: Map<Pair<CoreKey, McOrdinal>, List<CoreBuild>> =
        coreBuilds.groupBy { it.core to it.mc }.mapValues { (_, v) -> v.sortedByDescending { buildNumber(it.build) } }
    private val contentBySlug: Map<String, Content> = content.associateBy { it.slug }
    private val versionsBySlug: Map<String, List<ContentVersion>> = contentVersions.groupBy { it.slug }
    private val providers: Map<Capability, List<String>> =
        contentVersions
            .flatMap { cv ->
                cv.deps
                    .filter { it.kind == DepKind.PROVIDES }
                    .mapNotNull { (it.target as? DepTarget.Cap)?.value }
                    .map { cap -> cap to cv.slug }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, slugs) -> slugs.distinct() }
    private val confidenceMap: Map<ConfidenceKey, Confidence> =
        confidence.associate { ConfidenceKey(it.slug, it.version, it.mc, it.core) to it.confidence }

    /** 전체 MC 버전 (ordinal 오름차순). 프루닝·UI 목록용. */
    public fun allMc(): List<McVersion> = mcSorted

    override fun mcByLabel(label: String): McVersion? = mcByLabel[label]

    override fun mcLatest(allowSnapshot: Boolean): McVersion? = mcSorted.lastOrNull { allowSnapshot || !it.isSnapshot }

    /** "1.21" → 1.21, 1.21.x, 1.21-pre/rc. "1.2" 가 "1.20" 을 잡지 않도록 세그먼트 경계('.' 또는 '-')를 본다. */
    override fun mcInFamily(prefix: String): List<McVersion> =
        mcSorted.filter { it.label == prefix || it.label.startsWith("$prefix.") || it.label.startsWith("$prefix-") }

    override fun coreBuilds(core: CoreKey, mc: McOrdinal, stableOnly: Boolean): List<CoreBuild> =
        builds[core to mc].orEmpty().filter { !stableOnly || it.channel == Channel.STABLE }

    override fun content(slug: String): Content? = contentBySlug[slug]

    override fun contentVersions(slug: String): List<ContentVersion> = versionsBySlug[slug].orEmpty()

    override fun providersOf(cap: Capability): List<String> = providers[cap].orEmpty()

    /** 데이터가 없으면 🟡 — "메타데이터 기준 호환" (설계서 §3.6). 확신 없으면 초록을 주지 않는다. */
    override fun confidenceOf(slug: String, version: String, mc: McOrdinal, core: CoreKey): Confidence =
        confidenceMap[ConfidenceKey(slug, version, mc, core)] ?: Confidence.YELLOW

    private data class ConfidenceKey(val slug: String, val version: String, val mc: McOrdinal, val core: CoreKey)

    private companion object {
        /** "60" → 60, "build-62" → 62, 숫자 없으면 -1. 정렬 전용 — 비교 의미는 없다. */
        fun buildNumber(build: String): Long = build.filter(Char::isDigit).toLongOrNull() ?: -1L
    }
}

/** (slug, version, mc, core) 조합의 신호등. 정적 검증·텔레메트리 집계 결과를 이 형태로 주입한다. */
@Serializable
public data class ConfidenceEntry(
    val slug: String,
    val version: String,
    val mc: McOrdinal,
    val core: CoreKey,
    val confidence: Confidence,
)

/**
 * JSON 픽스처 스키마. 개발용 `dev-compat.json`, 테스트 픽스처, 오프라인 스냅샷 교환 포맷이 전부 이거다.
 * 파일을 읽는 코드는 여기 없다 — 문자열을 받아 파싱만 한다.
 */
@Serializable
public data class CompatFixture(
    val mcVersions: List<McVersion> = emptyList(),
    val coreBuilds: List<CoreBuild> = emptyList(),
    val content: List<Content> = emptyList(),
    val contentVersions: List<ContentVersion> = emptyList(),
    val confidence: List<ConfidenceEntry> = emptyList(),
) {
    public fun toDb(): InMemoryCompatDb = InMemoryCompatDb(mcVersions, coreBuilds, content, contentVersions, confidence)

    public fun toJson(json: Json = CompatJson): String = json.encodeToString(serializer(), this)

    public companion object {
        public fun fromJson(text: String, json: Json = CompatJson): CompatFixture = json.decodeFromString(serializer(), text)
    }
}
