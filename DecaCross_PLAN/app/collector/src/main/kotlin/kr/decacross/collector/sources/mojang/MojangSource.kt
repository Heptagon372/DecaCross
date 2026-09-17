package kr.decacross.collector.sources.mojang

import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.CollectorSource
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.core.SourceReport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Mojang `version_manifest_v2.json` → mc_versions (서수 발급) + client/server jar 의 version.json/pack.mcmeta 를
 * HTTP Range 로 읽어 rp/dp 포맷·protocol 을 채운다. jar 전체를 받지 않는다.
 */
class MojangSource : CollectorSource {
    override val id: SourceId = SourceId.MOJANG
    override val interval: Duration = 5.minutes
    override val dependsOn: Set<SourceId> = emptySet()
    override val required: Boolean = true

    override suspend fun collect(ctx: CollectContext): SourceReport = TODO("WP-C2")
}
