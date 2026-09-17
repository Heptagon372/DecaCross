package kr.decacross.collector.sources.fill

import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.CollectorSource
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.core.SourceReport
import kr.decacross.compat.model.CoreKey
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** PaperMC Fill v3 프로젝트. 프록시(velocity/waterfall)는 대상이 아니다. */
enum class FillProject(val apiId: String, val core: CoreKey, val sourceId: SourceId) {
    PAPER("paper", CoreKey.PAPER, SourceId.PAPER),
    FOLIA("folia", CoreKey.FOLIA, SourceId.FOLIA),
}

/** Fill v3 `/v3/projects/{p}/versions/{v}/builds` → core_builds. sha256·size 는 API 값을 그대로 쓴다. */
class FillSource(val project: FillProject) : CollectorSource {
    override val id: SourceId = project.sourceId
    override val interval: Duration = 15.minutes
    override val dependsOn: Set<SourceId> = setOf(SourceId.MOJANG)
    override val required: Boolean = project == FillProject.PAPER

    override suspend fun collect(ctx: CollectContext): SourceReport = TODO("WP-C2")
}
