# 04. Java 런타임 + CAS + 데몬/UI 분리

**선행조건:** 03 완료
**목표:** 버전에 맞는 Java를 격리 설치하고, 같은 파일을 두 번 받지 않고, 데몬을 분리한다
**완료 판정:**
```
서버 3개 동일 구성 생성 → 디스크에서 동일 jar 중복 0
Java 미설치 환경에서 create → 자동 설치 후 기동 성공
데몬 단독 실행 시 상주 메모리 < 192MB
```

---

## 프롬프트

```
Java 런타임 자동 설치, CAS 캐시, 그리고 데몬/UI 분리 구조를 만든다.

먼저 읽어라:
- docs/00_스택결정_Kotlin.md §3 C-2 (왜 데몬을 분리하는가)
- docs/03_구현명세_v2.md §8 (런처 JVM vs 서버 JVM — 신규 함정), §8.1 (메모리 예산)
- docs/02_설계서.md §4.3 (CAS), §4.4 (Java 런타임)
- docs/01_기획서.md §4.3 (Java 매핑 표)
- CLAUDE.md 불변식 9, 10

1. runtime/JavaRuntime.kt
   suspend fun ensureRuntime(feature: Int, os: Os, arch: Arch): Path

   - 로컬 보유 확인 → 없으면 Adoptium API v3:
     https://api.adoptium.net/v3/binary/latest/{feature}/ga/{os}/{arch}/{image}/hotspot/normal/eclipse
     feature: 8|16|17|21|25, os: windows|mac|linux, arch: x64|aarch64, image: jre
   - 404 폴백 체인: jre→jdk, aarch64→x64, 최신 GA→이전 GA
   - 압축 해제: %LOCALAPPDATA%/Decacross/runtimes/temurin-{feature}-jre/
   - ★ 설치 후 반드시 `java -version` 을 실제로 실행해 검증해라. 메타데이터를 믿지 마라.

   ★★ 가장 중요한 함정: 런처 자신의 번들 JRE 로 마크 서버를 실행하지 마라.
      jlink 커스텀 런타임은 모듈이 빠져 있어 서버가 안 돌 수 있고,
      런처 업데이트 때 버전이 바뀌면 서버가 갑자기 깨진다.
      feature 가 같아 보여도 항상 별도로 받는다.
      경로도 분리: jre/ (런처 번들) vs runtimes/ (서버용)

   절대 금지: JAVA_HOME 설정, PATH 추가, 레지스트리 수정, 기존 Java 제거

2. MC → Java 매핑은 DB(mc_versions.javaMin/javaRecommended)에서 읽는다.
   하드코딩 금지. DB 가 비었을 때 폴백 테이블은 허용하되 "폴백"임을 주석으로
   명시하고 경고 로그를 남겨라.
   참고(검증값): 1.8~1.16.5→8, 1.17.x→16, 1.18~1.20.4→17, 1.20.5~1.21.x→21, 26.x→25
   ★ "최소"가 아니라 "권장" 버전을 설치한다. 최신 하나로 통일하려 하지 마라.

3. runtime/Cas.kt
   - %LOCALAPPDATA%/Decacross/cache/blobs/{sha[0..1]}/{sha256}
   - 설치 시 서버 폴더 파일은 blob 으로의 하드링크 (Files.createLink)
   - 하드링크 실패(다른 볼륨/FS) → 복사 폴백 + 경고 로그
   - fun gc(olderThan: Duration): Long
     참조 판정은 각 서버의 .decacross/manifest.json 해시 집합으로
   - fun stats(): CasStats(blobCount, totalBytes, savedBytes)

4. ★ 데몬/UI 분리 구조 확정
   app:daemon
     - 헤드리스. main 에서 Ktor embedded server 기동 (127.0.0.1, 27565~27575 중 하나)
     - 설치 파이프라인 + 프로세스 감독 + SQLDelight 로컬 DB 소유
     - JVM 옵션 -Xmx192m 으로 실행되도록 런처 스크립트 구성
     - 기동 시 %LOCALAPPDATA%/Decacross/ui.token 에 랜덤 토큰 기록 (권한 600)
     - 종료: UI 가 닫혀도 살아있다. 서버가 하나도 없으면 N분 후 자동 종료(옵션)

   app:cli 는 이제 데몬에 HTTP 로 붙는 모드도 지원 (--daemon)
   데몬이 안 떠 있으면 인프로세스로 직접 실행 (--standalone, 기본)

5. SQLDelight 로컬 DB (app/daemon/store/)
   서버 목록, 설치 이력, 호환성 스냅샷(compat.sqlite), CAS 참조

6. RAM 권장값 계산 (신규 요구사항)
   권장 = min(시스템메모리 × 0.5, 상한) − hostOverheadMb − 클라이언트예약(기본 2048)
   hostOverheadMb = 데몬 실사용 + (UI 실행 중이면 UI 실사용)
   ResolveRequest.hostOverheadMb 에 실제 값을 넣어 호출해라.

테스트:
- 같은 jar 요구 서버 2개 → blob 1개
- 하드링크 불가 상황 → 복사 폴백
- gc 가 참조 중인 blob 을 안 지움
- Adoptium 404 폴백 체인
- ★ 번들 JRE 경로가 서버 start 스크립트에 절대 안 들어가는지 (테스트로 강제)
```

---

## 하지 말 것
- 번들 JRE로 서버 실행
- 시스템 Java 환경 변경
- 버전 매핑 하드코딩 (DB 우선)
- `java -version` 검증 생략
