package kr.decacross.analysis

import java.nio.file.Path
import java.util.UUID

// K-3 Jmx / K-4 Nbt — Phase 1 에서는 시그니처만 확정. Phase 2~3 에서 구현.

public data class ServerMetrics(
    val heapUsedBytes: Long,
    val heapMaxBytes: Long,
    val threadCount: Int,
    val gcPauseMsLastMinute: Long,
)

public data class PlayerNbt(val uuid: UUID, val raw: Map<String, Any?>)

public data class LevelInfo(val levelName: String, val seed: Long?, val dataVersion: Int?)

/** 힙 / 스레드 / GC — spark 없이 */
public fun attachJmx(pid: Long): ServerMetrics? = TODO("Phase 2")

public fun readPlayerData(worldDir: Path, uuid: UUID): PlayerNbt? = TODO("Phase 2")

public fun readLevelInfo(worldDir: Path): LevelInfo? = TODO("Phase 2")
