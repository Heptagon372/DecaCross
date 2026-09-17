# 02. 수집기 (Kotlin) + DB 스키마

**선행조건:** 01 완료
**목표:** 외부 API에서 버전·코어·Java·콘텐츠 메타데이터를 긁어 DB를 채운다
**완료 판정:**
```
./gradlew :app:collector:run --args="--once"
→ mc_versions ≥ 200행, core_builds ≥ 500행, content ≥ 100행
```

---

## 프롬프트

```
수집기와 DB 스키마를 만든다. 여기서 만든 데이터가 엔진의 연료다.
Python 대신 Kotlin/JVM 으로 통합한다 — jar 분석이 JVM 에서 훨씬 쉽기 때문이다.

먼저 읽어라:
- docs/03_구현명세_v2.md §9 (마이그레이션 필수 컬럼), §6.3 (바이트코드 표)
- docs/02_설계서.md §9 (수집기 설계, jar 분석 파이프라인)
- docs/01_기획서.md 부록 B (API 엔드포인트)

1. infra/supabase/migrations/
   0001_base_versions.sql   mc_versions, cores, core_builds
   0002_content.sql         content, content_versions, content_deps
   0003_compat_facts.sql    compat_facts + compat_summary 뷰

   반드시 (빠지면 나중에 전면 마이그레이션):
   - mc_versions.ordinal : int unique, append-only (SQL 주석으로 명시)
   - mc_versions.rp_format / dp_format : numeric, 별개 컬럼
   - content.redistributable : boolean not null default false
   - content_versions.java_major : smallint (ASM 실측값)

2. app/collector — Ktor Client + kotlinx.serialization
   sources/
     Mojang.kt    piston-meta version_manifest_v2 → mc_versions
                  client.jar 받아서 pack.mcmeta 파싱 → rpFormat / dpFormat 각각 추출
     Paper.kt     https://fill.papermc.io/v3/projects/{project}/versions/{v}/builds
                  ★ User-Agent 필수: "DecaCross/0.1 (contact@example.com)"
                    generic UA 는 PaperMC 정책 위반이다
     Purpur.kt    api.purpurmc.org
     Adoptium.kt  api.adoptium.net/v3
     Modrinth.kt  /v2/search + /v2/project/{id}/version
                  facets 예: [["categories:paper"],["versions:1.21.8"],["project_type:mod"]]
     Hangar.kt    hangar.papermc.io API

3. core/jvm-analysis 에 분석 코드 (collector 가 호출)
   JarMeta.kt   java.util.jar.JarFile 로 plugin.yml / paper-plugin.yml /
                fabric.mod.json / mods.toml 파싱
                ★ Bukkit 의 depend / softdepend / loadbefore / libraries 시맨틱을
                  정확히 재현해라. 추측하지 말고 Bukkit 문서를 확인해라.
   Bytecode.kt  ASM ClassReader 로 모든 .class 의 major version 최댓값
                → javaMajor = max - 44   (52→8, 60→16, 61→17, 65→21, 69→25)
   PackMeta.kt  pack.mcmeta: pack_format / supported_formats / min_format / max_format

   ★ 분석이 끝난 jar 는 즉시 삭제한다. 메타데이터만 보관하고 재배포하지 않는다.

4. Capability 추론 (jvm-analysis/CapabilityInfer.kt)
   ASM 으로 클래스 시그니처를 보고 판정:
   - ItemsAdder / Oraxen / Nexo → CustomItemFramework
   - Vault 의 Economy/Permission 서비스 등록 호출이 보이면 → 해당 Provider
   ★ 애매하면 저장하지 마라. 잘못된 Provides 는 해결을 망친다. 로그만 남겨라.

5. Runner
   --once / --loop
   주기: 코어 15분, MC manifest 5분, 콘텐츠 인덱스 6시간, Adoptium 24시간
   레이트리밋 + 지수 백오프 재시도

6. 서수 시딩
   최초 1회만. release 를 releasedAt 순 정렬해 1000 + i*10.
   이후 신규는 max(ordinal)+10. 재할당 코드를 만들지 마라.

제약:
- Exposed 또는 직접 SQL. DB 접근은 한 곳에 모아라
- 라이선스를 못 읽으면 redistributable = false (안전한 기본값)
- 모든 외부 호출에 식별 가능한 User-Agent + 연락처
```

---

## 하지 말 것
- jar 파일 보관 (해시만)
- 라이선스 확인 없이 `redistributable = true`
- SpigotMC 스크래핑 (정책 위반 — 링크만)
- 애매한 Capability 저장
