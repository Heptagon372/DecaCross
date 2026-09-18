package kr.decacross.daemon.install.fetch

import kotlinx.coroutines.runBlocking
import kr.decacross.daemon.install.ArtifactKind
import kr.decacross.daemon.install.ArtifactVerifier
import kr.decacross.daemon.install.VerifyOutcome
import kr.decacross.daemon.install.verifyFile
import kr.decacross.daemon.testkit.TestJars
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 설계 §2.12 검증. jar 은 전부 메모리에서 만든다 (레포에 바이너리 금지). */
class VerifyFileTest {
    private fun write(dir: Path, name: String, bytes: ByteArray): Path =
        dir.resolve(name).also { Files.write(it, bytes) }

    @Test
    fun `크기가 다르면 SizeMismatch`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = TestJars.serverJar(randomEntryBytes = 4096)
            val file = write(dir, "a.jar", bytes)
            val outcome = verifyFile(file, TestJars.sha256Hex(bytes), bytes.size + 1L, ArtifactKind.JAR)
            assertEquals(VerifyOutcome.SizeMismatch(bytes.size + 1L, bytes.size.toLong()), outcome)
        }
    }

    @Test
    fun `해시가 다르면 HashMismatch`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = TestJars.serverJar(randomEntryBytes = 4096)
            val file = write(dir, "a.jar", bytes)
            val wrong = "0".repeat(64)
            val outcome = verifyFile(file, wrong, bytes.size.toLong(), ArtifactKind.JAR)
            val mismatch = assertIs<VerifyOutcome.HashMismatch>(outcome)
            assertEquals(wrong, mismatch.expected)
            assertEquals(TestJars.sha256Hex(bytes), mismatch.actual)
        }
    }

    @Test
    fun `압축 엔트리의 비트 하나가 뒤집히면 CorruptArchive`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = TestJars.serverJar()
            // 엔트리 데이터 한가운데 (META-INF/libraries/blob.bin) 의 비트 하나를 뒤집는다
            val flipIndex = (bytes.size * 0.4).toInt()
            bytes[flipIndex] = (bytes[flipIndex].toInt() xor 0x01).toByte()
            val file = write(dir, "a.jar", bytes)
            val outcome = verifyFile(file, TestJars.sha256Hex(bytes), bytes.size.toLong(), ArtifactKind.JAR)
            assertIs<VerifyOutcome.CorruptArchive>(outcome)
        }
    }

    @Test
    fun `꼬리 30바이트가 잘리면 CorruptArchive`(): Unit = runBlocking {
        withTempDir { dir ->
            val full = TestJars.serverJar(randomEntryBytes = 4096)
            val bytes = full.copyOfRange(0, full.size - 30)
            val file = write(dir, "a.jar", bytes)
            val outcome = verifyFile(file, TestJars.sha256Hex(bytes), bytes.size.toLong(), ArtifactKind.JAR)
            assertIs<VerifyOutcome.CorruptArchive>(outcome)
        }
    }

    @Test
    fun `HTML 본문은 CorruptArchive 이고 OTHER 종류면 통과한다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = "<html><body>captive portal</body></html>".toByteArray()
            val file = write(dir, "a.jar", bytes)
            val sha = TestJars.sha256Hex(bytes)
            assertIs<VerifyOutcome.CorruptArchive>(verifyFile(file, sha, bytes.size.toLong(), ArtifactKind.ZIP))
            // OTHER 는 zip 검사를 건너뛴다 (크기·해시만)
            assertEquals(
                VerifyOutcome.Verified(sha, bytes.size.toLong()),
                verifyFile(file, sha, bytes.size.toLong(), ArtifactKind.OTHER),
            )
        }
    }

    @Test
    fun `Main-Class 가 없으면 SERVER_JAR 은 CorruptArchive`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = TestJars.serverJar(randomEntryBytes = 4096, withMainClass = false)
            val file = write(dir, "a.jar", bytes)
            val sha = TestJars.sha256Hex(bytes)
            val outcome = verifyFile(file, sha, bytes.size.toLong(), ArtifactKind.SERVER_JAR)
            val corrupt = assertIs<VerifyOutcome.CorruptArchive>(outcome)
            assertEquals("Main-Class 없음", corrupt.reason)
            // 같은 파일도 일반 JAR 로 보면 정상이다
            assertIs<VerifyOutcome.Verified>(verifyFile(file, sha, bytes.size.toLong(), ArtifactKind.JAR))
        }
    }

    @Test
    fun `엔트리가 없는 zip 은 CorruptArchive`(): Unit = runBlocking {
        withTempDir { dir ->
            // 엔트리 0개 zip = EOCD 22바이트뿐 (ZipOutputStream 은 빈 zip 을 만들지 못한다)
            val bytes = ByteArray(22).also {
                it[0] = 0x50
                it[1] = 0x4B
                it[2] = 0x05
                it[3] = 0x06
            }
            val file = write(dir, "a.zip", bytes)
            val outcome = verifyFile(file, TestJars.sha256Hex(bytes), bytes.size.toLong(), ArtifactKind.ZIP)
            assertEquals(VerifyOutcome.CorruptArchive("엔트리 없음"), outcome)
        }
    }

    @Test
    fun `검증 뒤에는 핸들이 닫혀 파일을 옮길 수 있다`(): Unit = runBlocking {
        withTempDir { dir ->
            val bytes = TestJars.serverJar(randomEntryBytes = 8192)
            val file = write(dir, "a.jar", bytes)
            val sha = TestJars.sha256Hex(bytes)
            val item = testItem(bytes, kind = ArtifactKind.SERVER_JAR)
            // 기본 구현(ArtifactVerifier.DEFAULT)도 같은 경로를 탄다
            assertEquals(
                VerifyOutcome.Verified(sha, bytes.size.toLong()),
                ArtifactVerifier.DEFAULT.verify(file, item),
            )
            val target = dir.resolve("moved.jar")
            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE)
            assertTrue(Files.isRegularFile(target))
        }
    }

    @Test
    fun `파일이 없으면 Io`(): Unit = runBlocking {
        withTempDir { dir ->
            val outcome = verifyFile(dir.resolve("없음.jar"), "0".repeat(64), 1, ArtifactKind.OTHER)
            assertIs<VerifyOutcome.Io>(outcome)
        }
    }
}
