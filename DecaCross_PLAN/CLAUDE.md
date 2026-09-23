# CLAUDE.md — 데카크로스 (DecaCross) · Kotlin

## 이 프로젝트가 뭔가

웹에서 클릭 몇 번으로 **버전이 맞는** 마인크래프트 서버 한 벌을 조립하고, 사용자 PC에서 즉시 실행·개방까지 끝내는 통합 서버 구축 플랫폼.

**핵심 자산은 "버전 호환성 엔진" 하나다.** 나머지(다운로드·GUI·콘솔)는 전부 복제 가능하다. 엔진의 정확도와 "안 될 때 왜 안 되는지 말해주는 능력"이 제품의 전부다. 기능을 늘리고 싶어질 때마다 이 문장을 다시 읽어라.

**언어는 Kotlin이다.** 우리 도메인(마크 서버·플러그인 jar·NBT)이 JVM이라서, 다른 언어로는 불가능하거나 어려운 일들이 여기선 1급 시민이다. 그 이점을 적극적으로 써라 — 특히 ASM 기반 정적 검증.

## 먼저 읽을 문서

| 상황 | 읽을 것 |
|---|---|
| 왜 Kotlin인가, 무엇이 달라졌나 | `docs/00_스택결정_Kotlin.md` |
| 무엇을·왜 | `docs/01_기획서.md` |
| 구조·알고리즘 | `docs/02_설계서.md` |
| **정확한 타입·시그니처·스키마** | `docs/03_구현명세_v2.md` ← 코드 쓸 땐 이거 |
| 작업 단위 | `prompts/NN_*.md` (순서대로) |

> `docs/02_설계서.md`는 Rust 기준으로 쓰였다. **구조·알고리즘·보안 설계는 그대로 유효**하고, 언어·라이브러리 부분만 `docs/03_구현명세_v2.md`가 덮어쓴다. 충돌하면 03이 이긴다.

새 작업은 `docs/03_구현명세_v2.md`의 해당 섹션을 **먼저 읽고** 타입을 맞춰라. 명세와 다르게 구현하고 싶으면 코드를 먼저 쓰지 말고 **명세 수정을 제안**해라.

---

## 불변식 — 어기면 나중에 전면 재작업

| # | 규칙 | 이유 |
|---|---|---|
| **1** | MC 버전 비교는 **무조건 `McOrdinal`**. `String`·SemVer 비교 금지 | 2026년 Mojang이 `1.` 접두사를 폐지해 `"1.21.8"` vs `"26.3"` 문자열 비교가 뒤집힌다 |
| **2** | 발급된 `ordinal`은 **영원히 재할당 금지**. 신규는 `max+10` | 재할당하면 모든 범위 데이터가 무효 |
| **3** | `pack_format`은 **`numeric` + `PackFormat(major, minor)`**. `Int` 금지 | 1.21.9부터 `88.0` 형태 |
| **4** | **리소스팩 포맷 ≠ 데이터팩 포맷**. 별개 컬럼 | 같은 MC 버전에서 값이 다르다 (1.21: 34 vs 48) |
| **5** | `content.redistributable` 없이 콘텐츠 저장 금지. 기본값 `false` | 재배포 불가 파일을 미러링하면 법적 문제. 섞인 뒤엔 분리 불가 |
| **6** | `core/*` 모듈에 **파일·네트워크 I/O 금지**. 전부 인터페이스 주입 | `jvm-analysis`만 예외 |
| **7** | `Explanation.fixes`는 **항상 1개 이상** | 대안 없는 에러는 벽이다 |
| **8** | Fix는 **실제로 재해결(resolve)해서 되는 것만** 제시 | 안 되는 해결책 한 번이면 신뢰가 끝 |
| **9** | **번들 JRE로 마크 서버를 실행하지 마라**. 서버용 런타임은 항상 별도 | jlink 커스텀 런타임은 모듈이 빠져 있고, 런처 업데이트 때 바뀐다 |
| **10** | 시스템 `JAVA_HOME`/`PATH`/레지스트리 **수정 금지**. 절대경로 호출 | 사용자의 다른 Java 환경을 깨면 안 된다 |
| **11** | 생성된 `start.bat`은 **런처 없이도 실행 가능** | 락인 금지 = 신뢰 |
| **12** | 설치는 **스테이징 → 원자적 이동**. 반쯤 만든 서버를 남기지 마라 | |
| **13** | 서버 종료는 `save-all` → `stop` → 60초 → SIGTERM → 30초 → SIGKILL | 월드 손상 방지. 순서 생략 금지 |
| **14** | 로그는 **50ms 배칭** 후 WS 전송 | 라인마다 보내면 대량 로그에서 UI가 죽는다 |
| **15** | **브리지 라우트에서 UI 핸들러를 재사용하지 마라** | 웹에 서버 목록·경로가 새는 경로가 된다 |
| **16** | 정적 검증 미해결 참조를 **전부 에러로 처리하지 마라** | 리플렉션·`softdepend`는 경고. 오탐이 많으면 기능 자체가 무용지물 |
| **17** | 외부 API는 **식별 가능한 User-Agent + 연락처**. generic UA 금지 | PaperMC가 명시적으로 요구 |

---

## 모듈 소유권

| 경로 | 타겟 | 책임 |
|---|---|---|
| `core/pubgrub-kt/` | KMP | PubGrub 포팅. 순수 알고리즘, 도메인 지식 0 |
| `core/compat-engine/` | KMP | ★ 호환성 판정. I/O는 `CompatDb`로 주입 |
| `core/dcx/` | KMP | `.dcx` 파싱·검증. 스키마 JSON이 단일 진실 소스 |
| `core/logparse/` | KMP | 로그 → 이벤트/시그니처. 순수 함수 |
| `core/jvm-analysis/` | JVM | ★ ASM/jar/NBT/JMX. Kotlin 전환의 이득이 나오는 곳 |
| `app/daemon/` | JVM | 헤드리스. 설치·프로세스·런타임·Ktor API |
| `app/launcher-ui/` | Compose Desktop | UI만. 로직은 데몬에 |
| `app/cli/` | JVM | 3주차 데모 + 개발용 |
| `app/collector/` | JVM | 수집 배치. 분석한 jar는 **즉시 폐기** |
| `verifier/` | JVM | 정적 검증 우선, 실기동은 보조 |
| `web/` | Next.js 16 | Gradle 밖. pnpm |
| `infra/supabase/migrations/` | SQL | append-only. 기존 파일 수정 금지 |

---

## 코딩 규칙

**Kotlin**
- 2.4.x, JVM target 25. explicit API mode 켠다 (`core/*` 필수)
- 도메인 타입은 `data class` + `sealed interface`. 상속 계층 만들지 마라
- 단일 값 래퍼는 `@JvmInline value class` (`McOrdinal` 등)
- `!!` 금지. `lateinit`은 UI 계층에서만
- 예외 대신 `sealed interface` 결과 타입 (`ResolveOutcome` 참고). 라이브러리 모듈에서 throw 최소화
- 코루틴: `suspend` + `Flow`. `GlobalScope` 금지, 구조적 동시성
- 공개 API는 KDoc 필수. 불변식은 `# 불변식` 절로 명시
- **명세 §2/§3/§4에 있는 타입은 그 이름 그대로 쓴다**

**의존성**
- 전부 `gradle/libs.versions.toml` version catalog. build 파일에 버전 문자열 금지
- 새 라이브러리를 추가하기 전에 "JVM 표준 라이브러리로 되나?"를 먼저 확인

**TypeScript (web)**
- `strict: true`, `any` 금지
- `.dcx` 타입은 JSON Schema에서 생성한 것만

**공통**
- 주석은 한국어, 식별자는 영어
- 수집한 외부 데이터를 코드에 하드코딩하지 마라 (버전 매핑·pack_format·에러 정규식 전부 DB)

---

## 테스트

```bash
./gradlew build
./gradlew :core:pubgrub-kt:test                              # 포팅 골든 케이스
./gradlew :core:compat-engine:test --tests '*invariant_*'    # I1~I6
./gradlew :core:logparse:test --tests '*corpus*'
./gradlew :core:jvm-analysis:test                            # 정적 검증 오탐률
./gradlew :app:daemon:test --tests '*bridgeSecurity*'
pnpm -C web test
```

**엔진 변경 시 골든 케이스 회귀가 0이어야 한다.** 깨졌는데 "개선돼서"라고 판단되면, 골든 파일을 고치기 전에 **왜 바뀌었는지 먼저 설명**해라.

---

## 작업 방식

- **큰 작업은 계획부터.** `prompts/` 한 파일이 대략 한 세션이다. 그보다 크면 쪼개자고 제안해라
- **명세와 충돌하면 멈춰라.** 우회하지 말고 무엇이 왜 안 되는지 말하고 명세 수정을 제안해라
- **모르는 API는 추측하지 마라.** Compose Multiplatform 1.12, Ktor 3.4, Kotlin 2.4는 API 변경 이력이 있다. 공식 문서를 먼저 확인해라
- **버전을 하드코딩하지 마라.** 명세의 버전 표는 major/minor 기준, patch는 catalog에 기록

## 커밋

```
<scope>: <한 줄 요약>

scope = pubgrub | engine | dcx | logparse | analysis | daemon | ui | cli
      | collector | verifier | web | infra | docs
```
한 커밋에 한 가지 일만. 포맷팅과 로직 변경을 섞지 마라.

---

## 지금 어디까지 왔나

Phase 1 MVP 진행 중. 스코프는 **Paper 계열 단일**, P0 기능 12개. Fabric/Forge/모드팩/마켓은 이번 Phase에서 **하지 않는다.** 좋은 아이디어가 떠오르면 구현하지 말고 `docs/01_기획서.md`의 해당 Phase에 적어둬라.

**`pubgrub-kt` 포팅은 1주차부터 병행 착수한다.** 7주차에 시작하면 늦는다. 이게 Kotlin 전환의 최대 비용이고, 유일한 일정 리스크다.

**주차별 완료 판정은 `docs/03_구현명세_v2.md` §12에 있다. 그게 되기 전엔 다음 주로 넘어가지 않는다.**
