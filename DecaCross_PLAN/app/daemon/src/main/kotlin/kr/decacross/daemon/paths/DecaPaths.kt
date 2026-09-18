package kr.decacross.daemon.paths

import kr.decacross.compat.model.Os
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * 데카크로스가 쓰는 디렉터리 두 갈래 (설계서 §14).
 *
 * - [internalRoot] 내부 데이터: 런타임·캐시·DB. 사용자가 건드리지 않는 곳 (Windows `%LOCALAPPDATA%\DecaCross`).
 * - [serversRoot] 서버 폴더: 사용자가 직접 열어 보고 백업하는 곳. **AppData 에 숨기지 않는다.**
 *
 * # 불변식
 * - 두 경로 모두 절대경로이고 정규화돼 있다.
 * - 스테이징([stagingRoot])은 항상 [serversRoot] 아래다 → 같은 볼륨이라 원자적 이동이 가능하다.
 * - 부분 다운로드([partialDir])는 스테이징 밖이다 → 롤백이 이어받기 데이터를 지우지 않는다.
 */
data class DecaPaths(val internalRoot: Path, val serversRoot: ServersRoot) {
    /** `cache/` */
    val cacheDir: Path get() = internalRoot.resolve("cache")

    /** 이어받기·검증 완료 파일 캐시 `cache/partial/{sha256}.part` (SCP-F2). 04 의 CAS 가 흡수한다. */
    val partialDir: Path get() = cacheDir.resolve("partial")

    /** 스테이징 루트 `servers/.staging/`. */
    val stagingRoot: Path get() = serversRoot.path.resolve(STAGING_DIR_NAME)

    companion object {
        /** 스테이징 디렉터리 이름. 서버 이름으로 쓸 수 없다 (점으로 시작하는 이름 전부 금지). */
        const val STAGING_DIR_NAME: String = ".staging"

        /** 서버 루트 강제 지정 환경변수 (`--servers-dir` 다음 우선순위). */
        const val SERVERS_DIR_ENV: String = "DECACROSS_SERVERS_DIR"

        /** 내부 루트 강제 지정 환경변수. 테스트·개발용 (실사용자 문서화 안 함). */
        const val HOME_ENV: String = "DECACROSS_HOME"

        /** 폴더 이름 표기 (SCP-I2: 문서의 `Decacross` 대신 이미 디스크에 있는 `DecaCross`). */
        const val APP_DIR_NAME: String = "DecaCross"

        /**
         * 환경에서 경로를 정한다. 파일을 만들지 않는다 (읽기 전용 판정).
         *
         * 서버 루트 우선순위: [serversDirOption] > [SERVERS_DIR_ENV] > 문서 폴더 정책([oneDrivePolicy]).
         * 문서 폴더가 OneDrive·UNC 아래면 [OneDrivePolicy.AVOID] 일 때 `{userHome}/DecaCross/servers` 로 대체하고 안내문을 붙인다.
         * [env] 조회는 Windows 에서 이름 대소문자 무시 ([hostEnvironment] 로 감싸 준다).
         *
         * @param os [currentOs]
         */
        fun resolve(
            env: Map<String, String>,
            os: Os,
            userHome: Path,
            serversDirOption: Path?,
            oneDrivePolicy: OneDrivePolicy = OneDrivePolicy.AVOID,
            documents: DocumentsLocator = DocumentsLocator.system(os, env, userHome),
        ): PathsResolution {
            // Windows 는 환경변수 이름 대소문자를 구분하지 않는다 (D-I40). 호출자가 이미 감쌌어도 한 번 더 감싸는 것은 무해하다.
            val e = hostEnvironment(os, env)
            val home = userHome.toAbsolutePath().normalize()
            val internalRoot = when (val r = resolveInternalRoot(e, os, home)) {
                is InternalRootResult.Invalid -> return PathsResolution.Invalid(r.reasonKo)
                is InternalRootResult.Ok -> r.path
            }
            val servers = resolveServersRoot(e, home, serversDirOption, oneDrivePolicy, documents)
            if (servers.path.startsWith(internalRoot)) {
                return PathsResolution.Invalid(
                    "서버 폴더(${servers.path})가 내부 데이터 폴더($internalRoot) 안에 있습니다. --servers-dir 로 다른 위치를 지정하세요.",
                )
            }
            return PathsResolution.Resolved(DecaPaths(internalRoot, servers))
        }

        /** 내부 루트 판정 결과 (내부 전용). */
        private sealed interface InternalRootResult {
            data class Ok(val path: Path) : InternalRootResult

            data class Invalid(val reasonKo: String) : InternalRootResult
        }

        private fun resolveInternalRoot(env: Map<String, String>, os: Os, userHome: Path): InternalRootResult {
            val explicit = env[HOME_ENV]?.takeIf { it.isNotBlank() }
            val root = if (explicit != null) {
                val p = try {
                    Path.of(explicit)
                } catch (e: InvalidPathException) {
                    return InternalRootResult.Invalid("$HOME_ENV 경로를 해석할 수 없습니다: $explicit")
                }
                if (!p.isAbsolute) return InternalRootResult.Invalid("$HOME_ENV 는 절대경로여야 합니다: $explicit")
                p
            } else {
                when (os) {
                    Os.WINDOWS -> {
                        val localAppData = env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }
                            ?: return InternalRootResult.Invalid(
                                "환경변수 LOCALAPPDATA 가 없어 내부 데이터 폴더를 정할 수 없습니다. $HOME_ENV 로 직접 지정하세요.",
                            )
                        val p = try {
                            Path.of(localAppData)
                        } catch (e: InvalidPathException) {
                            return InternalRootResult.Invalid("LOCALAPPDATA 경로를 해석할 수 없습니다: $localAppData")
                        }
                        p.resolve(APP_DIR_NAME)
                    }

                    Os.MAC -> userHome.resolve("Library").resolve("Application Support").resolve(APP_DIR_NAME)

                    Os.LINUX -> {
                        val xdg = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }
                        val base = if (xdg == null) {
                            userHome.resolve(".local").resolve("share")
                        } else {
                            try {
                                Path.of(xdg)
                            } catch (e: InvalidPathException) {
                                return InternalRootResult.Invalid("XDG_DATA_HOME 경로를 해석할 수 없습니다: $xdg")
                            }
                        }
                        base.resolve(APP_DIR_NAME)
                    }
                }
            }
            return InternalRootResult.Ok(root.toAbsolutePath().normalize())
        }

        private fun resolveServersRoot(
            env: Map<String, String>,
            userHome: Path,
            serversDirOption: Path?,
            oneDrivePolicy: OneDrivePolicy,
            documents: DocumentsLocator,
        ): ServersRoot {
            val fallback = userHome.resolve(APP_DIR_NAME).resolve(SERVERS_DIR_NAME).toAbsolutePath().normalize()
            if (serversDirOption != null) {
                val p = serversDirOption.toAbsolutePath().normalize()
                return ServersRoot(p, ServersRootSource.OPTION, oneDriveWarning(p, env))
            }
            val fromEnv = env[SERVERS_DIR_ENV]?.takeIf { it.isNotBlank() }
            if (fromEnv != null) {
                val p = try {
                    Path.of(fromEnv).toAbsolutePath().normalize()
                } catch (e: InvalidPathException) {
                    null
                }
                if (p != null) return ServersRoot(p, ServersRootSource.ENV, oneDriveWarning(p, env))
            }
            val docs = try {
                documents.documentsDir()?.toAbsolutePath()?.normalize()
            } catch (e: InvalidPathException) {
                null
            }
            if (docs == null) {
                return ServersRoot(
                    fallback,
                    ServersRootSource.FALLBACK_NO_DOCUMENTS,
                    "문서 폴더를 찾지 못해 서버를 $fallback 에 둡니다. 바꾸려면 --servers-dir 또는 $SERVERS_DIR_ENV.",
                )
            }
            if (isUncPath(docs)) {
                return ServersRoot(
                    fallback,
                    ServersRootSource.FALLBACK_UNC,
                    "문서 폴더($docs)가 네트워크 경로(UNC)라 서버를 $fallback 에 둡니다. 바꾸려면 --servers-dir 또는 $SERVERS_DIR_ENV.",
                )
            }
            val inDocuments = docs.resolve(APP_DIR_NAME).resolve(SERVERS_DIR_NAME)
            if (isUnderOneDrive(docs, env)) {
                return when (oneDrivePolicy) {
                    OneDrivePolicy.AVOID -> ServersRoot(
                        fallback,
                        ServersRootSource.FALLBACK_ONEDRIVE,
                        "문서 폴더($docs)가 OneDrive 동기화 폴더라 서버를 $fallback 에 둡니다. 바꾸려면 --servers-dir 또는 $SERVERS_DIR_ENV.",
                    )

                    OneDrivePolicy.ALLOW_WITH_WARNING -> ServersRoot(
                        inDocuments,
                        ServersRootSource.DOCUMENTS_ON_ONEDRIVE,
                        "서버 폴더($inDocuments)가 OneDrive 동기화 폴더 안입니다. 동기화가 파일을 잠그면 설치·실행이 실패할 수 있습니다.",
                    )
                }
            }
            return ServersRoot(inDocuments, ServersRootSource.DOCUMENTS, null)
        }

        /** 사용자가 직접 고른 경로가 OneDrive 아래면 경고만 한다 (위치를 바꾸지 않는다). */
        private fun oneDriveWarning(path: Path, env: Map<String, String>): String? =
            if (isUnderOneDrive(path, env)) {
                "서버 폴더($path)가 OneDrive 동기화 폴더 안입니다. 동기화가 파일을 잠그면 설치·실행이 실패할 수 있습니다."
            } else {
                null
            }

        /** 서버 폴더 이름 (`{루트}/DecaCross/servers`). */
        private const val SERVERS_DIR_NAME: String = "servers"
    }
}

/** 서버 루트를 어디서 정했는가. CLI 가 한 줄 안내를 띄울지 판단한다. */
enum class ServersRootSource {
    /** `--servers-dir` */
    OPTION,

    /** 환경변수 `DECACROSS_SERVERS_DIR` */
    ENV,

    /** 문서 폴더 (OneDrive 아님) */
    DOCUMENTS,

    /** 문서 폴더가 OneDrive 아래지만 정책이 허용함 (경고 동반) */
    DOCUMENTS_ON_ONEDRIVE,

    /** 문서 폴더가 OneDrive 아래라서 사용자 홈 아래로 대체 */
    FALLBACK_ONEDRIVE,

    /** 문서 폴더가 네트워크 경로(UNC)라서 대체 */
    FALLBACK_UNC,

    /** 문서 폴더를 알 수 없어서 대체 */
    FALLBACK_NO_DOCUMENTS,
}

/** 서버 루트와 그 결정 근거. [noticeKo] 가 있으면 CLI/UI 가 그대로 한 줄 보여준다. */
data class ServersRoot(val path: Path, val source: ServersRootSource, val noticeKo: String?)

/** 문서 폴더가 OneDrive 동기화 대상일 때의 정책 (SCP-I1, 사용자 결정 대기 — 기본 [AVOID]). */
enum class OneDrivePolicy {
    /** 동기화 폴더에 서버를 두지 않는다: `{userHome}/DecaCross/servers` 로 대체 + 안내. */
    AVOID,

    /** 문서 폴더를 그대로 쓰되 매번 경고한다. */
    ALLOW_WITH_WARNING,
}

/** [DecaPaths.resolve] 결과. 예외 대신 sealed 결과. */
sealed interface PathsResolution {
    /** 결정됨. */
    data class Resolved(val paths: DecaPaths) : PathsResolution

    /** 내부 루트를 정할 수 없음(예: `LOCALAPPDATA` 없음) 또는 지정 경로가 잘못됨. */
    data class Invalid(val reasonKo: String) : PathsResolution
}

/** 사용자 "문서" 폴더 조회. 테스트는 람다로 대체한다. */
fun interface DocumentsLocator {
    /** 문서 폴더 절대경로. 알 수 없으면 null. */
    fun documentsDir(): Path?

    companion object {
        /**
         * OS 기본 구현.
         * - Windows: `SHGetKnownFolderPath(FOLDERID_Documents)` (FFM). `%USERPROFILE%\Documents` 를 그대로 쓰지 않는다 —
         *   OneDrive 로 옮겨진 PC 에서는 엉뚱한(비어 있는) 폴더를 가리킨다 (research codebase-windows §2).
         * - macOS: `~/Documents`. Linux: `XDG_DOCUMENTS_DIR` (user-dirs.dirs) → `~/Documents`.
         */
        fun system(os: Os, env: Map<String, String>, userHome: Path): DocumentsLocator {
            val e = hostEnvironment(os, env)
            val home = userHome.toAbsolutePath().normalize()
            return when (os) {
                Os.WINDOWS -> DocumentsLocator { WindowsKnownFolders.documents() }
                Os.MAC -> DocumentsLocator { home.resolve("Documents") }
                Os.LINUX -> DocumentsLocator { xdgDocumentsDir(e, home) ?: home.resolve("Documents") }
            }
        }

        /**
         * `~/.config/user-dirs.dirs` 의 `XDG_DOCUMENTS_DIR="$HOME/문서"` 한 줄. 읽기 실패·형식 오류는 null.
         * (환경변수로 직접 온 값이 있으면 그것을 먼저 쓴다 — 데스크톱 세션이 export 한 경우.)
         */
        private fun xdgDocumentsDir(env: Map<String, String>, userHome: Path): Path? {
            val fromEnv = env["XDG_DOCUMENTS_DIR"]?.takeIf { it.isNotBlank() }
            if (fromEnv != null) return parseXdgValue(fromEnv, userHome)
            val configHome = env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }?.let { value ->
                try {
                    Path.of(value)
                } catch (e: InvalidPathException) {
                    null
                }
            } ?: userHome.resolve(".config")
            val file = configHome.resolve("user-dirs.dirs")
            val lines = try {
                if (Files.isRegularFile(file)) Files.readAllLines(file, Charsets.UTF_8) else return null
            } catch (e: IOException) {
                return null
            } catch (e: SecurityException) {
                return null
            }
            val raw = lines
                .map { it.trim() }
                .lastOrNull { it.startsWith("XDG_DOCUMENTS_DIR=") }
                ?.substringAfter('=')
                ?.trim()
                ?.trim('"')
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return parseXdgValue(raw, userHome)
        }

        private fun parseXdgValue(raw: String, userHome: Path): Path? {
            val expanded = raw
                .replace("\${HOME}", userHome.toString())
                .replace("\$HOME", userHome.toString())
            return try {
                Path.of(expanded).takeIf { it.isAbsolute }
            } catch (e: InvalidPathException) {
                null
            }
        }
    }
}
