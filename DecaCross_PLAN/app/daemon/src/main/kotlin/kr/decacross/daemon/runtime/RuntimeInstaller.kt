package kr.decacross.daemon.runtime

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.decacross.compat.model.Arch
import kr.decacross.compat.model.ImageType
import kr.decacross.compat.model.Os
import kr.decacross.daemon.install.Fetcher
import kr.decacross.daemon.install.sha256
import kr.decacross.daemon.paths.DecaPaths
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/** 기본 HTTP 클라이언트를 쓰는 설치기. 앱 계층은 Ktor 타입을 몰라도 된다. */
fun defaultRuntimeInstaller(paths: DecaPaths): RuntimeInstaller =
    RuntimeInstaller(paths, kr.decacross.daemon.install.defaultHttpClient(), kr.decacross.daemon.install.defaultFetcher())

/** 설치된 서버용 런타임 목록 항목 (GET /api/runtimes). */
data class InstalledRuntime(val feature: Int, val path: Path, val javaExe: Path, val versionString: String)

sealed interface EnsureResult {
    data class Ok(val info: JavaInfo, val downloaded: Boolean) : EnsureResult

    data class Failed(val messageKo: String) : EnsureResult
}

/**
 * 서버 실행용 Java 런타임 자동 설치 (기획서 §4.3, 명세 §8).
 *
 * `%LOCALAPPDATA%/Decacross/runtimes/temurin-{feature}-jre/` 에 격리 설치한다.
 *
 * # 불변식
 * - JAVA_HOME / PATH / 레지스트리를 절대 수정하지 않는다. 절대경로만 돌려준다 (불변식 10).
 * - 런처 번들 JRE(`paths.bundledJre`)는 서버용으로 쓰지 않는다. feature 가 같아 보여도 항상 별도로 받는다 (불변식 9).
 * - 설치 후 반드시 `java -version` 을 실제로 실행해 검증한다. 메타데이터를 믿지 않는다.
 */
class RuntimeInstaller(
    private val paths: DecaPaths,
    private val client: HttpClient,
    private val fetcher: Fetcher,
    private val apiBase: String = "https://api.adoptium.net/v3",
) {
    private val log = LoggerFactory.getLogger(RuntimeInstaller::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun runtimeDir(feature: Int, image: ImageType = ImageType.JRE): Path =
        paths.runtimes.resolve("temurin-$feature-${image.name.lowercase()}")

    fun javaExe(runtimeDir: Path, os: Os): Path =
        when (os) {
            Os.WINDOWS -> runtimeDir.resolve("bin/java.exe")
            Os.MAC -> runtimeDir.resolve("Contents/Home/bin/java").takeIf(Files::exists) ?: runtimeDir.resolve("bin/java")
            Os.LINUX -> runtimeDir.resolve("bin/java")
        }

    /** 로컬 보유 목록. 실행돼서 버전이 맞는 것만. */
    fun installed(os: Os): List<InstalledRuntime> {
        if (!Files.isDirectory(paths.runtimes)) return emptyList()
        return Files.list(paths.runtimes).use { s ->
            s.filter { Files.isDirectory(it) }.toList()
        }.mapNotNull { dir ->
            val feature = Regex("temurin-(\\d+)-").find(dir.fileName.toString())?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val exe = javaExe(dir, os)
            val info = JavaRuntime.probe(exe) ?: return@mapNotNull null
            if (info.feature != feature) return@mapNotNull null
            InstalledRuntime(feature, dir, exe, info.versionString)
        }.sortedBy { it.feature }
    }

    /**
     * [feature] 런타임을 보장한다. 있으면 그대로, 없으면 Adoptium 에서 받아 설치·검증한다.
     * 404 폴백 체인: jre→jdk, aarch64→x64 (Rosetta/에뮬레이션).
     */
    suspend fun ensureRuntime(
        feature: Int,
        os: Os,
        arch: Arch,
        onProgress: suspend (done: Long, total: Long?) -> Unit = { _, _ -> },
    ): EnsureResult {
        for (image in listOf(ImageType.JRE, ImageType.JDK)) {
            val dir = runtimeDir(feature, image)
            val info = JavaRuntime.probe(javaExe(dir, os))
            if (info != null && info.feature == feature) return EnsureResult.Ok(info, downloaded = false)
        }
        val candidates = buildList {
            for (a in listOf(arch, Arch.X64).distinct()) {
                for (img in listOf(ImageType.JRE, ImageType.JDK)) add(a to img)
            }
        }
        val errors = ArrayList<String>()
        for ((a, img) in candidates) {
            val asset = latestAsset(feature, os, a, img) ?: run {
                errors += "${os.name.lowercase()}/${a.name.lowercase()}/${img.name.lowercase()}: 없음"
                continue
            }
            log.info("Temurin {} {} 다운로드: {}", feature, img, asset.name)
            val result = install(asset, feature, os, img, onProgress)
            if (result is EnsureResult.Ok) return result
            errors += (result as EnsureResult.Failed).messageKo
        }
        return EnsureResult.Failed("Java $feature 런타임을 설치하지 못했습니다: ${errors.joinToString("; ")}")
    }

    data class Asset(val name: String, val link: String, val sha256: String?, val size: Long?, val releaseName: String)

    /** `/v3/assets/latest/{feature}/hotspot?os=&architecture=&image_type=&vendor=eclipse` → 첫 항목. 없으면 null. */
    suspend fun latestAsset(feature: Int, os: Os, arch: Arch, image: ImageType): Asset? {
        val url = "$apiBase/assets/latest/$feature/hotspot?os=${adoptOs(os)}&architecture=${adoptArch(arch)}&image_type=${image.name.lowercase()}&vendor=eclipse"
        val resp = runCatching { client.get(url) }.getOrElse { return null }
        if (resp.status != HttpStatusCode.OK) return null
        val arr = runCatching { json.parseToJsonElement(resp.bodyAsText()).jsonArray }.getOrNull() ?: return null
        val first = arr.firstOrNull()?.jsonObject ?: return null
        val binary = first["binary"]?.jsonObject ?: return null
        val pkg = binary["package"]?.jsonObject ?: return null
        return Asset(
            name = pkg["name"]?.jsonPrimitive?.content ?: return null,
            link = pkg["link"]?.jsonPrimitive?.content ?: return null,
            sha256 = pkg["checksum"]?.jsonPrimitive?.content,
            size = pkg["size"]?.jsonPrimitive?.content?.toLongOrNull(),
            releaseName = first["release_name"]?.jsonPrimitive?.content ?: "",
        )
    }

    @OptIn(ExperimentalPathApi::class)
    private suspend fun install(
        asset: Asset,
        feature: Int,
        os: Os,
        image: ImageType,
        onProgress: suspend (Long, Long?) -> Unit,
    ): EnsureResult {
        Files.createDirectories(paths.cacheTmp)
        val archive = paths.cacheTmp.resolve(asset.name)
        val extractDir = paths.cacheTmp.resolve(asset.name + ".extract")
        try {
            fetcher.download(listOf(asset.link), archive, asset.size, onProgress)
            if (asset.sha256 != null) {
                val actual = sha256(archive)
                if (!actual.equals(asset.sha256, ignoreCase = true)) return EnsureResult.Failed("런타임 해시 불일치: ${asset.name}")
            }
            extractDir.deleteRecursively()
            Files.createDirectories(extractDir)
            extract(archive, extractDir)
            // 압축 안의 최상위 디렉터리 하나(jdk-21.0.4+7-jre 등)를 런타임 폴더로 옮긴다
            val inner = Files.list(extractDir).use { s -> s.filter { Files.isDirectory(it) }.toList() }.singleOrNull()
                ?: return EnsureResult.Failed("압축 구조를 인식할 수 없습니다: ${asset.name}")
            val target = runtimeDir(feature, image)
            Files.createDirectories(target.parent)
            target.deleteRecursively()
            Files.move(inner, target, StandardCopyOption.ATOMIC_MOVE)
            // ★ 메타데이터를 믿지 않는다 — 실제로 실행해 본다
            val info = JavaRuntime.probe(javaExe(target, os))
                ?: return EnsureResult.Failed("설치된 런타임이 실행되지 않습니다: $target").also { target.deleteRecursively() }
            if (info.feature != feature) {
                target.deleteRecursively()
                return EnsureResult.Failed("설치된 런타임 버전 불일치: 요청 $feature, 실제 ${info.feature}")
            }
            return EnsureResult.Ok(info, downloaded = true)
        } catch (e: Exception) {
            return EnsureResult.Failed("${asset.name}: ${e.message ?: e.toString()}")
        } finally {
            runCatching { Files.deleteIfExists(archive) }
            runCatching { extractDir.deleteRecursively() }
        }
    }

    private fun extract(archive: Path, into: Path) {
        val name = archive.fileName.toString()
        if (name.endsWith(".zip")) {
            ZipInputStream(Files.newInputStream(archive)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val out = into.resolve(e.name).normalize()
                    require(out.startsWith(into)) { "zip slip: ${e.name}" }
                    if (e.isDirectory) {
                        Files.createDirectories(out)
                    } else {
                        Files.createDirectories(out.parent)
                        Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING)
                    }
                    zin.closeEntry()
                }
            }
        } else {
            // tar.gz — JDK 에 tar 가 없으므로 시스템 tar 사용 (macOS/Linux 기본, Windows 10+ 도 bsdtar 내장)
            val p = ProcessBuilder("tar", "-xzf", archive.toAbsolutePath().toString(), "-C", into.toAbsolutePath().toString())
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "tar 실패: $out" }
        }
    }

    companion object {
        fun adoptOs(os: Os): String = when (os) {
            Os.WINDOWS -> "windows"
            Os.MAC -> "mac"
            Os.LINUX -> "linux"
        }

        fun adoptArch(arch: Arch): String = when (arch) {
            Arch.X64 -> "x64"
            Arch.AARCH64 -> "aarch64"
        }

        fun currentOs(): Os {
            val n = System.getProperty("os.name").lowercase()
            return when {
                n.contains("win") -> Os.WINDOWS
                n.contains("mac") -> Os.MAC
                else -> Os.LINUX
            }
        }

        fun currentArch(): Arch = if (System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }) Arch.AARCH64 else Arch.X64
    }
}
