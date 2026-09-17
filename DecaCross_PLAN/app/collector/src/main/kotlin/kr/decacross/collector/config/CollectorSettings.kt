package kr.decacross.collector.config

import kr.decacross.compat.model.Arch
import kr.decacross.compat.model.ImageType
import kr.decacross.compat.model.Os
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** User-Agent 에 들어가는 수집기 버전. */
const val COLLECTOR_VERSION: String = "0.1"

/**
 * 기본 연락처. ★ 이메일을 코드에 넣지 마라.
 * 운영자는 환경변수 `DECACROSS_UA_CONTACT` 로 교체한다 (CLAUDE.md 불변식 17: 식별 가능한 UA + 연락처).
 */
const val DEFAULT_UA_CONTACT: String = "+https://github.com/Heptagon372/DecaCross"

const val MIB: Long = 1024L * 1024L

/**
 * `DecaCross/0.1 (+https://github.com/Heptagon372/DecaCross)` 형태의 UA.
 *
 * # 불변식
 * - 연락처가 비어 있으면 만들지 않는다 (generic UA 금지).
 */
fun buildUserAgent(contact: String = DEFAULT_UA_CONTACT): String {
    val c = contact.trim()
    require(c.isNotEmpty() && c.none { it == '(' || it == ')' || it.isISOControl() }) {
        "User-Agent 연락처가 비었거나 잘못됐다: '$contact' — generic UA 는 PaperMC 정책 위반"
    }
    return "DecaCross/$COLLECTOR_VERSION ($c)"
}

/** 호스트별 요청 간격·동시성. */
data class HostPolicy(val minIntervalMs: Long, val maxConcurrent: Int) {
    init {
        require(minIntervalMs >= 0 && maxConcurrent >= 1) { "잘못된 HostPolicy: $this" }
    }
}

/** 호스트 예의(politeness) 기본표. 근거: 연구 보고서(각 API 문서의 한도·관측값). */
val DEFAULT_HOST_POLICIES: Map<String, HostPolicy> = mapOf(
    "piston-meta.mojang.com" to HostPolicy(minIntervalMs = 100, maxConcurrent = 4),
    "launchermeta.mojang.com" to HostPolicy(minIntervalMs = 100, maxConcurrent = 4),
    "piston-data.mojang.com" to HostPolicy(minIntervalMs = 50, maxConcurrent = 4),
    "launcher.mojang.com" to HostPolicy(minIntervalMs = 50, maxConcurrent = 4),
    "fill.papermc.io" to HostPolicy(minIntervalMs = 500, maxConcurrent = 1),
    "api.purpurmc.org" to HostPolicy(minIntervalMs = 1_000, maxConcurrent = 1),
    "api.adoptium.net" to HostPolicy(minIntervalMs = 1_000, maxConcurrent = 1),
    "api.modrinth.com" to HostPolicy(minIntervalMs = 250, maxConcurrent = 1),
    "cdn.modrinth.com" to HostPolicy(minIntervalMs = 0, maxConcurrent = 2),
    "hangar.papermc.io" to HostPolicy(minIntervalMs = 350, maxConcurrent = 1),
    "hangarcdn.papermc.io" to HostPolicy(minIntervalMs = 0, maxConcurrent = 2),
)

/** HTTP 계층 설정 (WP-C1 KtorHttp 가 해석한다). */
data class HttpSettings(
    val connectTimeoutMs: Long = 15_000,
    val socketTimeoutMs: Long = 60_000,
    /** 429 / 500 / 502 / 503 / 504 / IOException 재시도 횟수. 그 밖의 4xx 는 재시도하지 않는다. */
    val maxRetries: Int = 4,
    /** Retry-After 포함 재시도 지연 상한. */
    val maxRetryDelayMs: Long = 300_000,
    /** 스트리밍 다운로드는 본문 도중 실패를 플러그인이 재시도하지 않으므로 자체 재시도 횟수. */
    val downloadAttempts: Int = 3,
    /** 호스트별 연속 실패가 이만큼이면 [circuitOpenMs] 동안 즉시 실패시킨다. */
    val circuitBreakerFailures: Int = 5,
    val circuitOpenMs: Long = 300_000,
    val hostPolicies: Map<String, HostPolicy> = DEFAULT_HOST_POLICIES,
    val defaultHostPolicy: HostPolicy = HostPolicy(minIntervalMs = 1_000, maxConcurrent = 1),
)

/** client/server jar 에서 pack_format·protocol 을 읽는 범위. */
enum class JarMetaMode { OFF, RELEASES, ALL }

data class MojangSettings(
    val manifestUrl: String = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
    val includeSnapshots: Boolean = true,
    /**
     * 빈 mc_versions 에 최초 서수 시딩을 허용하는가. 개발 DB 면 Runner 가 true 로 둔다.
     * 운영 DB 는 `--seed-ordinals` 를 명시해야 한다 (서수는 한 번 발급하면 되돌릴 수 없다).
     */
    val allowInitialSeed: Boolean = false,
    val jarMeta: JarMetaMode = JarMetaMode.RELEASES,
    /** 한 사이클에서 jar 메타를 읽을 최대 버전 수 (최신 서수부터). */
    val maxJarMetaPerCycle: Int = 120,
    /** 중앙 디렉터리가 이보다 큰 jar 는 읽지 않는다. */
    val maxCentralDirectoryBytes: Int = 4 * 1024 * 1024,
    /** Range 로 먼저 받는 꼬리 크기 (EOCD + 번들러 jar 중앙 디렉터리가 들어갈 크기). */
    val tailBytes: Int = 64 * 1024 + 22,
    val versionJsonConcurrency: Int = 4,
)

data class FillSettings(
    val baseUrl: String = "https://fill.papermc.io",
)

/**
 * Purpur 는 sha256 을 주지 않으므로 jar 를 스트리밍 해시한다 (디스크에 쓰지 않음).
 *
 * # 불변식
 * - 과거 빌드 전체 백필은 [BACKFILL] 을 **명시**했을 때만 한다 (자원봉사 운영 API — 41 버전·2,600+ 빌드·100 GB+).
 */
enum class PurpurHashMode {
    /** 해시하지 않음 → Purpur 행 없음 */
    OFF,

    /** 최신 [PurpurSettings.latestVersions] 개 MC 버전의 최신 SUCCESS 빌드만 (예산 내). `--once` 기본 */
    LATEST,

    /**
     * 최신 [PurpurSettings.latestVersions] 개 MC 버전에서, DB 에 있는 최고 빌드 번호보다 **새로운** SUCCESS 빌드 전부
     * (DB 에 그 버전 빌드가 없으면 [LATEST] 와 같다). 백필하지 않는다. `--loop` 기본
     */
    NEW,

    /** DB 에 없는 SUCCESS 빌드 전부, 모든 MC 버전 (최신부터, 사이클·일일 예산 내). 명시적 opt-in 전용 */
    BACKFILL,
}

data class PurpurSettings(
    val baseUrl: String = "https://api.purpurmc.org",
    val hashMode: PurpurHashMode = PurpurHashMode.LATEST,
    /** LATEST/NEW 가 보는 MC 버전 수 (서수 내림차순). */
    val latestVersions: Int = 6,
    val maxJarsPerCycle: Int = 6,
    val maxBytesPerCycle: Long = 512L * MIB,
    /** UTC 하루 누적 해시 바이트 상한 (collector_state `purpur.budget.<yyyy-MM-dd>`). */
    val maxBytesPerDay: Long = 1024L * MIB,
    val maxJarBytes: Long = 128L * MIB,
)

data class AdoptiumSettings(
    val baseUrl: String = "https://api.adoptium.net",
    val oses: Set<Os> = Os.entries.toSet(),
    val arches: Set<Arch> = Arch.entries.toSet(),
    val imageTypes: Set<ImageType> = ImageType.entries.toSet(),
)

data class ContentSettings(
    val modrinthBaseUrl: String = "https://api.modrinth.com",
    val hangarBaseUrl: String = "https://hangar.papermc.io",
    /** Modrinth 검색 후보 수 (Bukkit 계열 다운로드 합으로 재정렬 전). */
    val modrinthCandidates: Int = 200,
    val modrinthTop: Int = 100,
    val hangarTop: Int = 100,
    /** 프로젝트당 메타데이터로 저장할 최신 버전 수. */
    val versionsPerProject: Int = 100,
    /** 프로젝트당 jar 를 받아 분석할 버전 수. 0 이면 다운로드 없음. */
    val analyzePerProject: Int = 1,
    val maxJarBytes: Long = 64L * MIB,
    val maxDownloadBytesPerCycle: Long = 700L * MIB,
    val downloadConcurrency: Int = 2,
)

/** 02 완료 판정 기준 (prompts/02_collector.md). */
data class CompletionThresholds(
    val mcVersions: Long = 200,
    val coreBuilds: Long = 500,
    val content: Long = 100,
)

/**
 * 수집기 전체 설정. CLI(WP-C1)가 만들고 모든 소스가 읽는다.
 *
 * # 불변식
 * - [tempDir] 는 OneDrive 동기화 폴더가 아니어야 한다 (jar 가 클라우드로 올라가면 라이선스 정책 위반).
 *   CLI 가 거부한다 (명시적 override 플래그가 있을 때만 허용).
 * - Runner 는 [tempDir] 아래 실행별 하위 디렉터리(`run-<pid>`)를 만들어 그 경로로 바꾼 설정을 소스에 넘긴다.
 */
data class CollectorSettings(
    val tempDir: Path,
    val userAgent: String = buildUserAgent(),
    /** 소스 하나의 최대 실행 시간 (--once). */
    val sourceTimeout: Duration = 60.minutes,
    val http: HttpSettings = HttpSettings(),
    val mojang: MojangSettings = MojangSettings(),
    val fill: FillSettings = FillSettings(),
    val purpur: PurpurSettings = PurpurSettings(),
    val adoptium: AdoptiumSettings = AdoptiumSettings(),
    val content: ContentSettings = ContentSettings(),
    val thresholds: CompletionThresholds = CompletionThresholds(),
)
