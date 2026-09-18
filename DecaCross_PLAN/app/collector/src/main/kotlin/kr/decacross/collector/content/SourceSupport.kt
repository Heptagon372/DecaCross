package kr.decacross.collector.content

import kr.decacross.collector.core.ReportBuilder
import kr.decacross.collector.store.DepRow
import kr.decacross.compat.model.DepKind
import java.net.URLEncoder
import kotlin.time.Instant

// ── 콘텐츠 소스(Modrinth·Hangar) 공용 도우미: URL 인코딩, 의존성 병합, 날짜 해석 ─────────────

/** 쿼리 파라미터 값 인코딩 (`URLEncoder`, UTF-8). Http 구현은 다시 인코딩하지 않는다. */
internal fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)

/** 경로 세그먼트 인코딩. 공백은 `+` 가 아니라 `%20` 이어야 한다 (경로에서 `+` 는 글자 그대로다). */
internal fun encSeg(s: String): String = enc(s).replace("+", "%20")

/**
 * 한 버전의 플랫폼 의존성(REQUIRE/OPTIONAL)을 대상 slug 기준으로 모은다.
 *
 * # 불변식
 * - 같은 slug 가 여러 번 나오면 REQUIRE 가 이긴다 (OPTIONAL 로 약해지지 않는다).
 * - 결과 순서는 처음 등장한 순서다 (결정적).
 */
internal class DepAccumulator {
    private val bySlug = LinkedHashMap<String, DepKind>()

    fun add(slug: String, kind: DepKind) {
        require(kind != DepKind.PROVIDES) { "PROVIDES 는 분석 결과로만 기록한다" }
        val old = bySlug[slug]
        if (old == null || (old == DepKind.OPTIONAL && kind == DepKind.REQUIRE)) bySlug[slug] = kind
    }

    fun rows(): List<DepRow> = bySlug.map { (slug, kind) -> DepRow(kind, targetSlug = slug, targetCapability = null, range = "*") }
}

/**
 * 플랫폼 게시 시각 해석기. 해석할 수 없으면 null 을 돌려주고, 경고는 소스 실행당 한 번만 남긴다
 * (나머지는 `dates.unparseable` 카운터로만 센다).
 */
internal class PublishedAtParser(private val report: ReportBuilder) {
    private var warned = false

    fun parse(text: String, what: String): Instant? {
        val parsed = parseInstantOrNull(text)
        if (parsed == null) {
            report.inc("dates.unparseable")
            if (!warned) {
                warned = true
                report.warn("게시 시각 해석 실패 → published_at null: $what '$text'")
            }
        }
        return parsed
    }
}
