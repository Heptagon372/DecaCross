package kr.decacross.collector.sources.mojang

import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.version.ORDINAL_BASE
import kr.decacross.compat.version.ORDINAL_STEP
import kr.decacross.compat.version.nextOrdinal
import kr.decacross.compat.version.snapshotOrdinal
import java.util.TreeMap
import kotlin.time.Instant

/** 매니페스트 항목 하나 (발급 후보). */
internal data class IssuerEntry(val label: String, val isSnapshot: Boolean, val releasedAt: Instant)

/** 이미 발급된 mc_versions 행. */
internal data class ExistingOrdinal(val label: String, val ordinal: McOrdinal, val isSnapshot: Boolean, val releasedAt: Instant)

/** 서수를 발급하지 못한 이유 (SCP-19). 어느 경우에도 기존 서수를 바꾸지 않는다. */
internal enum class SkipReason {
    /** 직전 릴리스 뒤 9칸이 이미 찼다 (예상된 결과 — 카운트만). */
    SLOT_OVERFLOW,

    /** 이미 발급된 최신 릴리스보다 키가 작은 릴리스 (늦게 도착). 영구 미발급. */
    BACKDATED_RELEASE,

    /** 같은 칸 구간에 키가 더 큰 스냅샷이 이미 자리를 잡았다 (늦게 도착). 영구 미발급. */
    OUT_OF_ORDER_SNAPSHOT,

    /** 앞선 릴리스가 없는 스냅샷. */
    NO_PREVIOUS_RELEASE,
}

/**
 * 발급 계획. [issued] 는 키 순서, [skipped] 에는 비지 않은 이유만 들어 있다.
 *
 * @property releaseInversions 늦게 도착한 릴리스 label → 이 릴리스보다 **늦게** 나왔는데 이미 더 작은 서수를 받은 스냅샷 label 들 (SCP-19 영구 효과, INV-2).
 *   릴리스는 그대로 발급하고(릴리스를 영구 미발급으로 두는 쪽이 더 해롭다) 경고만 한다. 한 번에 발급하면 항상 비어 있다.
 */
internal data class OrdinalPlan(
    val issued: List<Pair<IssuerEntry, McOrdinal>>,
    val skipped: Map<SkipReason, List<String>>,
    val releaseInversions: Map<String, List<String>> = emptyMap(),
)

internal sealed interface PlanResult {
    data class Planned(val plan: OrdinalPlan) : PlanResult

    /** 내부 불변식 위반(발급할 서수가 기존 최대 이하). 아무것도 INSERT 하지 않는다. */
    data class Aborted(val reason: String) : PlanResult
}

/**
 * 순수 서수 발급기 (명세 §2.1, D1/D9/D54, SCP-19). 기준 구현: `DESIGN_DIR/sim_ordinals.py`.
 *
 * 키 = `(releasedAt, 릴리스 0 / 스냅샷 1, label)`. label 비교는 결정적 동률 처리일 뿐 버전 순서가 아니다.
 *
 * # 불변식
 * - 기존 행의 서수를 바꾸는 결과를 만들지 않는다 (CLAUDE.md 불변식 2). 계획은 신규 행 INSERT 뿐이다.
 * - 신규 릴리스 = 키 순 마지막 릴리스 + [ORDINAL_STEP] (없으면 [ORDINAL_BASE]). 반드시 기존 최대 서수보다 크다.
 * - 스냅샷 = 키상 직전 릴리스 p 의 `(p, p+9]` 구간에서 선착순.
 * - 접두사 닫힘: 키 순 앞부분을 먼저 발급하고 나머지를 나중에 발급해도 한 번에 발급한 결과와 같다.
 *   접두사가 아닌 순서로 도착하면 결과가 달라질 수 있고, 그 경우는 전부 계획에 드러난다:
 *   [SkipReason.BACKDATED_RELEASE]·[SkipReason.OUT_OF_ORDER_SNAPSHOT] (영구 미발급) 또는
 *   [OrdinalPlan.releaseInversions] (릴리스는 발급하지만, 그보다 늦게 나온 기존 스냅샷의 서수가 더 작게 남는다).
 */
internal object OrdinalIssuer {
    private data class Key(val releasedAt: Instant, val typeRank: Int, val label: String) : Comparable<Key> {
        override fun compareTo(other: Key): Int = compareValuesBy(this, other, Key::releasedAt, Key::typeRank, Key::label)
    }

    private fun key(label: String, isSnapshot: Boolean, releasedAt: Instant): Key = Key(releasedAt, if (isSnapshot) 1 else 0, label)

    private fun IssuerEntry.key(): Key = key(label, isSnapshot, releasedAt)

    private fun ExistingOrdinal.key(): Key = key(label, isSnapshot, releasedAt)

    private data class Release(val key: Key, val ordinal: McOrdinal)

    fun plan(existing: List<ExistingOrdinal>, entries: List<IssuerEntry>): PlanResult {
        // ordinal → 키. 칸 구간 조회용
        val byOrdinal = TreeMap<Int, Key>()
        val labels = HashSet<String>()
        for (row in existing) {
            byOrdinal[row.ordinal.value] = row.key()
            labels += row.label
        }
        val releases = existing.filter { !it.isSnapshot }.map { Release(it.key(), it.ordinal) }.sortedBy { it.key }.toMutableList()
        val fresh = entries.filter { it.label !in labels }.sortedBy { it.key() }

        val issued = ArrayList<Pair<IssuerEntry, McOrdinal>>()
        val skipped = LinkedHashMap<SkipReason, MutableList<String>>()
        val inversions = LinkedHashMap<String, List<String>>()
        fun skip(reason: SkipReason, label: String) {
            skipped.getOrPut(reason) { ArrayList() } += label
        }

        for (e in fresh) {
            // 같은 label 이 입력에 두 번 있으면 첫 번째(키가 작은 쪽)만 본다
            if (e.label in labels) continue
            val k = e.key()
            val ordinal: McOrdinal
            if (!e.isSnapshot) {
                val last = releases.lastOrNull()
                if (last != null && k <= last.key) {
                    skip(SkipReason.BACKDATED_RELEASE, e.label)
                    continue
                }
                ordinal = last?.let { nextOrdinal(it.ordinal) } ?: McOrdinal(ORDINAL_BASE)
                val max = byOrdinal.lastEntry()?.key
                if (max != null && ordinal.value <= max) {
                    return PlanResult.Aborted("ordinal invariant: ${e.label} ${ordinal.value} <= max $max")
                }
                // 새 서수는 기존 최대보다 크므로, 키가 더 큰(= 더 늦게 나온) 기존 항목은 전부 이 릴리스보다 작은 서수를 가진 스냅샷이다
                // (릴리스는 모두 last.key 이하). 서수는 바꾸지 않고 드러내기만 한다 (INV-2)
                val later = byOrdinal.values.filter { it > k }.map { it.label }
                if (later.isNotEmpty()) inversions[e.label] = later
                releases += Release(k, ordinal)
            } else {
                val p = lastReleaseBefore(releases, k)
                if (p == null) {
                    skip(SkipReason.NO_PREVIOUS_RELEASE, e.label)
                    continue
                }
                val gap = byOrdinal.subMap(p.ordinal.value, false, p.ordinal.value + ORDINAL_STEP - 1, true)
                if (gap.values.any { it > k }) {
                    skip(SkipReason.OUT_OF_ORDER_SNAPSHOT, e.label)
                    continue
                }
                // 빈 구간(첫 스냅샷)이면 p 자신을 기준으로 → p+1. lastKey()/maxOf 는 빈 구간에서 던지므로 먼저 확인한다
                val top = if (gap.isEmpty()) p.ordinal.value else gap.lastKey()
                val idx = top - p.ordinal.value + 1
                val o = snapshotOrdinal(p.ordinal, idx)
                if (o == null) {
                    skip(SkipReason.SLOT_OVERFLOW, e.label)
                    continue
                }
                ordinal = o
            }
            byOrdinal[ordinal.value] = k
            labels += e.label
            issued += e to ordinal
        }
        return PlanResult.Planned(OrdinalPlan(issued, skipped.mapValues { it.value.toList() }, inversions))
    }

    /** 키가 [k] 보다 작은 마지막 릴리스 (releases 는 키 오름차순). */
    private fun lastReleaseBefore(releases: List<Release>, k: Key): Release? {
        var lo = 0
        var hi = releases.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (releases[mid].key < k) lo = mid + 1 else hi = mid
        }
        return if (lo == 0) null else releases[lo - 1]
    }

    /**
     * per-version JSON 실패 후 이번 사이클에 INSERT 할 gap-closed 부분집합 (D54).
     * 결과는 [OrdinalPlan.issued] 의 부분 리스트이며 키 순서를 유지한다.
     *
     * - 실패한 **릴리스** 뒤(키 순)의 항목은 전부 버린다.
     * - 실패한 **스냅샷** 은 자기 칸 구간 `(p, p+9]` 에서 뒤따르는 스냅샷만 버린다.
     *
     * # 불변식
     * - `plan(existing + 결과)` 다음 `plan(전체)` 는 한 번에 한 `plan(existing, 전체)` 와 같은 label→ordinal 을 만든다.
     */
    fun retain(plan: OrdinalPlan, failed: Set<String>): List<Pair<IssuerEntry, McOrdinal>> {
        if (failed.isEmpty()) return plan.issued
        val out = ArrayList<Pair<IssuerEntry, McOrdinal>>(plan.issued.size)
        var droppedRelease = false
        val droppedGaps = HashSet<Int>()
        for (item in plan.issued) {
            if (droppedRelease) break
            val (e, ordinal) = item
            if (!e.isSnapshot) {
                if (e.label in failed) droppedRelease = true else out += item
                continue
            }
            val gapRelease = ordinal.value - Math.floorMod(ordinal.value - ORDINAL_BASE, ORDINAL_STEP)
            when {
                gapRelease in droppedGaps -> Unit
                e.label in failed -> droppedGaps += gapRelease
                else -> out += item
            }
        }
        return out
    }
}
