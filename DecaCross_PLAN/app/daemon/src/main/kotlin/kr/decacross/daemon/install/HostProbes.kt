package kr.decacross.daemon.install

import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

/** 물리 메모리 (바이트). [availableBytes] 는 OS 가 말하는 "사용 가능" (Windows: MEMORYSTATUSEX.ullAvailPhys). */
data class MemoryInfo(val totalBytes: Long, val availableBytes: Long)

/** 메모리 조회. 테스트는 고정값 람다. */
fun interface MemoryProbe {
    /** 알 수 없으면 null. */
    fun read(): MemoryInfo?

    companion object {
        /** `com.sun.management.OperatingSystemMXBean` (jdk.management). 없으면 null. */
        val SYSTEM: MemoryProbe = MemoryProbe {
            val bean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            bean?.let { MemoryInfo(it.totalMemorySize, it.freeMemorySize) }
        }
    }
}

/** 디스크 여유 공간 조회. 테스트는 고정값 람다. */
fun interface DiskSpaceProbe {
    /** [path] 가 아직 없으면 존재하는 가장 가까운 조상의 파일 저장소 기준. 알 수 없으면 null. */
    fun usableBytes(path: Path): Long?

    companion object {
        val SYSTEM: DiskSpaceProbe = DiskSpaceProbe { path ->
            var p: Path? = path.toAbsolutePath()
            while (p != null && !Files.exists(p)) p = p.parent
            if (p == null) {
                null
            } else {
                try {
                    Files.getFileStore(p).usableSpace
                } catch (e: IOException) {
                    null
                }
            }
        }
    }
}
