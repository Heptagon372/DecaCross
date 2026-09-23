package kr.decacross.compat.version

import kr.decacross.compat.model.McOrdinal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class OrdinalTest {
    private val t0 = Instant.parse("2011-11-18T22:00:00Z")

    private fun at(days: Int): Instant = Instant.fromEpochSeconds(t0.epochSeconds + days * 86_400L)

    @Test
    fun seed_sortsByReleaseDate_notByLabel() {
        // 라벨 정렬이면 "1.21.8" 이 "26.3" 보다 뒤다. 출시일 정렬이면 앞이다.
        val seeded = seedOrdinals(
            listOf(
                "26.3" to at(5000),
                "1.8.9" to at(1000),
                "1.21.8" to at(4000),
            ),
        )
        assertEquals(listOf("1.8.9", "1.21.8", "26.3"), seeded.map { it.first })
        assertEquals(listOf(1000, 1010, 1020), seeded.map { it.second.value })
    }

    @Test
    fun seed_isDeterministicOnEqualTimestamps() {
        val a = seedOrdinals(listOf("b" to at(1), "a" to at(1)))
        val b = seedOrdinals(listOf("a" to at(1), "b" to at(1)))
        assertEquals(a, b)
    }

    @Test
    fun next_isStrictlyMonotonic_andNeverReusesGaps() {
        var cur = McOrdinal(ORDINAL_BASE)
        val issued = mutableListOf(cur)
        repeat(50) {
            val n = nextOrdinal(cur)
            assertTrue(n > cur, "발급은 단조 증가해야 한다")
            assertEquals(ORDINAL_STEP, n.value - cur.value)
            cur = n
            issued += n
        }
        assertEquals(issued.size, issued.toSet().size, "재할당 없음")
    }

    @Test
    fun snapshot_takesGapSlots_andRefusesOverflow() {
        val rel = McOrdinal(2000)
        assertEquals(McOrdinal(2001), snapshotOrdinal(rel, 1))
        assertEquals(McOrdinal(2009), snapshotOrdinal(rel, 9))
        assertNull(snapshotOrdinal(rel, 10), "10번째 스냅샷은 다음 릴리스 자리를 침범하므로 발급하지 않는다")
        assertNull(snapshotOrdinal(rel, 0))
    }
}
