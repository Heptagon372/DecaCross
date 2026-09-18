package kr.decacross.daemon.install

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** 검증 결과. [Verified] 외에는 전부 설치 중단 사유다 (해시 불일치는 재시도하지 않는다 — 변조 의심). */
sealed interface VerifyOutcome {
    data class Verified(val sha256: String, val size: Long) : VerifyOutcome

    data class SizeMismatch(val expected: Long, val actual: Long) : VerifyOutcome

    /** ★ 변조 또는 낡은 메타데이터 의심. 즉시 중단. */
    data class HashMismatch(val expected: String, val actual: String) : VerifyOutcome

    /** 해시는 맞는데 zip 이 깨짐 → 원본 자체의 결함. 재시도 무의미. */
    data class CorruptArchive(val reason: String) : VerifyOutcome

    data class Io(val message: String) : VerifyOutcome
}

/** 파이프라인이 보는 검증 경계. */
fun interface ArtifactVerifier {
    suspend fun verify(file: Path, item: FetchItem): VerifyOutcome

    companion object {
        /** [verifyFile] 을 쓰는 기본 구현. */
        val DEFAULT: ArtifactVerifier = ArtifactVerifier { file, item -> verifyFile(file, item.sha256, item.size, item.kind) }
    }
}

/**
 * 크기 → SHA-256 → (jar/zip 이면) zip 무결성 순으로 검사한다. `Dispatchers.IO` 에서 돈다.
 *
 * - zip 검사는 `ZipFile` 로 모든 엔트리를 읽어 CRC32·크기를 대조한다. `ZipInputStream` 은 HTML 파일·꼬리 잘린 파일을
 *   정상으로 보고하므로 쓰지 않는다 (research fetch-verify §1.3). 중첩 jar 는 열지 않는다.
 * - [ArtifactKind.SERVER_JAR] 는 매니페스트 `Main-Class` 또는 `META-INF/main-class` 가 있어야 한다.
 * - 반환 전에 모든 파일 핸들을 닫는다 (Windows 에서 열린 `ZipFile` 은 이동·삭제를 막는다).
 * - 파일을 지우지 않는다. 지우기는 호출자([ArtifactFetcher.discard]) 몫.
 */
suspend fun verifyFile(path: Path, sha256: String, size: Long, kind: ArtifactKind): VerifyOutcome =
    withContext(Dispatchers.IO) {
        val actualSize = try {
            if (!Files.isRegularFile(path)) return@withContext VerifyOutcome.Io("파일이 없다: $path")
            Files.size(path)
        } catch (e: IOException) {
            return@withContext VerifyOutcome.Io(e.toString())
        }
        if (actualSize != size) return@withContext VerifyOutcome.SizeMismatch(size, actualSize)

        val actualHash = try {
            sha256Of(path)
        } catch (e: IOException) {
            return@withContext VerifyOutcome.Io(e.toString())
        }
        if (!actualHash.equals(sha256, ignoreCase = true)) {
            return@withContext VerifyOutcome.HashMismatch(sha256.lowercase(), actualHash)
        }

        if (kind == ArtifactKind.OTHER) return@withContext VerifyOutcome.Verified(actualHash, actualSize)

        val archiveProblem = try {
            checkArchive(path, kind)
        } catch (e: ZipException) {
            e.message?.let { "zip: $it" } ?: "zip 구조가 깨졌다"
        } catch (e: IOException) {
            return@withContext VerifyOutcome.Io(e.toString())
        }
        if (archiveProblem != null) return@withContext VerifyOutcome.CorruptArchive(archiveProblem)

        VerifyOutcome.Verified(actualHash, actualSize)
    }

/** 64 KiB 씩 읽어 SHA-256. 16 MiB 마다 취소 확인 (큰 jar 에서 Ctrl+C 가 먹히게). */
private suspend fun sha256Of(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteBuffer.allocate(64 * 1024)
    var sinceCheck = 0L
    FileChannel.open(path, StandardOpenOption.READ).use { channel ->
        while (true) {
            buffer.clear()
            val n = channel.read(buffer)
            if (n < 0) break
            buffer.flip()
            digest.update(buffer)
            sinceCheck += n
            if (sinceCheck >= 16L * 1024 * 1024) {
                currentCoroutineContext().ensureActive()
                sinceCheck = 0
            }
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

/**
 * zip 전수 검사. 문제가 없으면 null, 있으면 한국어 사유.
 *
 * `ZipInputStream` 은 HTML 파일과 꼬리 잘린 파일을 정상으로 보고하므로 쓰지 않는다 (research fetch-verify §1.3).
 * 중첩 jar 는 열지 않는다 (Paperclip 이 자기 내용 해시를 스스로 검사한다 — D-I26/SCP-I21).
 */
private fun checkArchive(path: Path, kind: ArtifactKind): String? {
    val buffer = ByteArray(64 * 1024)
    ZipFile(path.toFile()).use { zip ->
        val entries = zip.entries().toList()
        if (entries.isEmpty()) return "엔트리 없음"
        for (entry in entries) {
            if (entry.isDirectory) continue
            val crc = CRC32()
            var count = 0L
            zip.getInputStream(entry).use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    crc.update(buffer, 0, n)
                    count += n
                }
            }
            if (entry.crc >= 0 && crc.value != entry.crc) return "${entry.name}: CRC"
            if (entry.size >= 0 && count != entry.size) return "${entry.name}: 크기"
        }
    }
    if (kind != ArtifactKind.SERVER_JAR) return null
    JarFile(path.toFile(), false).use { jar ->
        val fromManifest = jar.manifest?.mainAttributes?.getValue("Main-Class")
        if (fromManifest == null && jar.getJarEntry("META-INF/main-class") == null) return "Main-Class 없음"
    }
    return null
}
