package kr.decacross.daemon.store

import kotlinx.serialization.json.Json
import kr.decacross.daemon.install.InstalledServer
import kr.decacross.daemon.paths.DecaPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/**
 * 설치된 서버 목록. 진실 소스는 각 서버 폴더의 `.decacross/server.json` 이다 — 별도 DB 가 없어도
 * 사용자가 폴더를 옮기거나 지워도 목록이 맞는다 (락인 없음).
 * 서버 id = 폴더 이름.
 */
class ServerRegistry(private val paths: DecaPaths) {
    private val json = Json { ignoreUnknownKeys = true }

    fun list(): List<InstalledServer> {
        if (!Files.isDirectory(paths.serversRoot)) return emptyList()
        return Files.list(paths.serversRoot).use { s ->
            s.filter { Files.isRegularFile(it.resolve(".decacross/server.json")) }.toList()
        }.mapNotNull(::read).sortedBy { it.name }
    }

    fun get(id: String): InstalledServer? {
        if (id.contains('/') || id.contains('\\') || id == "." || id == "..") return null
        return read(paths.serversRoot.resolve(id))
    }

    /** 서버 삭제. [keepWorld] 면 `world*` 폴더를 `<servers>/.trash/<name>-<ts>/` 로 옮겨 남긴다. */
    @OptIn(ExperimentalPathApi::class)
    fun delete(id: String, keepWorld: Boolean): Boolean {
        val server = get(id) ?: return false
        val dir = Path.of(server.dir)
        if (keepWorld) {
            val trash = paths.serversRoot.resolve(".trash").resolve("${server.name}-${System.currentTimeMillis()}")
            Files.createDirectories(trash)
            Files.list(dir).use { s ->
                s.filter { it.fileName.toString().startsWith("world") }.toList().forEach { w ->
                    Files.move(w, trash.resolve(w.fileName.toString()))
                }
            }
        }
        dir.deleteRecursively()
        return true
    }

    private fun read(dir: Path): InstalledServer? {
        val f = dir.resolve(".decacross/server.json")
        if (!Files.isRegularFile(f)) return null
        return runCatching { json.decodeFromString(InstalledServer.serializer(), Files.readString(f)) }
            .getOrNull()
            // 폴더가 옮겨졌을 수 있으니 실제 위치를 우선한다
            ?.copy(dir = dir.toAbsolutePath().toString(), name = dir.fileName.toString())
    }
}
