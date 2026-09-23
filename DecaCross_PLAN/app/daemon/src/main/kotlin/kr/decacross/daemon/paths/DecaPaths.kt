package kr.decacross.daemon.paths

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.filechooser.FileSystemView

/**
 * 사용자 PC 디렉터리 레이아웃 (설계서 §14, 명세 §8).
 *
 * - [appData]     내부 데이터. 사용자가 안 건드림. `%LOCALAPPDATA%\Decacross`
 * - [serversRoot] ★ 사용자가 직접 접근하는 곳. `Documents\Decacross\servers`. AppData 에 서버를 숨기지 않는다.
 *
 * 환경변수 `DECACROSS_HOME` / `DECACROSS_SERVERS` 로 덮어쓸 수 있다 (테스트·CI·포터블 설치).
 */
class DecaPaths(
    val appData: Path,
    val serversRoot: Path,
) {
    /** 서버 실행용 Java 런타임 (Adoptium 다운로드). 버전별 1벌. */
    val runtimes: Path get() = appData.resolve("runtimes")

    /**
     * ★ 런처 자신의 번들 JRE (jlink). 마크 서버 실행에 절대 쓰지 않는다 (CLAUDE.md 불변식 9).
     * 이 경로가 start 스크립트에 들어가면 테스트가 잡는다.
     */
    val bundledJre: Path get() = appData.resolve("jre")

    val cacheBlobs: Path get() = appData.resolve("cache").resolve("blobs")
    val cacheTmp: Path get() = appData.resolve("cache").resolve("tmp")
    val db: Path get() = appData.resolve("db")
    val logs: Path get() = appData.resolve("logs")

    /** 설치 스테이징. 서버 폴더와 같은 볼륨이어야 원자적 이동이 된다. */
    val staging: Path get() = serversRoot.resolve(".staging")

    fun server(name: String): Path = serversRoot.resolve(name)

    companion object {
        fun detect(env: Map<String, String> = System.getenv()): DecaPaths {
            val home = Paths.get(System.getProperty("user.home"))
            val os = System.getProperty("os.name").lowercase()
            val appData = env["DECACROSS_HOME"]?.let(Paths::get) ?: when {
                os.contains("win") -> (env["LOCALAPPDATA"]?.let(Paths::get) ?: home.resolve("AppData/Local")).resolve("Decacross")
                os.contains("mac") -> home.resolve("Library/Application Support/Decacross")
                else -> (env["XDG_DATA_HOME"]?.let(Paths::get) ?: home.resolve(".local/share")).resolve("decacross")
            }
            val servers = env["DECACROSS_SERVERS"]?.let(Paths::get)
                ?: documentsDir(home).resolve("Decacross").resolve("servers")
            return DecaPaths(appData, servers)
        }

        /** Windows 의 "문서" 폴더는 OneDrive 로 리다이렉트될 수 있어 셸 API 로 실제 위치를 묻는다. */
        private fun documentsDir(home: Path): Path =
            runCatching { FileSystemView.getFileSystemView().defaultDirectory?.toPath() }
                .getOrNull()
                ?.takeIf { Files.isDirectory(it) }
                ?: home.resolve("Documents")
    }
}
