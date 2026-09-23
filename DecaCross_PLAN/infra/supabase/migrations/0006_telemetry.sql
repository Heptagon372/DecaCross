-- 0006_telemetry.sql — telemetry_events (옵트인, 익명)
-- ★ append-only. 수정 금지.
-- ★ 개인정보·월드·채팅 내용은 절대 저장하지 않는다. 설치 ID 는 해시. IP 는 저장하지 않는다 (설계서 §12).

create table if not exists telemetry_events (
  id              bigserial primary key,
  install_id_hash text not null,                   -- sha256(설치 ID + 앱 솔트)
  event           text not null check (event in ('server_start_ok','server_start_fail','install_ok','install_fail','fix_applied','signature_unmatched')),
  mc_label        text,
  core            text,
  core_build      text,
  java_major      smallint,
  content         jsonb,                           -- [{slug, version}] 만
  error_sig       text,                            -- error_signatures.key 또는 미매칭 시 스택 해시
  app_version     text not null,
  os              text,
  observed_at     timestamptz not null default now()
);
create index if not exists telemetry_events_sig_idx on telemetry_events (error_sig) where error_sig is not null;
create index if not exists telemetry_events_time_idx on telemetry_events (observed_at);

-- compat_facts 로의 승격은 야간 배치(verifier)가 한다: server_start_ok/fail → compat_facts(source='telemetry')
