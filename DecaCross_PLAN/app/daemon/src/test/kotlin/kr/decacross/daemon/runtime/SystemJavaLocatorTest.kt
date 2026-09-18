package kr.decacross.daemon.runtime

import kotlinx.coroutines.runBlocking
import kr.decacross.compat.model.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [SystemJavaLocator] — 임시 폴더의 가짜 `java.exe` + 파일 내용을 읽는 가짜 프로브 (DESIGN2 §2.14, §4.7).
 * `os = WINDOWS` 로 고정해 이름 대소문자 무시(D-I40)와 `%ProgramFiles%` 탐색(AE-22)을 함께 본다.
 */
class SystemJavaLocatorTest {
    private val os = Os.WINDOWS
    private val probe = FileVersionProbe()
    private val root: Path = Files.createTempDirectory("dcx-java")

    /** `<root>/<name>/bin/java.exe` 를 만들고 그 경로를 준다. [version] 이 null 이면 실행 실패로 흉내 낸다. */
    private fun javaExe(name: String, version: String?, parent: Path = root): Path {
        val exe = parent.resolve(name).resolve("bin").resolve("java.exe")
        Files.createDirectories(exe.parent)
        Files.writeString(exe, version?.let(::versionOutput) ?: PROBE_FAIL)
        return exe
    }

    private fun versionOutput(raw: String): String = "openjdk version \"$raw\" 2024-07-16\nOpenJDK 64-Bit Server VM\n"

    /** `JAVA_HOME` 은 `bin` 의 부모다. */
    private fun home(exe: Path): String = exe.parent.parent.toString()

    private fun bin(exe: Path): String = exe.parent.toString()

    private fun locator(env: Map<String, String>, excludedRoots: List<Path> = emptyList()) =
        SystemJavaLocator(env, os, excludedRoots, probe)

    private fun found(result: JavaLocateResult): JavaLocateResult.Found =
        result as? JavaLocateResult.Found ?: fail("Found 를 기대했다: $result")

    private fun notFound(result: JavaLocateResult): JavaLocateResult.NotFound =
        result as? JavaLocateResult.NotFound ?: fail("NotFound 를 기대했다: $result")

    @Test
    fun explicitWinsEvenWhenPathHasRecommended() = runBlocking {
        val explicit = javaExe("jdk-25", "25.0.4.1")
        val onPath = javaExe("jdk-21", "21.0.4")
        val result = found(locator(mapOf("Path" to bin(onPath))).locate(JavaRequirement(21, 21), explicit))
        assertEquals(JavaOrigin.EXPLICIT, result.selection.origin)
        assertEquals(explicit.toAbsolutePath(), result.selection.javaPath)
        assertEquals(1, result.candidates.size, "explicit 이면 그것만 본다: ${result.candidates}")
        Unit
    }

    @Test
    fun javaHomeComesBeforePath() = runBlocking {
        val fromHome = javaExe("home-21", "21.0.4")
        val fromPath = javaExe("path-21", "21.0.4")
        val env = mapOf("JAVA_HOME" to home(fromHome), "Path" to bin(fromPath))
        val result = found(locator(env).locate(JavaRequirement(21, 21), explicit = null))
        assertEquals(JavaOrigin.JAVA_HOME, result.selection.origin)
        assertEquals(fromHome.toAbsolutePath(), result.selection.javaPath)
        Unit
    }

    @Test
    fun recommendedFeatureWinsOverLowerAndHigher() = runBlocking {
        val j17 = javaExe("jdk-17", "17.0.12")
        val j21 = javaExe("jdk-21", "21.0.4")
        val j25 = javaExe("jdk-25", "25.0.4.1")
        val env = mapOf("Path" to listOf(bin(j25), bin(j17), bin(j21)).joinToString(";"))
        val result = found(locator(env).locate(JavaRequirement(17, 21), explicit = null))
        assertEquals(21, result.selection.feature)
        assertEquals(j21.toAbsolutePath(), result.selection.javaPath)
        assertTrue(result.selection.warningsKo.isEmpty(), "권장값과 같으면 경고 없음: ${result.selection.warningsKo}")
        Unit
    }

    @Test
    fun lowestAboveMinimumWhenRecommendedMissing() = runBlocking {
        val j25 = javaExe("jdk-25", "25.0.4.1")
        val j24 = javaExe("jdk-24", "24.0.1")
        val j17 = javaExe("jdk-17", "17.0.12")
        val env = mapOf("Path" to listOf(bin(j25), bin(j17), bin(j24)).joinToString(";"))
        val result = found(locator(env).locate(JavaRequirement(21, 21), explicit = null))
        assertEquals(24, result.selection.feature)
        assertEquals("DB 권장값은 Java 21 입니다 (Java 24 로 실행)", result.selection.warningsKo.firstOrNull())
        val tooOld = result.candidates.first { it.feature == 17 }
        assertEquals("Java 17 < 21", tooOld.problemKo)
        Unit
    }

    @Test
    fun preReleaseIsExcluded() = runBlocking {
        val ea = javaExe("jdk-22-ea", "22-ea")
        val ok = javaExe("jdk-24", "24.0.1")
        val env = mapOf("Path" to listOf(bin(ea), bin(ok)).joinToString(";"))
        val result = found(locator(env).locate(JavaRequirement(21, 21), explicit = null))
        assertEquals(24, result.selection.feature)
        assertEquals("사전 출시판(Paper 가 거부)", result.candidates.first { it.versionString == "22-ea" }.problemKo)
        Unit
    }

    @Test
    fun duplicatesByRealPathAreProbedOnce_andKeepTheFirstCandidatePath() = runBlocking {
        val real = javaExe("jdk-21", "21.0.4")
        // `sub/..` 로 우회한 같은 파일 — toRealPath 로는 같고, 문자열로는 다르다
        val detour = real.parent.parent.resolve("bin").resolve("x").resolve("..").resolve("java.exe")
        Files.createDirectories(real.parent.resolve("x"))
        val env = mapOf("JAVA_HOME" to home(real), "Path" to detour.parent.toString())
        val result = found(locator(env).locate(JavaRequirement(21, 21), explicit = null))
        assertEquals(1, result.candidates.size, "중복은 한 번만 센다: ${result.candidates}")
        assertEquals(1, probe.probed.size, "중복은 `java -version` 도 한 번만")
        assertEquals(real.toAbsolutePath(), result.selection.javaPath)
        Unit
    }

    @Test
    fun junctionCandidateKeepsLinkPathNotRealPath() = runBlocking {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"), "정션은 Windows 전용")
        val real = javaExe("jdk-25.0.4.1", "25.0.4.1")
        val link = root.resolve("latest")
        val rc = ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), real.parent.parent.toString())
            .redirectErrorStream(true)
            .start()
            .waitFor()
        assumeTrue(rc == 0, "정션을 만들지 못했다")
        val linked = link.resolve("bin").resolve("java.exe")
        val env = mapOf("Path" to listOf(bin(linked), bin(real)).joinToString(";"))
        val result = found(locator(env).locate(JavaRequirement(21, 25), explicit = null))
        // 링크 경로를 그대로 보관한다 (critique W9: 실제 경로로 풀면 JDK 갱신 뒤 start.bat 이 깨진다)
        assertEquals(linked.toAbsolutePath(), result.selection.javaPath)
        assertNotEquals(real.toAbsolutePath(), result.selection.javaPath)
        assertEquals(1, result.candidates.size, "정션과 원본은 같은 후보다: ${result.candidates}")
        Unit
    }

    @Test
    fun quotedPathEntriesAndWindowsEnvKeyCasing() = runBlocking {
        val onPath = javaExe("jdk-25", "25.0.4.1")
        val installed = javaExe("jdk-21", "21.0.4", parent = root.resolve("Program Files").resolve("Java"))
        // 실제 Windows 셸의 이름 (`Path`, `PROGRAMFILES`) — 대소문자 무시 조회를 확인한다
        val env = mapOf(
            "Path" to "\"${bin(onPath)}\" ; ",
            "PROGRAMFILES" to root.resolve("Program Files").toString(),
        )
        val result = found(locator(env).locate(JavaRequirement(21, 21), explicit = null))
        assertEquals(JavaOrigin.INSTALLED, result.selection.origin)
        assertEquals(installed.toAbsolutePath(), result.selection.javaPath)
        assertTrue(result.candidates.any { it.origin == JavaOrigin.PATH && it.feature == 25 }, "따옴표 항목도 후보: ${result.candidates}")
        Unit
    }

    @Test
    fun invalidPathEntriesAreRecordedAsProblems() = runBlocking {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"), "경로 금지 문자는 Windows 기준")
        val ok = javaExe("jdk-21", "21.0.4")
        val env = mapOf(
            "JAVA_HOME" to "\"${home(ok)}\"",
            "Path" to listOf("C:\\<bad>|dir", bin(ok)).joinToString(";"),
        )
        val result = found(locator(env).locate(JavaRequirement(21, 21), explicit = null))
        assertEquals(ok.toAbsolutePath(), result.selection.javaPath)
        val broken = result.candidates.filter { it.problemKo?.startsWith("경로 형식 오류") == true }
        assertEquals(2, broken.size, "따옴표 JAVA_HOME 과 금지 문자 PATH 항목: ${result.candidates}")
        assertTrue(broken.any { it.origin == JavaOrigin.JAVA_HOME })
        assertTrue(broken.any { it.origin == JavaOrigin.PATH })
        // 사용자가 고쳐야 할 값이므로 합성한 경로가 아니라 환경 변수의 원문을 그대로 보여 준다
        assertEquals(
            listOf("경로 형식 오류: \"${home(ok)}\"", "경로 형식 오류: C:\\<bad>|dir"),
            broken.map { it.problemKo },
        )
        Unit
    }

    @Test
    fun installedScanPrefersRecommendedOverPath() = runBlocking {
        val programFiles = root.resolve("Program Files")
        val installed = javaExe("jdk-21", "21.0.4", parent = programFiles.resolve("Java"))
        val onPath = javaExe("jdk-25", "25.0.4.1")
        val env = mapOf("Path" to bin(onPath), "ProgramFiles" to programFiles.toString())
        val result = found(locator(env).locate(JavaRequirement(21, 21), explicit = null))
        assertEquals(installed.toAbsolutePath(), result.selection.javaPath)
        assertEquals(JavaOrigin.INSTALLED, result.selection.origin)
        assertEquals(21, result.selection.feature)
        Unit
    }

    @Test
    fun candidateUnderExcludedRootIsRejected() = runBlocking {
        val bundled = javaExe("jre", "21.0.4", parent = root.resolve("internal"))
        val env = mapOf("Path" to bin(bundled))
        val result = notFound(locator(env, excludedRoots = listOf(root.resolve("internal"))).locate(JavaRequirement(21, 21), null))
        assertEquals(1, result.candidates.size)
        assertEquals("런처 번들 런타임(불변식 9)", result.candidates.first().problemKo)
        assertTrue(probe.probed.isEmpty(), "제외된 후보는 실행하지 않는다")
        Unit
    }

    @Test
    fun notFoundListsEveryCandidateWithItsProblem() = runBlocking {
        val missing = root.resolve("없음").resolve("bin").resolve("java.exe")
        val broken = javaExe("broken", version = null)
        val garbage = javaExe("garbage", "x").also { Files.writeString(it, "무슨 말인지 모를 출력") }
        val old = javaExe("jdk-8", "1.8.0_392")
        val env = mapOf(
            "JAVA_HOME" to missing.parent.parent.toString(),
            "Path" to listOf(bin(broken), bin(garbage), bin(old)).joinToString(";"),
        )
        val result = notFound(locator(env).locate(JavaRequirement(21, 21), explicit = null))
        assertEquals(JavaRequirement(21, 21), result.requirement)
        assertEquals(4, result.candidates.size, "후보 전부를 보고한다: ${result.candidates}")
        assertTrue(result.candidates.all { it.problemKo != null })
        assertContains(result.candidates.map { it.problemKo }, "실행 파일 없음")
        assertContains(result.candidates.map { it.problemKo }, "실행 실패")
        assertContains(result.candidates.map { it.problemKo }, "버전 해석 실패")
        assertContains(result.candidates.map { it.problemKo }, "Java 8 < 21")
        Unit
    }

    @Test
    fun missingPathEntriesAreSkippedSilently() = runBlocking {
        val ok = javaExe("jdk-21", "21.0.4")
        val env = mapOf("Path" to listOf(root.resolve("없는 폴더").toString(), bin(ok)).joinToString(";"))
        val result = found(locator(env).locate(JavaRequirement(21, 21), explicit = null))
        assertEquals(1, result.candidates.size, "PATH 의 빈 항목은 조용히 건너뛴다: ${result.candidates}")
        Unit
    }

    @Test
    fun javaOptionsEnvProducesWarning() = runBlocking {
        val ok = javaExe("jdk-21", "21.0.4")
        // 두 변수 모두 경고 대상이고, Windows 는 변수 이름 대소문자를 가리지 않는다 (D-I40)
        for (key in listOf("_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_java_options")) {
            val env = mapOf("Path" to bin(ok), key to "-Dfoo=bar")
            val result = found(locator(env).locate(JavaRequirement(21, 21), explicit = null))
            assertEquals(
                listOf("_JAVA_OPTIONS 가 설정돼 있어 -Xmx 등이 덮어써질 수 있습니다"),
                result.selection.warningsKo,
                key,
            )
        }
        val clean = found(locator(mapOf("Path" to bin(ok))).locate(JavaRequirement(21, 21), explicit = null))
        assertTrue(clean.selection.warningsKo.isEmpty(), "설정이 없으면 경고도 없다: ${clean.selection.warningsKo}")
        Unit
    }
}

/** 프로브 실패로 흉내 낼 파일 내용. */
private const val PROBE_FAIL = "FAIL"

/** 가짜 `java.exe` 의 **내용**을 `java -version` 출력으로 돌려준다 (링크·중복도 그대로 따라간다). */
private class FileVersionProbe : JavaVersionProbe {
    val probed: MutableList<Path> = ArrayList()

    override suspend fun versionOutput(java: Path): String? {
        probed.add(java)
        val text = try {
            Files.readString(java)
        } catch (e: IOException) {
            return null
        }
        return if (text.trim() == PROBE_FAIL) null else text
    }
}
