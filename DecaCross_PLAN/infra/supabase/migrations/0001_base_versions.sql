-- 0001_base_versions.sql — mc_versions / cores / core_builds
-- ★ 마이그레이션은 append-only. 이 파일을 수정하지 말고 새 파일(000N_*.sql)을 추가한다.
--
-- [명세 §9 대비 의도적 차이 — pack_format 컬럼 타입]
--   명세는 numeric 을 요구하지만 numeric 은 101.1 과 101.10 을 같은 값으로 취급한다.
--   pack_format 의 minor 는 소수가 아니라 정수(88.0 → major 88, minor 0; 101.10 → minor 10)라서
--   numeric 저장은 데이터 손실이다. 그래서 정규화된 문자열("34", "88.0", "101.1") + CHECK 로 저장하고,
--   비교는 항상 Kotlin `PackFormat(major, minor)` 로 한다. (명세 §9 수정 제안: numeric → text)
--   int 저장 금지라는 원래 의도(1.21.9 부터 "88.0" 형태)는 그대로 지켜진다.

create table if not exists mc_versions (
  id               bigserial primary key,
  label            text unique not null,          -- '1.21.8', '26.3' — 표시 전용. 비교 금지.
  -- ★ 비교는 이걸로. append-only: 한 번 발급된 서수는 영원히 재할당하지 않는다.
  --   신규 릴리스 = max(ordinal) + 10, 스냅샷 = 직전 릴리스 + 1..9. 서수를 바꾸는 UPDATE 를 만들지 마라.
  ordinal          int unique not null,
  released_at      timestamptz not null,
  is_snapshot      boolean not null default false,
  java_min         smallint not null,             -- 8 | 16 | 17 | 21 | 25
  java_recommended smallint not null,
  -- 리소스팩 포맷. 정규화 문자열 (예: '34', '88.0', '101.1'). 미수집이면 null.
  rp_format        text check (rp_format is null or rp_format ~ '^[0-9]+(\.[0-9]+)?$'),
  -- 데이터팩 포맷 ← 리소스팩과 ★별개 컬럼★. 같은 버전에서 값이 다르다 (1.21: rp 34 / dp 48). 합치지 마라.
  dp_format        text check (dp_format is null or dp_format ~ '^[0-9]+(\.[0-9]+)?$'),
  nms_version      text,                          -- 'v1_21_R1'
  protocol         int,
  client_jar_url   text,
  client_jar_sha1  text,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);
create index if not exists mc_versions_ordinal_idx on mc_versions (ordinal);
create index if not exists mc_versions_release_idx on mc_versions (is_snapshot, ordinal desc);

create table if not exists cores (
  id            bigserial primary key,
  key           text unique not null,            -- paper|purpur|folia|spigot|vanilla|fabric|neoforge|forge
  loader_family text not null check (loader_family in ('bukkit','fabric','forge','vanilla')),
  api_kind      text check (api_kind in ('bukkit','fabric','forge'))
);
insert into cores (key, loader_family, api_kind) values
  ('paper',    'bukkit',  'bukkit'),
  ('purpur',   'bukkit',  'bukkit'),
  ('folia',    'bukkit',  'bukkit'),
  ('spigot',   'bukkit',  'bukkit'),
  ('vanilla',  'vanilla', null),
  ('fabric',   'fabric',  'fabric'),
  ('neoforge', 'forge',   'forge'),
  ('forge',    'forge',   'forge')
on conflict (key) do nothing;

create table if not exists core_builds (
  id            bigserial primary key,
  core_id       bigint not null references cores(id),
  mc_version_id bigint not null references mc_versions(id),
  build         text not null,
  channel       text not null check (channel in ('STABLE','EXPERIMENTAL')),  -- Fill 의 ALPHA/BETA 는 EXPERIMENTAL
  download_url  text not null,
  sha256        text not null,
  size          bigint not null,
  published_at  timestamptz,
  collected_at  timestamptz not null default now(),
  unique (core_id, mc_version_id, build)
);
create index if not exists core_builds_lookup_idx on core_builds (core_id, mc_version_id, channel);
