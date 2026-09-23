# app/collector — 수집 배치

외부 API 에서 MC 버전·코어 빌드·콘텐츠 메타데이터를 긁어 **엔진의 연료**(`CompatFixture` JSON 스냅샷, 선택적으로 Postgres)를 만든다.
분석한 jar 는 즉시 폐기한다 — 메타데이터만 남기고 파일은 재배포하지 않는다.

```
.\gradlew.bat :app:collector:run --args="--once --sources mojang,paper --max-client-jars 5 --out build/collector/snapshot.json"
```

## 소스

| 이름 | 엔드포인트 | 산출 | 기본 |
|---|---|---|---|
| `mojang` | `piston-meta.mojang.com/mc/game/version_manifest_v2.json` → 버전별 JSON → client.jar | `mcVersions` (javaMin, rp/dp 포맷, protocol) | ✔ |
| `paper` | `fill.papermc.io/v3/projects/paper` → `/versions/{v}/builds` | `coreBuilds` (PAPER) | ✔ |
| `purpur` | `api.purpurmc.org/v2/purpur` → `/{v}` | `coreBuilds` (PURPUR) — sha256 없음 | |
| `adoptium` | `api.adoptium.net/v3/info/available_releases`, `/assets/latest/{8,16,17,21,25}/hotspot?os=windows&architecture=x64&image_type=jre` | `java-runtimes.json` (보고용) | |
| `modrinth` | `api.modrinth.com/v2/search` (Bukkit 계열 플러그인, 다운로드순) → `/project/{id}/version` → `/projects?ids=` | `content` + `contentVersions` | |
| `hangar` | `hangar.papermc.io/api/v1/projects?sort=-stars` → `/projects/{slug}/versions` | `content` + `contentVersions` | |

SpigotMC 는 정책상 스크래핑하지 않는다 (링크만).

## 플래그

| 플래그 | 기본 | 뜻 |
|---|---|---|
| `--once` / `--loop` | `--once` | 한 번 / 그룹별 주기 반복 (manifest 5분, 코어 15분, 콘텐츠 6시간, Adoptium 24시간) |
| `--sources a,b` | `mojang,paper` | 소스 선택 |
| `--out PATH` | `build/collector/snapshot.json` | 스냅샷 출력 (`collector-report.json`, `java-runtimes.json`, `<out>.analysis.json` 은 옆에) |
| `--in PATH` | `--out` 이 있으면 그것 | 기존 스냅샷. **서수는 여기서 그대로 이어받는다** |
| `--max-client-jars N` | 20 | pack 포맷을 읽기 위해 받을 client.jar 수 (포맷이 빈 버전, 최신순) |
| `--max-content N` | 100 | 소스당 콘텐츠 수 |
| `--max-versions-per-content N` | 5 | 콘텐츠당 버전 수 |
| `--analyze-jars` / `--max-jars N` | 꺼짐 / 30 | 플러그인 jar 를 받아 ASM 분석 후 삭제 |
| `--dry-run` | | 수집만, 파일·DB 안 씀 |

환경변수: `DECACROSS_UA` (User-Agent 덮어쓰기, 기본 `DecaCross/0.1 (+https://github.com/Heptagon372/DecaCross)`), `DATABASE_URL` (JDBC URL; 있으면 `db/PostgresSink.kt` 가 마이그레이션 스키마에 upsert).

## 출력

- `snapshot.json` — `kr.decacross.compat.db.CompatFixture` (mcVersions / coreBuilds / content / contentVersions / confidence). 데몬의 `dev-compat.json` 과 같은 형식.
- `collector-report.json` — 카운트, javaMin 폴백 목록, 서수 생략 스냅샷, pack 포맷 채운 버전, 건너뛴 코어 라벨, 소스별 노트.
- `snapshot.json.analysis.json` — jar 분석 상태 (`slug@version` → analyzerVersion). 같은 `ANALYZER_VERSION` 이면 재분석하지 않는다.

병합 규칙: 기존 스냅샷 행은 지우지 않는다. `mcVersions` 는 라벨 기준(서수 불변, 포맷·protocol 만 덧입힘), `coreBuilds` 는 (core, mc, build), `content` 는 slug (Modrinth ↔ Hangar 충돌 시 Modrinth 유지), `contentVersions` 는 (slug, version) 기준이며 새 메타에 없는 분석 결과(sha256·javaMajor·apiVersion·PROVIDES)는 보존한다.

## 서수 (불변식 1·2)

- `--in` 의 서수는 그대로. 재할당 코드는 없다.
- 최초 실행: 릴리스를 `seedOrdinals` (releasedAt 순 1000 + i·10). 이후 신규 릴리스는 `nextOrdinal(max 릴리스 서수)` 를 releasedAt 순으로.
- 스냅샷: 직전 릴리스 + (그 뒤 몇 번째인지, 1..9). 10번째부터, 또는 그 칸을 다른 라벨이 이미 쓰면 **서수 없이 생략** (보고서 `ordinalOmitted`). 지어내지 않는다.
  → 최신 릴리스 이후 스냅샷이 9개를 넘으면 가장 새 스냅샷이 빠질 수 있다. 명세 §2.1 규칙이며, 필요하면 명세 수정으로 다룬다.
- `old_alpha` / `old_beta` 는 수집하지 않는다.

## 관측한 API 형태 (2026-09-24 실측)

**Mojang** `version_manifest_v2.json`: `versions[] = {id, type: release|snapshot|old_beta|old_alpha, url, time, releaseTime, sha1, complianceLevel}` (총 916, release 103 / snapshot 752). 버전별 JSON: `javaVersion.{component, majorVersion}` (1.12.2 → 8, 1.20.1 → 17, 1.21.8 → 21, 26.3 → 25), `downloads.client.{sha1, size, url}`. `javaVersion` 이 없는 아주 오래된 버전은 javaMin=8 로 두고 보고서 `javaMinFallback` 에 남긴다. `javaRecommended = javaMin` (더 높은 LTS 권장은 "그 MC 가 그 Java 에서 도는가" 데이터 없이는 하드코딩이라 하지 않는다).

**client.jar 루트 `version.json`** — `pack_version` 형태가 버전마다 다르다. 전부 처리하고 rp / dp 를 **별개 필드**로 낸다 (불변식 3·4):

| 버전 | `pack_version` | 결과 |
|---|---|---|
| 1.13~1.14 | `4` | rp = dp = 4 |
| 1.15 ~ 1.21.8 (실측 1.21.8) | `{"resource": 64, "data": 81}` | rp 64 / dp 81 (protocol 772) |
| 26.3 (실측) | `{"resource_major": 97, "resource_minor": 1, "data_major": 121, "data_minor": 0}` | rp `97.1` / dp `121` (protocol 777) |
| 방어적 | `{"resource": "88.0"}`, `{"resource": {"major": 88, "minor": 0}}` | 문자열·객체도 파싱 |

루트 `pack.mcmeta` 는 현대 client.jar 에는 없다 (1.21.8·26.3 둘 다 없음). 있으면 `pack.pack_format` 단일값을 rp = dp 로 대체.

**Fill v3**: `GET /v3/projects/paper` → `versions: {"26.3": ["26.3", "26.3-rc-3"], "1.21": ["1.21.8", …]}` (계열 → 버전 목록). `GET …/versions/{v}/builds` → 최신순 배열 `{id, time, channel: STABLE|ALPHA|BETA, commits[], downloads: {"server:default": {name, url, size, checksums: {sha256}}}}`. STABLE → `Channel.STABLE`, 나머지 → `EXPERIMENTAL`. rc 라벨(`26.3-rc-3`)은 Mojang manifest 에 snapshot 으로 있어 서수가 있으면 수집되고, 없으면 `coreVersionsSkipped`.

**Purpur v2**: `/v2/purpur` → `{versions: [...]}`, `/v2/purpur/{v}` → `{builds: {latest, all: [...]}}`, 빌드 상세는 `md5` 만. 다운로드 URL 은 `/v2/purpur/{v}/{build}/download`. → `sha256 = ""`, `size = 0`, 채널 전부 STABLE. **설치 파이프라인의 VERIFY 가 다운로드 후 직접 해시해야 한다.**

**Adoptium v3**: `available_releases` 에 8, 11, 16, 17, …, 25, 26, 27. `/assets/latest/21/hotspot?...` → `[{release_name: "jdk-21.0.12.1+1", binary: {os, architecture, image_type, package: {name, link, checksum, size}}}]`.

**Modrinth v2**: 검색 facet 은 `[["categories:paper","categories:spigot","categories:purpur","categories:bukkit","categories:folia"],["project_type:plugin"]]` — `project_type:mod` 는 109건, `project_type:plugin` 은 17,307건이라 `plugin` 을 쓴다. 검색 hit 의 `project_type` 은 플러그인이어도 `"mod"` 로 오고 `all_project_types` 에 `"plugin"` 이 들어 있다. hit 에 `license` 가 SPDX 문자열(`"MIT"`, `"LGPL-3.0-only"`, `"ARR"`)로 바로 있어 `/v2/project/{id}` 는 부르지 않는다 (요청 100개 절약). 버전: `{version_number, game_versions[], loaders[], dependencies[{project_id|null, version_id|null, dependency_type: required|optional|incompatible|embedded}], files[{url, filename, primary, size, hashes: {sha1, sha512}}]}` — **sha256 없음 → null** (`--analyze-jars` 로 받아서 해시하면 채워진다). `incompatible` 은 엔진에 CONFLICT 가 없어 생략 + 노트. 의존 `project_id` → slug 는 `/v2/projects?ids=[...]` 50개씩.

**Hangar v1**: `/projects?limit=25&offset=0&sort=-stars` → `{pagination: {count, limit, offset}, result: [{name, namespace: {owner, slug}, stats: {downloads, stars}, settings: {license: {name, type, url}}, avatarUrl}]}`. `/projects/{slug}/versions?limit=25` → `result: [{name, downloads: {PAPER: {fileInfo: {name, sizeBytes, sha256Hash}, externalUrl, downloadUrl}}, pluginDependencies: {PAPER: [{name, required, projectId}]}, platformDependencies: {PAPER: ["1.21.8", …]}}]`. PAPER 다운로드가 없는 버전은 생략. slug 는 소문자 정규화.

## 라이선스 규칙 (불변식 5)

`redistributable = true` 는 **둘 다** 만족할 때만: (1) 라이선스가 허용목록 — MIT, Apache-2.0, BSD-2/3-Clause, ISC, MPL-2.0, GPL-2.0/3.0, LGPL-2.1/3.0, AGPL-3.0 (`-only`/`-or-later` 허용), CC0-1.0, Unlicense, EPL-2.0, Zlib, 그리고 Hangar 식 별칭(`GPL`, `LGPL`, `AGPL`, `Apache 2.0`, `MIT License`); (2) 소스가 Modrinth 또는 Hangar. ARR / custom / `LicenseRef-*` / Unspecified / null / 모르는 값은 전부 false. `Redistributable.kt` + `RedistributableTest`.

## jar 분석 (`--analyze-jars`)

`app/collector/.tmp/<uuid>.jar` 로 받고(gitignored) `readJarMeta` (apiVersion, depend → REQUIRE, softdepend → OPTIONAL; 이름은 알려진 slug 에 소문자로 맞으면 그것, 아니면 `Slug(name.lowercase())`), `requiredJavaFeature` (→ javaMajor, 메타데이터보다 우선), `inferCapabilities` (→ `Dep(PROVIDES, Cap)`), 스트리밍 sha256. `finally` 에서 삭제. `ANALYZER_VERSION` 이 같으면 재분석하지 않는다.

## HTTP (불변식 17)

`Http.kt`: 식별 가능한 UA, 호스트당 ≤ 2 req/s, IOException/429/5xx 재시도 (500 ms → 1.5 s → 4 s, 3회, `Retry-After` 우선), 30 s 타임아웃, 다른 4xx 는 즉시 실패. 결과는 `HttpOutcome` (sealed) — 소스 하나가 실패해도 나머지는 계속.

## 한계 · 검증 안 된 것

- Postgres 싱크(`db/PostgresSink.kt`, Exposed 1.x upsert)는 컴파일만 확인했고 실제 DB 로 돌리지 않았다. `mc_versions.ordinal` 은 UPDATE 목록에서 제외돼 있다.
- `--loop` 는 코루틴 그룹 + 뮤텍스로 구현했으나 장시간 실행은 검증하지 않았다.
- Purpur 는 sha256/size 가 없다 (위 참고). Purpur/Folia 등 다른 Fill 프로젝트는 `PaperSource(project=…, core=…)` 로 열 수 있지만 CLI 에 노출하지 않았다.
- Modrinth `game_versions` 에 우리 목록에 없는 라벨(서수 없는 스냅샷 등)은 범위 계산에서 빠진다.
- Hangar 의 `pluginDependencies[].name` 은 플러그인 이름이지 slug 가 아니다. 소문자화한 값을 `Slug` 로 쓰므로 Modrinth slug 와 어긋날 수 있다 (예: `ViaBackwards` → `viabackwards` 는 맞지만 이름이 다른 경우는 못 잡는다).
- 최초 실행 시 스냅샷 상세 요청이 수백 건이라 레이트리밋 때문에 수 분 걸린다. 두 번째 실행부터는 `--in` 에 있는 버전은 다시 요청하지 않는다.

## 실행 결과 (2026-09-24)

아래 "실행 기록" 절 참고.
