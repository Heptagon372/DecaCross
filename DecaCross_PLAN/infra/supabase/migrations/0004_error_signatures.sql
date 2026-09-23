-- 0004_error_signatures.sql — error_signatures + 시드 18종 (명세 §10)
-- ★ append-only. 규칙은 코드가 아니라 데이터다. 정규식/해결책 변경은 새 마이그레이션의 UPSERT 로.
-- 이 파일은 core/logparse/src/commonMain/resources/error-signatures.seed.json 에서 생성됐다 (같은 내용).

create table if not exists error_signatures (
  id         bigserial primary key,
  key        text unique not null,
  pattern    text not null,                 -- 정규식 (Kotlin Regex 문법)
  category   text not null,
  captures   text[] not null default '{}',  -- 캡처 그룹 이름 (순서대로)
  priority   int not null default 0,        -- 한 엔트리에 여럿 맞으면 큰 값이 이긴다
  title_ko   text not null,
  cause_ko   text not null,
  -- [{ "labelKo": "...", "action": { "type": "ChangeJava", "capture": "major" }, "recommended": true }] — 항상 1개 이상
  fixes      jsonb not null check (jsonb_typeof(fixes) = 'array' and jsonb_array_length(fixes) >= 1),
  enabled    boolean not null default true,
  updated_at timestamptz not null default now()
);

insert into error_signatures (key, pattern, category, captures, priority, title_ko, cause_ko, fixes) values
  ('unsupported_class_version', 'Unsupported class file major version (\d+)', 'java', array['major']::text[], 100, 'Java 버전이 낮습니다', '플러그인이 현재 서버 런타임보다 높은 Java 로 빌드되었습니다. 클래스 파일 메이저 버전 − 44 가 필요한 Java 입니다.', '[{"labelKo": "필요한 Java 로 런타임 전환", "action": {"type": "ChangeJava", "capture": "major"}, "recommended": true}]'::jsonb),
  ('unsupported_class_error', 'java\.lang\.UnsupportedClassVersionError(?:.*?class file version (\d+))?', 'java', array['major']::text[], 90, 'Java 버전이 낮습니다', 'JVM 이 인식하지 못하는 상위 버전의 클래스 파일입니다. 서버 런타임을 올려야 합니다.', '[{"labelKo": "필요한 Java 로 런타임 전환", "action": {"type": "ChangeJava", "capture": "major"}, "recommended": true}]'::jsonb),
  ('eula_not_agreed', 'You need to agree to the EULA', 'config', '{}'::text[], 100, 'EULA 에 동의하지 않았습니다', 'eula.txt 의 `eula=true` 가 아닙니다. 동의 전에는 서버가 기동을 거부합니다.', '[{"labelKo": "EULA 다시 보기 및 동의", "action": {"type": "ShowEulaDialog"}, "recommended": true}]'::jsonb),
  ('port_in_use', 'FAILED TO BIND TO PORT|Address already in use', 'network', '{}'::text[], 100, '포트가 이미 사용 중입니다', '다른 프로세스(대개 이미 떠 있는 서버)가 같은 포트를 점유하고 있습니다.', '[{"labelKo": "포트를 쓰는 프로세스 찾기", "action": {"type": "FindPortOwner"}, "recommended": false}, {"labelKo": "다른 포트로 변경", "action": {"type": "SuggestPort"}, "recommended": true}]'::jsonb),
  ('heap_reserve_failed', 'Could not reserve enough space for object heap', 'memory', '{}'::text[], 100, '힙 메모리를 확보하지 못했습니다', '할당한 RAM(-Xmx) 이 가용 메모리보다 크거나, 32bit Java 를 쓰고 있습니다.', '[{"labelKo": "RAM 할당량 재계산", "action": {"type": "RecalcRam"}, "recommended": true}]'::jsonb),
  ('oom_heap', 'OutOfMemoryError: Java heap space', 'memory', '{}'::text[], 100, '메모리 부족 (Java heap space)', '서버가 할당된 RAM 을 다 썼습니다. 플레이어·플러그인 규모에 비해 적거나, 플러그인 메모리 누수입니다.', '[{"labelKo": "RAM 할당량 늘리기", "action": {"type": "IncreaseRam"}, "recommended": true}, {"labelKo": "다음 OOM 때 힙 덤프 남기기", "action": {"type": "EnableHeapDump"}, "recommended": false}]'::jsonb),
  ('unknown_dependency', 'Unknown dependency:? ([\w\-]+)', 'plugin', array['dependency']::text[], 80, '의존 플러그인이 없습니다', 'plugin.yml 의 depend 에 적힌 플러그인이 설치되어 있지 않습니다.', '[{"labelKo": "누락된 플러그인 설치", "action": {"type": "InstallDependency", "capture": "dependency"}, "recommended": true}]'::jsonb),
  ('plugin_load_failed', 'Could not load (?:plugin )?''([^'']+)''', 'plugin', array['file']::text[], 50, '플러그인을 불러오지 못했습니다', 'jar 가 손상되었거나 이 서버 버전과 맞지 않습니다. 아래 원인 줄(Caused by)을 확인하세요.', '[{"labelKo": "호환성 엔진으로 재질의", "action": {"type": "ReResolve", "capture": "file"}, "recommended": true}]'::jsonb),
  ('nms_missing', 'NoClassDefFoundError: net/minecraft/server/(v[\w_]+)', 'plugin', array['nmsVersion']::text[], 85, '플러그인의 NMS 버전이 다릅니다', '플러그인이 특정 MC 버전의 내부 클래스(NMS)에 직접 의존합니다. 서버 버전과 맞지 않습니다.', '[{"labelKo": "서버 버전에 맞는 빌드로 교체", "action": {"type": "ReplaceWithMatchingBuild", "capture": "nmsVersion"}, "recommended": true}]'::jsonb),
  ('plugin_enable_failed', 'Error occurred while enabling (\S+)(?: v(\S+))?', 'plugin', array['plugin','version']::text[], 60, '플러그인 활성화 중 오류', '플러그인의 onEnable 에서 예외가 났습니다. 설정 오류·의존성 불일치가 흔한 원인입니다.', '[{"labelKo": "해당 플러그인 비활성화 후 재기동", "action": {"type": "DisablePlugin", "capture": "plugin"}, "recommended": false}, {"labelKo": "호환성 엔진으로 재질의", "action": {"type": "ReResolve", "capture": "plugin"}, "recommended": true}]'::jsonb),
  ('watchdog', 'Watchdog.*(thread dump|stopped responding|not responded)', 'performance', '{}'::text[], 70, '서버가 응답하지 않습니다 (Watchdog)', '메인 스레드가 멈췄습니다. 스레드 덤프에 나오는 플러그인 패키지가 범인일 가능성이 높습니다.', '[{"labelKo": "스택에서 원인 플러그인 지목", "action": {"type": "BlamePluginFromStack"}, "recommended": true}]'::jsonb),
  ('cant_keep_up', 'Can''t keep up!.*?(\d+)ms', 'performance', array['ms']::text[], 40, '서버 TPS 저하', '틱 처리가 밀리고 있습니다. 엔티티·청크 과다 또는 무거운 플러그인이 원인입니다.', '[{"labelKo": "프로파일러로 원인 찾기", "action": {"type": "SuggestJmxProfile"}, "recommended": true}]'::jsonb),
  ('invalid_pack_format', '(?i)(Invalid|Unsupported) pack.?format', 'pack', '{}'::text[], 80, '팩 포맷이 맞지 않습니다', '리소스팩/데이터팩의 pack_format 이 이 MC 버전과 다릅니다.', '[{"labelKo": "pack_format 리넘버링 제안", "action": {"type": "SuggestRenumber"}, "recommended": true}]'::jsonb),
  ('library_download_failed', '(Failed to download|ConnectException).*librar', 'network', '{}'::text[], 70, '라이브러리 다운로드 실패', '서버가 기동에 필요한 라이브러리를 내려받지 못했습니다. 네트워크·프록시·방화벽 문제입니다.', '[{"labelKo": "미러로 재시도", "action": {"type": "RetryWithMirror"}, "recommended": true}]'::jsonb),
  ('incompatible_server_version', 'This server is running .* which is not compatible', 'plugin', '{}'::text[], 70, '서버 버전과 플러그인 API 가 맞지 않습니다', '플러그인이 요구하는 API 버전과 코어 버전이 다릅니다.', '[{"labelKo": "호환성 엔진으로 재질의", "action": {"type": "ReResolve"}, "recommended": true}]'::jsonb),
  ('java_not_found', '''java'' is not recognized|java: command not found', 'runtime', '{}'::text[], 100, 'Java 를 찾지 못했습니다', 'start.bat 이 가리키는 Java 실행 파일이 없습니다. 런타임이 삭제되었거나 경로가 바뀌었습니다.', '[{"labelKo": "런타임 복구", "action": {"type": "RepairRuntime"}, "recommended": true}]'::jsonb),
  ('world_version_mismatch', '(?i)(world|level).*(newer|older) version', 'world', '{}'::text[], 80, '월드 버전이 서버와 다릅니다', '월드가 다른 MC 버전으로 저장되었습니다. 상위 버전 월드는 하위 서버에서 열 수 없습니다.', '[{"labelKo": "스냅샷에서 월드 복원", "action": {"type": "RestoreSnapshot"}, "recommended": false}, {"labelKo": "서버 MC 버전 올리기", "action": {"type": "BumpMc"}, "recommended": true}]'::jsonb),
  ('linkage_error', '(NoSuchMethodError|IncompatibleClassChangeError)(?:: (.+))?', 'plugin', array['kind','member']::text[], 75, '라이브러리 충돌 (링크 오류)', '플러그인이 기대하는 메서드/클래스 형태가 실제 로드된 것과 다릅니다. 대개 shaded 라이브러리 버전 충돌입니다.', '[{"labelKo": "shaded 라이브러리 충돌 보기", "action": {"type": "ShowShadeConflict", "capture": "member"}, "recommended": true}]'::jsonb)
on conflict (key) do update set
  pattern = excluded.pattern, category = excluded.category, captures = excluded.captures,
  priority = excluded.priority, title_ko = excluded.title_ko, cause_ko = excluded.cause_ko,
  fixes = excluded.fixes, updated_at = now();
