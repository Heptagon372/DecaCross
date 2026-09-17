# P1. `pubgrub-kt` 포팅 — 병행 트랙 (1~6주) ★ 최대 일정 리스크

**선행조건:** 00 완료
**기간:** 2~3주 분량. **1주차에 착수**해서 6주차까지 끝낸다. 7주차에 07이 이걸 쓴다.
**완료 판정:**
```
./gradlew :core:pubgrub-kt:test
→ 이식한 골든 케이스 전부 통과
→ NoSolution 케이스에서 DerivationTree 가 사람이 읽을 수 있는 형태로 나옴
```

> **왜 직접 만드나:** Kotlin/JVM 에 쓸만한 PubGrub 구현이 없다(조사 확인).
> Rust `pubgrub`, Dart `pub`, Python `uv` 셋뿐이다. 그래서 포팅이 Kotlin 전환의 유일한 실비용이다.
> 대신 참고 구현이 셋이나 있어서 막히면 대조할 수 있다.

---

## 한 번에 하지 마라 — 4단계로 쪼갠다

| 단계 | 내용 | 완료 판정 |
|---|---|---|
| P1-a | `VersionSet` 대수 + `Range` 구현 | 구간 연산 속성 테스트 통과 |
| P1-b | `Incompatibility` + `PartialSolution` + 단위 전파 | 단순 그래프 해결 |
| P1-c | `conflictResolution` + 백점프 | 충돌 케이스 해결 |
| P1-d | `DerivationTree` 품질 + 골든 케이스 이식 | 전부 통과 |

각 단계를 별도 세션으로 돌려라.

---

## 프롬프트 (P1-a)

```
PubGrub 의존성 해결 알고리즘을 Kotlin 으로 포팅한다. 첫 단계는 버전 집합 대수다.

먼저 읽어라:
- docs/03_구현명세_v2.md §4 (pubgrub-kt 공개 API)
- docs/00_스택결정_Kotlin.md §3 C-1 (왜 포팅하는가, 검증 전략)

★ 시작 전에: PubGrub 알고리즘 원문 설명을 먼저 확인해라.
  Dart pub 의 solver 문서와 Rust pubgrub 의 구현을 참고 자료로 본다.
  네가 이해한 알고리즘 개요를 먼저 요약해서 보여주고, 맞는지 확인받고 시작해라.

core/pubgrub-kt/src/commonMain/kotlin/kr/decacross/pubgrub/

1. VersionSet.kt — 명세 §4.1 인터페이스
   interface VersionSet<V : Comparable<V>> {
       val isEmpty: Boolean
       fun contains(v: V): Boolean
       fun intersect(other: VersionSet<V>): VersionSet<V>
       fun union(other: VersionSet<V>): VersionSet<V>
       fun complement(): VersionSet<V>
   }

2. Range.kt — 구간 합집합으로 구현한 기본 VersionSet
   내부 표현: 정렬된 disjoint 구간 리스트 [(lo, hi), ...]
   경계는 포함/미포함 구분 (Bound.Inclusive / Exclusive / Unbounded)

   연산이 항상 정규형(normalized)을 유지해야 한다:
   - 구간이 정렬되어 있고
   - 인접/중첩 구간이 병합되어 있고
   - 빈 구간이 없다

3. 속성 테스트 (kotest property 또는 직접)
   무작위 구간 집합 100만 조합으로:
   - a ∩ complement(a) = ∅
   - a ∪ complement(a) = full
   - (a ∩ b) ∩ c = a ∩ (b ∩ c)
   - contains(v) 가 집합 연산 결과와 일치
   - 정규형 불변식이 모든 연산 후 유지됨

제약:
- KMP common. 플랫폼 의존성 0
- 도메인 지식 0. 마크나 플러그인을 여기서 언급하지 마라. 순수 알고리즘이다
- explicit API mode
- 성능: intersect/union 이 구간 수에 대해 선형이어야 한다

이 단계는 여기까지. Solver 는 다음 세션에서.
```

---

## 프롬프트 (P1-b)

```
PubGrub 포팅 2단계: Incompatibility, PartialSolution, 단위 전파.

선행: P1-a (VersionSet/Range) 완료

1. Term.kt
   data class Term<P, V>(val pkg: P, val set: VersionSet<V>, val positive: Boolean)
   - satisfies / contradicts / relation(other) 판정

2. Incompatibility.kt
   "이 항들이 동시에 참일 수 없다"는 집합.
   원인(cause)을 함께 들고 다닌다 — 나중에 DerivationTree 가 된다.
   sealed interface Cause {
       Root, NoVersions, Unavailable, Dependency, DerivedFrom(i1, i2)
   }

3. PartialSolution.kt
   - assignments: 결정(decision)과 유도(derivation)의 스택
   - decisionLevel 추적
   - relation(incompatibility): Satisfied / AlmostSatisfied(term) / Contradicted / Inconclusive
   - backtrack(level): 해당 레벨까지 되돌리기

4. Solver.kt — unitPropagation 까지만
   현재 부분해에서 강제되는 결론을 전파한다.
   충돌이 나면 일단 예외/결과로 표시만 하고 conflictResolution 은 다음 단계.

5. 테스트
   - 의존성이 단순한 그래프(충돌 없음)를 해결
   - 단위 전파가 올바른 항을 유도하는지
   - backtrack 이 상태를 정확히 되돌리는지

제약: P1-a 와 동일. 도메인 지식 0.
```

---

## 프롬프트 (P1-c)

```
PubGrub 포팅 3단계: 충돌 해결(conflict resolution)과 백점프.

선행: P1-b 완료

1. Solver.kt 의 conflictResolution()
   PubGrub 의 핵심이다. 충돌한 incompatibility 에서 시작해:
   - 원인을 역추적하며 새 incompatibility 를 학습(resolvent)
   - "거의 만족"하는 지점까지 올라가 백점프
   - 학습한 incompatibility 를 추가해 같은 실패를 반복하지 않게 한다

   ★ 이 부분이 포팅에서 가장 까다롭다. Rust/Dart 구현을 정확히 대조해라.
     이해가 안 되는 부분이 있으면 우회하지 말고 말해줘.

2. decisionMaking()
   - prioritize() 로 다음에 결정할 패키지 선택 (후보 수가 적은 것 우선)
   - chooseVersion() 으로 버전 선택
   - getDependencies() 결과를 incompatibility 로 변환해 추가

3. resolve() 완성
   fun resolve(provider, root, rootVersion): SolverResult

4. 테스트
   - 백트래킹이 필요한 케이스 (얕은 선택이 나중에 막히는 그래프)
   - 순환 의존성
   - 해가 없는 케이스에서 무한루프 없이 종료
   - 성능: 패키지 100개 × 버전 20개 그래프에서 100ms 이내

5. 무한루프 방어
   반복 횟수 상한을 두고, 초과 시 명확한 에러를 던져라.
   (알고리즘상 종료가 보장되지만, 포팅 버그로 안 끝날 수 있다)
```

---

## 프롬프트 (P1-d)

```
PubGrub 포팅 4단계: DerivationTree 품질 + 골든 케이스 이식.

선행: P1-c 완료

★ 이 단계가 진짜 목표다. 해만 찾고 트리가 엉망이면 포팅 실패다 —
  데카크로스의 차별점(왜 안 되는지 설명하기)이 전부 이 트리 위에 올라간다.

1. DerivationTree.kt — 명세 §4.1 구조
   sealed interface DerivationTree { External, Derived }
   sealed interface ExternalKind { NotRoot, NoVersions, Unavailable, FromDependencyOf }

   Incompatibility 의 Cause 체인을 트리로 변환.

2. 트리 정제
   - 같은 External 이 여러 번 나오면 병합
   - 한쪽 가지만 있는 Derived 는 접기
   - 깊이가 너무 깊으면 중간 요약 노드로 압축
   ★ 목표: 사람이 3~5줄로 읽을 수 있는 형태

3. 골든 케이스 이식
   - Rust pubgrub 의 테스트 스위트를 JSON 픽스처로 변환
   - Dart pub 의 solver 테스트도 가능한 만큼
   - OfflineDependencyProvider 유사 구현(Map 기반)을 테스트 유틸로 제공
   포맷 예:
   {
     "name": "backtracking_to_correct_version",
     "packages": { "a": { "1.0.0": { "b": ">=1.0.0" } }, ... },
     "root": { "a": "*" },
     "expect": { "solution": { "a": "1.0.0", "b": "1.0.0" } }
   }
   또는 "expect": { "noSolution": true, "mentions": ["a", "b"] }

4. 트리 품질 테스트
   실패 케이스마다 "트리에 반드시 등장해야 하는 패키지 집합"을 명시하고 검증.
   무관한 패키지가 등장하면 실패로 처리 (최소 충돌 집합이 아니라는 뜻).

5. 문서
   core/pubgrub-kt/README.md 에 알고리즘 개요와 우리 구현의 한계를 적어라.
   나중에 이 코드를 다시 볼 때가 온다.

완료 판정: 이식한 골든 케이스 전부 통과 + 트리 품질 테스트 통과
```

---

## 하지 말 것
- 도메인 지식을 `pubgrub-kt`에 넣기 (마크·플러그인 언급 금지 — 순수 알고리즘)
- 4단계를 한 세션에 몰아서
- DerivationTree 품질을 "나중에" 미루기 ← 이게 진짜 목표다
- 이해 안 되는 부분을 대충 우회
