package kr.decacross.daemon.install

import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kr.decacross.daemon.paths.DecaPaths
import kr.decacross.daemon.runtime.Cas
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * 설치 상태머신 (설계서 §4.1).
 *
 * IDLE → RESOLVE → PLAN → FETCH → VERIFY → LAYOUT → CONFIG → EULA → READY
 * 실패 → ROLLBACK(스테이징 삭제) → 사용자 폴더 무변화.
 *
 * 전부 `servers/.staging/{uuid}/` 에서 조립하고 마지막에 한 번의 rename 으로 등장한다.
 */
class InstallPipeline(
    private val paths: DecaPaths,
    private val fetcher: Fetcher,
    private val cas: Cas = Cas(paths.cacheBlobs),
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
    },
) {
    private class Abort(val stage: InstallStage, val error: InstallError) : RuntimeException()

    fun run(spec: InstallSpec): Flow<InstallEvent> = channelFlow {
        var staging: Path? = null
        var stage = InstallStage.RESOLVE
        try {
            // ── RESOLVE ────────────────────────────────────────────
            stage = enter(InstallStage.RESOLVE)
            validateName(spec.name)?.let { throw Abort(stage, InstallError.InvalidName(spec.name, it)) }
            val target = paths.server(spec.name)
            if (Files.exists(target)) throw Abort(stage, InstallError.AlreadyExists(target))
            if (spec.ramMb < 512) throw Abort(stage, InstallError.InvalidName(spec.name, "RAM 은 512MB 이상이어야 합니다"))

            // ── PLAN ───────────────────────────────────────────────
            stage = enter(InstallStage.PLAN)
            val coreJar = coreJarName(spec)
            val cached = cas.has(spec.core.sha256.lowercase())
            send(
                InstallEvent.Message(
                    "다운로드 계획: $coreJar (${mb(spec.core.size)}MB)" + if (cached) " — 캐시 히트, 다운로드 생략" else "",
                ),
            )

            // ── FETCH ──────────────────────────────────────────────
            stage = enter(InstallStage.FETCH)
            Files.createDirectories(paths.staging)
            staging = Atomic.newStaging(paths.staging)
            if (!cached) fetchCore(spec, stage)

            // ── VERIFY ─────────────────────────────────────────────
            stage = enter(InstallStage.VERIFY)
            val jarInStaging = staging.resolve(coreJar)
            cas.linkInto(spec.core.sha256.lowercase(), jarInStaging)
            val actual = sha256(jarInStaging)
            if (!actual.equals(spec.core.sha256, ignoreCase = true)) {
                throw Abort(stage, InstallError.HashMismatch(coreJar, spec.core.sha256, actual))
            }
            if (!isValidZip(jarInStaging)) throw Abort(stage, InstallError.CorruptArchive(coreJar))
            send(InstallEvent.Message("무결성 확인: sha256 일치, zip 정상"))

            // ── LAYOUT ─────────────────────────────────────────────
            stage = enter(InstallStage.LAYOUT)
            listOf("plugins", "logs", ".decacross").forEach { Files.createDirectories(staging.resolve(it)) }

            // ── CONFIG ─────────────────────────────────────────────
            stage = enter(InstallStage.CONFIG)
            Config.writeServerProperties(staging, Config.defaultProperties(spec))
            Config.writeStartScripts(staging, spec, coreJar)
            val manifest = InstallManifest(listOf(ManifestEntry(coreJar, spec.core.sha256.lowercase(), spec.core.size)))
            Files.writeString(staging.resolve(".decacross/manifest.json"), json.encodeToString(InstallManifest.serializer(), manifest))

            // ── EULA ───────────────────────────────────────────────
            stage = enter(InstallStage.EULA)
            if (!spec.acceptEula) throw Abort(stage, InstallError.EulaNotAccepted)
            val now = Instant.now()
            Config.writeEula(staging, accepted = true, at = now)
            val record = InstalledServer(
                name = spec.name,
                dir = target.toAbsolutePath().toString(),
                mcLabel = spec.mc.label,
                mcOrdinal = spec.mc.ordinal.value,
                core = spec.core.core,
                build = spec.core.build,
                coreJar = coreJar,
                javaExe = spec.javaExe.toAbsolutePath().toString(),
                ramMb = spec.ramMb,
                port = spec.port,
                createdAt = now.toString(),
                eulaAcceptedAt = now.toString(),
            )
            Files.writeString(staging.resolve(".decacross/server.json"), json.encodeToString(InstalledServer.serializer(), record))

            // ── READY ──────────────────────────────────────────────
            stage = enter(InstallStage.READY)
            try {
                Atomic.moveInto(staging, target)
            } catch (e: IOException) {
                throw Abort(stage, InstallError.Io(e.message ?: e.toString()))
            }
            staging = null
            send(InstallEvent.Completed(record))
        } catch (a: Abort) {
            staging?.let(Atomic::deleteStaging)
            send(InstallEvent.Failed(a.stage, a.error))
        } catch (e: Exception) {
            staging?.let(Atomic::deleteStaging)
            send(InstallEvent.Failed(stage, InstallError.Io(e.message ?: e.toString())))
        }
    }

    private suspend fun ProducerScope<InstallEvent>.enter(stage: InstallStage): InstallStage {
        send(InstallEvent.StageChanged(stage))
        return stage
    }

    private suspend fun ProducerScope<InstallEvent>.fetchCore(spec: InstallSpec, stage: InstallStage) {
        Files.createDirectories(paths.cacheTmp)
        val tmp = paths.cacheTmp.resolve(spec.core.sha256.lowercase() + ".download")
        try {
            fetcher.download(listOf(spec.core.downloadUrl), tmp, spec.core.size) { done, total ->
                send(InstallEvent.Progress(stage, done, total, coreJarName(spec)))
            }
        } catch (e: IOException) {
            throw Abort(stage, InstallError.Network(spec.core.downloadUrl, e.cause?.message ?: e.message ?: "네트워크 오류"))
        }
        val actual = sha256(tmp)
        // ★ 불일치는 재시도하지 않는다 — 변조 의심. 즉시 중단.
        cas.put(tmp, spec.core.sha256.lowercase(), actual)
            ?: throw Abort(InstallStage.VERIFY, InstallError.HashMismatch(coreJarName(spec), spec.core.sha256, actual))
    }

    companion object {
        private val NAME_RE = Regex("^[A-Za-z0-9가-힣_][A-Za-z0-9가-힣_ .-]{0,63}$")

        /** 폴더 이름으로 안전한가. null 이면 OK. */
        fun validateName(name: String): String? =
            when {
                name.isBlank() -> "비어 있음"
                name == "." || name == ".." -> "예약된 이름"
                name.startsWith(".") -> "점으로 시작할 수 없음"
                name.endsWith(".") || name.endsWith(" ") -> "점이나 공백으로 끝날 수 없음"
                !NAME_RE.matches(name) -> "영문·숫자·한글·공백·_ . - 만 허용 (64자 이내)"
                else -> null
            }

        fun coreJarName(spec: InstallSpec): String = "${spec.core.core.name.lowercase()}-${spec.mc.label}-${spec.core.build}.jar"

        private fun mb(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)
    }
}
