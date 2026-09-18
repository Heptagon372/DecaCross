package kr.decacross.pubgrub

import kotlin.random.Random
import kotlin.test.fail

/**
 * 속성 테스트용 유한 도메인과 독립 오라클. [Range] 의 집합 연산을 쓰지 않는다 (소속 관찰은 [Range.contains] 만).
 *
 * - 경계값은 짝수 `0, 2, …, 2(k−1)`.
 * - 탐침(probe) `t ∈ 0..2k` 는 점 `t − 1` 을 뜻한다 → 원자 수 `atoms = 2k + 1`.
 *   홀수 점(짝수 탐침)은 두 경계값 "사이"를 대신하고(조밀성 대리), `-1` 과 `2k−1` 은 양 끝 바깥이다.
 * - 집합은 탐침별 소속 비트마스크(`Long`)로 비교한다. `k ≤ 31` 이면 원자가 63개 이하라 `Long` 에 들어간다.
 */
internal class Dom(
    val k: Int,
) {
    init {
        // k > 31 이면 원자가 63개를 넘어 `1L shl t` 가 64 로 감겨 오라클이 조용히 깨진다
        require(k in 1..31) { "Dom: Long 마스크는 k ≤ 31 까지만 지원 (k=$k)" }
    }

    val atoms: Int = 2 * k + 1

    /** 모든 원자가 켜진 마스크. 원자 63개면 `Long.MAX_VALUE`. */
    val fullMask: Long = (1L shl atoms) - 1

    /** 탐침별 `contains(t − 1)` 비트마스크. */
    fun mask(r: Range<Int>): Long {
        var m = 0L
        for (t in 0 until atoms) {
            if (r.contains(t - 1)) m = m or (1L shl t)
        }
        return m
    }

    /** 1 비트 구간 `i..j` 가 시작하는 탐침 i 의 하한. */
    private fun lowerOf(i: Int): Bound<Int> =
        when {
            i == 0 -> Bound.Unbounded
            i % 2 == 1 -> Bound.Inclusive(i - 1)
            else -> Bound.Exclusive(i - 2)
        }

    /** 1 비트 구간 `i..j` 가 끝나는 탐침 j 의 상한. */
    private fun upperOf(j: Int): Bound<Int> =
        when {
            j == atoms - 1 -> Bound.Unbounded
            j % 2 == 1 -> Bound.Inclusive(j - 1)
            else -> Bound.Exclusive(j)
        }

    /** 마스크 → 정규형 구간 목록. 1 비트가 연속된 구간마다 구간 하나 (Range 연산을 쓰지 않는다). */
    fun canonical(m: Long): List<Interval<Int>> {
        val out = ArrayList<Interval<Int>>()
        var t = 0
        while (t < atoms) {
            if (m and (1L shl t) != 0L) {
                var u = t
                while (u + 1 < atoms && m and (1L shl (u + 1)) != 0L) u++
                out += Interval(lowerOf(t), upperOf(u))
                t = u + 1
            } else {
                t++
            }
        }
        return out
    }

    fun fromMask(m: Long): Range<Int> = Range.fromIntervals(canonical(m))

    /** 무작위 경계. 값은 경계값 중 하나, 종류는 Inclusive 2/5 · Exclusive 2/5 · Unbounded 1/5. */
    private fun randomBound(rnd: Random): Bound<Int> {
        val v = rnd.nextInt(0, k) * 2
        return when (rnd.nextInt(5)) {
            0, 1 -> Bound.Inclusive(v)
            2, 3 -> Bound.Exclusive(v)
            else -> Bound.Unbounded
        }
    }

    /** 겹침·접촉·빈·역전 구간이 섞인 원시 구간 `0..maxRaw` 개. */
    private fun randomRaw(
        rnd: Random,
        maxRaw: Int,
    ): List<Interval<Int>> = List(rnd.nextInt(0, maxRaw + 1)) { Interval(randomBound(rnd), randomBound(rnd)) }

    /**
     * 세 생성기를 같은 비율로 섞는다: 원시 구간 [Range.fromIntervals], 원시 구간마다 [Range.of] 를 만들어 [Range.union] 으로 접기,
     * 무작위 마스크의 [fromMask].
     */
    fun randomRange(
        rnd: Random,
        maxRaw: Int = 4,
    ): Range<Int> =
        when (rnd.nextInt(3)) {
            0 -> Range.fromIntervals(randomRaw(rnd, maxRaw))
            1 -> randomRaw(rnd, maxRaw).fold(Range.empty()) { acc, iv -> acc.union(Range.of(iv.lower, iv.upper)) }
            else -> fromMask(rnd.nextLong() and fullMask)
        }
}

/** 조건이 거짓일 때만 메시지를 만들어 실패시킨다 (수백만 번 호출되므로 메시지는 지연 생성). */
internal inline fun law(
    cond: Boolean,
    msg: () -> String,
) {
    if (!cond) fail(msg())
}

/** [r] 이 정규형이고, 소속이 [expectedMask] 와 같고, 마스크에서 독립적으로 만든 정규형과 구조까지 같은지 검사한다. */
internal fun assertCanonical(
    d: Dom,
    r: Range<Int>,
    expectedMask: Long,
    what: () -> String = { "" },
) {
    val violation = r.canonicalViolation()
    law(violation == null) { "정규형 위반 ($violation) — ${what()}" }
    val m = d.mask(r)
    law(m == expectedMask) {
        "소속 불일치 — ${what()}: got $r mask=${m.toString(2)} expected=${expectedMask.toString(2)}"
    }
    // 구조적 동등 ⇔ 집합 동등: 결과가 마스크에서 독립적으로 만든 정규형과 구조적으로 같아야 한다.
    law(r.intervals == d.canonical(expectedMask)) { "구조 불일치 — ${what()}: $r vs ${d.canonical(expectedMask)}" }
}
