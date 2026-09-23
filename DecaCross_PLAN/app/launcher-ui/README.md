# app:launcher-ui — 데카크로스 런처 UI

Compose Multiplatform Desktop 창. **"내 PC가 서버 컴퓨터가 된다"** 의 실물이다.

이 모듈에는 비즈니스 로직이 없다. 설치·프로세스 제어·런타임·로그 배칭은 전부 `app:daemon` 이 하고, UI 는 HTTP/WebSocket 으로 묻고 보여줄 뿐이다. `:app:daemon` 프로젝트 의존성은 DTO 클래스(`ServerSummary`, `StreamFrame` 등)를 공유하고 데몬 프로세스를 띄우기 위한 클래스패스 용도다 — 데몬 내부 함수를 직접 호출하는 코드는 없다.

## 구조

```
kr.decacross.ui
├─ Main.kt                 application { Tray + Window }. 창 닫기 = 트레이로 숨김, 종료 = UI 프로세스만
├─ daemon/
│  ├─ DaemonClient.kt      Ktor(CIO) 클라이언트. /api 라우트 하나당 suspend 함수 하나 + WS Flow 둘
│  ├─ DaemonDiscovery.kt   daemon.json + ui.token 읽기 → GET /api/system 확인 → 없으면 spawn → 15초 폴링
│  ├─ DaemonLauncher.kt    별도 JVM 으로 데몬 기동 (-Xmx192m, 출력은 logs/daemon.log)
│  ├─ DaemonEndpoint.kt    {port, pid, token}
│  └─ DaemonError.kt       DaemonException.Api(status, messageKo) / Unreachable
├─ console/
│  ├─ ConsoleBuffer.kt     10만 줄 링 버퍼 (청크 append-only, 불변 스냅샷) + LogLevel 판정
│  └─ CommandHistory.kt    ↑/↓ 히스토리
├─ state/AppState.kt       화면 상태 전부 (Compose 스냅샷 상태). 폴링·스트림 재연결·설치 진행
└─ view/                   Sidebar / ServerDetailView / ConsolePanel / NewServerDialog / App / Theme
```

```
┌ launcher-ui (JVM, -Xmx384m) ┐        ┌ daemon (JVM, -Xmx192m, 상주) ┐        ┌ 마크 서버 (별도 JVM) ┐
│ AppState ── DaemonClient ───┼─HTTP──▶│ /api/servers, /api/install …  │──stdin─▶│ paper-1.21.8.jar     │
│ ConsoleBuffer ◀─ Flow ──────┼─WS ───▶│ /api/servers/{id}/stream      │◀─stdout─│ nogui                │
└─────────────────────────────┘        └───────────────────────────────┘        └──────────────────────┘
```

## 데몬 찾기 / 자동 기동

1. `DecaPaths.detect()` 로 앱 데이터 폴더를 정한다 (`%LOCALAPPDATA%\Decacross`, 환경변수 `DECACROSS_HOME` / `DECACROSS_SERVERS` 로 덮어쓰기 가능).
2. `daemon.json` (`{port, pid, version}`) 과 `ui.token` 을 읽는다. 둘 다 있어야 한다.
3. `GET /api/system` 을 Bearer 토큰으로 호출해 **실제로 살아있는지** 확인한다. 죽은 데몬이 남긴 파일, 토큰이 바뀐 데몬은 실패로 친다.
4. 실패하면 데몬을 **별도 프로세스**로 띄운다: 현재 UI 가 쓰는 `java` 실행파일 + 같은 클래스패스 + `-Xmx192m`, 메인 클래스 `kr.decacross.daemon.MainKt`. stdin 은 NUL, stdout/stderr 는 `<appData>\logs\daemon.log` 로 보낸다 — UI 와 파이프를 공유하지 않으므로 UI 가 죽어도 데몬은 산다.
5. 최대 15초 동안 0.4초 간격으로 `daemon.json` 을 다시 읽고 3번을 반복한다. 데몬은 토큰 파일을 먼저 쓰고 포트를 열고 나서 `daemon.json` 을 쓰므로, 파일이 보이면 곧 응답한다.
6. 연결 뒤에는 2초마다 `GET /api/servers`, 10초마다 `GET /api/system` 을 폴링한다. 폴링이 연결 오류로 실패하면 1번부터 다시 (지수 백오프, 최대 15초).

서버를 선택하면 `WS /api/servers/{id}/stream` 을 연다. 끊기면 0.5초부터 지수 백오프(최대 10초)로 재연결한다. 데몬이 접속 직후 최근 5,000줄을 replay 하므로 **재연결마다 콘솔 버퍼를 비우고** 다시 채운다.

## 콘솔 성능

- `ConsoleBuffer` 는 512줄짜리 청크 배열에 append-only 로 쌓는다. 배치 추가는 O(배치 + 청크 수) — 전체 복사가 없다. 10만 줄이 찬 뒤에도 200줄 배치 1,000번이 수백 ms 안에 끝난다 (`ConsoleBufferTest`).
- `snapshot()` 은 청크 리스트 참조 + 오프셋만 복사한 불변 `List<String>` 이고, 이후 append 는 스냅샷이 보는 범위 밖에만 쓴다. Compose 에는 이 스냅샷을 그대로 넘겨 `LazyColumn` 이 보이는 줄만 렌더한다.
- 줄마다 하는 일은 `LogLevel.of(line)` 의 `contains` 몇 번뿐이다 (WARN 주황, ERROR/FATAL/스택트레이스 빨강). 이벤트 파싱·아이콘은 06 단계.
- 새 배치가 오면 맨 아래로 따라간다. 사용자가 위로 스크롤하면 멈추고 "↓ 최신" 칩이 뜬다. 다시 맨 아래에 닿으면 자동 복귀.

## 키보드

| 키 | 동작 |
|---|---|
| `Enter` (명령 입력창) | 명령 전송 (`POST /api/servers/{id}/command`) |
| `↑` / `↓` | 명령 히스토리 (최근 200개, 연속 중복 제거) |
| 창 닫기 (`Alt+F4`) | 트레이로 숨김. 데몬·서버는 계속 실행 |

트레이 아이콘 더블클릭 또는 메뉴 "열기" 로 창을 다시 띄운다. 트레이 "종료" 는 **UI 프로세스만** 끝낸다. 트레이를 지원하지 않는 환경이거나 `--no-tray` 인자 / `DECACROSS_NO_TRAY=1` 이면 창 닫기가 곧 UI 종료다. 어느 경로로도 데몬을 멈추지 않는다.

## 새 서버

이름 · MC 버전(`GET /api/mc`, Java 권장 버전 표시, 안정 빌드 없는 버전 표시, 스냅샷은 체크 시에만) · 코어(Paper 만, Purpur/Folia 비활성) · RAM 슬라이더(512MB ~ 시스템 권장, 기본 권장값) · 포트 · **EULA 체크박스**. [설치] 는 체크박스를 켜야만 활성화되고, 어떤 코드 경로도 `acceptEula` 를 자동으로 켜지 않는다. 설치 진행은 `WS /api/install/{jobId}/stream` 으로 단계·MB·메시지·실패 사유를 보여주고, 완료되면 목록을 갱신하고 [서버 열기] 로 바로 선택할 수 있다.

## 실행 / 테스트

```
.\gradlew.bat :app:launcher-ui:run          # 데몬이 없으면 자동 기동
.\gradlew.bat :app:launcher-ui:test         # ConsoleBufferTest, CommandHistoryTest, DaemonClientTest(MockEngine)
.\gradlew.bat :app:launcher-ui:build        # ktlint 포함
```

개발 중 격리 환경: `DECACROSS_HOME`, `DECACROSS_SERVERS` 를 임시 폴더로 잡으면 데몬·UI 모두 거기만 쓴다.

## 알려진 한계

- 콘솔 텍스트 선택/복사가 안 된다 (가상 스크롤 + `SelectionContainer` 조합이 불안정해 뺐다). 접속 주소만 [복사] 버튼이 있다.
- 스트림 재연결 시 버퍼를 비우므로 최근 5,000줄(데몬 링 버퍼) 이전 로그는 사라진다.
- TPS · RAM 사용량은 데몬이 06 단계(JMX/로그)에서 채운다. 그때까지 `—` 로 보인다.
- 데몬은 `StreamFrame.Shutdown` 프레임을 정의만 하고 아직 보내지 않는다 (`POST /stop` 이 `onPhase` 를 넘기지 않음). 정지 중에는 상태 `STOPPING` 과 "정지 중… (save-all → stop)" 텍스트만 보인다. 데몬이 프레임을 보내기 시작하면 UI 는 그대로 "종료 단계: …" 로 표시한다.
- 콘솔 명령은 HTTP(`/command`) 로 보낸다. WS 텍스트 프레임 경로는 쓰지 않는다.
- `DELETE /api/servers/{id}` 의 `keepWorld` 가 실제로 월드 폴더를 남기는지는 데몬 구현에 달려 있다. UI 는 체크박스 값을 그대로 전달한다.
- 트레이 아이콘은 코드로 그린 임시 아이콘이다.
