# 05. Compose Desktop UI + 데몬 프로토콜

**선행조건:** 04 완료
**목표:** GUI에서 서버를 만들고 띄우고 명령을 넣는다. UI를 닫아도 서버는 산다.
**완료 판정:**
```
런처 실행 → 서버 생성 → 기동 → 콘솔 스트리밍 → 명령 입력 →
UI 창 닫기 → 서버 계속 실행 확인 → UI 재실행 → 상태 복원
정지 시 save-all→stop 순서 확인, 강제종료 시 경고 모달
```

---

## 프롬프트

```
Compose Multiplatform Desktop UI 와 데몬 API 를 만든다.
이게 "내 PC가 서버 컴퓨터가 된다"의 실물이다.

먼저 읽어라:
- docs/01_기획서.md §4.6 (콘솔 창 UI 스케치)
- docs/02_설계서.md §5 (프로세스 제어, 종료 프로토콜, 크래시 루프)
- docs/03_구현명세_v2.md §7 (데몬 API 전체 목록), §7.3 (스트리밍 배칭)
- CLAUDE.md 불변식 13, 14, 15

★ Compose Multiplatform 1.12 와 Ktor 3.4 의 실제 API 를 먼저 확인해라.
  추측으로 쓰지 말고 공식 문서를 보고, 확인한 내용을 먼저 보고해줘.
  계획을 확인받은 뒤 구현한다.

1. process/Supervisor.kt (데몬)
   - ProcessBuilder 로 spawn, stdin/stdout/stderr 파이프
   - 항상 nogui (바닐라 Swing 창이 같이 뜨면 안 됨)
   - stdout/stderr → Flow<String>
   - stdin 에 명령 write

2. process/Shutdown.kt — 순서 생략 금지
   ① stdin "save-all" → 저장 완료 로그 대기
   ② stdin "stop"
   ③ 최대 60초 대기
   ④ 미종료 → SIGTERM (Windows: taskkill /T, CTRL_BREAK)
   ⑤ 30초 추가
   ⑥ 미종료 → 강제 종료 + "월드 손상 가능" 명시 경고
   강제종료 버튼은 ④부터 시작하되 누르기 전 경고 모달 필수.

3. process/CrashLoop.kt
   - 비정상 종료 감지 → 마지막 200줄 + crash-reports/ 최신 파일 수집
   - 5분 내 3회 → 자동 재시작 중단, 진단 모드 진입

4. api/UiRoutes.kt — docs/03_구현명세_v2.md §7.1 목록 그대로
   - Bearer 토큰 인증 (ui.token 파일)
   - WS /api/servers/{id}/stream : 로그·상태 스트리밍
   ★ 로그를 라인마다 프레임으로 보내지 마라. 50ms 윈도우로 배칭해라.
     대량 로그(플러그인 100개 로딩)에서 UI 가 죽는다.
   - 상태는 1초 주기 { state, players, tps, ram }

   ★ api/BridgeRoutes.kt 는 08 단계에서. 지금 만들지 마라.
     만들더라도 UI 핸들러를 절대 재사용하지 마라 (정보 유출 경로).

5. app:launcher-ui (Compose Desktop)
   - 데몬에 HTTP/WS 로 붙는다. 로직을 UI 에 넣지 마라.
   - 데몬이 안 떠 있으면 자동 기동 (별도 프로세스, -Xmx192m)
   - 서버 목록 사이드바
   - 서버 상세: 상단 상태바(접속 주소/플레이어/TPS/RAM/업타임)
   - 콘솔 패널: ★ 가상 스크롤(LazyColumn) 필수. 10만 줄에서도 부드러워야 한다
   - 명령 입력창 (히스토리 ↑↓)
   - 플레이어 패널 (지금은 목록만)
   - 다크 모드 기본
   - 창을 닫으면 UI 프로세스만 종료. 데몬과 서버는 유지.
   - 트레이 아이콘으로 다시 열기

6. 로그는 지금 원문 그대로 표시. 이벤트 파싱/아이콘은 06 단계.

성능 요구:
- 콘솔 10만 줄에서 스크롤이 부드러울 것
- 서버 기동 중 UI 가 멈추지 않을 것 (전부 코루틴)
- UI 힙 -Xmx384m 안에서 동작할 것

테스트:
- 종료 프로토콜 각 단계가 순서대로 (mock 프로세스로)
- 크래시 루프 차단이 3회에서 동작
- 50ms 배칭이 실제로 프레임 수를 줄이는지
- UI 종료 후 데몬·서버 생존
```

---

## 하지 말 것
- 로그 이벤트 파싱 (06에서)
- 브리지 라우트 추가 (08에서)
- UI에 비즈니스 로직 넣기
- `stop` 없이 kill / 라인마다 emit
