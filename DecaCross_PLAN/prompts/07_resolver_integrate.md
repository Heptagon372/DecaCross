# 07. 솔버 통합 + 플러그인 설치 — ★ 핵심 자산

**선행조건:** 06 완료, **P1(pubgrub-kt 포팅)이 끝나 있을 것**
**목표:** 플러그인을 고르면 의존성이 자동으로 붙고, 안 되면 왜 안 되는지 한국어로 말한다
**완료 판정:**
```
플러그인 10개 선택 → 의존성 자동 추가 → 설치 → 기동 성공
충돌 케이스(26.3 + 구버전 ProtocolLib) → 한국어 설명 + fix 2개 이상
./gradlew :core:compat-engine:test --tests '*invariant_*'
```

> P1이 안 끝났으면 여기서 막힌다. 그래서 1주차에 시작하라고 한 것이다.

---

## 프롬프트

```
compat-engine 의 솔버를 pubgrub-kt 위에 올린다. 이게 프로젝트의 유일한 해자다.

먼저 읽어라:
- docs/02_설계서.md §3 전체 (특히 §3.3 도메인 매핑, §3.4 explain)
- docs/03_구현명세_v2.md §3 (공개 API), §3.1 (불변식), §5 (PubGrub 매핑), §5.1 (explain)
- core/pubgrub-kt 의 실제 구현 (P1 산출물)

1. resolve/Pkg.kt — 명세 §5 의 Pkg / Ver / ApiKind 그대로
   ★ 배타 제약(Oraxen ↔ ItemsAdder)은 Conflicts 가 아니라 Provides 로 표현한다.
     같은 Capability 를 제공하는 패키지가 여럿이면 PubGrub 이 하나만 고른다.
     Debian 의 Provides 관용구와 같은 기법.

2. resolve/DecaProvider.kt — DependencyProvider 구현
   getDependencies 에서 R1~R12 를 전부 "의존성"으로 번역 (명세 §5 표 그대로)
   - Core@build   → Mc 고정, Java 하한, Api 수준
   - Content@ver  → Api 구간, Java 하한/상한(ASM 실측), Mc 구간,
                    Require 의존, Provides capability
   chooseVersion 은 STABLE 먼저, 그다음 최신순.
   prioritize 는 후보 수가 적은 패키지를 먼저 (탐색 감소).

3. resolve/Prune.kt (2단계 사전 필터)
   로더 계열 불일치, MC 지원 범위 밖 후보 제거 → 솔버 입력 축소

4. resolve/PostCheck.kt (4단계)
   PubGrub 으로 표현 안 되는 것:
   - pack_format 구간 교집합 (PackDecl.isCompatibleWith)
   - 알려진 버그 블랙리스트
   - RAM 하한 추정 + hostOverheadMb 차감 (불변식 I5)
   - Folia + 미대응 플러그인 성능 경고

5. resolve/Score.kt (5단계)
   score = 충족률×0.45 + 검증등급×0.25 + 최신성×0.15
         + 코어안정성×0.10 + 인기도×0.05
   신호등 판정 (설계서 §3.6):
     🟢 정적검증 통과 + (CI통과 또는 distinct_installs≥20 && 성공률≥95%, 90일)
     🟡 제약 위반 없음, 실행 데이터 20건 미만
     🟠 성공률 70~95%
     🔴 제약 위반 또는 성공률<70% (표본 10건+)
   ★ 정적 검증 결과(static_verify_results)를 1차 근거로 쓴다. Kotlin 전환의 이득.

6. explain/ — ★ 제품의 차별점
   DerivationTree → Explanation

   ① 후위 순회, External 노드만 수집
   ② 같은 subject 병합 → 최소 충돌 집합 (전체 트리 노출 금지)
   ③ ko.kt 템플릿으로 한국어 문장
   ④ 충돌 축(mc/java/core/content)마다 Fix 후보 생성
   ⑤ ★ 각 Fix 를 가정하고 resolve() 를 실제로 재실행해서, 진짜 되는 것만 남긴다.
      "될 것 같다"를 제시하지 마라.
   ⑥ 부수효과 계산 → sideEffectsKo

   목표 출력:
     ❌ 26.3에서는 ProtocolLib을 쓸 수 없습니다.
        26.3은 Java 25로 실행되는데, ProtocolLib 5.1.0은 Java 21까지만 지원합니다.
        ① ProtocolLib 5.4.0으로 올리기 (권장)
        ② 마크 버전을 1.21.8로 낮추기 — 나머지 25개 전부 호환
        ③ ProtocolLib 제외 — 요구하는 2개도 함께 빠짐 (WorldGuard, EssentialsXSpawn)

7. 불변식 테스트 (명세 §3.1) — I1~I6 전부

8. 골든 케이스
   core/compat-engine/src/commonTest/resources/golden/ 에 알려진 조합 50건+.
   엔진 수정 시 회귀 0.

9. 설치 파이프라인 연결
   RESOLVE 단계가 이 resolve() 를 호출. autoAdded=true 항목은 UI 에서 구분 표시.

10. 플러그인 설치
    Modrinth/Hangar 에서 다운로드 → plugins/ 배치 → CAS 연동
    ★ redistributable=false 인 콘텐츠는 우리가 미러링하지 않고 원본 URL 로만 받는다.

계획을 먼저 보여주고 확인받은 뒤 시작해라.
```

---

## 하지 말 것
- `Conflicts`로 배타 표현 (Provides 트릭)
- 재해결 검증 없는 Fix 제시
- DerivationTree 원문을 UI에 노출
- `redistributable=false` 콘텐츠 미러링
