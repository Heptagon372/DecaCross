-- 0007_static_verify.sql — ★ 정적 검증 결과 (Kotlin 전환 신규, 명세 §9)
-- ★ append-only. 수정 금지.
-- 신호등 판정의 1차 근거. 실기동 텔레메트리(compat_facts)는 2차.

create table if not exists static_verify_results (
  id                 bigserial primary key,
  content_version_id bigint not null references content_versions(id) on delete cascade,
  mc_version_id      bigint not null references mc_versions(id),
  core_id            bigint not null references cores(id),
  core_build         text,
  ok                 boolean not null,
  -- StaticVerifyResult 직렬화: { missingClasses:[{from,target,detail}], missingMethods:[...], missingFields:[...], warnings:[...] }
  missing_refs       jsonb,
  warning_count      int not null default 0,
  error_count        int not null default 0,
  elapsed_ms         int,
  analyzed_at        timestamptz not null default now(),
  -- ★ 분석기 버전이 바뀌면 재분석 대상. 분류 규칙(경고/에러) 변경 때마다 올린다.
  analyzer_version   text not null,
  unique (content_version_id, mc_version_id, core_id, analyzer_version)
);
create index if not exists static_verify_lookup_idx on static_verify_results (content_version_id, mc_version_id, ok);

-- 최신 분석기 기준 요약: compat-engine Score.kt 가 confidenceOf 의 1차 근거로 읽는다
create or replace view static_verify_latest as
select distinct on (content_version_id, mc_version_id, core_id)
  content_version_id, mc_version_id, core_id, ok, error_count, warning_count, analyzer_version, analyzed_at
from static_verify_results
order by content_version_id, mc_version_id, core_id, analyzed_at desc;
