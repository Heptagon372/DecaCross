# P2. 정적 플러그인 검증 + VERIFIER — 병행 트랙 (2~8주) ★ Kotlin 전환의 이득

**선행조건:** 02 완료 (DB 스키마 + jvm-analysis 골격)
**목표:** 서버를 띄우지 않고 호환성을 판정한다 → 출시일에 🟢 데이터를 들고 시작
**완료 판정:**
```
8주차에 static_verify_results 5,000건+, 오탐률 < 5%
조합 1건 분석 < 500ms
```

> **이게 Kotlin 전환의 가장 큰 이득이다.**
> Rust 안에서는 조합당 실제 기동 40초 → 1,800 조합에 20시간이었다.
> ASM 정적 분석이면 조합당 0.5초 → **15분**이다. 80배.
> 기획서 v1.1의 최대 허점 H2(호환성 데이터 닭-달걀)가 여기서 해소된다.

---

## 프롬프트 (P2-a: 정적 검증 엔진)

```
ASM 기반 정적 플러그인 검증을 만든다. 이게 Kotlin 을 선택한 가장 큰 이유다.

먼저 읽어라:
- docs/00_스택결정_Kotlin.md §2 K-1, K-2 (무엇이 가능해지는지)
- docs/03_구현명세_v2.md §6 (jvm-analysis 명세)
- CLAUDE.md 불변식 16 (오탐 관리)

core/jvm-analysis/src/main/kotlin/kr/decacross/analysis/

1. StaticVerify.kt
   fun staticVerify(
       pluginJar: Path,
       coreApiJars: List<Path>,     // paper-api, bukkit, + 함께 설치될 다른 플러그인
       javaFeature: Int,
   ): StaticVerifyResult

   구현:
   ① ASM ClassReader 로 jar 안 모든 .class 순회
   ② ClassVisitor/MethodVisitor 로 참조하는 외부 타입·메서드 시그니처 수집
      - 필드 타입, 메서드 시그니처, 상속/구현, 어노테이션
      - INVOKEVIRTUAL/STATIC/INTERFACE/SPECIAL 의 owner + name + descriptor
   ③ classpath(코어 API + 동시 설치 플러그인 + JDK 모듈)에서 해석 시도
   ④ 미해결 참조 보고

   ★★ 오탐 관리가 관건이다. 이걸 못 하면 기능 자체가 무용지물이 된다.
      미해결 참조를 다음 기준으로 분류해라:
      - try/catch (NoClassDefFoundError | ClassNotFoundException) 안   → 경고
      - plugin.yml 의 softdepend 에 선언된 플러그인 소속               → 경고
      - Class.forName / 리플렉션 문자열 상수                            → 경고
      - 그 외                                                           → 에러
      경고는 신호등을 내리지 않는다. 에러만 🔴 으로 간다.

2. ShadeConflict.kt (K-2) — 아무도 안 만든 진단
   fun detectShadeConflicts(jars: List<Path>): List<ShadeConflict>
   - 각 jar 의 클래스 경로 집합을 비교해 중복 탐지
   - relocate 된 것(플러그인 자체 패키지 하위)은 제외
   - 심각도: 널리 쓰이는 공용 라이브러리(gson, guava, netty 등)가
     relocate 없이 중복되면 HIGH

3. Bytecode.kt
   fun requiredJavaFeature(jar: Path): Int?     // max(class major) - 44

4. 정확도 측정 테스트 ★ 이게 없으면 만든 의미가 없다
   - 알려진 호환 조합 50건 → 전부 ok 여야 한다 (거짓 양성 = 오탐)
   - 알려진 비호환 조합 30건 → 전부 에러를 잡아야 한다 (거짓 음성 = 미탐)
   - 오탐률 < 5% 를 만족할 때까지 분류 기준을 조정해라
   - 측정 결과를 README 에 기록해라

5. 성능
   조합 1건 < 500ms. jar 파싱 결과를 캐싱해서 같은 플러그인을
   여러 조합에서 재분석하지 않게 해라.

제약:
- core/jvm-analysis 는 JVM 전용 모듈이다 (KMP 아님)
- 분석 후 jar 는 삭제. 재배포하지 않는다
```

---

## 프롬프트 (P2-b: VERIFIER CI)

```
정적 검증을 CI 로 돌려서 호환성 데이터를 대량 생산한다.

선행: P2-a 완료

먼저 읽어라:
- docs/02_설계서.md §3.6 (부트스트랩 문제)
- docs/03_구현명세_v2.md §9 (0007_static_verify.sql)

1. infra/supabase/migrations/0007_static_verify.sql
   static_verify_results 테이블 (명세 §9)
   ★ analyzer_version 컬럼 필수 — 분석기를 고치면 재분석 대상이 된다

2. verifier/ (Kotlin/JVM)
   Plan.kt
     - Modrinth/Hangar 다운로드 상위 150개 플러그인
     - 최근 MC 12개 버전 × Paper 최신 stable
     - 이미 분석된 조합(같은 analyzer_version)은 건너뛴다
     - 우선순위: 인기 높은 것 / 최근 버전 / 데이터 없는 것

   RunStatic.kt
     - 조합마다 staticVerify 실행 → static_verify_results 에 기록
     - 코어 API jar 는 한 번 받아서 재사용

   RunLive.kt (보조)
     - 정적 분석이 애매한 조합(경고만 있는 것)만 실제 기동으로 확인
     - Testcontainers 또는 로컬 프로세스
     - server.properties: online-mode=false, max-players=1,
       level-type=flat, view-distance=4 (빠른 기동)
     - 90초 timeout, 로그에 "Done (" 있으면 ok
     - 결과는 compat_facts 에 source='ci'

3. .github/workflows/verify.yml
   - 야간 스케줄
   - 정적 분석은 매일 전량, 실기동은 애매한 것만
   ★ 검증용 jar 를 아티팩트로 남기지 마라 (라이선스)

4. 커버리지 대시보드
   web/app/internal/coverage — MC 버전 × 플러그인 히트맵.
   🟢/🟡/🔴 비율. 이 숫자가 출시 가능 여부의 판단 기준이다.

5. 신호등 연동
   compat-engine 의 Score.kt 가 static_verify_results 를 1차 근거로 읽는다.
   (07 단계와 연결)

목표:
  8주차까지 static_verify_results 5,000건+, 오탐률 < 5%,
  주요 플러그인 × 최근 버전 조합의 🟢 커버리지 60%
```

---

## 하지 말 것
- 오탐률 측정 없이 배포 (측정 안 하면 만든 의미가 없다)
- 미해결 참조를 전부 에러로 처리
- 검증용 jar를 아티팩트로 보관
- 정적 분석으로 될 걸 전부 실기동으로 돌리기
