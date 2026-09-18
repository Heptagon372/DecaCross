package kr.decacross.pubgrub.testutil

import kr.decacross.pubgrub.Assignment
import kr.decacross.pubgrub.SolverState

/**
 * 부분해 로그를 사람이 읽을 수 있는 한 줄씩으로. 단위 전파·결정 루프 테스트가 같은 형식을 쓰므로 여기 둔다.
 *
 * - 결정: `g{전역번호} L{결정레벨} {패키지} = {버전}`
 * - 유도: `g{전역번호} L{결정레벨} {패키지} ← i{원인 비호환 번호} {항} ⇒ {누적 항}`
 */
internal fun <P : Any, V : Comparable<V>> formatLog(state: SolverState<P, V>): List<String> =
    state.partialSolution.snapshot().log.map { a ->
        val name = state.packages[a.pkg]
        when (a) {
            is Assignment.Decision -> "g${a.globalIndex} L${a.decisionLevel} $name = ${a.version}"

            is Assignment.Derivation ->
                "g${a.globalIndex} L${a.decisionLevel} $name ← i${a.cause.raw} ${a.term} ⇒ ${a.accumulated}"
        }
    }
