package kr.decacross.daemon.install.fetch

import kr.decacross.daemon.install.FetchItem
import kr.decacross.daemon.install.FetchProgress
import kr.decacross.daemon.install.ItemProgress
import kr.decacross.daemon.install.ItemState
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * 진행률 집계기 (설계 §2.11 Progress).
 *
 * 다운로드 코루틴은 [add]·[setDone]·[setState] 로 값만 바꾸고, 티커 코루틴이 [snapshotIfChanged] 로 초당 최대 10회만
 * 스냅샷을 만든다. `Flow.sample` 은 마지막 값을 버릴 수 있어 쓰지 않는다 (F14).
 *
 * # 불변식
 * - 바이트 카운터는 원자적이라 잠금 없이 여러 코루틴이 더할 수 있다.
 * - [snapshot]·[snapshotIfChanged] 는 `@Synchronized` 다 (이동 평균 표본이 공유 상태).
 * - [ItemProgress.doneBytes] 는 [setDone] 으로만 줄어든다 (Range 거부 뒤 되감기).
 */
internal class ProgressTracker(items: List<FetchItem>) {
    private val ids: List<String> = items.map { it.id }
    private val totals: LongArray = LongArray(items.size) { items[it].size }
    private val done = AtomicLongArray(items.size)
    private val states = AtomicReferenceArray<ItemState>(items.size)

    /** 전체 기대 크기. */
    val totalBytes: Long = totals.sum()

    /** (nanoTime, doneBytes) 표본 — 최근 약 2초만 유지한다. */
    private val samples = ArrayDeque<LongArray>()
    private var lastDone: Long = -1
    private var lastStates: List<ItemState> = emptyList()

    init {
        for (i in items.indices) states.set(i, ItemState.QUEUED)
    }

    fun add(index: Int, bytes: Int) {
        if (bytes > 0) done.addAndGet(index, bytes.toLong())
    }

    /** 절대값으로 맞춘다 (이어받기 시작 오프셋, Range 거부 뒤 0). */
    fun setDone(index: Int, bytes: Long) {
        done.set(index, bytes.coerceAtLeast(0L))
    }

    fun setState(index: Int, state: ItemState) {
        states.set(index, state)
    }

    /** 무조건 스냅샷 (마지막 강제 스냅샷용). */
    @Synchronized
    fun snapshot(): FetchProgress = build()

    /** 바이트나 상태가 바뀐 경우에만 스냅샷. */
    @Synchronized
    fun snapshotIfChanged(): FetchProgress? {
        val total = currentDone()
        val st = currentStates()
        if (total == lastDone && st == lastStates) return null
        return build(total, st)
    }

    private fun currentDone(): Long {
        var sum = 0L
        for (i in 0 until done.length()) sum += done.get(i)
        return sum
    }

    private fun currentStates(): List<ItemState> = List(states.length()) { states.get(it) ?: ItemState.QUEUED }

    private fun build(doneSum: Long = currentDone(), st: List<ItemState> = currentStates()): FetchProgress {
        lastDone = doneSum
        lastStates = st
        val now = System.nanoTime()
        samples.addLast(longArrayOf(now, doneSum))
        while (samples.size > 1 && now - samples.first()[0] > SAMPLE_WINDOW_NANOS) samples.removeFirst()
        val oldest = samples.first()
        val elapsedNanos = now - oldest[0]
        val bps = if (elapsedNanos <= 0L) 0L else (doneSum - oldest[1]) * 1_000_000_000L / elapsedNanos
        val perItem = ids.indices.map { i -> ItemProgress(ids[i], done.get(i), totals[i], st[i]) }
        return FetchProgress(doneSum, totalBytes, bps.coerceAtLeast(0L), perItem)
    }

    companion object {
        /** 이동 평균 창 ≈ 2초. */
        private const val SAMPLE_WINDOW_NANOS: Long = 2_000_000_000L
    }
}
