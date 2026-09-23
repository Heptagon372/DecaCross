package kr.decacross.compat.version

import kr.decacross.compat.model.McOrdinal
import kotlin.test.Test
import kotlin.test.assertTrue

class McOrdinalTest {
    /**
     * ★ 불변식 1 의 근거. 2026년 "1." 접두사 폐지 후 문자열 비교는 뒤집힌다.
     * 이 테스트는 "문자열 비교가 틀린다"를 명시적으로 고정해 둔다 — 누군가 label 비교로 되돌리면 여기서 걸린다.
     */
    @Test
    fun stringComparison_isWrong() {
        // "1.21.8" < "26.3" 은 우연히 맞는다 ('1' < '2'). 문자열 비교가 실제로 뒤집히는 곳은 자릿수가 바뀌는 지점이다.
        assertTrue("1.9" > "1.21", "문자열 비교: 1.9 가 1.21 보다 크게 나온다")
        assertTrue("1.21.10" < "1.21.8", "문자열 비교: 1.21.10 이 1.21.8 보다 작게 나온다")
        assertTrue("26.10" < "26.9", "문자열 비교: 새 체계에서도 26.10 이 26.9 보다 작게 나온다")
    }

    @Test
    fun numericSemVerComparison_isAlsoWrong_forSnapshotsAndHotfixes() {
        // SemVer 로 파싱하면 "26.3-pre1"(프리릴리스) 과 "1.21.8" 의 관계, 핫픽스(1.20.4 뒤에 나온 1.19.x 백포트) 처럼
        // 출시 순서와 숫자 순서가 다른 경우를 표현할 수 없다. 서수는 출시 순서만 본다.
        val backportedOld = McOrdinal(1500) // 숫자는 작지만 나중에 발급됐다면 서수가 더 크다 — 서수만이 진실
        val newer = McOrdinal(1490)
        assertTrue(backportedOld > newer)
    }

    @Test
    fun ordinalComparison_isCorrect() {
        val v1_21_8 = McOrdinal(1940)
        val v26_3 = McOrdinal(2020)
        assertTrue(v1_21_8 < v26_3)
        assertTrue(McOrdinal(1090) < McOrdinal(1210), "1.9 < 1.21")
        assertTrue(McOrdinal(1950) > McOrdinal(1940), "1.21.10 > 1.21.8")
    }

    @Test
    fun ordinal_isTotalOrder_andConsistentWithEquals() {
        val a = McOrdinal(5)
        val b = McOrdinal(5)
        assertTrue(a.compareTo(b) == 0 && a == b)
        assertTrue(McOrdinal(Int.MIN_VALUE) < McOrdinal(Int.MAX_VALUE))
    }
}
