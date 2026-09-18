package kr.decacross.daemon.install.fetch

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kr.decacross.daemon.install.FetchProgress
import kr.decacross.daemon.install.ItemState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 진행률 합치기 (설계 §2.11, §4.5 테스트 22). 실제 시간으로 돈다 — `runTest` 가상 시간이 아니다. */
class ProgressTrackerTest {
    @Test
    fun `여러 번 더해도 스냅샷은 초당 10회로 합쳐지고 마지막은 전체 크기다`(): Unit = runBlocking {
        // 설계 §4.5 테스트 22 는 1000회지만, Windows 의 delay(1) 은 2 ms 넘게 걸려 테스트만 길어진다.
        // 300회로도 "더한 횟수 ≫ 스냅샷 수" 는 그대로다 (다른 WP 와 병렬로 도는 3분 예산).
        val total = 300L
        val item = testItem(ByteArray(total.toInt()))
        val tracker = ProgressTracker(listOf(item))
        val snapshots = ArrayList<FetchProgress>()

        val ticker = launch {
            while (isActive) {
                delay(100)
                tracker.snapshotIfChanged()?.let { snapshots.add(it) }
            }
        }
        tracker.setState(0, ItemState.DOWNLOADING)
        val startNanos = System.nanoTime()
        repeat(total.toInt()) {
            tracker.add(0, 1)
            delay(1)
        }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        ticker.cancel()
        tracker.setState(0, ItemState.DONE)
        val last = tracker.snapshot()
        snapshots.add(last)

        // 핵심: 더한 횟수(300)에 비해 스냅샷은 훨씬 적다 = 합쳐졌다. 상한은 "초당 10회 + 마지막 강제 1회".
        assertTrue(snapshots.size * 5 < total, "스냅샷이 합쳐지지 않았다: ${snapshots.size}")
        val allowed = elapsedMs / 100 + 2
        assertTrue(snapshots.size <= allowed, "스냅샷이 너무 많다: ${snapshots.size} (${elapsedMs}ms, 허용 $allowed)")
        assertEquals(total, last.doneBytes)
        assertEquals(total, last.totalBytes)
        assertEquals(ItemState.DONE, last.items.single().state)
        // 단조 증가 (ResumeRejected 가 없으면 줄어들지 않는다)
        assertEquals(snapshots.map { it.doneBytes }.sorted(), snapshots.map { it.doneBytes })
    }

    @Test
    fun `바뀐 것이 없으면 스냅샷을 만들지 않는다`() {
        val item = testItem(ByteArray(10))
        val tracker = ProgressTracker(listOf(item))
        assertTrue(tracker.snapshotIfChanged() != null, "첫 스냅샷은 나와야 한다")
        assertEquals(null, tracker.snapshotIfChanged())
        tracker.add(0, 3)
        assertEquals(3L, tracker.snapshotIfChanged()?.doneBytes)
        assertEquals(null, tracker.snapshotIfChanged())
        tracker.setState(0, ItemState.FAILED)
        assertEquals(ItemState.FAILED, tracker.snapshotIfChanged()?.items?.single()?.state)
    }

    @Test
    fun `이어받기 오프셋에서 시작하고 Range 거부 때만 줄어든다`() {
        val item = testItem(ByteArray(100))
        val tracker = ProgressTracker(listOf(item))
        tracker.setDone(0, 40)
        assertEquals(40L, tracker.snapshot().doneBytes)
        tracker.add(0, 10)
        assertEquals(50L, tracker.snapshot().doneBytes)
        tracker.setDone(0, 0)
        assertEquals(0L, tracker.snapshot().doneBytes)
    }
}
