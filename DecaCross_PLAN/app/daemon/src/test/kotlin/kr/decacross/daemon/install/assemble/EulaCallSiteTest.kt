package kr.decacross.daemon.install.assemble

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ★ `eula=true` 를 누가 쓸 수 있는지를 **실제로** 막는 검사 (verify03 F2).
 *
 * 이 프로젝트에서 가장 위험한 한 줄은 "동의 없이 eula.txt 를 쓰는 코드" 다. 지금까지 그 규칙은 주석 두 개가
 * `merge_wps2.py 가 다른 호출을 거부한다` 고 **주장**만 했고, 그 스크립트는 레포에 없었다 — Gradle 태스크도
 * ktlint 규칙도 테스트도 없었다. 그래서 규칙을 소스에 대고 직접 확인한다.
 *
 * # 불변식
 * - app 아래 모든 모듈의 main 소스에서 [EULA_SYMBOLS] 를 언급하는 파일 집합은 [ALLOWED_FILES] 와 정확히 같다.
 * - 새 호출처가 생기면 이 시험이 **경로를 대며** 깨진다. 정말 필요하면 여기에 한 줄 추가하고,
 *   왜 그 자리가 동의를 확인한 뒤인지 리뷰에서 설명해라.
 */
class EulaCallSiteTest {
    @Test
    fun `eula를 쓰는 곳은 허용 목록뿐이다`() {
        val repoRoot = findRepoRoot()
        val mainRoots = Files.list(repoRoot.resolve("app")).use { stream -> stream.toList() }
            .map { it.resolve("src").resolve("main").resolve("kotlin") }
            .filter { it.exists() }
        assertTrue(mainRoots.size >= 2, "app 아래 모듈 소스를 찾지 못했다: $mainRoots")

        val found = sortedSetOf<String>()
        for (root in mainRoots) {
            Files.walk(root).use { stream -> stream.toList() }
                .filter { Files.isRegularFile(it) && it.name.endsWith(".kt") }
                .forEach { file ->
                    val text = Files.readString(file, Charsets.UTF_8)
                    if (EULA_SYMBOLS.any { it in text }) found.add(repoRoot.relativize(file).toString().replace('\\', '/'))
                }
        }
        assertEquals(
            ALLOWED_FILES,
            found.toSet(),
            "eula.txt 를 쓰는(또는 그 이름을 아는) 파일 집합이 바뀌었다. " +
                "새 호출처가 정말 사용자 동의 뒤인지 확인하고 허용 목록을 고쳐라",
        )
    }

    private companion object {
        /** eula.txt 를 만들 수 있는 심볼. 이름만 알아도(=파일을 직접 쓸 수 있어도) 목록에 들어와야 한다. */
        val EULA_SYMBOLS = listOf("renderEulaTxt(", "writeEulaAccepted(", "EULA_FILE_NAME")

        /**
         * 허용 호출처.
         * - `Config.kt` 렌더러 본체 (`internal`)
         * - `ServerCatalog.kt` `writeEulaAccepted` 와 동의 여부 읽기
         * - `Pipeline.kt` 설치 EULA 단계 — `EulaAnswer.Accepted` 분기 안에서만
         * - `StartCommand.kt` CLI `start` 의 동의 경로
         */
        val ALLOWED_FILES = setOf(
            "app/cli/src/main/kotlin/kr/decacross/cli/StartCommand.kt",
            "app/daemon/src/main/kotlin/kr/decacross/daemon/install/Config.kt",
            "app/daemon/src/main/kotlin/kr/decacross/daemon/install/Pipeline.kt",
            "app/daemon/src/main/kotlin/kr/decacross/daemon/install/ServerCatalog.kt",
        )

        /** 테스트 작업 디렉터리(모듈 폴더)에서 위로 올라가며 `settings.gradle.kts` 가 있는 곳을 찾는다. */
        fun findRepoRoot(): Path {
            var current: Path? = Path.of("").toAbsolutePath().normalize()
            while (current != null) {
                if (current.resolve("settings.gradle.kts").exists()) return current
                current = current.parent
            }
            error("레포 루트(settings.gradle.kts)를 찾지 못했다: ${Path.of("").toAbsolutePath()}")
        }
    }
}
