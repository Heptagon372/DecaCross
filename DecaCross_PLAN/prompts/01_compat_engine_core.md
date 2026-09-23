# 01. compat-engine 코어 — 타입과 버전 대수

**선행조건:** 00 완료
**목표:** 데이터 계약 + MC 서수/범위/pack_format + DB 추상화
**완료 판정:**
```
./gradlew :app:cli:run --args="lookup 1.21.8"
→ mc=1.21.8 (ordinal=NNNN) javaMin=21 javaRec=21 rpFormat=.. dpFormat=..
./gradlew :core:compat-engine:test
```

---

## 프롬프트

```
compat-engine 의 코어를 구현한다. 이 프로젝트의 핵심 자산이니
타입 이름 하나까지 명세와 맞춰라.

먼저 읽어라:
- docs/03_구현명세_v2.md §2 (Kotlin 타입 계약) — 이 이름 그대로 쓴다
- docs/03_구현명세_v2.md §2.1 (McOrdinal 발급 규칙)
- docs/02_설계서.md §3.3 (왜 서수가 필요한지)
- CLAUDE.md 불변식 1~6

구현할 것 — core/compat-engine/src/commonMain/kotlin/kr/decacross/compat/

1. model/ — 명세 §2 의 타입 전부
   McOrdinal(@JvmInline value class), McVersion, PackFormat, PackDecl,
   CoreKey, LoaderFamily, Channel, CoreBuild, JavaSpec, Distribution,
   ImageType, Os, Arch, ContentKind, Source, Content, ContentVersion,
   Dep, DepKind, DepTarget, Capability

   - 전부 data class / sealed interface / enum. 상속 계층 만들지 마라
   - KDoc 으로 불변식 명시 (특히 McOrdinal, redistributable)

2. version/Ordinal.kt
   - nextOrdinal(maxExisting: McOrdinal): McOrdinal        // +10
   - seedOrdinals(releases: List<Pair<String, Instant>>): List<Pair<String, McOrdinal>>
     (releasedAt 오름차순, 1000 + i*10)
   - 재할당 함수를 만들지 마라. append-only 다.

3. version/McRange.kt
   McOrdinal 구간 집합: 합집합/교집합/여집합/포함검사.
   나중에 pubgrub-kt 의 VersionSet 으로 감쌀 거다. 지금은 독립 타입으로.

4. version/PackFormat.kt
   - PackFormat.parse("34") / ("88.0") / ("101.1") / 잘못된 입력
   - PackDecl.isCompatibleWith(target): 구간 교집합 판정
   - ★ 리소스팩 포맷과 데이터팩 포맷을 절대 섞지 마라 (별개 필드/컬럼)

5. db/CompatDb.kt — 명세 §3 의 interface 그대로
   조회 전용. 네트워크·파일 I/O 는 전부 이 뒤에 숨는다.

6. db/InMemoryCompatDb.kt — 테스트용 (JSON fixture 로드)
   app/daemon 쪽에 SqlDelightCompatDb 는 04 단계에서.

7. app:cli 에 lookup 서브커맨드

테스트 (반드시 포함):
- ordinal 발급이 단조 증가하고 재할당되지 않는지
- ★ "1.21.8" vs "26.3" 비교 — 문자열 비교면 뒤집히는 걸 명시적으로 테스트하고,
  McOrdinal 비교는 올바른지 확인
- PackFormat 파싱 전 케이스
- PackDecl.Supported 구간 교집합

제약:
- explicit API mode
- core/* 에 I/O 코드 금지
- 예외 대신 sealed interface 결과 타입
- pubgrub-kt 는 아직 건드리지 마라 (P1 트랙)
```

---

## 하지 말 것
- resolve() 구현 (07에서)
- pubgrub 연동 (P1 → 07)
- 실제 데이터 수집 (02에서)
