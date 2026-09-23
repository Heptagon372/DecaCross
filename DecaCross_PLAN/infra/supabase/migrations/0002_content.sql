-- 0002_content.sql — content / content_versions / content_deps
-- ★ append-only. 수정 금지.

create table if not exists content (
  id              bigserial primary key,
  slug            text not null,
  name            text not null,
  kind            text not null check (kind in ('PLUGIN','MOD','SCRIPT','RESOURCE_PACK','DATA_PACK','WORLD','MODEL_PACK')),
  source          text not null check (source in ('MODRINTH','HANGAR','SPIGOT','URL','USER_UPLOAD','BUNDLED')),
  source_id       text,                           -- 원본 저장소의 프로젝트 ID
  license         text,                           -- ★ 재배포 가능 여부 판단 근거 (SPDX 또는 원문)
  -- ★ false 면 우리가 파일을 미러링·재배포하지 않는다 (원본 URL 로만 받는다).
  --   라이선스를 읽지 못했으면 반드시 false. 기본값이 안전한 쪽이어야 한다. 섞인 뒤엔 분리 불가.
  redistributable boolean not null default false,
  author          text,
  downloads       bigint,
  icon_url        text,
  description     text,
  page_url        text,
  updated_at      timestamptz not null default now(),
  unique (source, slug)
);
create unique index if not exists content_source_id_uniq on content (source, source_id) where source_id is not null;

create table if not exists content_versions (
  id               bigserial primary key,
  content_id       bigint not null references content(id) on delete cascade,
  version          text not null,
  file_url         text,
  sha256           text,
  size             bigint,
  -- ★ 바이트코드 실측값 (max class major - 44). plugin.yml 의 자칭보다 이 값을 우선 신뢰한다.
  java_major       smallint,
  api_version      text,                          -- plugin.yml api-version
  loaders          text[] not null default '{}',  -- BUKKIT|FABRIC|FORGE|VANILLA
  -- ★ 서수 구간 (mc_versions.ordinal 값). FK 가 아닌 이유: 스냅샷 서수(릴리스+1..9)도 담을 수 있어야 한다.
  mc_ordinal_min   int,
  mc_ordinal_max   int,
  -- PackDecl 직렬화 ({"type":"single"|"range"|"supported", ...}). 팩 종류(kind)에 따라 rp 또는 dp 포맷.
  pack_decl        jsonb,
  published_at     timestamptz,
  analyzed_at      timestamptz,                   -- jar 분석 시각. jar 자체는 보관하지 않는다.
  analyzer_version text,
  unique (content_id, version)
);
create index if not exists content_versions_mc_idx on content_versions (mc_ordinal_min, mc_ordinal_max);

create table if not exists content_deps (
  id                 bigserial primary key,
  content_version_id bigint not null references content_versions(id) on delete cascade,
  -- ★ CONFLICT 없음. 배타 제약은 PROVIDES(같은 capability 제공자는 하나만 선택됨)로 표현한다.
  kind               text not null check (kind in ('REQUIRE','OPTIONAL','PROVIDES')),
  target_slug        text,
  target_capability  text,                        -- custom_item_framework | economy_provider | permission_provider | anti_cheat | chunk_generator | other:<key>
  range              text not null default '*',
  check ((target_slug is not null) <> (target_capability is not null))
);
create index if not exists content_deps_version_idx on content_deps (content_version_id);
create index if not exists content_deps_cap_idx on content_deps (target_capability) where target_capability is not null;
