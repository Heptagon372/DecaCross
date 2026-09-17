-- 0008_collector_runtime_state.sql — java_runtimes / collector_state / content_versions 수집 컬럼
-- ★ append-only. 수정 금지.
--
-- [명세 수정 제안 SCP-1 — 사용자 확인 전 기본안]
--   명세 §9 는 0004~0007 을 예약(error_signatures / recipes_rls / telemetry / static_verify)했고 아직 파일이 없다.
--   02 수집기가 쓸 테이블이 명세에 없어서, 예약 번호와 겹치지 않는 0008 을 쓴다.
--   · 개발 DB 러너는 "마지막 적용분보다 앞 번호 미적용 파일"을 개발 모드에서만 WARN 후 적용한다.
--   · Supabase 에 0004~0007 보다 먼저 0008 을 push 하면 CLI 가 순서 오류를 낸다 → 그때는 --include-all.
--   대안: 이 파일을 0004 로 하고 예약 번호를 +1 씩 민다 (docs/03 §9, prompts/06, prompts/P2 수정 필요).
-- 전부 추가(additive)만 한다. 기존 컬럼·제약을 바꾸지 않는다.

-- ── Adoptium(Temurin) 서버용 런타임 메타데이터 ─────────────────────────────
-- 행이 없음 = 그 조합의 바이너리가 없음 (예: JRE 16 전 플랫폼, mac/aarch64 Java 8).
-- 데몬/엔진이 404 왕복 없이 폴백(jre→jdk, aarch64→x64)을 계획할 수 있다.
create table if not exists java_runtimes (
  id              bigserial primary key,
  distribution    text not null default 'temurin' check (distribution in ('temurin')),
  feature         smallint not null,                                   -- JavaSpec.feature (8 | 16 | 17 | 21 | 25 …)
  os              text not null check (os in ('windows','mac','linux')),
  arch            text not null check (arch in ('x64','aarch64')),
  image_type      text not null check (image_type in ('jre','jdk')),
  release_name    text not null,                                       -- 'jdk-21.0.12.1+1' | 'jdk8u504-b01'
  openjdk_version text not null,                                       -- '21.0.12.1+1-LTS' (semver 필드는 4단 버전에서 깨져서 쓰지 않는다)
  package_name    text not null,                                       -- 확장자로 zip / tar.gz 판별
  download_url    text not null,                                       -- binary.package.link
  sha256          text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  size            bigint not null,
  published_at    timestamptz,                                         -- binary.updated_at
  collected_at    timestamptz not null default now(),
  unique (distribution, feature, os, arch, image_type, release_name)   -- 이력 보존 → "이전 GA" 폴백 가능
);
create index if not exists java_runtimes_lookup_idx
  on java_runtimes (distribution, feature, os, arch, image_type, published_at desc);

-- ── 수집기 상태 (조건부 요청 ETag/Last-Modified, jar 메타 읽음 표시 등) ─────────
-- 재시작해도 조건부 요청·중복 다운로드 방지가 유지되도록 DB 에 둔다. 엔진은 읽지 않는다.
create table if not exists collector_state (
  key        text primary key,                                         -- 'mojang.manifest' | 'mojang.jarmeta.<label>' | 'fill.paper.builds.<v>' …
  value      jsonb not null,
  updated_at timestamptz not null default now()
);
-- 수집기 내부 상태(다음 Range 읽기 URL 등). 클라이언트(anon/authenticated)가 읽거나 쓸 이유가 없다.
-- 정책 없이 RLS 만 켠다 → Data API 로는 접근 불가, 테이블 소유자(수집기 연결 역할)는 RLS 를 우회한다.
-- (java_runtimes 는 0001~0003 의 공개 데이터 테이블과 같은 취급 — 프로젝트 전체 RLS 결정(명세 0005 등)을 따른다.)
alter table collector_state enable row level security;

-- ── content_versions 수집 컬럼 ──────────────────────────────────────────
-- source_version_id : 원본 저장소의 버전 ID (Modrinth version id / Hangar version id). version 문자열은 중복될 수 있다.
-- channel           : Modrinth version_type(release|beta|alpha) / Hangar 채널 이름 원문.
-- analysis          : jar 분석 원자료 (descriptor 이름·depend/softdepend/provides·libraries·folia-supported·loadErrors·
--                     바이트코드 프로파일·NMS 신호·Capability 근거). jar 를 다시 받지 않고 재해석할 수 있게 보관한다.
alter table content_versions add column if not exists source_version_id text;
alter table content_versions add column if not exists channel text;
alter table content_versions add column if not exists analysis jsonb;
create index if not exists content_versions_source_version_idx
  on content_versions (source_version_id) where source_version_id is not null;
create index if not exists content_versions_sha256_idx
  on content_versions (sha256) where sha256 is not null;
