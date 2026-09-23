package kr.decacross.collector

import kr.decacross.compat.model.McOrdinal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class OrdinalsTest {
    private fun stub(label: String, at: String, snapshot: Boolean = false) = VersionStub(label, snapshot, Instant.parse(at))

    /** 이전 실행 결과를 "기존 스냅샷" 형태로. */
    private fun existingOf(a: OrdinalAssignment, stubs: List<VersionStub>): List<ExistingOrdinal> {
        val snap = stubs.filter { it.isSnapshot }.map { it.label }.toSet()
        return a.ordinals.map { (label, ord) -> ExistingOrdinal(label, ord, label in snap) }
    }

    private val base = listOf(
        stub("1.20.4", "2023-12-07T12:00:00Z"),
        stub("1.21", "2024-06-13T12:00:00Z"),
        stub("24w33a", "2024-08-15T12:00:00Z", snapshot = true),
        stub("24w34a", "2024-08-22T12:00:00Z", snapshot = true),
        stub("1.21.1", "2024-08-08T12:00:00Z"),
    )

    @Test
    fun firstRun_seedsReleasesByReleaseTime() {
        val a = assignOrdinals(emptyList(), base)
        assertEquals(McOrdinal(1000), a.ordinals["1.20.4"])
        assertEquals(McOrdinal(1010), a.ordinals["1.21"])
        assertEquals(McOrdinal(1020), a.ordinals["1.21.1"])
        // 스냅샷은 직전 릴리스(1.21.1, 1020) + 1, +2
        assertEquals(McOrdinal(1021), a.ordinals["24w33a"])
        assertEquals(McOrdinal(1022), a.ordinals["24w34a"])
        assertTrue(a.omitted.isEmpty())
    }

    @Test
    fun rerunOverSuperset_neverChangesExistingOrdinal() {
        val first = assignOrdinals(emptyList(), base)
        val superset = base + listOf(
            stub("1.21.2", "2024-10-22T12:00:00Z"),
            stub("24w35a", "2024-08-29T12:00:00Z", snapshot = true),
            // 기존 최신보다 오래된 릴리스가 새로 나타나도 끼워 넣지 않는다
            stub("1.19.4", "2023-03-14T12:00:00Z"),
        )
        val second = assignOrdinals(existingOf(first, base), superset)
        for ((label, ord) in first.ordinals) assertEquals(ord, second.ordinals[label], "서수가 바뀜: $label")
        // 신규 릴리스는 releasedAt 순으로 max(릴리스 서수)+10 — 스냅샷 칸(1022)은 최댓값에 안 들어간다
        assertEquals(McOrdinal(1030), second.ordinals["1.19.4"])
        assertEquals(McOrdinal(1040), second.ordinals["1.21.2"])
        assertEquals(McOrdinal(1023), second.ordinals["24w35a"])
        // 세 번째 실행도 동일
        val third = assignOrdinals(existingOf(second, superset), superset)
        assertEquals(second.ordinals, third.ordinals)
    }

    @Test
    fun snapshots_beyondNineSlotsAreOmittedNotInvented() {
        val many = listOf(stub("1.21", "2024-06-13T12:00:00Z")) +
            (1..12).map { stub("s$it", "2024-07-%02dT12:00:00Z".format(it), snapshot = true) }
        val a = assignOrdinals(emptyList(), many)
        assertEquals(McOrdinal(1009), a.ordinals["s9"])
        assertNull(a.ordinals["s10"])
        assertEquals(listOf("s10", "s11", "s12"), a.omitted)
    }

    @Test
    fun snapshotBeforeAnyRelease_isOmitted() {
        val a = assignOrdinals(emptyList(), listOf(stub("s0", "2024-01-01T00:00:00Z", snapshot = true), stub("1.21", "2024-06-13T12:00:00Z")))
        assertNull(a.ordinals["s0"])
        assertEquals(listOf("s0"), a.omitted)
    }

    @Test
    fun snapshotSlotAlreadyTakenByExisting_isOmitted() {
        val existing = listOf(ExistingOrdinal("1.21", McOrdinal(1000), false), ExistingOrdinal("hotfix", McOrdinal(1001), true))
        val a = assignOrdinals(existing, listOf(stub("1.21", "2024-06-13T12:00:00Z"), stub("24w30a", "2024-07-01T00:00:00Z", snapshot = true)))
        assertNull(a.ordinals["24w30a"])
        assertEquals(McOrdinal(1001), a.ordinals["hotfix"])
    }
}
