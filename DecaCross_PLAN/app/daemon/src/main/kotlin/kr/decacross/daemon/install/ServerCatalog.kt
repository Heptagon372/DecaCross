package kr.decacross.daemon.install

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.time.Instant

/**
 * 서버 루트의 항목 하나 (`list`·`start` 용).
 *
 * @property problemKo 서버로 쓸 수 없는 이유 (매니페스트·launch.json 없음/손상 등). null 이면 정상.
 */
data class ServerEntry(
    val name: String,
    val dir: Path,
    val manifest: InstallManifest?,
    val launch: LaunchSpec?,
    val problemKo: String?,
)

/**
 * 서버 루트 스캔: 점으로 시작하지 않는 디렉터리 중 `.decacross/` 가 있는 것. `.decacross/INCOMPLETE` 가 있으면 제외.
 * 이름순(대소문자 무시). 루트가 없으면 빈 목록. 쓰기 없음.
 */
fun listServers(serversRoot: Path): List<ServerEntry> =
    listDirectories(serversRoot)
        .filter { !it.fileName.toString().startsWith(".") }
        .filter { Files.isDirectory(it.resolve(META_DIR_NAME)) }
        .filter { !Files.exists(it.resolve(META_DIR_NAME).resolve(INCOMPLETE_MARKER_NAME)) }
        .map { readServerEntry(it) }
        .sortedBy { it.name.lowercase() }

/** `eula.txt` 파일 이름. */
internal const val EULA_FILE_NAME: String = "eula.txt"

/** 서버 폴더의 메타 파일은 런처가 쓴 것이지만, 사용자가 손댔을 수 있으므로 모르는 키는 무시한다. */
private val catalogJson = Json { ignoreUnknownKeys = true }

private fun readServerEntry(dir: Path): ServerEntry {
    val meta = dir.resolve(META_DIR_NAME)
    val launchText = readTextOrNull(meta.resolve(LAUNCH_FILE_NAME))
    val manifestText = readTextOrNull(meta.resolve(MANIFEST_FILE_NAME))
    val launch = launchText?.let { decodeOrNull(LaunchSpec.serializer(), it) }
    val manifest = manifestText?.let { decodeOrNull(InstallManifest.serializer(), it) }
    val problemKo = when {
        launchText == null -> "$META_DIR_NAME/$LAUNCH_FILE_NAME 이 없습니다 (DecaCross 로 만든 서버가 아닐 수 있습니다)"
        launch == null -> "$META_DIR_NAME/$LAUNCH_FILE_NAME 을 읽을 수 없습니다 (형식 오류)"
        manifestText == null -> "$META_DIR_NAME/$MANIFEST_FILE_NAME 이 없습니다"
        manifest == null -> "$META_DIR_NAME/$MANIFEST_FILE_NAME 을 읽을 수 없습니다 (형식 오류)"
        else -> null
    }
    return ServerEntry(dir.fileName.toString(), dir, manifest, launch, problemKo)
}

private fun <T> decodeOrNull(deserializer: DeserializationStrategy<T>, text: String): T? =
    try {
        catalogJson.decodeFromString(deserializer, text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

private fun readTextOrNull(path: Path): String? =
    try {
        if (Files.isRegularFile(path)) Files.readString(path, Charsets.UTF_8) else null
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

/** 이름으로 찾기 (Windows 처럼 대소문자 무시). */
fun findServer(serversRoot: Path, name: String): ServerEntry? =
    listServers(serversRoot).firstOrNull { it.name.equals(name, ignoreCase = true) }

/** `eula.txt` 에 `eula=true` 가 있는가 (Properties 규칙으로 읽음, 값은 대소문자 무시). 파일 없음·읽기 실패 → false. */
fun isEulaAccepted(serverDir: Path): Boolean {
    val file = serverDir.resolve(EULA_FILE_NAME)
    return try {
        if (!Files.isRegularFile(file)) {
            false
        } else {
            val properties = Properties()
            Files.newInputStream(file).use { properties.load(it) }
            properties.getProperty("eula")?.trim().equals("true", ignoreCase = true)
        }
    } catch (e: IOException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

/**
 * 동의한 사용자를 위해 eula.txt 를 [renderEulaTxt] 로 원자적 교체. ★ 호출자는 명시적 동의([EulaAnswer.Accepted])를 받은 뒤에만 부른다.
 * 허용 호출 위치: CLI `start` 의 동의 경로, (05) 데몬의 동의 다이얼로그, 테스트(임시 디렉터리). merge_wps2.py 가 그 밖의 호출을 거부한다.
 */
fun writeEulaAccepted(serverDir: Path, channel: ConsentChannel, acceptedAt: Instant): LayoutIoResult =
    writeFileAtomically(serverDir.resolve(EULA_FILE_NAME), renderEulaTxt(channel, acceptedAt))
