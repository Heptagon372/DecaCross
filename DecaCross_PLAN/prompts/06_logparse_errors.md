# 06. 로그 파서 + 에러 택소노미

**선행조건:** 05 완료
**목표:** 로그를 "현상"이 아니라 "원인과 해결책"으로 바꾼다
**완료 판정:**
```
의도적 오류 10종 주입 → 전부 진단 카드 표시 + 각각 fix 1개 이상
./gradlew :core:logparse:test --tests '*corpus*'   (오탐률 < 2%)
```

---

## 프롬프트

```
logparse 모듈과 에러 진단을 구현한다.
이 단계가 제품 체감 품질의 8할이다. Server Engine 은 로그를 예쁘게 보여주기만
하지만, 우리는 에러를 해결책으로 바꾼다.

먼저 읽어라:
- docs/02_설계서.md §3.7 (에러 택소노미), §5.4 (로그 파서)
- docs/03_구현명세_v2.md §10 (에러 시그니처 18종 — 정규식 포함)
- docs/01_기획서.md §4.6 (이벤트 표)

1. core/logparse/ (KMP common)
   Format.kt — 버전별 포맷 자동 감지
     1.7 이하  [12:03:41 INFO]: msg
     1.8+      [12:03:41] [Server thread/INFO]: msg
     Paper     [12:03:41] [Server thread/INFO]: [PluginName] msg
     멀티라인 병합: "\tat " / "Caused by:" 는 직전 라인에 이어붙임

   Event.kt
     sealed interface LogEvent {
       Join, Leave, Chat, Death, Command, Startup(tookMs),
       Warn, Error, PluginLoad(name, version, ok), Lag(ms), Raw
     }
   ★ 사망 메시지를 하드코딩하지 마라. client.jar 의 ko_kr.json / en_us.json 에서
     추출한 템플릿으로 매칭한다 (collector 가 수집). 수집 전에는 Raw.

   Signature.kt
     data class Signature(key, pattern: Regex, category, captures)
     규칙은 코드가 아니라 데이터다. DB(error_signatures)에서 로드.
     모듈에는 시드 JSON 만 번들.

2. infra/supabase/migrations/0004_error_signatures.sql
   docs/03_구현명세_v2.md §10 의 18개를 INSERT 로 시드.
   fixes 는 jsonb: [{ "labelKo": "...", "action": {...}, "recommended": true }]

3. 진단 (app/daemon)
   Diagnosis(titleKo, causeKo, fixes: List<Fix>)
   ★ fix 가 0개인 Diagnosis 를 만들지 마라.

   특수 처리:
   - unsupported_class_version: 캡처한 major − 44 로 필요 Java 산출
     → "Java 21이 필요합니다" + [런타임 전환] (04의 ensureRuntime 호출)
   - unknown_dependency: 캡처한 slug 로 콘텐츠 검색 → [설치]
   - watchdog: 스택 덤프에서 플러그인 패키지명 추출 → 범인 지목
   - port_in_use: 해당 포트를 쓰는 프로세스를 찾아 이름 표시 + 대체 포트 제안
   - ★ linkage_error (NoSuchMethodError / IncompatibleClassChangeError):
     jvm-analysis 의 detectShadeConflicts 를 호출해 shaded 라이브러리 충돌을
     찾아서 보여줘라. 이건 Kotlin 이라서 가능한 진단이다. (P2 트랙과 연결)

4. UI 연결
   - 에러 라인 접이식 (스택트레이스 접기)
   - 매칭된 에러는 콘솔 위 진단 카드로
   - 아이콘: 접속 👤 / 채팅 💬 / 사망 ⚔️ / 명령 ⌘ / 경고 ⚠️

5. 미매칭 ERROR 라인 익명 수집 (옵트인)
   개인정보·월드·채팅 내용 전송 금지. 시그니처 후보만.

6. 테스트 코퍼스
   core/logparse/src/commonTest/resources/corpus/ 에 실제 로그 파일.
   최소: Paper 정상기동 / Java 불일치 / 의존성 누락 / 포트 충돌 / OOM /
         EULA 미동의 / watchdog / 팩 포맷 / NoSuchMethodError

검증:
  의도적으로 오류 10종을 만들어 실제 서버를 띄우고,
  전부 진단 카드가 뜨고 fix 가 1개 이상인지 보여줘.
```

---

## 하지 말 것
- 사망 메시지 하드코딩
- 정규식을 코드에 박기 (DB 시드로)
- fix 없는 Diagnosis
