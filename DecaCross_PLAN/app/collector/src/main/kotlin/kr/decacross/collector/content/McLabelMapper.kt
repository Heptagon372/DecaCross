package kr.decacross.collector.content

import kr.decacross.collector.store.McRow
import kr.decacross.compat.model.McOrdinal

/**
 * 플랫폼이 준 MC 버전 라벨 목록 → `mc_ordinal_min` / `mc_ordinal_max` (D29).
 *
 * # 불변식 (CLAUDE.md 불변식 1)
 * - min/max 는 `mc_versions` 에 **존재하는** 라벨의 서수로만 계산한다. 라벨 문자열을 정렬·비교하지 않는다.
 * - 입력 순서는 결과에 영향을 주지 않는다.
 * - 하나도 매핑되지 않으면 둘 다 null.
 */
internal class McLabelMapper(index: List<McRow>) {
    private val byLabel: Map<String, McOrdinal> = index.associate { it.label to it.ordinal }

    /** [unknown] = `mc_versions` 에 없는 서로 다른 라벨 수. */
    data class Mapped(val min: McOrdinal?, val max: McOrdinal?, val unknown: Int)

    fun map(labels: Collection<String>): Mapped {
        var min: McOrdinal? = null
        var max: McOrdinal? = null
        var unknown = 0
        for (label in labels.toSet()) {
            val ordinal = byLabel[label]
            if (ordinal == null) {
                unknown++
                continue
            }
            if (min == null || ordinal < min) min = ordinal
            if (max == null || ordinal > max) max = ordinal
        }
        return Mapped(min, max, unknown)
    }
}
