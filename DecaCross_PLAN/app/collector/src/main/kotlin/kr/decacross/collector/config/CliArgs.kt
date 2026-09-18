package kr.decacross.collector.config

import kr.decacross.collector.core.RunMode
import kr.decacross.collector.core.SourceId
import kr.decacross.collector.store.DbTarget
import java.io.ByteArrayOutputStream
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/** CLI 해석 결과. 예외 대신 sealed. */
sealed interface CliParse {
    data class Ok(val options: CliOptions) : CliParse

    /** 사용자에게 그대로 보여줄 한국어 메시지. */
    data class Error(val message: String) : CliParse

    data object Help : CliParse
}

/**
 * 해석이 끝난 실행 옵션.
 *
 * # 불변식
 * - [settings] 의 `tempDir` 와 [devDbDir] 는 OneDrive 동기화 폴더가 아니다 (`--allow-onedrive-temp` 가 없으면 CLI 가 거부).
 * - [sanityOnly] 면 [sanity] 도 true 다.
 */
data class CliOptions(
    val mode: RunMode,
    val sources: Set<SourceId>,
    val db: DbTarget,
    val migrate: Boolean,
    val check: Boolean,
    val sanity: Boolean,
    val sanityOnly: Boolean,
    val sanityExpect: Path?,
    val settings: CollectorSettings,
    val devDbDir: Path,
    val devDbPort: Int,
)

/** `--help` 출력. */
val CLI_USAGE: String =
    """
    |사용법: collector (--once | --loop) [옵션...]
    |
    |실행 모드 (정확히 하나)
    |  --once                       모든 소스를 한 번 수집하고 종료
    |  --loop                       한 번 수집 후 소스별 주기로 계속 수집 (Ctrl+C 로 종료)
    |
    |소스·DB
    |  --sources=a,b                mojang,paper,folia,purpur,adoptium,modrinth,hangar (기본: 전부)
    |  --db-url=URL                 jdbc:postgresql://… 또는 postgresql://user:pass@host:port/db?…
    |                               (기본: 환경변수 DECACROSS_DB_URL, 없으면 임베디드 개발 DB)
    |  --db-user=, --db-password=   (기본: DECACROSS_DB_USER / DECACROSS_DB_PASSWORD)
    |  --dev-db-dir=DIR             임베디드 개발 DB 위치 (기본: %LOCALAPPDATA%\DecaCross\devdb)
    |  --dev-db-port=N              임베디드 개발 DB 포트 (기본: 54329)
    |  --migrate                    외부 DB 에 마이그레이션 적용 (개발 DB 는 항상 적용)
    |  --seed-ordinals              빈 운영 DB 에 최초 서수 시딩 허용 (되돌릴 수 없다)
    |  환경변수 DECACROSS_REQUIRE_DB_URL=true 면 임베디드 DB 로 대체하지 않는다.
    |
    |  Supabase: 세션 풀러(포트 5432) + sslmode=require 를 쓴다.
    |            트랜잭션 풀러(6543)는 prepareThreshold=0 이 필요하다 (미검증).
    |
    |임시 디렉터리
    |  --temp-dir=DIR               jar 임시 저장 위치 (기본: %LOCALAPPDATA%\DecaCross\collector-tmp)
    |  --allow-onedrive-temp        OneDrive 아래 경로를 허용 (기본: 거부 — jar 가 클라우드로 올라간다)
    |
    |Mojang
    |  --mojang-snapshots=true|false        스냅샷 포함 (기본 true)
    |  --mojang-jarmeta=off|releases|all    jar 에서 pack format 읽기 (기본: --once releases / --loop all)
    |  --mojang-jarmeta-max=N               사이클당 최대 버전 수 (기본 120)
    |
    |Purpur (sha256 스트리밍 해시)
    |  --purpur-hash=off|latest|new|backfill (기본: --once latest / --loop new; backfill 은 명시할 때만)
    |  --purpur-latest-versions=N (6)  --purpur-max-jars=N (6)  --purpur-max-mb=N (512)  --purpur-max-mb-per-day=N (1024)
    |
    |콘텐츠 (Modrinth / Hangar)
    |  --modrinth-candidates=N (200)  --modrinth-top=N (100)  --hangar-top=N (100)  --content-versions=N (100)
    |  --content-analyze=N (1, 0 이면 jar 다운로드 없음)  --content-max-mb=N (700)
    |
    |기타
    |  --source-timeout-min=N        소스 하나의 최대 실행 시간(분) (기본 60)
    |  --quick                       jar 메타 끔 + Purpur 해시 끔 + 콘텐츠 분석 0 (뒤에 준 플래그가 이긴다)
    |  --check                       02 완료 기준 미달이면 종료 코드 3
    |  --sanity                      수집 후 DB 정합성 검사, FAIL 이면 종료 코드 5
    |  --sanity-only                 수집 없이 정합성 검사만 (읽기 전용)
    |  --sanity-expect=FILE          S14 기대값 JSON (예: src/test/resources/c1/sanity-expect-2026-09-17.json)
    |  환경변수 DECACROSS_UA_CONTACT  User-Agent 연락처 교체
    |  --help                        이 도움말
    |
    |종료 코드: 0 정상 · 1 설정/DB 치명 · 2 필수 소스 실패 · 3 --check 미달 · 4 실행 락 보유 중 · 5 --sanity FAIL
    """.trimMargin()

private val VALUE_FLAGS = setOf(
    "sources", "db-url", "db-user", "db-password", "dev-db-dir", "dev-db-port", "temp-dir",
    "mojang-snapshots", "mojang-jarmeta", "mojang-jarmeta-max",
    "purpur-hash", "purpur-latest-versions", "purpur-max-jars", "purpur-max-mb", "purpur-max-mb-per-day",
    "modrinth-candidates", "modrinth-top", "hangar-top", "content-versions", "content-analyze", "content-max-mb",
    "source-timeout-min", "sanity-expect",
)

private val BARE_FLAGS = setOf(
    "once", "loop", "help", "allow-onedrive-temp", "migrate", "seed-ordinals", "quick", "check", "sanity", "sanity-only",
)

/** 해석 중 오류. 파일 내부에서만 쓰고 [CliParse.Error] 로 바꾼다. */
private class CliFailure(message: String) : RuntimeException(message)

private fun fail(message: String): Nothing = throw CliFailure(message)

/**
 * 명령줄 인자 → [CliParse]. clikt 없이 `--key=value` 와 맨 `--flag` 만 받는다.
 *
 * # 불변식
 * - 예외를 던지지 않는다. 모든 오류는 [CliParse.Error].
 * - 모드별 기본값(--once / --loop)을 먼저 깔고, 플래그를 **주어진 순서대로** 적용한다 (`--quick` 뒤의 명시 플래그가 이긴다).
 * - `--purpur-hash=backfill` 은 절대 기본값이 되지 않는다.
 *
 * @param env 환경변수 (테스트가 주입한다)
 * @param os `os.name` (Windows 기본 경로 판단용)
 */
fun parseCli(args: Array<String>, env: Map<String, String>, os: String = System.getProperty("os.name")): CliParse =
    try {
        parseCliOrThrow(args, env, os)
    } catch (e: CliFailure) {
        CliParse.Error(e.message ?: "잘못된 인자")
    }

private fun parseCliOrThrow(args: Array<String>, env: Map<String, String>, os: String): CliParse {
    val parsed = args.map { raw ->
        if (!raw.startsWith("--") || raw.length == 2) fail("알 수 없는 인자: '$raw' (--help 참고)")
        val body = raw.substring(2)
        val eq = body.indexOf('=')
        val key = if (eq < 0) body else body.substring(0, eq)
        val value = if (eq < 0) null else body.substring(eq + 1)
        when {
            key in BARE_FLAGS -> if (value != null) fail("--$key 는 값을 받지 않는다: '$raw'")
            key in VALUE_FLAGS -> if (value == null) fail("--$key 에는 값이 필요하다 (--$key=…)")
            else -> fail("알 수 없는 플래그: --$key (--help 참고)")
        }
        key to value
    }
    if (parsed.any { it.first == "help" }) return CliParse.Help

    val once = parsed.any { it.first == "once" }
    val loop = parsed.any { it.first == "loop" }
    if (once == loop) fail("--once 와 --loop 중 정확히 하나가 필요하다")
    val mode = if (once) RunMode.ONCE else RunMode.LOOP

    val windows = os.lowercase().startsWith("windows")
    val b = OptionsBuilder(mode, defaultTempDir(env, windows), defaultDevDbDir(env, windows))
    for ((key, value) in parsed) b.apply(key, value)

    val ua = env["DECACROSS_UA_CONTACT"]?.let { contact ->
        try {
            buildUserAgent(contact)
        } catch (e: IllegalArgumentException) {
            fail("DECACROSS_UA_CONTACT 가 잘못됐다: ${e.message}")
        }
    }

    if (!b.allowOneDrive) {
        if (isUnderOneDrive(b.tempDir, env)) fail("임시 디렉터리가 OneDrive 아래다: ${b.tempDir} (--allow-onedrive-temp 로만 허용)")
        if (isUnderOneDrive(b.devDbDir, env)) fail("개발 DB 디렉터리가 OneDrive 아래다: ${b.devDbDir} (--allow-onedrive-temp 로만 허용)")
    }

    val db = resolveDb(b, env)
    val settings = b.settings(ua)
    return CliParse.Ok(
        CliOptions(
            mode = mode,
            sources = b.sources,
            db = db,
            migrate = b.migrate,
            check = b.check,
            sanity = b.sanity || b.sanityOnly,
            sanityOnly = b.sanityOnly,
            sanityExpect = b.sanityExpect,
            settings = settings,
            devDbDir = b.devDbDir,
            devDbPort = b.devDbPort,
        ),
    )
}

/** 플래그를 순서대로 적용하는 가변 상태. */
private class OptionsBuilder(val mode: RunMode, var tempDir: Path, var devDbDir: Path) {
    var sources: Set<SourceId> = SourceId.entries.toSet()
    var dbUrl: String? = null
    var dbUser: String? = null
    var dbPassword: String? = null
    var devDbPort: Int = DbTarget.DEFAULT_DEV_DB_PORT
    var allowOneDrive = false
    var migrate = false
    var seedOrdinals = false
    var check = false
    var sanity = false
    var sanityOnly = false
    var sanityExpect: Path? = null

    private val defaults = CollectorSettings(tempDir = tempDir)
    var mojang: MojangSettings = defaults.mojang.copy(jarMeta = if (mode == RunMode.ONCE) JarMetaMode.RELEASES else JarMetaMode.ALL)
    var purpur: PurpurSettings = defaults.purpur.copy(hashMode = if (mode == RunMode.ONCE) PurpurHashMode.LATEST else PurpurHashMode.NEW)
    var content: ContentSettings = defaults.content
    var sourceTimeout = defaults.sourceTimeout

    fun apply(key: String, value: String?) {
        val v = value.orEmpty()
        when (key) {
            "once", "loop", "help" -> Unit

            "sources" -> sources = parseSources(v)

            "db-url" -> dbUrl = v.trim().ifEmpty { fail("--db-url 이 비었다") }

            "db-user" -> dbUser = v

            "db-password" -> dbPassword = v

            "dev-db-dir" -> devDbDir = parsePath(key, v)

            "dev-db-port" -> devDbPort = parseInt(key, v, 1..65_535)

            "temp-dir" -> tempDir = parsePath(key, v)

            "allow-onedrive-temp" -> allowOneDrive = true

            "migrate" -> migrate = true

            "seed-ordinals" -> seedOrdinals = true

            "check" -> check = true

            "sanity" -> sanity = true

            "sanity-only" -> sanityOnly = true

            "sanity-expect" -> sanityExpect = parsePath(key, v)

            "mojang-snapshots" -> mojang = mojang.copy(includeSnapshots = parseBool(key, v))

            "mojang-jarmeta" -> mojang = mojang.copy(jarMeta = parseEnum(key, v, JarMetaMode.entries))

            "mojang-jarmeta-max" -> mojang = mojang.copy(maxJarMetaPerCycle = parseInt(key, v, 0..Int.MAX_VALUE))

            "purpur-hash" -> purpur = purpur.copy(hashMode = parseEnum(key, v, PurpurHashMode.entries))

            "purpur-latest-versions" -> purpur = purpur.copy(latestVersions = parseInt(key, v, 0..Int.MAX_VALUE))

            "purpur-max-jars" -> purpur = purpur.copy(maxJarsPerCycle = parseInt(key, v, 0..Int.MAX_VALUE))

            "purpur-max-mb" -> purpur = purpur.copy(maxBytesPerCycle = parseMib(key, v))

            "purpur-max-mb-per-day" -> purpur = purpur.copy(maxBytesPerDay = parseMib(key, v))

            "modrinth-candidates" -> content = content.copy(modrinthCandidates = parseInt(key, v, 0..Int.MAX_VALUE))

            "modrinth-top" -> content = content.copy(modrinthTop = parseInt(key, v, 0..Int.MAX_VALUE))

            "hangar-top" -> content = content.copy(hangarTop = parseInt(key, v, 0..Int.MAX_VALUE))

            "content-versions" -> content = content.copy(versionsPerProject = parseInt(key, v, 0..Int.MAX_VALUE))

            "content-analyze" -> content = content.copy(analyzePerProject = parseInt(key, v, 0..Int.MAX_VALUE))

            "content-max-mb" -> content = content.copy(maxDownloadBytesPerCycle = parseMib(key, v))

            "source-timeout-min" -> sourceTimeout = parseInt(key, v, 1..Int.MAX_VALUE).minutes

            "quick" -> {
                mojang = mojang.copy(jarMeta = JarMetaMode.OFF)
                purpur = purpur.copy(hashMode = PurpurHashMode.OFF)
                content = content.copy(analyzePerProject = 0)
            }

            else -> fail("알 수 없는 플래그: --$key")
        }
    }

    fun settings(userAgent: String?): CollectorSettings {
        val base = CollectorSettings(
            tempDir = tempDir,
            sourceTimeout = sourceTimeout,
            mojang = mojang.copy(allowInitialSeed = seedOrdinals),
            purpur = purpur,
            content = content,
        )
        return if (userAgent == null) base else base.copy(userAgent = userAgent)
    }
}

private fun parseSources(v: String): Set<SourceId> {
    val keys = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (keys.isEmpty()) fail("--sources 가 비었다")
    return keys.mapTo(LinkedHashSet()) { k ->
        SourceId.fromKey(k) ?: fail("알 수 없는 소스: '$k' (가능: ${SourceId.entries.joinToString(",") { it.key }})")
    }
}

private fun parseInt(key: String, v: String, range: IntRange): Int {
    val n = v.trim().toIntOrNull() ?: fail("--$key 는 정수여야 한다: '$v'")
    if (n !in range) fail("--$key 범위 밖: $n")
    return n
}

private fun parseMib(key: String, v: String): Long {
    val n = v.trim().toLongOrNull() ?: fail("--$key 는 정수(MB)여야 한다: '$v'")
    if (n < 0 || n > Long.MAX_VALUE / MIB) fail("--$key 범위 밖: $n")
    return n * MIB
}

private fun parseBool(key: String, v: String): Boolean = when (v.trim().lowercase()) {
    "true", "yes", "on", "1" -> true
    "false", "no", "off", "0" -> false
    else -> fail("--$key 는 true|false 여야 한다: '$v'")
}

private fun <E : Enum<E>> parseEnum(key: String, v: String, entries: List<E>): E =
    entries.firstOrNull { it.name.equals(v.trim(), ignoreCase = true) }
        ?: fail("--$key 값이 잘못됐다: '$v' (가능: ${entries.joinToString("|") { it.name.lowercase() }})")

private fun parsePath(key: String, v: String): Path {
    if (v.isBlank()) fail("--$key 경로가 비었다")
    return try {
        Path.of(v.trim())
    } catch (e: InvalidPathException) {
        fail("--$key 경로가 잘못됐다: ${e.message}")
    }
}

private fun resolveDb(b: OptionsBuilder, env: Map<String, String>): DbTarget {
    val raw = b.dbUrl ?: env["DECACROSS_DB_URL"]?.trim()?.takeIf { it.isNotEmpty() }
    if (raw == null) {
        if (env["DECACROSS_REQUIRE_DB_URL"]?.trim().equals("true", ignoreCase = true)) fail("DB URL 필요 (DECACROSS_REQUIRE_DB_URL)")
        return DbTarget.EmbeddedDev(b.devDbDir, b.devDbPort)
    }
    val fromUrl = toJdbcUrl(raw)
    val user = b.dbUser ?: env["DECACROSS_DB_USER"] ?: fromUrl.user
    val password = b.dbPassword ?: env["DECACROSS_DB_PASSWORD"] ?: fromUrl.password
    return DbTarget.Url(fromUrl.jdbcUrl, user, password)
}

/**
 * `jdbc:postgresql://…` 는 그대로, `postgresql://user:pass@host:port/db?params` / `postgres://…` 는
 * `jdbc:postgresql://host:port/db?params` 로 바꾸고 user/pass 를 퍼센트 디코딩해 따로 돌려준다.
 */
private fun toJdbcUrl(raw: String): DbTarget.Url {
    if (raw.startsWith("jdbc:postgresql://")) return DbTarget.Url(raw, null, null)
    val scheme = listOf("postgresql://", "postgres://").firstOrNull { raw.startsWith(it, ignoreCase = true) }
        ?: fail("DB URL 형식이 잘못됐다 (jdbc:postgresql:// 또는 postgresql:// 만 지원)")
    val rest = raw.substring(scheme.length)
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd)
    val tail = rest.substring(authorityEnd)
    val at = authority.lastIndexOf('@')
    val hostPort = if (at < 0) authority else authority.substring(at + 1)
    if (hostPort.isEmpty()) fail("DB URL 에 호스트가 없다")
    var user: String? = null
    var password: String? = null
    if (at >= 0) {
        val userInfo = authority.substring(0, at)
        val colon = userInfo.indexOf(':')
        user = percentDecode(if (colon < 0) userInfo else userInfo.substring(0, colon)).ifEmpty { null }
        password = if (colon < 0) null else percentDecode(userInfo.substring(colon + 1))
    }
    return DbTarget.Url("jdbc:postgresql://$hostPort$tail", user, password)
}

/** URI userinfo 퍼센트 디코딩 (UTF-8). `+` 는 공백으로 바꾸지 않는다 (URLDecoder 와 다름). */
private fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < s.length) {
        val next = s.indexOf('%', i)
        if (next < 0) {
            out.write(s.substring(i).encodeToByteArray())
            break
        }
        if (next > i) out.write(s.substring(i, next).encodeToByteArray())
        val hi = if (next + 1 < s.length) Character.digit(s[next + 1], 16) else -1
        val lo = if (next + 2 < s.length) Character.digit(s[next + 2], 16) else -1
        if (hi < 0 || lo < 0) fail("DB URL 퍼센트 인코딩이 잘못됐다")
        out.write(hi * 16 + lo)
        i = next + 3
    }
    return out.toByteArray().decodeToString()
}

private fun defaultTempDir(env: Map<String, String>, windows: Boolean): Path =
    if (windows) {
        localAppData(env).resolve("DecaCross").resolve("collector-tmp")
    } else {
        val xdg = env["XDG_CACHE_HOME"]?.takeIf { it.isNotBlank() }
        (if (xdg != null) Path.of(xdg) else home(env).resolve(".cache")).resolve("decacross").resolve("collector-tmp")
    }

private fun defaultDevDbDir(env: Map<String, String>, windows: Boolean): Path =
    if (windows) {
        localAppData(env).resolve("DecaCross").resolve("devdb")
    } else {
        val xdg = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }
        (if (xdg != null) Path.of(xdg) else home(env).resolve(".local").resolve("share")).resolve("decacross").resolve("devdb")
    }

private fun localAppData(env: Map<String, String>): Path =
    env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: home(env).resolve("AppData").resolve("Local")

private fun home(env: Map<String, String>): Path =
    Path.of(env["HOME"]?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"))

/**
 * OneDrive 동기화 폴더 판정: 경로 문자열에 `OneDrive` 가 있거나(대소문자 무시),
 * 환경변수 `OneDrive` / `OneDriveConsumer` / `OneDriveCommercial` 디렉터리 아래다.
 */
fun isUnderOneDrive(path: Path, env: Map<String, String>): Boolean {
    val p = normalizedText(path)
    if (p.contains("onedrive")) return true
    return listOf("OneDrive", "OneDriveConsumer", "OneDriveCommercial").any { name ->
        val root = env[name]?.takeIf { it.isNotBlank() } ?: return@any false
        val r = try {
            normalizedText(Path.of(root)).trimEnd('/')
        } catch (e: InvalidPathException) {
            return@any false
        }
        r.isNotEmpty() && (p == r || p.startsWith("$r/"))
    }
}

private fun normalizedText(path: Path): String = path.toAbsolutePath().normalize().toString().replace('\\', '/').lowercase()
