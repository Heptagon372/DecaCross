package kr.decacross.compat.version

import kr.decacross.compat.model.McOrdinal
import kotlin.time.Instant

/** 초기 시딩 기준값: i번째 릴리스 → `ORDINAL_BASE + i * ORDINAL_STEP`. 사이 9칸은 스냅샷/핫픽스 여유. */
public const val ORDINAL_BASE: Int = 1000

public const val ORDINAL_STEP: Int = 10

/**
 * 신규 릴리스 서수 발급. append-only.
 *
 * # 불변식
 * 기존 서수를 재할당하는 함수는 이 모듈에 존재하지 않는다. 만들지 마라 (CLAUDE.md 불변식 2).
 */
public fun nextOrdinal(maxExisting: McOrdinal): McOrdinal = McOrdinal(maxExisting.value + ORDINAL_STEP)

/**
 * 초기 시딩 (1회만). `releasedAt` 오름차순으로 정렬해 i번째 → `1000 + i*10`.
 * 같은 시각이면 라벨 순으로 안정 정렬해 결정적으로 만든다.
 */
public fun seedOrdinals(releases: List<Pair<String, Instant>>): List<Pair<String, McOrdinal>> =
    releases
        .sortedWith(compareBy({ it.second }, { it.first }))
        .mapIndexed { i, (label, _) -> label to McOrdinal(ORDINAL_BASE + i * ORDINAL_STEP) }

/**
 * 스냅샷 서수: 직전 릴리스 서수 + 1..9. 자리가 없으면(10번째 이후) null — 호출자가 처리한다.
 * 릴리스 사이 여유 칸을 다 쓰면 그 스냅샷은 서수 없이 둔다. 재할당은 없다.
 */
public fun snapshotOrdinal(previousRelease: McOrdinal, indexAfterRelease: Int): McOrdinal? =
    if (indexAfterRelease in 1 until ORDINAL_STEP) McOrdinal(previousRelease.value + indexAfterRelease) else null
