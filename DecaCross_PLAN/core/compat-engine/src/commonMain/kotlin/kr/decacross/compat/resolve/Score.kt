package kr.decacross.compat.resolve

import kr.decacross.compat.Confidence
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.McOrdinal

/**
 * ⑤ SCORE (설계서 §3.5):
 * `score = 충족률×0.45 + 검증등급×0.25 + 최신성×0.15 + 코어안정성×0.10 + 인기도×0.05`
 *
 * 신호등(§3.6)은 데이터 계층(CompatDb.confidenceOf)이 정적 검증·텔레메트리를 집계해 준다. 여기서는 합산만 한다.
 */
public object Score {
    public fun confidenceValue(c: Confidence): Float = when (c) {
        Confidence.GREEN -> 1f
        Confidence.YELLOW -> 0.6f
        Confidence.ORANGE -> 0.3f
        Confidence.RED -> 0f
    }

    /** 플랜 전체 신호등 = 항목 중 최악. 확신 없으면 초록을 주지 않는다 (D5). */
    public fun overall(confidences: List<Confidence>): Confidence = confidences.maxByOrNull { it.ordinal } ?: Confidence.YELLOW

    public fun compute(
        wantsTotal: Int,
        wantsSatisfied: Int,
        itemConfidences: List<Confidence>,
        chosenMc: McOrdinal,
        candidateMcs: List<McOrdinal>,
        coreChannel: Channel,
        popularity: Float = 0.5f,
    ): Float {
        val fulfilment = if (wantsTotal == 0) 1f else wantsSatisfied.toFloat() / wantsTotal
        val verified = if (itemConfidences.isEmpty()) 1f else itemConfidences.map(::confidenceValue).average().toFloat()
        val sorted = candidateMcs.sorted()
        val recency = if (sorted.size <= 1) 1f else sorted.indexOf(chosenMc).coerceAtLeast(0).toFloat() / (sorted.size - 1)
        val stability = if (coreChannel == Channel.STABLE) 1f else 0.5f
        return fulfilment * 0.45f + verified * 0.25f + recency * 0.15f + stability * 0.10f + popularity * 0.05f
    }
}
