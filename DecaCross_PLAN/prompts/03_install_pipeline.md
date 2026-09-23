# 03. 설치 파이프라인 — ★ 첫 데모

**선행조건:** 01, 02 완료
**목표:** CLI 한 줄로 마크 서버가 실제로 뜬다
**완료 판정:**
```
./gradlew :app:cli:run --args="create --mc 1.21.8 --core paper --ram 4G --name demo --start"
→ 서버 폴더 생성 → 기동 → 콘솔에 "Done (" 출현
```
> **이게 되면 나머지는 전부 UI 문제다.** 여기까지가 프로젝트의 진짜 리스크 구간.

---

## 프롬프트

```
설치 파이프라인을 만든다. 목표는 단 하나 —
CLI 한 줄로 마인크래프트 서버가 실제로 뜨는 것.

먼저 읽어라:
- docs/02_설계서.md §4 (상태머신, 원자성, CAS)
- docs/03_구현명세_v2.md §12 (3주차 완료 판정), §13 (금지 목록)
- CLAUDE.md 불변식 11, 12, 13

먼저 계획을 보여주고 확인받은 다음 구현해라.

구현 — app/daemon/src/main/kotlin/kr/decacross/daemon/install/
(라이브러리로 만들고 app:cli 가 호출한다. Ktor 서버는 아직 안 붙여도 된다)

1. Pipeline.kt — 상태머신
   IDLE → RESOLVE → PLAN → FETCH → VERIFY → LAYOUT → CONFIG → EULA → READY
   각 단계는 Flow<InstallEvent> 로 진행률 보고 (나중에 WS 로 연결)
   실패 → ROLLBACK → 스테이징 삭제 → 사용자 폴더 무변화

2. Fetch.kt
   - Ktor Client, 동시 4개 병렬
   - HTTP Range 로 중단 재개
   - 3회 재시도(지수 백오프) → 미러 → 실패
   - 진행률 보고

3. Verify.kt
   - SHA256 대조. 불일치는 재시도하지 말고 즉시 중단 (변조 의심)
   - zip 무결성 검사

4. Layout.kt + Atomic.kt
   - 스테이징: servers/.staging/{uuid}/ 에 전부 조립
   - 성공: Files.move(staging, target, ATOMIC_MOVE)
   - 실패: 스테이징 삭제
   ★ 사용자 폴더에 반쯤 만든 서버가 남으면 안 된다

5. Config.kt
   - server.properties (motd, max-players, difficulty, online-mode=true 기본)
   - start.bat / start.sh
     * Java 를 절대경로로 호출 (JAVA_HOME/PATH 안 건드림)
     * -Xms/-Xmx 는 인자 값
     * Aikar 플래그 프로파일 (12G 미만/이상 분기)
     * -Dfile.encoding=UTF-8 필수 (한글 깨짐 방지)
     * 마지막은 "-jar <core>.jar nogui"
     * ★ 이 스크립트는 런처 없이도 실행되어야 한다 (락인 금지)
   - eula.txt

6. 디렉터리 레이아웃 (docs/02_설계서.md §14, docs/03_구현명세_v2.md §8)
   서버: Documents/Decacross/servers/{name}/     ← 사용자가 접근 가능한 곳
   내부: %LOCALAPPDATA%/Decacross/               ← 런타임/캐시/DB
   AppData 에 서버를 숨기지 마라.

7. app:cli
   create --mc --core --ram --name [--start] [--accept-eula]
   list
   start <name>
   Java 는 이 단계에선 "시스템 java 를 찾아 쓴다"로 임시 처리해도 된다 (자동 설치는 04).
   단 버전이 안 맞으면 명확한 에러를 내라.

8. EULA
   --accept-eula 가 없으면 프롬프트로 물어본다.
   자동 동의하지 마라. 사용자가 명시적으로 동의해야 eula=true 를 쓴다.

9. 프로세스 기동 (--start)
   ProcessBuilder 로 nogui 실행, stdout 을 그대로 콘솔에 흘린다.
   Ctrl+C 시 save-all → stop 순서로 정리.

테스트:
- 각 단계 실패 주입 시 스테이징 정리 + 사용자 폴더 무오염
- SHA256 불일치 시 중단
- 생성된 start.bat 이 런처 없이 실행 가능 (경로에 공백 있는 케이스 포함)
- ATOMIC_MOVE 가 실패하는 파일시스템에서의 폴백

끝나면 실제로 실행해서 "Done (" 로그가 나오는 걸 보여줘.
```

---

## 하지 말 것
- Java 자동 설치 (04에서)
- GUI (05에서)
- 플러그인 설치 (07에서)
- EULA 자동 동의
