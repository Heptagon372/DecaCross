# 스택 결정 — Rust/Tauri → Kotlin/JVM 전환

> 2026-09-16 / 결론: **Kotlin이 이 프로젝트엔 오히려 더 맞다. 단, 비용 두 개를 정직하게 지불해야 한다.**

---

## 1. 핵심 근거 — 우리 도메인이 애초에 JVM이다

마인크래프트 서버는 JVM 위에서 돈다. 플러그인은 `.jar`다. 월드는 NBT다.
Rust로 만들면 **이 생태계 밖에서 유리창 너머로 들여다보는 셈**이고, Kotlin으로 만들면 **안에 들어가서 직접 만진다.**

| 작업 | Rust/Tauri | Kotlin/JVM |
|---|---|---|
| jar 메타데이터(`plugin.yml`) 파싱 | zip 열고 YAML 파싱 | `java.util.jar.JarFile` — 1급 시민 |
| 바이트코드 major version 판독 | 헤더 바이트 수동 파싱 | ASM |
| **플러그인의 외부 클래스 참조 해석** | 문자열 스캔 (부정확) | **ASM으로 정확히 해석** ★ |
| NBT (플레이어 인벤토리·통계) | 서드파티 크레이트 | 성숙한 JVM 라이브러리 |
| Anvil 월드 포맷 읽기 | 직접 구현 | 기존 라이브러리 |
| 서버 프로세스 제어 | `tokio::process` | `ProcessBuilder` (더 단순) |
| **실행 중 서버 내부 관측** | **불가능** | **JMX / Attach API** ★★ |
| 웹·데스크탑 로직 공유 | Rust + wasm-bindgen (삽질) | KMP (jvm + js) — 설계 목적 그 자체 |

★★ 표시가 핵심이다. **Rust 설계에서는 애초에 불가능했던 기능이 열린다.**

---

## 2. Kotlin으로 바꿔서 새로 가능해지는 것 (K-1 ~ K-5)

### K-1. 정적 플러그인 검증 — 부트스트랩 문제를 근본적으로 바꾼다 ★★★

Paper API jar를 classpath에 두고, ASM으로 플러그인이 참조하는 **모든 외부 클래스/메서드를 해석**한다.
→ 서버를 띄우지 않고도 `NoClassDefFoundError` / `NoSuchMethodError`를 **실행 전에 예측**한다.

| | Rust 설계 (VERIFIER CI) | Kotlin 설계 |
|---|---|---|
| 조합 1개 검증 | 실제 기동 **~40초** | 정적 분석 **~0.5초** |
| 1,800 조합 야간 배치 | 20시간 | **15분** |
| 실행 가능 환경 | 도커 + 메모리 | JVM 하나 |

기획서 v1.1의 최대 허점이었던 **H2(호환성 데이터 닭-달걀)**가 여기서 거의 해소된다.
정적 분석으로 전체를 훑고, 실제 기동 검증은 애매한 것만 골라서 한다.

> 이거 하나만으로도 Kotlin 전환이 정당화된다.

### K-2. shaded 라이브러리 충돌 사전 탐지 — 아무도 안 만든 도구

두 플러그인이 같은 라이브러리(예: `com.google.gson`)를 **relocate 없이 shade**하면 버전이 다를 때 런타임에 터진다. 마크 서버 운영에서 악명 높게 흔한데 진단 도구가 없다.

jar 안의 클래스 경로 목록을 비교해 중복을 찾으면 끝난다. **JVM에서는 사실상 공짜**다.

### K-3. 실행 중 서버 직접 관측 (JMX)

런처가 서버 JVM에 붙어서 힙·스레드·GC를 실시간 조회한다. spark 플러그인 설치 없이 프로파일링.
→ F-23(성능 진단)이 플러그인 의존 없이 동작한다.

### K-4. NBT / 월드 직접 읽기

Server Engine의 간판 기능(플레이어 인벤토리 조회, 월드 인스펙터)을 서드파티 없이 구현.
서버가 꺼져 있어도 저장된 데이터를 읽는다.

### K-5. Bukkit 의존성 시맨틱을 정확히 재현

`depend` / `softdepend` / `loadbefore` / `libraries`의 실제 로딩 순서 규칙을 Bukkit 구현 그대로 모델링할 수 있다. 추측이 아니라 실제 동작과 일치.

---

## 3. 지불해야 하는 비용 (정직하게)

### C-1. PubGrub을 직접 포팅해야 한다 ★ 최대 비용

Kotlin/JVM에 쓸만한 PubGrub 구현이 **없다**. (조사 확인 — Rust `pubgrub`, Dart `pub`, Python `uv` 뿐)

| | |
|---|---|
| 규모 | 핵심 알고리즘 약 1,500줄 |
| 기간 | **2~3주** |
| 난이도 | 중상 — 알고리즘 자체는 명확하지만 derivation tree 처리가 까다롭다 |

**완화책:**
- 순수 알고리즘이라 **I/O도 플랫폼 의존성도 없다** → KMP `common`에 두면 jvm/js 양쪽에서 쓴다
- `pubgrub-rs`의 테스트 스위트와 Dart `pub`의 테스트를 **골든 케이스로 그대로 이식**할 수 있다
- 참고 구현이 셋(Rust/Dart/Python)이나 있어서 막히면 대조 가능

**→ 로드맵 변경: PubGrub 포팅을 1~2주차에 병행 착수한다.** 7주차에 시작하면 늦는다.

### C-2. 런처 메모리·배포 크기

| | Tauri 안 | Compose Desktop 안 |
|---|---|---|
| 배포 크기 | ~10MB | 40~80MB (jlink 적용 시) |
| 런타임 메모리 | ~80MB | 200~400MB (무대응 시) |
| 콜드 스타트 | 즉시 | 1~3초 |

**배포 크기는 우리에겐 거의 무해하다.** 어차피 Java 런타임을 내려받아 설치하는 앱이다. "Java를 설치해주는 프로그램이 Java로 만들어진 것"은 자연스럽다.

**메모리는 진짜 문제다.** 8GB PC에서 `서버 4G + 마크 클라이언트 + 런처 400MB`는 빡빡하다. 설계로 막는다:

**① 데몬 / UI 분리 (가장 중요한 구조 변경)**
```
decacross-daemon   헤드리스. 서버 감독 + Ktor 브리지 + 설치 파이프라인.  -Xmx192m
       ▲ REST + WebSocket (127.0.0.1)
decacross-ui       Compose Desktop. 창을 닫으면 종료. 서버는 계속 돈다.  -Xmx384m
```
- UI를 닫아도 서버가 산다 → **Server Engine에 없는 기능이기도 하다**
- 평상시(UI 닫힘) 메모리 점유 192MB
- 웹 브리지가 이미 데몬에 있으므로 재사용
- **부수 효과: F-19(모바일 원격 제어)와 F-32(팀 협업)가 같은 API를 공짜로 쓴다.** Tauri 설계에서는 따로 만들어야 했던 것

**② RAM 권장값 계산에서 런처 사용량을 차감한다** ← Kotlin 전환으로 생긴 새 요구사항
`권장 서버 RAM = min(시스템 × 0.5, 상한) − 데몬/UI 실사용량 − 클라이언트 예약분`

**③ jlink로 커스텀 런타임** — 필요한 모듈만 담아 60MB 이하

---

## 4. 웹 전략 — Compose Web으로 가지 마라

세 가지 선택지가 있는데, **하나는 명백히 함정이다.**

| 안 | SEO | 평가 |
|---|---|---|
| A. **Next.js 16 유지 + 서버측 JVM 엔진** | ✅ | **채택** |
| B. Ktor + Kotlin/JS 풀스택 | △ | 생태계 작음, 이점 없음 |
| C. Compose Multiplatform Web (Wasm) | ❌ | **함정** |

**C가 왜 함정인가:** 기획서 §2.2에서 Server Engine의 빈틈 **G3**을 "웹사이트가 Flutter Web SPA라 검색 유입이 0"이라고 지적했다. Compose Web으로 가면 **정확히 같은 실수를 반복**한다. 경쟁자의 패인을 그대로 복사하는 셈.

**엔진 공유는 이렇게 한다:**
- **Phase 1: 서버측 JVM 엔진만.** 웹 위저드는 `POST /api/resolve`를 호출한다.
  - 불변식 D2(판정 로직 단일화)는 **엔진이 하나뿐이라 자동으로 지켜진다** — Rust+wasm 때 필요했던 parity 테스트(I5)가 아예 필요 없어진다
  - 런처는 같은 JVM 엔진을 **직접 임베드**하므로 오프라인 동작도 해결
- **Phase 2: KMP `js` 타겟 추가** — 타이핑 중 즉시 반응이 필요해지면 그때. npm 패키지로 배포해 Next.js가 소비.

> Rust 설계에서 wasm이 필수였던 이유는 "런처가 오프라인에서 판정해야 하는데 Rust 엔진을 브라우저에서도 써야 해서"였다. Kotlin에서는 런처가 JVM 엔진을 그냥 품으므로 **그 압박이 사라진다.** 웹 wasm은 순수한 UX 최적화로 내려간다.

---

## 5. 스택 확정표

| 영역 | Rust 안 | **Kotlin 안 (확정)** | 버전 |
|---|---|---|---|
| 언어 | Rust edition 2024 | **Kotlin** | 2.4.0 (2026-07-14) |
| 코어 엔진 | Rust crate + wasm | **KMP** (jvm 우선, js는 Phase 2) | — |
| 의존성 해결 | `pubgrub` 0.4 | **`pubgrub-kt` 직접 포팅** ★ | — |
| 런처 UI | Tauri 2.11 | **Compose Multiplatform Desktop** | 1.12.0 (2026-08) |
| 런처 코어 | Rust | **Kotlin/JVM 데몬** | — |
| jar/바이트코드 분석 | zip + 수동 파싱 | **ASM** ★ | — |
| 로컬 DB | rusqlite | **SQLDelight** | — |
| 서버 DB | Supabase | **Supabase + Exposed** | Exposed 1.0 |
| HTTP 서버(브리지) | axum | **Ktor Server (embedded)** | 3.4.x |
| HTTP 클라이언트 | reqwest | **Ktor Client** | 3.4.x |
| 직렬화 | serde | **kotlinx.serialization** | — |
| 수집기 | Python | **Kotlin/JVM** (jar 분석이 JVM에서 유리 → 통합) | — |
| 검증 CI | Python + Docker | **Kotlin + 정적분석 우선**, Testcontainers 보조 | — |
| 웹 | Next.js 16 | **Next.js 16 (유지)** | 16.x LTS |
| 빌드 | cargo + pnpm | **Gradle (KTS) + pnpm** | Gradle 9.x |
| 패키징 | Tauri bundler | **jpackage + jlink** | — |

> Gradle 호환 범위: Kotlin 2.4.0은 Gradle 7.6.3 ~ 9.5.0.

---

## 6. 변경 요약 — 무엇이 좋아지고 무엇이 나빠지는가

**좋아짐**
- 정적 검증으로 VERIFIER가 **80배 빨라짐** → 부트스트랩 문제 해소 (H2)
- Rust로는 불가능했던 기능 4개(K-2~K-5)가 열림
- wasm parity 문제(I5)가 아예 사라짐 — 엔진이 하나
- 데몬/UI 분리로 F-19·F-32가 공짜
- 수집기를 Python에서 통합 → 언어 하나 줄어듦 (1인 개발에 유의미)
- jar 다루는 코드 전반이 훨씬 단순해짐

**나빠짐**
- PubGrub 포팅 **2~3주** 추가 (C-1)
- 런처 메모리 200~400MB → 데몬 분리로 완화 (C-2)
- 배포 크기 10MB → 60MB (우리 도메인에선 거의 무해)
- 콜드 스타트 1~3초 → AppCDS로 완화

**순 판정: 유리하다.** K-1 하나가 C-1을 상쇄하고도 남는다.

---

## 7. 로드맵 영향

| 주 | Rust 계획 | **Kotlin 계획** |
|---|---|---|
| 1 | 엔진 골격 | 엔진 골격 + **PubGrub 포팅 착수(병행)** |
| 2 | 수집기 | 수집기(Kotlin) + **PubGrub 포팅 계속** |
| 3 | 설치 파이프라인 ★데모 | 설치 파이프라인 ★데모 (변동 없음) |
| 4 | Java 런타임 + CAS | 동일 + **데몬/UI 분리 구조 확정** |
| 5 | Tauri 셸 | **Compose UI + 데몬 프로토콜** |
| 6 | logparse | 동일 |
| 7 | pubgrub 통합 | **pubgrub-kt 통합** (포팅은 이미 끝나 있어야 함) |
| 8 | 웹 위저드 | 동일 (wasm 없이 서버 엔진 호출로 단순해짐) |
| 병행 | VERIFIER(도커 기동) | **VERIFIER(정적분석 우선)** — 훨씬 빠르고 싸다 |

**총 기간은 그대로 8주.** PubGrub 포팅 3주가 추가되지만, 정적 검증 전환으로 VERIFIER 작업이 줄고 wasm 삽질이 사라져 상쇄된다.
