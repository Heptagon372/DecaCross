package kr.decacross.compat.model

import kotlinx.serialization.Serializable

// ── 코어 / 런타임 ───────────────────────────────────────

@Serializable
public enum class CoreKey { PAPER, PURPUR, FOLIA, SPIGOT, VANILLA, FABRIC, NEOFORGE, FORGE }

@Serializable
public enum class LoaderFamily { BUKKIT, FABRIC, FORGE, VANILLA }

/** 코어 빌드 채널. PaperMC Fill 의 ALPHA/BETA 는 전부 EXPERIMENTAL 로 접는다. */
@Serializable
public enum class Channel { STABLE, EXPERIMENTAL }

@Serializable
public data class CoreBuild(
    val core: CoreKey,
    val mc: McOrdinal,
    val build: String,
    val channel: Channel,
    val downloadUrl: String,
    val sha256: String,
    val size: Long,
)

@Serializable
public enum class Distribution { TEMURIN }

@Serializable
public enum class ImageType { JRE, JDK }

@Serializable
public enum class Os { WINDOWS, MAC, LINUX }

@Serializable
public enum class Arch { X64, AARCH64 }

@Serializable
public data class JavaSpec(
    val feature: Int,
    val distribution: Distribution = Distribution.TEMURIN,
    val image: ImageType = ImageType.JRE,
)
