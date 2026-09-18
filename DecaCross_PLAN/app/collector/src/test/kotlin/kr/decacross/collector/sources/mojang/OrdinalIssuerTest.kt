package kr.decacross.collector.sources.mojang

import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.version.seedOrdinals
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** §9.2 OrdinalIssuer. 기준: DESIGN_DIR/sim_ordinals.py 를 라이브 매니페스트(2026-09-17)에 돌린 값. */
class OrdinalIssuerTest {
    private val all = MojangTestSupport.manifestEntries

    /** 한 번에 발급한 label → ordinal. */
    private val oneShot: Map<String, Int> by lazy { planned(emptyList(), all).issued.associate { it.first.label to it.second.value } }

    private fun keyOf(e: IssuerEntry) = Triple(e.releasedAt, if (e.isSnapshot) 1 else 0, e.label)

    private val keyOrder = compareBy<IssuerEntry>({ it.releasedAt }, { if (it.isSnapshot) 1 else 0 }, { it.label })

    /** existing 에 cycles 를 차례로 적용한 최종 label → ordinal. */
    private fun applyCycles(vararg cycles: List<IssuerEntry>): Map<String, Int> {
        var existing = emptyList<ExistingOrdinal>()
        for (entries in cycles) existing = existing.withIssued(planned(existing, entries).issued)
        return existing.labelToOrdinal()
    }

    @Test
    fun fullManifest_issues539_releases103_overflow315() {
        val plan = planned(emptyList(), all)
        assertEquals(539, plan.issued.size)
        assertEquals(103, plan.issued.count { !it.first.isSnapshot })
        assertEquals(315, plan.skipped[SkipReason.SLOT_OVERFLOW]?.size)
        assertEquals(setOf(SkipReason.SLOT_OVERFLOW), plan.skipped.keys)
        assertTrue(plan.releaseInversions.isEmpty())
    }

    @Test
    fun releaseOrdinals_equalSeedOrdinals() {
        val releases = all.filter { !it.isSnapshot }
        val seed = seedOrdinals(releases.map { it.label to it.releasedAt }).associate { it.first to it.second.value }
        val issuedReleases = oneShot.filterKeys { label -> releases.any { it.label == label } }
        assertEquals(seed, issuedReleases)
        val spot = mapOf(
            "1.0" to 1000, "1.1" to 1010, "1.4.5" to 1110, "1.4.6" to 1120, "1.12.2" to 1510, "1.13" to 1520,
            "1.16.5" to 1680, "1.21" to 1860, "1.21.8" to 1940, "1.21.11" to 1970, "26.1" to 1980, "26.2" to 2010, "26.3" to 2020,
        )
        for ((label, ordinal) in spot) assertEquals(ordinal, oneShot[label], label)
    }

    @Test
    fun snapshotSpotValues() {
        val spot = mapOf(
            "17w43a" to 1511, "17w49a" to 1519, "20w45a" to 1671, "26.1-snapshot-1" to 1971, "26w14a" to 1991,
            "26.3-snapshot-1" to 2011, "26.3-snapshot-9" to 2019, "1.6.3" to 1174, "13w37b" to 1175,
        )
        for ((label, ordinal) in spot) assertEquals(ordinal, oneShot[label], label)
        for (label in listOf("1.13-pre7", "26.3-rc-3", "1.21.11-rc3", "1.21.9-pre2")) assertNull(oneShot[label], label)
        assertTrue("1.14.2 Pre-Release 4" in oneShot)
    }

    @Test
    fun prefixClosed_splitAt2021() {
        val cut = Instant.parse("2021-01-01T00:00:00Z")
        val first = planned(emptyList(), all.filter { it.releasedAt < cut })
        val existing = emptyList<ExistingOrdinal>().withIssued(first.issued)
        val second = planned(existing, all)
        assertEquals(292, first.issued.size)
        assertEquals(247, second.issued.size)
        assertEquals(oneShot, existing.withIssued(second.issued).labelToOrdinal())
    }

    @Test
    fun prefixClosed_200RandomKeyCuts() {
        val sorted = all.sortedWith(keyOrder)
        val rnd = Random(20260917)
        repeat(200) { i ->
            val k = rnd.nextInt(0, sorted.size + 1)
            assertEquals(oneShot, applyCycles(sorted.subList(0, k), all), "cut #$i at $k")
        }
    }

    @Test
    fun prefixClosed_tenStepIncremental() {
        val sorted = all.sortedWith(keyOrder)
        val steps = (1..10).map { i -> sorted.subList(0, sorted.size * i / 10) }
        assertEquals(oneShot, applyCycles(*steps.toTypedArray()))
    }

    @Test
    fun releasesFirst_thenSnapshots_equal() {
        assertEquals(oneShot, applyCycles(all.filter { !it.isSnapshot }, all))
    }

    @Test
    fun retain_failureEquivalence_property() {
        val plan = planned(emptyList(), all)
        val labels = plan.issued.map { it.first.label }
        val rnd = Random(20260917)
        repeat(300) { i ->
            val failed = labels.shuffled(rnd).take(rnd.nextInt(1, 6)).toSet()
            val kept = OrdinalIssuer.retain(plan, failed)
            val existing = emptyList<ExistingOrdinal>().withIssued(kept)
            val next = planned(existing, all)
            assertEquals(oneShot, existing.withIssued(next.issued).labelToOrdinal(), "failure set #$i $failed")
            val truncated = plan.issued.takeWhile { it.first.label !in failed }.size
            assertTrue(kept.size >= truncated, "retain ${kept.size} < truncate $truncated for $failed")
            // 부분 리스트 + 키 순서 유지
            assertTrue(kept.all { it in plan.issued } && kept.zipWithNext().all { (a, b) -> keyOrder.compare(a.first, b.first) < 0 })
        }
    }

    @Test
    fun retain_snapshotFailure_dropsOnlyItsGap() {
        val plan = planned(emptyList(), all)
        assertEquals(530, OrdinalIssuer.retain(plan, setOf("17w43a")).size)
        assertEquals(202, OrdinalIssuer.retain(plan, setOf("1.13")).size)
        assertEquals(plan.issued, OrdinalIssuer.retain(plan, emptySet()))
    }

    @Test
    fun lateSnapshot_outOfOrder_documented() {
        val first = planned(emptyList(), all.filter { it.label != "26.3-snapshot-1" })
        val firstMap = first.issued.associate { it.first.label to it.second.value }
        assertEquals(2011, firstMap["26.3-snapshot-2"])
        val existing = emptyList<ExistingOrdinal>().withIssued(first.issued)
        val second = planned(existing, all)
        assertTrue("26.3-snapshot-1" in second.skipped[SkipReason.OUT_OF_ORDER_SNAPSHOT].orEmpty())
        assertTrue(second.issued.isEmpty())
        assertEquals(2011, existing.withIssued(second.issued).labelToOrdinal()["26.3-snapshot-2"])
    }

    @Test
    fun lateRelease_backdated_documented() {
        val first = planned(emptyList(), all.filter { it.label != "26.2" })
        val existing = emptyList<ExistingOrdinal>().withIssued(first.issued)
        val second = planned(existing, all)
        assertEquals(listOf("26.2"), second.skipped[SkipReason.BACKDATED_RELEASE])
        val finalMap = existing.withIssued(second.issued).labelToOrdinal()
        assertNull(finalMap["26.2"])
        for (i in 1..9) assertNull(finalMap["26.3-snapshot-$i"], "26.3-snapshot-$i")
    }

    @Test
    fun lateRelease_afterIssuedSnapshots_issuedWithInversionReported() {
        // INV-2: r2 가 늦게 도착했다. 그보다 늦게 나온 s-after 는 이미 r1 칸(1002)에 있다 → r2 는 발급하되 역전을 드러낸다
        val existing = listOf(
            ExistingOrdinal("r1", McOrdinal(1000), false, at(0)),
            ExistingOrdinal("s-before", McOrdinal(1001), true, at(1)),
            ExistingOrdinal("s-after", McOrdinal(1002), true, at(3)),
        )
        val plan = planned(existing, listOf(entry("r2", false, 2), entry("s-next", true, 4)))
        assertEquals(listOf("r2" to 1010, "s-next" to 1011), plan.issued.map { it.first.label to it.second.value })
        assertEquals(mapOf("r2" to listOf("s-after")), plan.releaseInversions)
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun lateTrailingReleases_everyInversionReported_property() {
        val releasesByKey = all.filter { !it.isSnapshot }.sortedWith(keyOrder)
        val rnd = Random(20260917)
        var sawInversion = false
        repeat(60) { i ->
            // 최신 릴리스 n 개 + 임의 릴리스 몇 개가 늦게 도착
            val trailing = releasesByKey.takeLast(rnd.nextInt(1, 7)).map { it.label }
            val random = releasesByKey.shuffled(rnd).take(rnd.nextInt(0, 3)).map { it.label }
            val late = (trailing + random).toSet()
            val first = planned(emptyList(), all.filter { it.label !in late })
            assertTrue(first.releaseInversions.isEmpty(), "순서대로 도착한 입력에는 역전이 없다")
            val existing = emptyList<ExistingOrdinal>().withIssued(first.issued)
            val second = planned(existing, all)
            val rows = existing.withIssued(second.issued)
            val reported = second.releaseInversions.flatMap { (r, snapshots) -> snapshots.map { r to it } }.toSet()
            val keyed = compareBy<ExistingOrdinal>({ it.releasedAt }, { if (it.isSnapshot) 1 else 0 }, { it.label })
            val actual = rows.filter { !it.isSnapshot }.flatMap { r ->
                rows.filter { s -> s.isSnapshot && s.ordinal.value < r.ordinal.value && keyed.compare(s, r) > 0 }.map { r.label to it.label }
            }.toSet()
            assertEquals(actual, reported, "late #$i $late")
            if (actual.isNotEmpty()) sawInversion = true
        }
        assertTrue(sawInversion, "속성 검사가 실제 역전 사례를 한 번 이상 만들어야 한다")
    }

    @Test
    fun emptyGap_maxOfOrNull_noThrow() {
        val r = entry("r", false, 0)
        val s = entry("s", true, 1)
        val plan = planned(emptyList(), listOf(r, s))
        assertEquals(listOf("r" to 1000, "s" to 1001), plan.issued.map { it.first.label to it.second.value })
        // 기존 릴리스만 있고 구간이 빈 상태에서도 p + 1
        val again = planned(listOf(ExistingOrdinal("r", McOrdinal(1000), false, r.releasedAt)), listOf(s))
        assertEquals(1001, again.issued.single().second.value)
    }

    @Test
    fun abortedInvariant_sealedResult() {
        val existing = listOf(
            ExistingOrdinal("r", McOrdinal(1000), false, at(0)),
            // 조작된 행: 다음 릴리스(1010)보다 큰 서수
            ExistingOrdinal("weird", McOrdinal(1500), true, at(1)),
        )
        val result = OrdinalIssuer.plan(existing, listOf(entry("r2", false, 2)))
        assertIs<PlanResult.Aborted>(result)
        assertTrue(result.reason.contains("r2"))
    }

    @Test
    fun idempotent_secondRunIssuesNothing() {
        val existing = emptyList<ExistingOrdinal>().withIssued(planned(emptyList(), all).issued)
        val again = planned(existing, all)
        assertTrue(again.issued.isEmpty())
        assertEquals(setOf(SkipReason.SLOT_OVERFLOW), again.skipped.keys)
    }

    @Test
    fun backdatedRelease_skipped() {
        val existing = listOf(
            ExistingOrdinal("a", McOrdinal(1000), false, at(0)),
            ExistingOrdinal("c", McOrdinal(1010), false, at(2)),
        )
        val plan = planned(existing, listOf(entry("b", false, 1), entry("d", false, 3)))
        assertEquals(listOf("b"), plan.skipped[SkipReason.BACKDATED_RELEASE])
        assertEquals(listOf("d" to 1020), plan.issued.map { it.first.label to it.second.value })
    }

    @Test
    fun outOfOrderSnapshot_skipped() {
        val existing = listOf(
            ExistingOrdinal("r", McOrdinal(1000), false, at(0)),
            ExistingOrdinal("s2", McOrdinal(1001), true, at(2)),
        )
        val plan = planned(existing, listOf(entry("s1", true, 1), entry("s3", true, 3)))
        assertEquals(listOf("s1"), plan.skipped[SkipReason.OUT_OF_ORDER_SNAPSHOT])
        assertEquals(listOf("s3" to 1002), plan.issued.map { it.first.label to it.second.value })
    }

    @Test
    fun snapshotBeforeFirstRelease_skipped() {
        val plan = planned(emptyList(), listOf(entry("s0", true, 0), entry("r", false, 1)))
        assertEquals(listOf("s0"), plan.skipped[SkipReason.NO_PREVIOUS_RELEASE])
        assertEquals(listOf("r" to 1000), plan.issued.map { it.first.label to it.second.value })
    }

    @Test
    fun emptyExisting_firstReleaseIsBase() {
        val plan = planned(emptyList(), listOf(entry("x", false, 5), entry("y", false, 6)))
        assertEquals(listOf("x" to 1000, "y" to 1010), plan.issued.map { it.first.label to it.second.value })
    }

    @Test
    fun tieBreak_releaseBeforeSnapshotAtSameInstant_thenLabel() {
        // 같은 시각: 릴리스(typeRank 0)가 스냅샷보다 먼저 → 스냅샷은 그 릴리스의 구간에 들어간다
        val plan = planned(emptyList(), listOf(entry("a-snapshot", true, 0), entry("z-release", false, 0)))
        assertEquals(listOf("z-release" to 1000, "a-snapshot" to 1001), plan.issued.map { it.first.label to it.second.value })
        // 같은 시각 릴리스 둘: String.compareTo(label) 순 (라이브: 1.4.5 / 1.4.6)
        val releases = planned(emptyList(), listOf(entry("1.4.6", false, 0), entry("1.4.5", false, 0)))
        assertEquals(listOf("1.4.5" to 1000, "1.4.6" to 1010), releases.issued.map { it.first.label to it.second.value })
        assertTrue(keyOf(entry("1.4.5", false, 0)).third < keyOf(entry("1.4.6", false, 0)).third)
    }

    private fun at(seconds: Long): Instant = Instant.fromEpochSeconds(1_700_000_000L + seconds)

    private fun entry(label: String, snapshot: Boolean, seconds: Long) = IssuerEntry(label, snapshot, at(seconds))
}
