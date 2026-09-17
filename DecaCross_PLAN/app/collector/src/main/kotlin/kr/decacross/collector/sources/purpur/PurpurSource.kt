package kr.decacross.collector.sources.purpur

import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.CollectorSource
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.core.SourceReport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Purpur API v2 → core_builds. API 가 md5 만 주므로 선택된 빌드를 스트리밍 해시해 sha256 을 만든다 (디스크 기록 없음). */
class PurpurSource : CollectorSource {
    override val id: SourceId = SourceId.PURPUR
    override val interval: Duration = 15.minutes
    override val dependsOn: Set<SourceId> = setOf(SourceId.MOJANG)
    override val required: Boolean = false

    override suspend fun collect(ctx: CollectContext): SourceReport = TODO("WP-C2")
}
