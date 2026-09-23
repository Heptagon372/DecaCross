package kr.decacross.daemon.api

import kotlinx.serialization.Serializable
import kr.decacross.compat.model.CoreKey
import kr.decacross.daemon.install.InstalledServer
import kr.decacross.daemon.process.ServerState
import kr.decacross.daemon.process.ServerStatus

@Serializable
data class ServerSummary(
    val id: String,
    val name: String,
    val mcLabel: String,
    val core: CoreKey,
    val build: String,
    val port: Int,
    val ramMb: Int,
    val state: ServerState,
)

@Serializable
data class ServerDetail(
    val server: InstalledServer,
    val status: ServerStatus,
    val recentLines: List<String>,
    val diagnoses: List<kr.decacross.daemon.diagnosis.Diagnosis> = emptyList(),
)

@Serializable
data class FixRequest(val action: kr.decacross.daemon.diagnosis.FixActionDto)

@Serializable
data class FixResult(val appliedKo: String, val restartRequired: Boolean = false)

@Serializable
data class StopRequest(val force: Boolean = false)

@Serializable
data class CommandRequest(val line: String)

@Serializable
data class InstallRequest(
    val name: String,
    val mc: String,
    val core: CoreKey = CoreKey.PAPER,
    val ramMb: Int,
    val port: Int = 25565,
    /** 사용자가 네이티브 UI 에서 체크했을 때만 true. */
    val acceptEula: Boolean,
    val allowExperimental: Boolean = false,
    val properties: Map<String, String> = emptyMap(),
    /** 07: 플러그인 슬러그. 비어 있지 않으면 resolve() 로 의존성까지 확정한 뒤 설치한다. */
    val plugins: List<String> = emptyList(),
)

@Serializable
data class JobRef(val jobId: String)

@Serializable
data class McVersionDto(val label: String, val ordinal: Int, val javaMin: Int, val javaRecommended: Int, val isSnapshot: Boolean, val hasStableBuild: Boolean)

@Serializable
data class RuntimeDto(val feature: Int, val path: String, val versionString: String)

@Serializable
data class CasStatsDto(val blobCount: Long, val totalBytes: Long)

@Serializable
data class SystemInfoDto(val totalMemoryMb: Long, val recommendedRamMb: Int, val daemonRssMb: Long, val version: String)

@Serializable
data class ErrorDto(val errorKo: String)

/** WS 프레임 (서버 스트림). */
@Serializable
sealed interface StreamFrame {
    @Serializable
    @kotlinx.serialization.SerialName("log")
    data class Log(val lines: List<String>) : StreamFrame

    @Serializable
    @kotlinx.serialization.SerialName("status")
    data class Status(val status: ServerStatus) : StreamFrame

    @Serializable
    @kotlinx.serialization.SerialName("shutdown")
    data class Shutdown(val phase: String) : StreamFrame

    /** 06: 새 진단 카드 */
    @Serializable
    @kotlinx.serialization.SerialName("diagnosis")
    data class DiagnosisFrame(val diagnosis: kr.decacross.daemon.diagnosis.Diagnosis) : StreamFrame
}

/** WS 프레임 (설치 스트림). */
@Serializable
data class InstallFrame(
    val type: String,
    val stage: String? = null,
    val done: Long? = null,
    val total: Long? = null,
    val textKo: String? = null,
    val server: InstalledServer? = null,
)
