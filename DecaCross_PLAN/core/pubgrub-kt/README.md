# pubgrub-kt — PubGrub 의존성 해결 알고리즘 Kotlin 포팅

순수 알고리즘. 도메인 지식 0, 플랫폼 의존성 0 (KMP `commonMain`, 현재 타겟 `jvm`).
공개 API 는 `docs/03_구현명세_v2.md` §4 그대로다 (`VersionSet`, `DependencyProvider`, `resolve`, `SolverResult`, `DerivationTree`).
참고 구현: [pubgrub-rs](https://github.com/pubgrub-rs/pubgrub) (`internal/core.rs`, `partial_solution.rs`, `incompatibility.rs`)
와 Dart `pub` (`version_solver.dart`). 내부 구조는 pubgrub-rs 를 따르고, 유도 트리 정제는 Dart 의 결과 형태를 목표로 한다.

```
./gradlew :core:pubgrub-kt:build        # 컴파일 + ktlint + 테스트 (골든 케이스 포함)
```

## 파일

| 파일 | 역할 |
|---|---|
| `VersionSet.kt` | 버전 집합 대수 인터페이스 + `empty/full/singleton` 팩토리, `subsetOf/isDisjoint/isFull` 확장 |
| `Range.kt` | 정규형(정렬·disjoint·병합) 구간 목록으로 구현한 기본 `VersionSet`. `Bound`(Unbounded/Included/Excluded), `Interval` |
| `Term.kt` | 항. `positive` 극성 + 집합. 부정 항은 "선택 안 됨"을 허용한다 |
| `Incompatibility.kt` | "동시에 참일 수 없는 항들" + `Cause`(NotRoot/NoVersions/Unavailable/Dependency/DerivedFrom). `priorCause`, `mergeDependents` |
| `PartialSolution.kt` | 결정·유도 스택, 결정 레벨, `relation`, `backtrack`, 만족자 탐색(`satisfierSearch`) |
| `SolverState.kt` | incompatibility 색인, 단위 전파, 충돌 해결, 반복 상한(`StepBudget`) |
| `Solver.kt` | `resolve()` 메인 루프 (결정 만들기), `SolverResult`, `MAX_SOLVER_STEPS` |
| `DerivationTreeBuilder.kt` | 종료 incompatibility → `DerivationTree` 변환 + 정제 |
| `DerivationTreeOps.kt` | `packages()`, `externals()`, `derivedCount()`, `render()`, `ExternalKind.describe()` |
| `MapDependencyProvider.kt` | Map 기반 제공자 (테스트·오프라인용, pubgrub-rs `OfflineDependencyProvider` 에 해당) |

## 알고리즘 개요

PubGrub 은 SAT 의 CDCL 을 버전 해결에 특화한 것이다. 지식은 전부 **incompatibility** — "이 항들이 동시에 참일 수 없다" — 로 표현한다.

- 항 `Term(positive, set)`: 긍정 `p ∈ set` 은 "p 가 선택되어 있고 버전이 set 안". 부정 `¬(p ∈ set)` 은 "p 가 선택되지 않았거나
  버전이 set 밖". 부정 항이 미선택을 허용하므로 `¬(p ∈ r) ≠ p ∈ ¬r` 이고, 부분집합·교집합 판정은 이 의미론을 따른다
  (pubgrub-rs `term.rs` 와 동일).
- 의존성 `p@v → q ∈ d` 는 `{p: +{v}, q: −d}`, "q 에 d 안의 버전이 없다"는 `{q: +d}`, "루트는 반드시 선택"은 `{root: −{v}}`.

`resolve()` 루프 한 바퀴:

1. **단위 전파** (`SolverState.unitPropagation`): 바뀐 패키지를 언급하는 incompatibility 를 최신 순으로 훑어
   - 전부 만족(Satisfied) → 충돌 → 충돌 해결로
   - 하나만 미정(AlmostSatisfied) → 그 항의 부정을 유도로 추가
   - 어떤 항이 모순(Contradicted) → 해당 결정 레벨로 백트랙하기 전까지 재검사하지 않음
2. **충돌 해결** (`conflictResolution`): 충돌한 incompatibility 의 **만족자**(각 항을 만족시키기 시작한 가장 늦은 할당)와
   **이전 만족자**(만족자 항을 맨 앞에 둔 셈 치고 다시 찾은 것)를 구한다. 같은 결정 레벨이면 만족자의 원인과 결합한
   resolvent(`priorCause`)로 바꿔 계속 올라가고, 다른 레벨이면 이전 만족자 레벨로 **백점프**한 뒤 학습한 incompatibility 를
   등록한다. 루트 항 하나뿐이거나 항이 없는 incompatibility 에 닿으면 해가 없다.
3. **결정 만들기**: 긍정 유도 항이 있지만 아직 결정되지 않은 패키지 중 `prioritize` 가 가장 큰 것(동률은 먼저 등장한 것)을 골라
   `chooseVersion`. 버전이 없으면 `NoVersions`, `Dependencies.Unavailable` 이면 `Unavailable` incompatibility 를 추가하고
   다음 바퀴로. 있으면 의존성을 incompatibility 로 등록하되, 방금 만든 것 중 이 결정으로 곧바로 충돌하는 게 있으면 결정을 보류한다
   (단위 전파가 그 버전을 제외한다 — "결정 중 충돌 회피"). 같은 (패키지, 버전)을 다시 결정할 때는 의존성을 중복 등록하지 않는다.
4. 후보가 없으면 결정들이 해다.

같은 (pkg, dep, depSet) 의존성 incompatibility 는 `mergeDependents` 로 버전 범위를 합쳐 하나로 유지한다
(`foo 1.0.0 | 1.1.0 | 1.2.0 은 bar ^1 에 의존`). 유도 트리의 문장 수를 줄이는 pubgrub-rs 의 기법이다.

## DerivationTree — 만들기와 정제

해가 없으면 종료 incompatibility 의 `Cause` 사슬이 곧 유도 DAG 다. `DerivationTreeBuilder` 는 id 오름차순(원인이 먼저)으로
처리하므로 재귀 깊이 문제가 없고, 공유 노드는 한 번만 만든다. 정제는 극성 정보가 살아 있는 **내부 DAG 위에서** 한다:

1. **구멍(NoVersions) 흡수.** `Range` 는 버전의 이산성을 모른다. `foo 1.0.0` 을 제외한 `^1.0.0` 의 나머지 `>1.0.0 <2.0.0` 은
   집합으로는 비어 있지 않아서 솔버가 거기서 버전을 찾다 실패하고 `NoVersions(foo, >1.0.0 <2.0.0)` 를 학습한다 (pubgrub-rs 도
   같은 현상: "no version of foo in >1.0.0, <2.0.0"). 이런 구멍이 `foo 1.0.0 은 bar 에 의존` 같은 긍정 진술과 결합되면,
   그 긍정 항이 유래한 External 까지 범위를 넓혀 넣고(`foo ^1.0.0 은 bar 에 의존`) 조상 노드는 전부 `priorCause` 로 다시 계산한다.
   결과의 꼭대기가 여전히 종료 조건이면 채택, 아니면 그 시도만 되돌린다. Dart pub 이 처음부터 넓은 범위의 incompatibility 를
   만드는 것과 같은 결과를 사후에 얻는다. 루트에 대한 부정 진술(`root 는 foo ^1 에 의존`)과만 결합된 NoVersions 는 흡수하지 않는다 —
   "foo 에 맞는 버전이 없다"는 그 자체로 보여줘야 할 사실이기 때문이다.
2. **군더더기 유도 접기.** 결과 항이 한쪽 원인의 항과 같은 Derived 는 그 원인으로 대체.
3. **External 병합.** 같은 (pkg, dep, depSet) 의존성 / 같은 pkg 의 NoVersions / 같은 (pkg, 사유) Unavailable 은 범위를 합집합으로.
   진술을 넓히기만 하므로 여전히 참이다.
4. **공유·중복 제거.** 같은 내용의 External 은 같은 객체. 나머지는 data class 동일성.

문서의 "Linear error reporting" 예제는 External 4개(root→foo, root→baz, foo→bar, bar→baz) + Derived 3개로 나온다.

`DerivationTree.Derived.terms` 는 명세대로 `Map<P, VersionSet<V>>` 이며 값은 **각 항이 허용하는 집합**이다
(부정 항은 여집합). 극성은 사라지므로, 문장 생성은 `externals()` 로 External 만 모아서 한다 (명세 §5.1 ①).
`render()` 는 디버깅용 들여쓰기 출력이다.

## 한계·주의

- `Range` 는 이산성을 모른다 → 위의 구멍 현상. 흡수가 실패하는 모양(부정 항과의 교집합 경로)에서는 `NoVersions(p, >a <b)` 가
  트리에 남을 수 있다. 제공자가 버전 목록을 주면 `Range` 를 그 목록 기준으로 단순화(pubgrub-rs `simplify`)할 수 있지만,
  명세 §4.1 의 `DependencyProvider` 에는 그 메서드가 없어 넣지 않았다.
- `Range` 는 다른 `VersionSet` 구현과 섞어 쓸 수 없다 (`IllegalArgumentException`). 솔버는 `VersionSet` 인터페이스로만
  동작하지만 구조적 `equals` 를 전제한다.
- `Term` 은 패키지를 담지 않는다 (prompts/P1 의 `Term<P, V>(pkg, …)` 와 다름). pubgrub-rs 처럼 `Incompatibility.terms`
  가 `Map<P, Term<V>>` 라서 패키지를 항에 중복 저장할 이유가 없었다.
- `Cause.DerivedFrom` 는 결합 축 패키지 `pkg` 를 함께 가진다 (트리 정제에서 resolvent 를 다시 계산하기 위해).
- 자기 자신에 대한 의존 `p@v → p ∈ d` 는 두 항을 교집합해 `{p: +({v} ∖ d)}` 로 만든다 (Dart 방식). 공집합이면 무시.
- 제공자 계약 위반(`chooseVersion` 이 범위 밖 버전을 고름)과 반복 상한 초과(`MAX_SOLVER_STEPS` = 1,000,000)는
  `IllegalStateException` 이다. 알고리즘상 종료가 보장되므로 상한 초과는 포팅 버그다.
- `has_ever_backtracked` 최적화(pubgrub-rs 가 첫 백트랙 전엔 충돌 검사 없이 결정하는 것)는 넣지 않았다. 항상 검사한다.
- 우선순위 캐시가 없다. 결정마다 미결정 패키지 전부에 `prioritize` 를 부른다 (100 패키지 × 20 버전 그래프 약 10ms 라 충분).
- 깊이 압축("중간 요약 노드")은 명세 트리에 그런 노드 타입이 없어 하지 않았다.

## 테스트

| 테스트 | 내용 |
|---|---|
| `RangeTest` | 팩토리·병합·여집합·표기 + 속성 테스트 20,000회 (∩¬=∅, ∪¬=full, 결합·분배·교환·멱등, ¬¬a=a, contains 일치, 정규형 유지) |
| `TermTest` | 부재 의미론의 subsetOf/intersect/union/relationWith |
| `PartialSolutionTest` | relation, backtrack 복원, 우선순위 선택, 만족자 탐색(백점프 레벨 / 같은 레벨 → 종료까지) |
| `SolverTest` | 문서 예제 4개, 백트래킹, 순환, 자기 의존, 해 없음 종료, Unavailable, 계약 위반, 반복 상한 |
| `DerivationTreeTest` | 선형 예제의 간결성, 의존성 병합, 무관 패키지 배제, 공유 노드, describe |
| `RandomGraphTest` | 시드 고정 무작위 그래프 1,500개를 완전 탐색과 대조(해 존재 여부 일치, 해의 유효성, 트리의 패키지 집합) + 30 패키지 그래프 300개 |
| `PerformanceTest` | 100 패키지 × 20 버전 < 2초 (실측 약 11ms, JIT 워밍업 후) |
| `golden/GoldenCaseTest` (jvmTest) | `resources/golden` 의 JSON 20개. 테스트 전용 `SemVer`, 범위 문자열 파서, 최소 JSON 파서 포함 |

### 골든 케이스 (`src/jvmTest/resources/golden`)

| 파일 | 출처 | 기대 |
|---|---|---|
| 01 no_conflict | PubGrub 문서 / pubgrub-rs examples.rs | 해 |
| 02 avoiding_conflict_during_decision_making | PubGrub 문서 / pubgrub-rs | 해 |
| 03 conflict_resolution | PubGrub 문서 / pubgrub-rs | 해 |
| 04 conflict_with_partial_satisfier | PubGrub 문서 / pubgrub-rs | 해 |
| 05 double_choices | pubgrub-rs examples.rs | 해 |
| 06 confusing_with_lots_of_holes | pubgrub-rs examples.rs | 해 없음, baz 미언급 |
| 07 linear_error_reporting | PubGrub 문서 | 해 없음 {root, foo, bar, baz} |
| 08 branching_error_reporting | PubGrub 문서 | 해 없음 {root, foo, a, b, x, y} |
| 09 backtracking_to_correct_version | prompts/P1 예시 | 해 |
| 10 circular_dependency | Dart pub 재구성 | 해 |
| 11 missing_package | 자체 | 해 없음 {root, foo} |
| 12 unavailable_only_version | 자체 | 해 없음 {root, a} |
| 13 self_dependency | 자체 | 해 |
| 14 diamond | Dart pub 재구성 | 해 |
| 15 rolls_back_leaf_versions_first | Dart pub 재구성 | 해 |
| 16 unrelated_package_not_mentioned | 자체 | 해 없음 {root, a, c} |
| 17 incompatible_root_constraints | 자체 | 해 없음 {root, a, b} |
| 18 deep_chain_backjump | 자체 | 해 |
| 19 merged_dependency_ranges | 자체 | 해 없음 {root, foo, bar} |
| 20 simple_transitive | Dart pub 재구성 | 해 |

골든 형식: `packages[pkg][version] = { dep: range }`, `unavailable[pkg][version] = 사유`, `root = { dep: range }`
(루트는 `root@1.0.0`), `expect.solution`(root 제외) 또는 `expect.noSolution + mentions`(트리에 등장하는 패키지 집합과 정확히 일치).
범위 문자열: `*`, `1.0.0`, `>=1.0.0 <2.0.0`, `^1.2.0`, `~1.2.0`, `>=1.0.0`, `<2.0.0`, ` || `.
"Dart pub 재구성"은 원본 픽스처를 그대로 옮긴 게 아니라 같은 이름의 시나리오를 손으로 다시 만든 것이다.
