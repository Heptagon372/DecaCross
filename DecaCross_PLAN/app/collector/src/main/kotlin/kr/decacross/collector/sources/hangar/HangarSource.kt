package kr.decacross.collector.sources.hangar

import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.CollectorSource
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.core.SourceReport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** Hangar v1 (PAPER 플랫폼) 인덱스 → content / content_versions / content_deps (+ 선택 버전 jar 분석 후 즉시 삭제). */
class HangarSource : CollectorSource {
    override val id: SourceId = SourceId.HANGAR
    override val interval: Duration = 6.hours
    override val dependsOn: Set<SourceId> = setOf(SourceId.MOJANG)
    override val required: Boolean = false

    override suspend fun collect(ctx: CollectContext): SourceReport = TODO("WP-C3")
}
