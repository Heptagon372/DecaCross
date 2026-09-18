package kr.decacross.daemon.paths

import kr.decacross.compat.model.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [DecaPaths.resolve] 규칙 (DESIGN2 §2.5, D-I1/D-I40).
 * 실제 사용자 폴더·환경을 읽지 않는다 — 전부 임시 디렉터리와 주입한 환경 맵이다 (§4.2 규칙 3).
 */
class DecaPathsTest {
    private val root: Path = Files.createTempDirectory("dcx-paths")
    private val userHome: Path = Files.createDirectories(root.resolve("home"))
    private val localAppData: Path = Files.createDirectories(root.resolve("AppData/Local"))

    private fun windowsEnv(vararg extra: Pair<String, String>): Map<String, String> =
        hostEnvironment(Os.WINDOWS, mapOf("LOCALAPPDATA" to localAppData.toString()) + extra.toMap())

    private fun docs(locator: Path?): DocumentsLocator = DocumentsLocator { locator }

    private fun resolved(result: PathsResolution): DecaPaths =
        (result as? PathsResolution.Resolved)?.paths ?: fail("Resolved 가 아님: $result")

    @Test
    fun internalRoot_windows_isLocalAppDataDecaCross() {
        val paths = resolved(
            DecaPaths.resolve(windowsEnv(), Os.WINDOWS, userHome, null, documents = docs(null)),
        )
        assertEquals(localAppData.resolve("DecaCross"), paths.internalRoot)
        assertEquals(paths.internalRoot.resolve("cache").resolve("partial"), paths.partialDir)
        assertEquals(paths.serversRoot.path.resolve(".staging"), paths.stagingRoot)
    }

    @Test
    fun internalRoot_windows_missingLocalAppData_isInvalid() {
        val result = DecaPaths.resolve(hostEnvironment(Os.WINDOWS, emptyMap()), Os.WINDOWS, userHome, null, documents = docs(null))
        val invalid = result as? PathsResolution.Invalid ?: fail("Invalid 여야 함: $result")
        assertTrue(invalid.reasonKo.contains("LOCALAPPDATA"), invalid.reasonKo)
    }

    @Test
    fun internalRoot_decacrossHome_wins_andMustBeAbsolute() {
        val custom = Files.createDirectories(root.resolve("custom-home"))
        val paths = resolved(
            DecaPaths.resolve(
                windowsEnv(DecaPaths.HOME_ENV to custom.toString()),
                Os.WINDOWS,
                userHome,
                null,
                documents = docs(null),
            ),
        )
        assertEquals(custom, paths.internalRoot)

        val relative = DecaPaths.resolve(
            windowsEnv(DecaPaths.HOME_ENV to "relative/path"),
            Os.WINDOWS,
            userHome,
            null,
            documents = docs(null),
        )
        assertTrue(relative is PathsResolution.Invalid, "상대경로 ${DecaPaths.HOME_ENV} 는 Invalid: $relative")
    }

    @Test
    fun internalRoot_linux_usesXdgDataHome() {
        val xdg = Files.createDirectories(root.resolve("xdg-data"))
        val paths = resolved(
            DecaPaths.resolve(mapOf("XDG_DATA_HOME" to xdg.toString()), Os.LINUX, userHome, null, documents = docs(null)),
        )
        assertEquals(xdg.resolve("DecaCross"), paths.internalRoot)

        val fallback = resolved(DecaPaths.resolve(emptyMap(), Os.LINUX, userHome, null, documents = docs(null)))
        assertEquals(userHome.resolve(".local").resolve("share").resolve("DecaCross"), fallback.internalRoot)
    }

    @Test
    fun internalRoot_mac_usesApplicationSupport() {
        val paths = resolved(DecaPaths.resolve(emptyMap(), Os.MAC, userHome, null, documents = docs(null)))
        assertEquals(userHome.resolve("Library").resolve("Application Support").resolve("DecaCross"), paths.internalRoot)
    }

    @Test
    fun serversRoot_option_winsOverEnvAndDocuments() {
        val option = root.resolve("chosen")
        val documents = Files.createDirectories(root.resolve("Documents"))
        val paths = resolved(
            DecaPaths.resolve(
                windowsEnv(DecaPaths.SERVERS_DIR_ENV to root.resolve("from-env").toString()),
                Os.WINDOWS,
                userHome,
                option,
                documents = docs(documents),
            ),
        )
        assertEquals(ServersRootSource.OPTION, paths.serversRoot.source)
        assertEquals(option, paths.serversRoot.path)
        assertNull(paths.serversRoot.noticeKo)
    }

    @Test
    fun serversRoot_env_winsOverDocuments() {
        val fromEnv = root.resolve("from-env")
        val documents = Files.createDirectories(root.resolve("Documents2"))
        val paths = resolved(
            DecaPaths.resolve(
                windowsEnv(DecaPaths.SERVERS_DIR_ENV to fromEnv.toString()),
                Os.WINDOWS,
                userHome,
                null,
                documents = docs(documents),
            ),
        )
        assertEquals(ServersRootSource.ENV, paths.serversRoot.source)
        assertEquals(fromEnv, paths.serversRoot.path)
    }

    @Test
    fun serversRoot_documents_notOnOneDrive_hasNoNotice() {
        val documents = Files.createDirectories(root.resolve("Documents3"))
        val paths = resolved(DecaPaths.resolve(windowsEnv(), Os.WINDOWS, userHome, null, documents = docs(documents)))
        assertEquals(ServersRootSource.DOCUMENTS, paths.serversRoot.source)
        assertEquals(documents.resolve("DecaCross").resolve("servers"), paths.serversRoot.path)
        assertNull(paths.serversRoot.noticeKo)
    }

    @Test
    fun serversRoot_documentsOnOneDrive_avoid_fallsBackToUserHome() {
        val oneDrive = Files.createDirectories(root.resolve("OneDrive"))
        val documents = Files.createDirectories(oneDrive.resolve("문서"))
        val paths = resolved(
            DecaPaths.resolve(
                windowsEnv("OneDrive" to oneDrive.toString()),
                Os.WINDOWS,
                userHome,
                null,
                OneDrivePolicy.AVOID,
                docs(documents),
            ),
        )
        assertEquals(ServersRootSource.FALLBACK_ONEDRIVE, paths.serversRoot.source)
        assertEquals(userHome.resolve("DecaCross").resolve("servers"), paths.serversRoot.path)
        val notice = assertNotNull(paths.serversRoot.noticeKo)
        assertTrue(notice.contains("OneDrive") && notice.contains("--servers-dir"), notice)
    }

    @Test
    fun serversRoot_documentsOnOneDrive_allow_usesDocumentsWithWarning() {
        val oneDrive = Files.createDirectories(root.resolve("OneDrive2"))
        val documents = Files.createDirectories(oneDrive.resolve("Documents"))
        val paths = resolved(
            DecaPaths.resolve(
                windowsEnv("OneDrive" to oneDrive.toString()),
                Os.WINDOWS,
                userHome,
                null,
                OneDrivePolicy.ALLOW_WITH_WARNING,
                docs(documents),
            ),
        )
        assertEquals(ServersRootSource.DOCUMENTS_ON_ONEDRIVE, paths.serversRoot.source)
        assertEquals(documents.resolve("DecaCross").resolve("servers"), paths.serversRoot.path)
        assertNotNull(paths.serversRoot.noticeKo)
    }

    @Test
    fun serversRoot_uncDocuments_fallsBack() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "UNC 경로는 Windows 에서만 의미가 있다")
        val unc = Path.of("\\\\nas\\share\\Documents")
        val paths = resolved(DecaPaths.resolve(windowsEnv(), Os.WINDOWS, userHome, null, documents = docs(unc)))
        assertEquals(ServersRootSource.FALLBACK_UNC, paths.serversRoot.source)
        assertEquals(userHome.resolve("DecaCross").resolve("servers"), paths.serversRoot.path)
        assertNotNull(paths.serversRoot.noticeKo)
    }

    @Test
    fun serversRoot_noDocuments_fallsBack() {
        val paths = resolved(DecaPaths.resolve(windowsEnv(), Os.WINDOWS, userHome, null, documents = docs(null)))
        assertEquals(ServersRootSource.FALLBACK_NO_DOCUMENTS, paths.serversRoot.source)
        assertEquals(userHome.resolve("DecaCross").resolve("servers"), paths.serversRoot.path)
        assertNotNull(paths.serversRoot.noticeKo)
    }

    @Test
    fun serversRoot_optionUnderOneDrive_keepsPathButWarns() {
        val oneDrive = Files.createDirectories(root.resolve("OneDrive3"))
        val option = oneDrive.resolve("servers")
        val paths = resolved(
            DecaPaths.resolve(windowsEnv("OneDrive" to oneDrive.toString()), Os.WINDOWS, userHome, option, documents = docs(null)),
        )
        assertEquals(ServersRootSource.OPTION, paths.serversRoot.source)
        assertEquals(option, paths.serversRoot.path)
        assertNotNull(paths.serversRoot.noticeKo)
    }

    @Test
    fun windowsEnvNames_areCaseInsensitive() {
        // 일반 Windows 셸은 `Path`, Git Bash 는 `PROGRAMFILES` — 이름 대소문자를 구분하면 안 된다 (D-I40)
        val plain = mapOf("localappdata" to localAppData.toString(), "Path" to "C:\\Windows")
        val paths = resolved(DecaPaths.resolve(plain, Os.WINDOWS, userHome, null, documents = docs(null)))
        assertEquals(localAppData.resolve("DecaCross"), paths.internalRoot)
    }

    @Test
    fun serversRootInsideInternalRoot_isInvalid() {
        val inside = localAppData.resolve("DecaCross").resolve("servers")
        val result = DecaPaths.resolve(windowsEnv(), Os.WINDOWS, userHome, inside, documents = docs(null))
        val invalid = result as? PathsResolution.Invalid ?: fail("Invalid 여야 함: $result")
        assertTrue(invalid.reasonKo.contains("내부 데이터"), invalid.reasonKo)
    }

    @Test
    fun documentsLocator_linux_readsUserDirsFile() {
        val config = Files.createDirectories(userHome.resolve(".config"))
        val documents = Files.createDirectories(userHome.resolve("문서"))
        Files.writeString(
            config.resolve("user-dirs.dirs"),
            "XDG_DESKTOP_DIR=\"\$HOME/바탕화면\"\nXDG_DOCUMENTS_DIR=\"\$HOME/문서\"\n",
        )
        val locator = DocumentsLocator.system(Os.LINUX, emptyMap(), userHome)
        assertEquals(documents, locator.documentsDir())
    }

    @Test
    fun documentsLocator_windows_returnsExistingAbsoluteDirectory() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "SHGetKnownFolderPath 는 Windows 전용")
        val locator = DocumentsLocator.system(Os.WINDOWS, hostEnvironment(), Path.of(System.getProperty("user.home")))
        val documents = assertNotNull(locator.documentsDir(), "FFM 으로 문서 폴더를 찾지 못했다")
        assertTrue(documents.isAbsolute, "$documents")
        assertTrue(Files.isDirectory(documents), "$documents 가 디렉터리가 아니다")
        assertTrue(documents.name.isNotEmpty())
    }
}
