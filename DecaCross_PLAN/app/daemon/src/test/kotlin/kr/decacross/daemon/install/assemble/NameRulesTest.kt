package kr.decacross.daemon.install.assemble

import kr.decacross.daemon.install.MAX_SERVER_DIR_LENGTH
import kr.decacross.daemon.install.NameCheck
import kr.decacross.daemon.install.findNameClash
import kr.decacross.daemon.install.validateServerDir
import kr.decacross.daemon.install.validateServerName
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 서버 이름·경로 규칙 (DESIGN2 §2.6, D-I30/D-I31, AE-16/AE-17). */
class NameRulesTest {
    private val root: Path = Files.createTempDirectory("dcx-names")

    @Test
    fun validNames_areAccepted() {
        for (name in listOf("demo", "내 서버 1", "a.b-c_d", "paper-1.21.8", "A", "x".repeat(64))) {
            assertEquals(NameCheck.Valid, validateServerName(name), name)
        }
    }

    @Test
    fun invalidNames_areRejectedWithReason() {
        val invalid = listOf(
            "" to "길이",
            "x".repeat(65) to "길이",
            ".x" to "점으로 시작",
            "x." to "점으로 끝",
            " x" to "공백으로 시작",
            "x " to "공백으로 끝",
            "CON" to "예약 이름",
            "con.txt" to "예약 이름(확장자 무관)",
            "LPT9" to "예약 이름",
            "nul" to "예약 이름(소문자)",
            "a/b" to "경로 구분자",
            "a\\b" to "경로 구분자",
            "a!b" to "금지 문자",
            "a+b" to "금지 문자",
            "a:b" to "금지 문자",
            "desktop.ini" to "OneDrive 금지 이름",
            "~\$x" to "OneDrive 금지 이름",
            "a_vti_b" to "OneDrive 금지 이름",
        )
        for ((name, why) in invalid) {
            val check = validateServerName(name)
            assertTrue(check is NameCheck.Invalid, "'$name' 은 거부돼야 한다 ($why)")
            assertTrue(check.reasonKo.isNotBlank(), "이유 문구가 있어야 한다: $name")
        }
    }

    @Test
    fun validateServerDir_rejectsExclamationAndPlusInRoot() {
        // Paper 는 경로에 ! 또는 + 가 있으면 기동하지 않는다 (AE-17)
        for (bad in listOf("root!", "root+x")) {
            val check = validateServerDir(root.resolve(bad), "demo")
            assertTrue(check is NameCheck.Invalid, bad)
            assertTrue(check.reasonKo.contains("!") || check.reasonKo.contains("+"), check.reasonKo)
        }
        assertEquals(NameCheck.Valid, validateServerDir(root, "demo"))
    }

    @Test
    fun validateServerDir_rejectsTooLongPath() {
        // 길이 검사는 순수 문자열 계산이라 실제 폴더가 필요 없다 (임시 폴더 경로 길이에 흔들리지 않게 합성 루트를 쓴다)
        val base = (root.root ?: root).resolve("dcx")
        val name = "d".repeat(40)
        val paddingLength = MAX_SERVER_DIR_LENGTH + 1 - base.toAbsolutePath().toString().length - 2 - name.length
        val tooDeep = base.resolve("p".repeat(paddingLength))
        assertEquals(MAX_SERVER_DIR_LENGTH + 1, tooDeep.resolve(name).toAbsolutePath().toString().length)
        val check = validateServerDir(tooDeep, name)
        assertTrue(check is NameCheck.Invalid, "201자 경로는 거부")
        assertTrue(check.reasonKo.contains("$MAX_SERVER_DIR_LENGTH"), check.reasonKo)

        val justFits = base.resolve("p".repeat(paddingLength - 1))
        assertEquals(MAX_SERVER_DIR_LENGTH, justFits.resolve(name).toAbsolutePath().toString().length)
        assertEquals(NameCheck.Valid, validateServerDir(justFits, name))
    }

    @Test
    fun findNameClash_isCaseInsensitive_andSeesRegularFiles() {
        val parent = Files.createDirectories(root.resolve("clash"))
        assertNull(findNameClash(parent, "demo"))
        Files.createDirectory(parent.resolve("Demo"))
        assertEquals(parent.resolve("Demo"), findNameClash(parent, "demo"))
        assertEquals(parent.resolve("Demo"), findNameClash(parent, "DEMO"))

        val fileParent = Files.createDirectories(root.resolve("clash-file"))
        Files.writeString(fileParent.resolve("server"), "x")
        assertEquals(fileParent.resolve("server"), findNameClash(fileParent, "SERVER"))
        assertNull(findNameClash(fileParent, "other"))
    }

    @Test
    fun findNameClash_missingParent_isNull() {
        assertNull(findNameClash(root.resolve("nope"), "demo"))
    }

    @Test
    fun nameLockFileName_isStableAndCaseInsensitive() {
        assertEquals(nameLockFileName("Demo"), nameLockFileName("demo"))
        assertTrue(nameLockFileName("demo").startsWith("name-"))
        assertTrue(nameLockFileName("demo").endsWith(".lock"))
        assertEquals("name-".length + 16 + ".lock".length, nameLockFileName("demo").length)
    }
}
