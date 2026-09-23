# core/jvm-analysis — JVM 전용 분석기

플러그인 jar 를 **서버를 띄우지 않고** 읽는 모듈. `core/*` 중 유일하게 파일 I/O 가 허용된다 (CLAUDE.md 불변식 6 예외).
`java.util.jar` + ObjectWeb ASM 만 쓰고, 분석이 끝난 jar 는 호출자가 즉시 폐기한다 — 여기서는 파일 핸들을 쥐고 있지 않는다.

명세: `docs/03_구현명세_v2.md` §6. 배경: `docs/00_스택결정_Kotlin.md` §2 (K-1, K-2). 작업 프롬프트: `prompts/P2_static_verify_ci.md` (P2-a), `prompts/02_collector.md` 3~4.

## 분석기 한눈에

| 파일 | 진입점 | 하는 일 |
|---|---|---|
| `JarMeta.kt` | `readJarMeta(jar): JarMeta?` | `paper-plugin.yml` → `plugin.yml` → `fabric.mod.json` → `mods.toml` 순으로 디스크립터를 읽는다. Bukkit 의 depend / softdepend / loadbefore / libraries / provides 시맨틱, Paper 의 `dependencies.server/bootstrap { required, load }` 매핑, Fabric 의 depends/recommends/breaks + 버전 범위. snakeyaml 은 `SafeConstructor` + `LoaderOptions` 만 |
| `Bytecode.kt` | `requiredJavaFeature(jar): Int?`, `classVersionProfile(jar)` | top-level `.class` 헤더 8바이트만 읽어 major 최댓값 → `max − 44`. `META-INF/versions/N/` 은 최소 요구치에서 제외하고 `versionedMax` 로 따로 보고 |
| `PackMeta.kt` | `readPackMeta(path)`, `readPackDecl(path): PackDecl?`, `parsePackMcmeta(text)` | zip/jar/디렉터리의 `pack.mcmeta`. `pack_format`(int/`88.0`/문자열), `supported_formats`(목록/`{min_inclusive,max_inclusive}`), `min_format`/`max_format`(int/`[major, minor]`) → compat-engine 의 `PackDecl` |
| `CapabilityInfer.kt` | `inferCapabilities(jar, meta): CapabilityInference` | 이름·메인 패키지·바이트코드(`ServicesManager.register(Economy.class …)`)로 `Capability` 추론. **애매하면 저장하지 않고 note 만** |
| `PluginScan.kt` | `scanPluginJar(jar): PluginScan` | 플러그인 클래스 전부를 코드 포함으로 읽어 외부 참조를 문맥(`RefContext`)·가드 여부와 함께 수집 |
| `ClassIndex.kt` | `JarIndexCache.memberIndex(jar)`, `JarIndexCache.pluginScan(jar)` | jar 멤버 인덱스(`SKIP_CODE`), JDK 시스템 모듈 인덱스(`ModuleFinder.ofSystem()`), (경로, 크기, mtime) 키 LRU 캐시 |
| `StaticVerify.kt` | `staticVerify(pluginJar, coreApiJars, javaFeature): StaticVerifyResult` | **K-1.** 모든 외부 참조를 플러그인 → API jar 들 → JDK 순으로 해석해 NoClassDefFoundError / NoSuchMethodError / NoSuchFieldError 예측 |
| `ShadeConflict.kt` | `detectShadeConflicts(jars): List<ShadeConflict>` | **K-2.** 여러 jar 에 같은 클래스 경로가 있으면 보고. relocate 된 것은 제외 |
| `Observe.kt` | `attachJmx` / `readPlayerData` / `readLevelInfo` | K-3 / K-4. Phase 1 은 시그니처만 (`TODO("Phase 2")`) |

## StaticVerify — 무엇을 참조로 보나

`PluginScan` 이 클래스마다 모으는 것:

- 클래스 헤더: 슈퍼클래스, 인터페이스 (`HIERARCHY`)
- 명령: `NEW` / `CHECKCAST` / `INSTANCEOF` / `ANEWARRAY` / `MULTIANEWARRAY`, `GETFIELD`/`PUTFIELD`/`GETSTATIC`/`PUTSTATIC` (owner+name+desc), `INVOKE*` (owner+name+desc+itf), `INVOKEDYNAMIC` 의 부트스트랩 핸들과 인자 핸들 + 반환 타입(람다의 함수형 인터페이스) (`INSTRUCTION`)
- `LDC Type` (`LDC_TYPE`), try/catch 의 예외 타입 (`CATCH_TYPE`)
- 플러그인 자신의 필드/메서드 디스크립터, `throws`, 호출 대상 디스크립터의 타입 (`SIGNATURE`)
- 어노테이션 타입과 값 안의 enum/Class (`ANNOTATION`)
- `Class.forName` / `ClassLoader.loadClass` 를 호출하는 메서드 안의 클래스명 꼴 문자열 상수 (`REFLECTION`)

해석: 클래스 존재 → 멤버 존재. 멤버는 슈퍼클래스·인터페이스 계층 전체를 BFS 로 걷고(인터페이스는 `java/lang/Object` 도 포함, 생성자는 owner 만), 계층 어딘가를 모르면 **단정하지 않는다**(존재로 간주). `MethodHandle`/`VarHandle` 의 폴리모픽 시그니처는 이름만 비교. 해석 불가 owner 는 **클래스 1건**으로만 보고하고 그 멤버는 세지 않는다. 브리지 메서드, `module-info`, `package-info` 는 건너뛴다.

## 경고 vs 에러 규칙 (CLAUDE.md 불변식 16)

미해결 참조 하나(owner 또는 owner+멤버 단위로 집계)에 대해 위에서부터 첫 규칙이 적용된다.

| 순서 | 조건 | 판정 | `WarnReason` |
|---|---|---|---|
| 1 | owner 가 `softdepend` 플러그인 소속 (이름 토큰 ↔ 패키지 세그먼트 휴리스틱 + 유명 플러그인 소표) | 경고 | `SOFTDEPEND` |
| 2 | owner 가 `depend` 플러그인 소속인데 그 패키지가 클래스패스에 아예 없음 | 경고 | `DEPEND_ABSENT` |
| 3 | owner 가 `plugin.yml` `libraries:` 좌표 소속 (groupId 접두사 또는 artifactId 토큰) | 경고 | `LIBRARY` |
| 4 | 가드 밖에서 `HIERARCHY` / `INSTRUCTION` / `LDC_TYPE` / `CATCH_TYPE` 문맥으로 참조 | **에러** | — |
| 5 | 위 문맥이지만 전부 가드 안 (핸들러가 `NoClassDefFoundError`, `ClassNotFoundException`, `NoSuchMethodError`, `NoSuchFieldError`, `NoSuchMethodException`, `NoSuchFieldException`, `ReflectiveOperationException`, `IncompatibleClassChangeError`, `LinkageError`, `Throwable`, `Exception`, `Error` 중 하나) | 경고 | `GUARDED_TRY` |
| 6 | `REFLECTION` 문맥만 | 경고 | `REFLECTION` |
| 7 | `SIGNATURE` 문맥만 (JVM 이 지연 로드) | 경고 | `SIGNATURE_ONLY` |
| 8 | `ANNOTATION` 문맥만이고 owner 가 `kotlin/`, `org/jetbrains/annotations/`, `lombok/`, `javax/annotation/`, `jakarta/annotation/`, `org/checkerframework/`, `com/google/errorprone/`, `org/intellij/lang/annotations/` | **무시** | — |
| 9 | `ANNOTATION` 문맥만 (그 외) | 경고 | `ANNOTATION_ONLY` |

`ok = analyzed && missingClasses·missingMethods·missingFields 가 전부 비어 있음`. 경고는 신호등을 내리지 않는다.

> 명세 초안은 "어노테이션 전용 참조는 목록 접두사만 무시, 나머지 전부 에러"였다. JVM 은 없는 어노테이션 클래스를 조용히 버리므로(리플렉션으로 읽기 전까지) 에러로 두면 오탐이 된다. 그래서 목록 밖 어노테이션은 `ANNOTATION_ONLY` **경고**로 낮췄다 — 불변식 16 의 취지에 맞춘 의도적 편차.

## ShadeConflict — 규칙

- 대상: `.class` 항목 중 `META-INF/` 밖, `module-info`/`package-info` 제외
- relocate 로 간주해 제외: 그 jar 의 메인 클래스 패키지 아래(`plugin.yml` `main`), 경로에 `/libs/`, `/lib/`, `/shaded/`, `/relocated/`, `/shadow/`
- 심각도: `WELL_KNOWN_LIBRARY_PREFIXES`(gson, guava, netty, kotlin, slf4j, jackson, snakeyaml, commons, okhttp3, okio, hikari, mysql, mariadb, adventure, bstats, jedis, httpclient, squareup)에 해당하면 **HIGH**, 아니면 **MEDIUM**, 모든 제공자의 바이트가 동일(sha-256)하면 **LOW**
- 해시는 중복된 항목만 계산한다 (jar 당 두 번 열되 필요한 항목만 읽음)

## 측정값 (테스트가 강제한다 — `./gradlew :core:jvm-analysis:test`)

테스트는 실제 플러그인 jar 를 쓰지 않는다 (재배포 불가, 불변식 5). ASM `ClassWriter` 로 **테스트 시점에 jar 를 생성**한다: 가짜 Bukkit API(`JavaPlugin`, `Bukkit`, `Server`, `CommandSender` ← `Player`, `Listener`, `ServicesManager`)와 그것을 참조하는 플러그인들.

| 항목 | 결과 (2026-09-23, JDK 25, Windows) |
|---|---|
| 알려진 호환 50건 (API·JDK·indy 문자열결합·람다·가드된 선택 참조·리플렉션 문자열·softdepend·시그니처 전용·어노테이션 전용을 무작위 조합) | **오탐 0/50 (0 %)** |
| 알려진 비호환 30건 (없는 클래스 / 메서드 / 필드 / 슈퍼클래스 / 인터페이스 / 잘못된 디스크립터 / 인터페이스 메서드를 무작위 클래스에 주입) | **미탐 0/30** |
| 300 클래스 × 20 참조 jar 1건 | cold 19 ms (스캔 포함), warm **2~5 ms** (목표 < 500 ms) |
| 테스트 수 | 41 (StaticVerify 15, ShadeConflict 6, Bytecode 5, JarMeta 5, PackMeta 5, CapabilityInfer 5) |

오탐률 0 % 는 **생성된 jar 기준**이다. 실제 플러그인 150개 × MC 12 버전에 대한 오탐률은 P2-b(verifier CI)에서 `static_verify_results` 로 다시 측정한다. 그때 5 % 를 넘으면 위 표의 규칙을 조정한다.

## 한계 — 알고 써야 하는 것

1. **JDK 클래스는 실행 중인 JDK 기준으로 해석한다.** `javaFeature` 보다 새 JDK 에서 돌리면 그 사이에 추가된 API(`List.reversed()` 등)는 "있음"으로 나온다(미탐). 제거된 API 는 `JdkRemovedApis` 표(javax.xml.bind 등)로 일부 보정한다. 정확히 하려면 verifier 를 목표 `javaFeature` 의 JDK 로 실행하거나 `ct.sym` 을 읽어야 한다 — Phase 2.
2. **존재만 검사한다.** 접근 제한(private/package), static ↔ instance 불일치(`IncompatibleClassChangeError`), 반환 타입 공변만 다른 경우는 잡지 않는다.
3. **실행 경로를 모른다.** 절대 실행되지 않는 분기의 참조도 에러다. 반대로 `SIGNATURE` 전용 참조는 실행 경로에 따라 터질 수 있지만 경고다.
4. **softdepend/libraries 소속 판정은 휴리스틱**이다. 플러그인 이름과 패키지가 전혀 다르면(`ProtocolLib` ↔ `com/comphenix/protocol`) `StaticVerify.kt` 의 `KNOWN_PLUGIN_PACKAGES` 소표에 있어야 한다. 소표에 없으면 그 참조는 에러가 될 수 있다 — CI 오탐 목록에서 채워 넣는다.
5. **리플렉션은 문자열 상수만 본다.** 문자열을 조립하거나 설정 파일에서 읽는 경우는 놓친다(그 경우 어차피 바이트코드 참조가 아니라 에러도 아니다).
6. **Kotlin 플러그인**: `kotlin/jvm/internal/Intrinsics` 등 stdlib 참조는 stdlib 를 shade 하지 않았고 `libraries:` 에도 없으면 에러다. Paper 는 Kotlin 을 번들하지 않으므로 이는 실제 결함이지만, Kotlin 제공 플러그인에 `depend` 하는 경우 `DEPEND_ABSENT` 경고로 내려간다.
7. **`mods.toml`** 은 `[[mods]]` 첫 항목과 `[[dependencies.<id>]]` 만 줄 단위로 읽는 최소 파서다. 인라인 테이블·배열·멀티라인은 note 로 남기고 건너뛴다. Forge 는 Phase 1 범위 밖.
8. **Multi-Release jar**: `Multi-Release: true` 매니페스트가 있을 때만 `versions/N` 을 `javaFeature` 이하 최대 N 으로 덮어쓴다 — 실제 `JarFile` 런타임 규칙과 같다.
9. `CapabilityInfer` 의 이름 목록(ItemsAdder, Vulcan 등)은 생태계 상식이지 수집 데이터가 아니다. 새 제공자는 코드에 추가하되, 이름만으로 애매하면 note 로 남기고 저장하지 않는다.

## 캐시

`JarIndexCache` 는 (절대경로, 크기, mtime) 키의 LRU(`maxEntries` 기본 64). 같은 파일의 `pluginScan` 이 있으면 `memberIndex` 는 그 안의 인덱스를 재사용한다. 파일 핸들은 파싱이 끝나면 닫히므로 분석 직후 jar 를 삭제해도 된다. `hits` / `misses` 로 효과를 볼 수 있다.
