package kr.decacross.collector.sources.adoptium

import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.CollectorSource
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.core.SourceReport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** Adoptium API v3 `assets/latest` → java_runtimes (0008). 기능 버전 목록은 mc_versions 에서 온다 (하드코딩 금지). */
class AdoptiumSource : CollectorSource {
    override val id: SourceId = SourceId.ADOPTIUM
    override val interval: Duration = 24.hours
    override val dependsOn: Set<SourceId> = setOf(SourceId.MOJANG)
    override val required: Boolean = false

    override suspend fun collect(ctx: CollectContext): SourceReport = TODO("WP-C2")
}
