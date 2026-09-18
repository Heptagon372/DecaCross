package kr.decacross.collector.sources.fill

import kr.decacross.compat.model.Channel

/**
 * Fill 빌드 채널 → [Channel] (D13, AE-10: Fill `LegacyBuildChannelSerializer` 와 같은 접기).
 *
 * - `STABLE`, `RECOMMENDED` → [Channel.STABLE]
 * - `ALPHA`, `BETA` → [Channel.EXPERIMENTAL]
 * - 그 밖의 값 → [Channel.EXPERIMENTAL], known = false (호출자가 값마다 한 번 경고)
 *
 * # 불변식
 * - 모르는 값을 STABLE 로 올리지 않는다 (보수적으로 EXPERIMENTAL).
 */
internal fun foldFillChannel(raw: String): Pair<Channel, Boolean> = when (raw) {
    "STABLE", "RECOMMENDED" -> Channel.STABLE to true
    "ALPHA", "BETA" -> Channel.EXPERIMENTAL to true
    else -> Channel.EXPERIMENTAL to false
}
