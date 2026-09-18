package kr.decacross.daemon.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kr.decacross.compat.model.Os
import kr.decacross.daemon.paths.hostEnvironment
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 서버에 필요한 Java (DB 의 `mc_versions.java_min / java_recommended`). */
data class JavaRequirement(val minFeature: Int, val recommendedFeature: Int)

/** 후보가 어디서 왔는가. 우선순위 순서. */
enum class JavaOrigin {
    /** `--java` (있으면 이것만 본다) */
    EXPLICIT,

    /** `JAVA_HOME/bin/java(.exe)` */
    JAVA_HOME,

    /** `PATH` 항목 순서 */
    PATH,

    /** 잘 알려진 설치 폴더 (AE-22, 예: `%ProgramFiles%\Java\*`) — critique windows #2 */
    INSTALLED,
}

/** 조사한 후보 하나 (실패 설명용으로 전부 보고한다). */
data class JavaCandidate(
    val path: Path,
    val origin: JavaOrigin,
    /** `java -version` 의 따옴표 안 문자열. 실행 실패면 null. */
    val versionString: String?,
    val feature: Int?,
    /** 쓸 수 없는 이유 (실행 실패, 사전 출시판, 버전 부족 등). null 이면 요구를 만족. */
    val problemKo: String?,
)

/**
 * 선택 결과.
 *
 * # 불변식
 * - [javaPath] 는 후보의 **절대경로 그대로**다 (`toRealPath` 는 중복 판정 키로만 쓴다 — `latest\jdk-25` 같은 링크를 풀어 두면
 *   JDK 업데이트 뒤 start.bat 이 깨진다, critique windows #9). 실제로 `-version` 이 성공했다.
 * - 런처 번들 런타임(명세 §8 `internalRoot/jre`, (05) jpackage 런타임) 아래의 Java 는 후보에서 제외한다 (불변식 9).
 */
data class JavaSelection(
    val javaPath: Path,
    val feature: Int,
    val versionString: String,
    val origin: JavaOrigin,
    /** 권장보다 높은 feature, `_JAVA_OPTIONS`/`JAVA_TOOL_OPTIONS` 설정됨 등. */
    val warningsKo: List<String>,
)

/** [JavaLocator.locate] 결과. */
sealed interface JavaLocateResult {
    /** 선택됨. [candidates] 는 조사한 전부 (안내용). */
    data class Found(val selection: JavaSelection, val candidates: List<JavaCandidate>) : JavaLocateResult

    /** 요구를 만족하는 후보 없음. */
    data class NotFound(val requirement: JavaRequirement, val candidates: List<JavaCandidate>) : JavaLocateResult
}

/** 서버용 Java 찾기. 03 은 [SystemJavaLocator], 04 는 자동 설치 런타임으로 대체한다. */
fun interface JavaLocator {
    /** @param explicit `--java` 로 받은 경로. 있으면 그것만 본다 (조용히 다른 Java 로 바꾸지 않는다). */
    suspend fun locate(requirement: JavaRequirement, explicit: Path?): JavaLocateResult
}

/** `java -version` 출력 얻기. 테스트는 고정 문자열. */
fun interface JavaVersionProbe {
    /** stdout+stderr 합친 텍스트 (ISO-8859-1 로 읽어도 되는 ASCII 줄). 실행 실패·10초 초과면 null. */
    suspend fun versionOutput(java: Path): String?
}

/** 해석된 버전. [preRelease] = 따옴표 안 문자열에 `-` 포함 (예: `22-ea`) → Paper 가 기동을 거부한다. */
data class ParsedJavaVersion(val raw: String, val feature: Int, val preRelease: Boolean)

/**
 * `java -version` 출력 해석 (research codebase-windows §4).
 * 모든 줄에서 `^(?:java|openjdk) version "([^"]+)"` 를 찾는다 (`JAVA_TOOL_OPTIONS` 가 있으면 첫 줄이 `Picked up …`).
 * feature: `1.x…` → x, 아니면 앞 정수. 못 찾으면 null.
 */
fun parseJavaVersionOutput(output: String): ParsedJavaVersion? {
    val raw = JAVA_VERSION_LINE.find(output)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
    // `1.8.0_392` 같은 옛 표기는 두 번째 마디가 feature, 그 밖에는 앞 정수 (`25.0.4.1`, `22-ea`, `17`)
    val featureText = if (raw.startsWith("1.")) raw.split('.').getOrNull(1).orEmpty() else raw
    val feature = LEADING_INT.find(featureText)?.value?.toIntOrNull() ?: return null
    return ParsedJavaVersion(raw = raw, feature = feature, preRelease = '-' in raw)
}

/** `java version "21.0.4"` / `openjdk version "21.0.4" 2024-07-16`. 앞에 `Picked up JAVA_TOOL_OPTIONS:` 줄이 올 수 있다. */
private val JAVA_VERSION_LINE = Regex("^(?:java|openjdk) version \"([^\"]+)\"", RegexOption.MULTILINE)

/** 앞쪽 정수. */
private val LEADING_INT = Regex("""^(\d+)""")

/** AE-22 Windows 벤더 설치 폴더 이름 (`%ProgramFiles%` 바로 아래). */
private val WINDOWS_JAVA_VENDOR_DIRS: List<String> =
    listOf("Java", "Eclipse Adoptium", "Microsoft", "Zulu", "Amazon Corretto", "BellSoft", "Semeru")

/** AE-22 Linux · macOS 설치 폴더. */
private const val LINUX_JVM_DIR = "/usr/lib/jvm"
private const val MAC_JVM_DIR = "/Library/Java/JavaVirtualMachines"

/** 경로를 만들지 못한 후보의 자리표시자 (문제 설명에 원문이 들어간다). */
private val UNPARSEABLE_PATH: Path = Path.of("")

/** 조사 대상 하나. [path] 가 null 이면 경로 형식 오류. */
private class JavaTarget(val origin: JavaOrigin, val path: Path?, val text: String)

/**
 * 시스템 Java 탐색 (03 임시, 04 에서 교체).
 *
 * 후보: explicit(있으면 그것만) > `JAVA_HOME/bin/java(.exe)` > `PATH` 순서 > 잘 알려진 설치 폴더(AE-22, 이름순).
 * - [env] 조회는 Windows 에서 이름 대소문자 무시 (`Path`/`PATH`, `ProgramFiles`/`PROGRAMFILES` — critique windows #1).
 * - 따옴표 제거, `InvalidPathException` 인 항목은 문제로 기록하고 건너뜀, `toRealPath` 로 중복 제거(먼저 나온 후보의 경로를 쓴다).
 * - 실제 경로가 [excludedRoots] 아래면 문제 "런처 번들 런타임(불변식 9)" 로 제외.
 * - 설치 폴더: Windows `%ProgramFiles%`·`%ProgramFiles(x86)%` 의 `Java`, `Eclipse Adoptium`, `Microsoft`, `Zulu`, `Amazon Corretto`,
 *   `BellSoft`, `Semeru` 바로 아래 폴더의 `bin\java.exe`; Linux `/usr/lib/jvm/<폴더>/bin/java`; macOS `/Library/Java/JavaVirtualMachines/<폴더>/Contents/Home/bin/java`.
 *   Minecraft 런처 번들 런타임은 찾지 않는다.
 * 선택: feature == 권장 → 없으면 min 이상 중 가장 낮은 feature (같으면 후보 순서). 사전 출시판 제외.
 * 경고: 선택 feature > DB 권장값, `_JAVA_OPTIONS`/`JAVA_TOOL_OPTIONS` 설정. 환경은 절대 바꾸지 않는다 (불변식 10).
 */
class SystemJavaLocator(
    private val env: Map<String, String>,
    private val os: Os,
    /** 제외할 런처 런타임 루트 (CLI: `paths.internalRoot/jre`). */
    private val excludedRoots: List<Path> = emptyList(),
    private val probe: JavaVersionProbe = ProcessJavaVersionProbe(),
) : JavaLocator {
    /** Windows 는 이름 대소문자를 무시한다 (D-I40). 테스트가 평범한 `mapOf` 를 줘도 `Path`/`PATH` 가 같이 걸린다. */
    private val lookup: Map<String, String> = hostEnvironment(os, env)

    override suspend fun locate(requirement: JavaRequirement, explicit: Path?): JavaLocateResult =
        withContext(Dispatchers.IO) {
            val excluded = excludedRoots.mapNotNull { realPathOrNull(it) }
            val candidates = ArrayList<JavaCandidate>()
            val seenReal = HashSet<Path>()
            for (target in targets(explicit)) {
                val path = target.path
                if (path == null) {
                    candidates += problem(UNPARSEABLE_PATH, target.origin, "경로 형식 오류: ${target.text}")
                    continue
                }
                if (!Files.isRegularFile(path)) {
                    // PATH·설치 폴더에는 없는 항목이 흔하다 — 사용자가 직접 지목한 것만 보고한다
                    if (target.origin == JavaOrigin.EXPLICIT || target.origin == JavaOrigin.JAVA_HOME) {
                        candidates += problem(path, target.origin, "실행 파일 없음")
                    }
                    continue
                }
                val real = realPathOrNull(path) ?: path.toAbsolutePath().normalize()
                if (!seenReal.add(real)) continue
                if (excluded.any { real.startsWith(it) }) {
                    candidates += problem(path, target.origin, "런처 번들 런타임(불변식 9)")
                    continue
                }
                val output = probe.versionOutput(path)
                if (output == null) {
                    candidates += problem(path, target.origin, "실행 실패")
                    continue
                }
                val parsed = parseJavaVersionOutput(output)
                if (parsed == null) {
                    candidates += problem(path, target.origin, "버전 해석 실패")
                    continue
                }
                val why = when {
                    parsed.preRelease -> "사전 출시판(Paper 가 거부)"
                    parsed.feature < requirement.minFeature -> "Java ${parsed.feature} < ${requirement.minFeature}"
                    else -> null
                }
                candidates += JavaCandidate(path, target.origin, parsed.raw, parsed.feature, why)
            }
            select(requirement, candidates)
        }

    /** 권장 feature 우선, 없으면 최소 요구 이상 중 가장 낮은 feature (같으면 후보 순서). */
    private fun select(requirement: JavaRequirement, candidates: List<JavaCandidate>): JavaLocateResult {
        val usable = candidates.filter { it.problemKo == null }
        val chosen = usable.firstOrNull { it.feature == requirement.recommendedFeature }
            ?: usable.minByOrNull { it.feature ?: Int.MAX_VALUE }
        val feature = chosen?.feature
        val versionString = chosen?.versionString
        if (chosen == null || feature == null || versionString == null) {
            return JavaLocateResult.NotFound(requirement, candidates)
        }
        val warnings = buildList {
            if (feature > requirement.recommendedFeature) {
                add("DB 권장값은 Java ${requirement.recommendedFeature} 입니다 (Java $feature 로 실행)")
            }
            if (!lookup["_JAVA_OPTIONS"].isNullOrBlank() || !lookup["JAVA_TOOL_OPTIONS"].isNullOrBlank()) {
                add("_JAVA_OPTIONS 가 설정돼 있어 -Xmx 등이 덮어써질 수 있습니다")
            }
        }
        // javaPath 는 후보 경로 그대로의 절대경로다 (toRealPath 는 중복 판정에만 썼다 — critique W9)
        val selection = JavaSelection(chosen.path.toAbsolutePath(), feature, versionString, chosen.origin, warnings)
        return JavaLocateResult.Found(selection, candidates)
    }

    private fun problem(path: Path, origin: JavaOrigin, reasonKo: String): JavaCandidate =
        JavaCandidate(path, origin, versionString = null, feature = null, problemKo = reasonKo)

    /** 조사 순서대로의 후보 목록. explicit 가 있으면 그것뿐이다. */
    private fun targets(explicit: Path?): List<JavaTarget> {
        if (explicit != null) return listOf(JavaTarget(JavaOrigin.EXPLICIT, explicit, explicit.toString()))
        val exe = if (os == Os.WINDOWS) "java.exe" else "java"
        val out = ArrayList<JavaTarget>()
        val javaHome = lookup["JAVA_HOME"]
        if (!javaHome.isNullOrBlank()) out += buildTarget(JavaOrigin.JAVA_HOME, javaHome, listOf(javaHome, "bin", exe))
        val separator = if (os == Os.WINDOWS) ';' else ':'
        for (entry in lookup["PATH"].orEmpty().split(separator)) {
            val trimmed = entry.trim().removeSurrounding("\"")
            if (trimmed.isBlank()) continue
            // 문제 설명에는 사용자가 실제로 넣은 값(entry)을 그대로 보여 준다 — 합성한 경로를 보여 주면 찾을 수 없다
            out += buildTarget(JavaOrigin.PATH, entry, listOf(trimmed, exe))
        }
        out += installedTargets(exe)
        return out
    }

    /** 잘 알려진 설치 폴더 (AE-22). 폴더 이름순. */
    private fun installedTargets(exe: String): List<JavaTarget> {
        val vendorRoots: List<Path> = when (os) {
            Os.WINDOWS ->
                listOfNotNull(lookup["ProgramFiles"], lookup["ProgramFiles(x86)"])
                    .filter { it.isNotBlank() }
                    .distinct()
                    .flatMap { base -> WINDOWS_JAVA_VENDOR_DIRS.mapNotNull { pathOrNull(base, it) } }

            Os.LINUX -> listOfNotNull(pathOrNull(LINUX_JVM_DIR))

            Os.MAC -> listOfNotNull(pathOrNull(MAC_JVM_DIR))
        }
        return vendorRoots.flatMap { root ->
            childDirectories(root).map { home ->
                val java = if (os == Os.MAC) {
                    home.resolve("Contents").resolve("Home").resolve("bin").resolve(exe)
                } else {
                    home.resolve("bin").resolve(exe)
                }
                JavaTarget(JavaOrigin.INSTALLED, java, java.toString())
            }
        }
    }

    private fun childDirectories(root: Path): List<Path> =
        try {
            Files.newDirectoryStream(root).use { stream -> stream.sortedBy { it.fileName.toString() } }
        } catch (e: IOException) {
            emptyList()
        } catch (e: DirectoryIteratorException) {
            emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }

    /** @param rawText 환경 변수에서 읽은 **원문** (문제 설명에 그대로 들어간다). */
    private fun buildTarget(origin: JavaOrigin, rawText: String, segments: List<String>): JavaTarget {
        val path = try {
            Path.of(segments.first(), *segments.drop(1).toTypedArray())
        } catch (e: InvalidPathException) {
            null
        }
        return JavaTarget(origin, path, rawText)
    }

    private fun pathOrNull(first: String, vararg more: String): Path? =
        try {
            Path.of(first, *more)
        } catch (e: InvalidPathException) {
            null
        }

    private fun realPathOrNull(path: Path): Path? =
        try {
            path.toRealPath()
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
}

/**
 * `<java> -version` 을 실제로 실행 (stdin 닫음, [timeout] 제한, 넘으면 destroyForcibly).
 *
 * # 불변식
 * - 출력은 **별도 데몬 스레드**가 계속 빨아들인다. 한 코루틴에서 전부 읽은 뒤 `waitFor` 를 부르면
 *   stdout 을 닫지 않는 프로그램(이름만 `java` 인 상주 프로그램 등)에서 제한 시간이 무의미해지고
 *   취소도 먹지 않는다 (DESIGN2 §2.14 의 순서를 이렇게 바로잡았다).
 * - 그래도 파이프는 계속 비워지므로 자식이 버퍼가 차서 멈추지 않는다.
 */
class ProcessJavaVersionProbe(private val timeout: Duration = PROBE_TIMEOUT) : JavaVersionProbe {
    override suspend fun versionOutput(java: Path): String? =
        withContext(Dispatchers.IO) {
            val process = try {
                ProcessBuilder(java.toString(), "-version").redirectErrorStream(true).start()
            } catch (e: IOException) {
                return@withContext null
            } catch (e: SecurityException) {
                return@withContext null
            }
            try {
                // stdin 을 바로 닫는다 — `-version` 은 입력을 읽지 않지만 파이프를 열어 두지 않는다
                process.outputStream.close()
            } catch (e: IOException) {
                // 닫기 실패는 무시한다 (읽기·대기 결과로 판정한다)
            }
            val drained = drainOnDaemonThread(process)
            val exited = withTimeoutOrNull(timeout) { process.onExit().await() }
            if (exited == null) {
                process.destroyForcibly()
                return@withContext null
            }
            // 프로세스가 끝났으면 파이프도 곧 EOF 다
            withTimeoutOrNull(DRAIN_AFTER_EXIT) { drained.await() }
        }
}

/** 자식의 stdout+stderr 를 끝까지 읽는 데몬 스레드. 읽기 실패는 빈 문자열로 본다. */
private fun drainOnDaemonThread(process: Process): CompletableFuture<String> {
    val done = CompletableFuture<String>()
    val thread = Thread({
        val bytes = try {
            process.inputStream.use { it.readBytes() }
        } catch (e: IOException) {
            ByteArray(0)
        }
        done.complete(bytes.toString(StandardCharsets.ISO_8859_1))
    }, "dcx-java-probe-${process.pid()}")
    thread.isDaemon = true
    thread.start()
    return done
}

/** `java -version` 최대 대기 (AE-21 계열 설계 상수). */
private val PROBE_TIMEOUT: Duration = 10.seconds

/** 종료를 확인한 뒤 남은 출력을 거두는 시간. */
private val DRAIN_AFTER_EXIT: Duration = 2.seconds
