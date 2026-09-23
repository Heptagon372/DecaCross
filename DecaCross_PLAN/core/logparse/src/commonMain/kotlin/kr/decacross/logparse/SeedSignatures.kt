package kr.decacross.logparse

import kotlinx.serialization.Serializable

/**
 * 해결책 액션. `type` 은 명세 §10 의 fixes 열에 있는 이름 그대로 (`ChangeJava`, `InstallDependency`, ...).
 * 실행은 app/daemon 의 몫이고 여기서는 데이터만 든다.
 *
 * @property capture 액션 인자로 넘길 캡처 이름 ([Signature.captures] 중 하나). 없으면 `null`
 */
@Serializable
public data class SeedFixAction(val type: String, val capture: String? = null)

/** 시드 fix 한 개. [labelKo] 는 UI 버튼 문구. */
@Serializable
public data class SeedFix(val labelKo: String, val action: SeedFixAction, val recommended: Boolean = false)

/**
 * `error-signatures.seed.json` 한 항목. DB `error_signatures` 행과 같은 모양.
 * 불변식: [fixes] 는 항상 1개 이상 (CLAUDE.md #7 — 대안 없는 에러는 벽이다).
 */
@Serializable
public data class SeedSignature(
    val key: String,
    val pattern: String,
    val category: String,
    val captures: List<String> = emptyList(),
    val priority: Int = 0,
    val titleKo: String,
    val causeKo: String,
    val fixes: List<SeedFix>,
) {
    /** 매칭용 [Signature] 로 변환 */
    public fun toSignature(): Signature = Signature(key, Regex(pattern), category, captures, priority)
}

/**
 * 명세 §10 의 시드 18종. `src/commonMain/resources/error-signatures.seed.json` 과 내용이 같아야 한다 (jvmTest 가 검증).
 * DB 연결 전·오프라인 폴백용이며, 실서비스에서는 DB 의 `error_signatures` 가 이 목록을 대체한다.
 *
 * 우선순위 설계: 한 스택트레이스에 여러 시그니처가 겹칠 때 더 구체적인 원인이 이기도록 둔다.
 * 예) `Could not load '...'`(50) + `Unsupported class file major version 65`(100) → Java 버전 문제로 진단.
 */
public object SeedSignatures {
    private const val JAVA = "java"
    private const val CONFIG = "config"
    private const val NETWORK = "network"
    private const val MEMORY = "memory"
    private const val PLUGIN = "plugin"
    private const val PERFORMANCE = "performance"
    private const val PACK = "pack"
    private const val RUNTIME = "runtime"
    private const val WORLD = "world"

    public val all: List<SeedSignature> =
        listOf(
            SeedSignature(
                key = "unsupported_class_version",
                pattern = """Unsupported class file major version (\d+)""",
                category = JAVA,
                captures = listOf("major"),
                priority = 100,
                titleKo = "Java 버전이 낮습니다",
                causeKo = "플러그인이 현재 서버 런타임보다 높은 Java 로 빌드되었습니다. 클래스 파일 메이저 버전 − 44 가 필요한 Java 입니다.",
                fixes = listOf(SeedFix("필요한 Java 로 런타임 전환", SeedFixAction("ChangeJava", "major"), recommended = true)),
            ),
            SeedSignature(
                key = "unsupported_class_error",
                pattern = """java\.lang\.UnsupportedClassVersionError(?:.*?class file version (\d+))?""",
                category = JAVA,
                captures = listOf("major"),
                priority = 90,
                titleKo = "Java 버전이 낮습니다",
                causeKo = "JVM 이 인식하지 못하는 상위 버전의 클래스 파일입니다. 서버 런타임을 올려야 합니다.",
                fixes = listOf(SeedFix("필요한 Java 로 런타임 전환", SeedFixAction("ChangeJava", "major"), recommended = true)),
            ),
            SeedSignature(
                key = "eula_not_agreed",
                pattern = """You need to agree to the EULA""",
                category = CONFIG,
                priority = 100,
                titleKo = "EULA 에 동의하지 않았습니다",
                causeKo = "eula.txt 의 `eula=true` 가 아닙니다. 동의 전에는 서버가 기동을 거부합니다.",
                fixes = listOf(SeedFix("EULA 다시 보기 및 동의", SeedFixAction("ShowEulaDialog"), recommended = true)),
            ),
            SeedSignature(
                key = "port_in_use",
                pattern = """FAILED TO BIND TO PORT|Address already in use""",
                category = NETWORK,
                priority = 100,
                titleKo = "포트가 이미 사용 중입니다",
                causeKo = "다른 프로세스(대개 이미 떠 있는 서버)가 같은 포트를 점유하고 있습니다.",
                fixes =
                    listOf(
                        SeedFix("포트를 쓰는 프로세스 찾기", SeedFixAction("FindPortOwner")),
                        SeedFix("다른 포트로 변경", SeedFixAction("SuggestPort"), recommended = true),
                    ),
            ),
            SeedSignature(
                key = "heap_reserve_failed",
                pattern = """Could not reserve enough space for object heap""",
                category = MEMORY,
                priority = 100,
                titleKo = "힙 메모리를 확보하지 못했습니다",
                causeKo = "할당한 RAM(-Xmx) 이 가용 메모리보다 크거나, 32bit Java 를 쓰고 있습니다.",
                fixes = listOf(SeedFix("RAM 할당량 재계산", SeedFixAction("RecalcRam"), recommended = true)),
            ),
            SeedSignature(
                key = "oom_heap",
                pattern = """OutOfMemoryError: Java heap space""",
                category = MEMORY,
                priority = 100,
                titleKo = "메모리 부족 (Java heap space)",
                causeKo = "서버가 할당된 RAM 을 다 썼습니다. 플레이어·플러그인 규모에 비해 적거나, 플러그인 메모리 누수입니다.",
                fixes =
                    listOf(
                        SeedFix("RAM 할당량 늘리기", SeedFixAction("IncreaseRam"), recommended = true),
                        SeedFix("다음 OOM 때 힙 덤프 남기기", SeedFixAction("EnableHeapDump")),
                    ),
            ),
            SeedSignature(
                key = "unknown_dependency",
                pattern = """Unknown dependency:? ([\w\-]+)""",
                category = PLUGIN,
                captures = listOf("dependency"),
                priority = 80,
                titleKo = "의존 플러그인이 없습니다",
                causeKo = "plugin.yml 의 depend 에 적힌 플러그인이 설치되어 있지 않습니다.",
                fixes = listOf(SeedFix("누락된 플러그인 설치", SeedFixAction("InstallDependency", "dependency"), recommended = true)),
            ),
            SeedSignature(
                key = "plugin_load_failed",
                pattern = """Could not load (?:plugin )?'([^']+)'""",
                category = PLUGIN,
                captures = listOf("file"),
                priority = 50,
                titleKo = "플러그인을 불러오지 못했습니다",
                causeKo = "jar 가 손상되었거나 이 서버 버전과 맞지 않습니다. 아래 원인 줄(Caused by)을 확인하세요.",
                fixes = listOf(SeedFix("호환성 엔진으로 재질의", SeedFixAction("ReResolve", "file"), recommended = true)),
            ),
            SeedSignature(
                key = "nms_missing",
                pattern = """NoClassDefFoundError: net/minecraft/server/(v[\w_]+)""",
                category = PLUGIN,
                captures = listOf("nmsVersion"),
                priority = 85,
                titleKo = "플러그인의 NMS 버전이 다릅니다",
                causeKo = "플러그인이 특정 MC 버전의 내부 클래스(NMS)에 직접 의존합니다. 서버 버전과 맞지 않습니다.",
                fixes = listOf(SeedFix("서버 버전에 맞는 빌드로 교체", SeedFixAction("ReplaceWithMatchingBuild", "nmsVersion"), recommended = true)),
            ),
            SeedSignature(
                key = "plugin_enable_failed",
                pattern = """Error occurred while enabling (\S+)(?: v(\S+))?""",
                category = PLUGIN,
                captures = listOf("plugin", "version"),
                priority = 60,
                titleKo = "플러그인 활성화 중 오류",
                causeKo = "플러그인의 onEnable 에서 예외가 났습니다. 설정 오류·의존성 불일치가 흔한 원인입니다.",
                fixes =
                    listOf(
                        SeedFix("해당 플러그인 비활성화 후 재기동", SeedFixAction("DisablePlugin", "plugin")),
                        SeedFix("호환성 엔진으로 재질의", SeedFixAction("ReResolve", "plugin"), recommended = true),
                    ),
            ),
            SeedSignature(
                key = "watchdog",
                pattern = """Watchdog.*(thread dump|stopped responding|not responded)""",
                category = PERFORMANCE,
                priority = 70,
                titleKo = "서버가 응답하지 않습니다 (Watchdog)",
                causeKo = "메인 스레드가 멈췄습니다. 스레드 덤프에 나오는 플러그인 패키지가 범인일 가능성이 높습니다.",
                fixes = listOf(SeedFix("스택에서 원인 플러그인 지목", SeedFixAction("BlamePluginFromStack"), recommended = true)),
            ),
            SeedSignature(
                key = "cant_keep_up",
                pattern = """Can't keep up!.*?(\d+)ms""",
                category = PERFORMANCE,
                captures = listOf("ms"),
                priority = 40,
                titleKo = "서버 TPS 저하",
                causeKo = "틱 처리가 밀리고 있습니다. 엔티티·청크 과다 또는 무거운 플러그인이 원인입니다.",
                fixes = listOf(SeedFix("프로파일러로 원인 찾기", SeedFixAction("SuggestJmxProfile"), recommended = true)),
            ),
            SeedSignature(
                key = "invalid_pack_format",
                pattern = """(?i)(Invalid|Unsupported) pack.?format""",
                category = PACK,
                priority = 80,
                titleKo = "팩 포맷이 맞지 않습니다",
                causeKo = "리소스팩/데이터팩의 pack_format 이 이 MC 버전과 다릅니다.",
                fixes = listOf(SeedFix("pack_format 리넘버링 제안", SeedFixAction("SuggestRenumber"), recommended = true)),
            ),
            SeedSignature(
                key = "library_download_failed",
                pattern = """(Failed to download|ConnectException).*librar""",
                category = NETWORK,
                priority = 70,
                titleKo = "라이브러리 다운로드 실패",
                causeKo = "서버가 기동에 필요한 라이브러리를 내려받지 못했습니다. 네트워크·프록시·방화벽 문제입니다.",
                fixes = listOf(SeedFix("미러로 재시도", SeedFixAction("RetryWithMirror"), recommended = true)),
            ),
            SeedSignature(
                key = "incompatible_server_version",
                pattern = """This server is running .* which is not compatible""",
                category = PLUGIN,
                priority = 70,
                titleKo = "서버 버전과 플러그인 API 가 맞지 않습니다",
                causeKo = "플러그인이 요구하는 API 버전과 코어 버전이 다릅니다.",
                fixes = listOf(SeedFix("호환성 엔진으로 재질의", SeedFixAction("ReResolve"), recommended = true)),
            ),
            SeedSignature(
                key = "java_not_found",
                pattern = """'java' is not recognized|java: command not found""",
                category = RUNTIME,
                priority = 100,
                titleKo = "Java 를 찾지 못했습니다",
                causeKo = "start.bat 이 가리키는 Java 실행 파일이 없습니다. 런타임이 삭제되었거나 경로가 바뀌었습니다.",
                fixes = listOf(SeedFix("런타임 복구", SeedFixAction("RepairRuntime"), recommended = true)),
            ),
            SeedSignature(
                key = "world_version_mismatch",
                pattern = """(?i)(world|level).*(newer|older) version""",
                category = WORLD,
                priority = 80,
                titleKo = "월드 버전이 서버와 다릅니다",
                causeKo = "월드가 다른 MC 버전으로 저장되었습니다. 상위 버전 월드는 하위 서버에서 열 수 없습니다.",
                fixes =
                    listOf(
                        SeedFix("스냅샷에서 월드 복원", SeedFixAction("RestoreSnapshot")),
                        SeedFix("서버 MC 버전 올리기", SeedFixAction("BumpMc"), recommended = true),
                    ),
            ),
            SeedSignature(
                key = "linkage_error",
                pattern = """(NoSuchMethodError|IncompatibleClassChangeError)(?:: (.+))?""",
                category = PLUGIN,
                captures = listOf("kind", "member"),
                priority = 75,
                titleKo = "라이브러리 충돌 (링크 오류)",
                causeKo = "플러그인이 기대하는 메서드/클래스 형태가 실제 로드된 것과 다릅니다. 대개 shaded 라이브러리 버전 충돌입니다.",
                fixes = listOf(SeedFix("shaded 라이브러리 충돌 보기", SeedFixAction("ShowShadeConflict", "member"), recommended = true)),
            ),
        )

    /** 매칭용으로 컴파일된 목록 */
    public val signatures: List<Signature> by lazy { all.map { it.toSignature() } }

    /** 키로 찾기 */
    public fun byKey(key: String): SeedSignature? = all.firstOrNull { it.key == key }
}
