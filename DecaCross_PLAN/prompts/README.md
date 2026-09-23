# Claude Code 프롬프트 세트 — Kotlin

## 두 개의 트랙이 동시에 돈다

```
순차 트랙   00 → 01 → 02 → 03 → 04 → 05 → 06 → 07 → 08     (주 1개씩)
병행 트랙   P1 pubgrub-kt 포팅        (1~6주)  ★ 최대 일정 리스크
            P2 정적 검증 + VERIFIER   (2~8주)
```

**P1을 1주차에 시작하지 않으면 7주차에 막힌다.** Kotlin에는 쓸만한 PubGrub 구현이 없어서 직접 포팅해야 하고, 그게 2~3주다. 순차 트랙을 하다가 틈날 때 P1을 조금씩 진행하는 식으로 굴려라.

## 쓰는 법

1. `DecaCross/` 에서 `claude` 실행
2. 해당 프롬프트 파일의 **"프롬프트" 블록 안 내용 전체**를 복사해 붙여넣기
3. **완료 판정**의 명령이 통과하면 커밋하고 다음으로

## 순차 트랙

| # | 파일 | 주 | 완료 판정 한 줄 |
|---|---|---|---|
| 00 | `00_부트스트랩.md` | 0 | `./gradlew build` + `pnpm -C web build` 통과 |
| 01 | `01_compat_engine_core.md` | 1 | `cli lookup 1.21.8` → `java=21` |
| 02 | `02_collector.md` | 2 | `mc_versions` ≥ 200행, `core_builds` ≥ 500행 |
| 03 | `03_install_pipeline.md` | 3 | **CLI 한 줄 → 서버 기동 → `Done (`** ★첫 데모 |
| 04 | `04_java_runtime_cas.md` | 4 | jar 중복 0 + 데몬 상주 < 192MB |
| 05 | `05_compose_ui_daemon.md` | 5 | GUI 기동/정지/명령 + UI 닫아도 서버 유지 |
| 06 | `06_logparse_errors.md` | 6 | 오류 10종 → 전부 진단 카드 + fix |
| 07 | `07_resolver_integrate.md` | 7 | 플러그인 10개 + 의존성 자동 해결 |
| 08 | `08_web_wizard_dcx.md` | 8 | 웹에서 시작 → 외부 접속 성공 ★MVP |

## 병행 트랙

| # | 파일 | 기간 | 완료 판정 |
|---|---|---|---|
| P1 | `P1_pubgrub_kt_포팅.md` | **1~6주** | 이식한 골든 케이스 전부 통과 + DerivationTree 품질 확인 |
| P2 | `P2_static_verify_ci.md` | 2~8주 | `static_verify_results` 5,000건+, 오탐률 < 5% |

## 규칙

- 한 프롬프트가 한 세션보다 커지면 **쪼개달라고 요청**해라
- 완료 판정이 안 되면 다음으로 넘어가지 마라
- Claude Code가 `docs/03_구현명세_v2.md`와 다르게 구현하려 하면 **명세를 먼저 고칠지 물어봐라**
- Kotlin/Compose/Ktor API를 추측으로 쓰려 하면 **문서 확인을 먼저 시켜라**
