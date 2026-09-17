-- 0003_compat_facts.sql — compat_facts + compat_summary 뷰
-- ★ append-only. 수정 금지.

create table if not exists compat_facts (
  id                 bigserial primary key,
  mc_version_id      bigint not null references mc_versions(id),
  core_id            bigint not null references cores(id),
  core_build         text,
  java_major         smallint,
  content_version_id bigint not null references content_versions(id) on delete cascade,
  result             text not null check (result in ('ok','fail')),
  error_sig          text,                        -- error_signatures.key (0004)
  source             text not null check (source in ('telemetry','ci','manual')),
  install_id_hash    text,                        -- 익명 설치 ID 해시. 중복 집계 방지. 원문은 절대 저장하지 않는다.
  observed_at        timestamptz not null default now()
);
create index if not exists compat_facts_lookup_idx on compat_facts (content_version_id, mc_version_id, result);
create index if not exists compat_facts_time_idx on compat_facts (observed_at);

-- 신호등 집계 (설계서 §3.6). 최근 90일, "서로 다른 설치 ID" 기준.
--   🟢 ci_ok 또는 (ok_installs >= 20 and 성공률 >= 95%)
--   🟠 성공률 70~95%
--   🔴 성공률 < 70% (샘플 10건 이상)
--   그 외 🟡 — 판정 자체는 compat-engine Score.kt 가 한다. 뷰는 숫자만 준다.
create or replace view compat_summary as
select
  content_version_id,
  mc_version_id,
  core_id,
  count(distinct install_id_hash) filter (where result = 'ok')   as ok_installs,
  count(distinct install_id_hash) filter (where result = 'fail') as fail_installs,
  count(*)                                                        as samples,
  bool_or(source = 'ci' and result = 'ok')                        as ci_ok,
  max(observed_at)                                                as last_seen
from compat_facts
where observed_at > now() - interval '90 days'
group by content_version_id, mc_version_id, core_id;
