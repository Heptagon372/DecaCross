# 08. 웹 위저드 + .dcx + 브리지 + 터널 — ★ MVP 완성

**선행조건:** 07 완료
**목표:** 브라우저에서 시작해서 친구가 접속한다
**완료 판정:**
```
브라우저 위저드 5스텝 → [설치하기] → 런처 인계 → 네이티브 확인 다이얼로그 →
설치 → 기동 → 터널 주소 발급 → 다른 네트워크에서 실제 접속 성공
./gradlew :app:daemon:test --tests '*bridgeSecurity*'
```

---

## 프롬프트

```
웹 빌더 위저드, .dcx 레시피, 웹↔런처 브리지, 터널을 구현한다. MVP 마지막 조각.

먼저 읽어라:
- docs/01_기획서.md §4.2 (위저드 5스텝 상세)
- docs/00_스택결정_Kotlin.md §4 (왜 Next.js 를 유지하는가 — Compose Web 은 함정)
- docs/02_설계서.md §6 (브리지 보안), §7 (.dcx v1.1 스펙), §10 (터널)
- docs/03_구현명세_v2.md §7.2 (브리지 API 와 보안 체크리스트)

계획을 먼저 보여주고 확인받아라. 한 세션에 안 끝나면 쪼개자고 제안해라.

1. .dcx 스키마 (core/dcx/src/commonMain/resources/dcx-1.1.schema.json)
   ★ 이 JSON Schema 가 단일 진실 소스다.
   - Kotlin 타입: kotlinx.serialization @Serializable data class (스키마와 1:1)
   - TS 타입: json-schema-to-typescript 로 생성 → web/src/types/dcx.ts
   손으로 두 곳을 맞추지 마라. 생성 태스크를 Gradle 에 걸어라.
   필드는 설계서 §7 예시를 정규화. variables / when 조건부 / signature 포함.

2. 웹 위저드 (web/app/build/)
   5스텝: 용도 → MC버전 → 코어 → 콘텐츠 → 실행설정
   - 선택할 때마다 뒤 스텝 선택지가 좁혀진다
   - 우측 패널 실시간 신호등 (🟢 N / 🟡 N / 🔴 N)
   - 🔴 는 반드시 대안 버튼과 함께
   ★ 판정은 서버측 JVM 엔진 호출(POST /api/resolve)로 한다.
     Phase 1 에서 KMP js 타겟을 만들지 마라 — 엔진이 하나뿐이라
     "웹과 런처 판정 불일치" 문제가 애초에 없다. 이게 Kotlin 전환의 이득이다.
     타이핑 중 반응성은 디바운스(200ms)+낙관적 UI 로 해결한다.

3. 웹 API
   Next.js Route Handler 가 데몬이 아니라 서버측 엔진(별도 JVM 서비스 또는
   Ktor 서버)을 호출한다. 라우트 목록은 docs/03_구현명세_v2.md §8 참고
   (Rust 판 §8 라우트 목록이 그대로 유효).

4. api/BridgeRoutes.kt (데몬) — ★ 이 단계 최대 리스크
   GET  /ping     → {"app":"decacross","version":"..."}   ← 이것만
   POST /install  → 202 {"jobId":...}

   보안 체크리스트를 전부 테스트로 강제해라:
   - Origin 화이트리스트(decacross.kr, 개발 시 localhost:3000) 아니면 403
   - Host 헤더가 127.0.0.1:{port} 정확히 아니면 403 (DNS rebinding)
   - X-Decacross-Bridge: 1 없으면 403 (단순요청 차단 → preflight 강제)
   - /ping 응답에 서버 목록·경로·사용자명 절대 없음
   - /install 은 사용자 승인 전에 파일을 쓰지 않음
   ★ UI 라우트 핸들러를 재사용하지 마라. 별도 라우팅 트리로 완전히 분리해라.
     재사용하면 정보가 새는 경로가 된다.

5. 설치 확인 다이얼로그 (Compose 네이티브, 웹이 위조 불가)
   서버팩 이름 / 제작자(확인 여부) / 구성 요약 / 총 용량 /
   ⚠️ 외부 URL 직접 다운로드가 있으면 그 사실과 도메인
   [자세히 보기] [취소] [설치]

6. 딥링크 decacross://install?r={slug}
   - Windows: 레지스트리 등록 (jpackage 설치 시)
   - 런처 미설치 시 웹에서 다운로드 안내 → 설치 후 pending 레시피 자동 인계

7. 터널 (Phase 1 은 서드파티 래핑)
   playit.gg 또는 ngrok 바이너리를 데몬이 관리(다운로드·실행·주소 파싱).
   자체 릴레이는 만들지 마라 (Phase 4).
   발급 주소를 상태바에 표시 + 복사 버튼.

8. 리소스팩 자동 호스팅
   데몬의 Ktor 서버로 팩 제공 → server.properties 의
   resource-pack / resource-pack-sha1 자동 기입. 터널이 켜져 있으면 터널 주소로.

9. 레시피 공유
   POST /api/recipes → slug → decacross.kr/p/{slug}
   공개 페이지에서 구성 미리보기 + [설치]

최종 검증:
  다른 네트워크에 있는 사람이 실제로 접속되는 것까지 확인해라.
  로컬에서만 되는 건 완료가 아니다.
```

---

## 하지 말 것
- Compose Web / KMP js 타겟 만들기 (Phase 2, 그리고 SEO 함정)
- 자체 터널 릴레이 (Phase 4)
- 브리지에서 UI 핸들러 재사용
- 사용자 승인 없는 설치
