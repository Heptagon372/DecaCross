package kr.decacross.collector

import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.version.nextOrdinal
import kr.decacross.compat.version.seedOrdinals
import kr.decacross.compat.version.snapshotOrdinal
import org.slf4j.LoggerFactory
import kotlin.time.Instant

/** 서수 배정 입력. 라벨·스냅샷 여부·출시 시각만 있으면 된다. */
data class VersionStub(val label: String, val isSnapshot: Boolean, val releasedAt: Instant)

/** 기존 스냅샷의 한 행. 릴리스 서수의 최댓값을 구할 때 스냅샷 칸(릴리스+1..9)을 섞지 않기 위해 [isSnapshot] 이 필요하다. */
data class ExistingOrdinal(val label: String, val ordinal: McOrdinal, val isSnapshot: Boolean)

/** [assignOrdinals] 결과. [omitted] 는 자리가 없어 서수 없이 남긴 스냅샷 (절대 지어내지 않는다). */
data class OrdinalAssignment(
    val ordinals: Map<String, McOrdinal>,
    val omitted: List<String>,
    val notes: List<String>,
)

private val log = LoggerFactory.getLogger("kr.decacross.collector.Ordinals")

/**
 * 서수 배정 (명세 §2.1, 불변식 2).
 *
 * - [existing] 에 있는 라벨은 그 서수를 **그대로** 쓴다. 재할당 코드는 여기에도, 어디에도 없다.
 * - 최초 실행(existing 이 비었음): 릴리스를 `seedOrdinals` 로 시딩 (releasedAt 순 1000 + i*10).
 * - 신규 릴리스: `nextOrdinal(max)` 를 releasedAt 순으로 발급. 기존 최대보다 오래된 릴리스가 새로 나타나도
 *   중간에 끼워 넣지 않고 max+10 이다 (append-only) — 로그로 남긴다.
 * - 스냅샷: 직전 릴리스(releasedAt 기준) 서수 + (그 릴리스 이후 몇 번째 스냅샷인지, 1..9). 자리가 없거나
 *   그 칸을 다른 라벨이 이미 쓰고 있으면 서수 없이 둔다 ([OrdinalAssignment.omitted]).
 *
 * 같은 입력의 상위집합으로 다시 돌려도 기존 서수는 절대 바뀌지 않는다 (테스트 `ordinalStability`).
 */
fun assignOrdinals(existing: List<ExistingOrdinal>, versions: List<VersionStub>): OrdinalAssignment {
    val notes = ArrayList<String>()
    val result = LinkedHashMap<String, McOrdinal>()
    existing.forEach { result[it.label] = it.ordinal }
    val existingLabels = existing.map { it.label }.toSet()
    val sorted = versions.sortedWith(compareBy({ it.releasedAt }, { it.label }))
    val releases = sorted.filter { !it.isSnapshot }

    // 1) 릴리스
    val newReleases = releases.filter { it.label !in result }
    if (result.isEmpty()) {
        seedOrdinals(newReleases.map { it.label to it.releasedAt }).forEach { (label, ord) -> result[label] = ord }
        notes += "최초 시딩: 릴리스 ${newReleases.size}개"
    } else if (newReleases.isNotEmpty()) {
        // ★ 신규 릴리스 = max(릴리스 서수) + 10. 스냅샷 칸(릴리스+1..9)은 최댓값 계산에서 제외 — 안 그러면 10의 배수 격자가 깨진다.
        var max = existing.filter { !it.isSnapshot }.maxOfOrNull { it.ordinal.value }?.let(::McOrdinal)
            ?: McOrdinal(result.values.maxOf { it.value })
        val maxReleased = releases.filter { it.label in existingLabels }.maxOfOrNull { it.releasedAt }
        for (r in newReleases) {
            max = nextOrdinal(max)
            result[r.label] = max
            if (maxReleased != null && r.releasedAt < maxReleased) {
                notes += "릴리스 ${r.label} 는 기존 최신보다 오래됐지만 append-only 규칙대로 ${max.value} 발급"
            }
        }
    }

    // 2) 스냅샷: 직전 릴리스 이후 순번
    val omitted = ArrayList<String>()
    val used = HashSet<Int>(result.values.map { it.value })
    var prevRelease: VersionStub? = null
    var idx = 0
    for (v in sorted) {
        if (!v.isSnapshot) {
            prevRelease = v
            idx = 0
            continue
        }
        idx += 1
        if (v.label in result) continue
        val base = prevRelease?.let { result[it.label] }
        if (base == null) {
            omitted += v.label
            notes += "스냅샷 ${v.label}: 직전 릴리스가 없어 서수 생략"
            continue
        }
        val ord = snapshotOrdinal(base, idx)
        if (ord == null || ord.value in used) {
            omitted += v.label
            continue
        }
        result[v.label] = ord
        used += ord.value
    }
    if (omitted.isNotEmpty()) {
        log.info("서수 없이 남긴 스냅샷 {}개 (릴리스 사이 9칸 초과 등): {}…", omitted.size, omitted.take(5))
        notes += "서수 생략 스냅샷 ${omitted.size}개"
    }
    return OrdinalAssignment(result, omitted, notes)
}
