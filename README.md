# 데카크로스 (DecaCross)

> 웹에서 클릭 몇 번으로 **버전이 맞는** 마인크래프트 서버 한 벌을 조립하고,
> 내 PC에서 즉시 실행·개방까지 끝내는 통합 서버 구축 플랫폼.

<!-- ✏️ 여기에 프로젝트 소개를 자유롭게 써 주세요 (스크린샷, 데모 GIF 등) -->

---

## 문서

| 파일 | 내용 |
|---|---|
| [`00_스택결정_Kotlin.md`](DecaCross_PLAN/docs/00_스택결정_Kotlin.md) | **왜 Kotlin인가** — Rust/Tauri 대비 득실, JVM이 여는 기능 5개, 지불할 비용 2개 |
| [`01_기획서.md`](DecaCross_PLAN/docs/01_기획서.md) | 무엇을·왜 — 시장, 경쟁 분석, 기능 45개, 로드맵, 수익 모델, 리스크 |
| [`02_설계서.md`](DecaCross_PLAN/docs/02_설계서.md) | 어떻게 — 아키텍처, PubGrub 매핑, 설치 파이프라인, 보안, DB 스키마 |
| [`03_구현명세_v2.md`](DecaCross_PLAN/docs/03_구현명세_v2.md) | **정확히 무엇을 타이핑할 것인가** — 타입 정의, 시그니처, 마이그레이션, 완료 판정 |
| [`CLAUDE.md`](DecaCross_PLAN/CLAUDE.md) | Claude Code 프로젝트 규칙 (불변식·함정·검증 명령) |
| [`prompts/`](DecaCross_PLAN/prompts/) | Claude Code 실행용 단계별 프롬프트 (00 → 09) |

## 시작하기

```bash
# 1. 저장소 받기
git clone https://github.com/Heptagon372/DecaCross.git
cd DecaCross/DecaCross_PLAN

# 2. Claude Code 실행
claude

# 3. 첫 프롬프트 붙여넣기
#    prompts/00_부트스트랩.md 의 "프롬프트" 블록 전체를 복사해서 붙여넣는다
```

`prompts/` 의 파일을 **00부터 순서대로** 하나씩 진행한다. 각 프롬프트는 이전 단계가 끝나 있다고 가정한다.

## 스택

| 영역 | 선택 | 버전 |
|---|---|---|
| 언어 | Kotlin | 2.4.0 |
| 코어 | Kotlin Multiplatform (`jvm` 우선) | — |
| 런처 UI | Compose Multiplatform Desktop | 1.12.0 |
| 런처 코어 | Kotlin/JVM 데몬 (헤드리스) | — |
| 의존성 해결 | `pubgrub-kt` (직접 포팅) | — |
| HTTP | Ktor Server / Client | 3.4.x |
| 로컬 DB | SQLDelight | — |
| 서버 DB | Supabase + Exposed | Exposed 1.0 |
| 바이트코드 분석 | ObjectWeb ASM | — |
| 웹 | Next.js (App Router) | 16.x LTS |
| 빌드 | Gradle (KTS) + pnpm | 9.x |

## 폴더 구조

```
DecaCross_PLAN/
├── app/        cli, collector
├── core/       compat-engine, dcx, jvm-analysis, logparse, pubgrub-kt
├── infra/      supabase 마이그레이션
├── docs/       기획서 · 설계서 · 구현명세
└── prompts/    단계별 Claude Code 프롬프트
```

## 현재 단계

**Phase 1 — MVP (8주)**: Paper 계열 단일, P0 기능 12개.
목표 → *마크 서버를 한 번도 안 만들어본 사람이 5분 안에 친구를 접속시킨다.*

3주차 첫 데모 기준 (`DecaCross_PLAN/` 폴더에서 실행):
```
./gradlew :app:cli:run --args="create --mc 1.21.8 --core paper --ram 4G"
→ Java 21 자동 설치 → Paper 다운로드 → EULA → 기동 → Done (8.2s)!
```

**최대 일정 리스크: `pubgrub-kt` 포팅 (2~3주).** 1주차부터 병행 착수한다.

<!-- ✏️ 라이선스, 기여 방법, 연락처 등 추가하고 싶은 내용을 아래에 써 주세요 -->
