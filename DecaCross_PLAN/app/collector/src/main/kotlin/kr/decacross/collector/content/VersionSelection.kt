package kr.decacross.collector.content

import kr.decacross.collector.sources.hangar.FLAG_UNSTABLE
import kr.decacross.collector.sources.hangar.HgVersion
import kr.decacross.collector.sources.hangar.isHosted
import kr.decacross.collector.sources.modrinth.MrVersion
import kr.decacross.collector.sources.modrinth.jarFile
import kotlin.time.Instant

/**
 * jar 를 받아 분석할 버전 고르기 (D31). 한 프로젝트에서 K 개.
 *
 * # 불변식
 * - Hangar 외부 링크 버전은 절대 고르지 않는다.
 * - "최신" = 게시 시각 내림차순. 시각을 해석할 수 없는 항목은 뒤로 (같으면 원문 문자열 내림차순).
 */
internal object VersionSelection {
    private const val RELEASE_TYPE = "release"
    private const val RELEASE_CHANNEL = "Release"

    /**
     * Modrinth: jar 파일이 있는 버전 중 `version_type == release` 최신부터, 그다음 나머지 최신부터 K 개.
     */
    fun modrinth(versions: List<MrVersion>, k: Int): List<MrVersion> {
        if (k <= 0) return emptyList()
        val withJar = versions.filter { it.jarFile() != null }.sortedWith(newestFirst { it.date_published })
        val (releases, others) = withJar.partition { it.version_type == RELEASE_TYPE }
        return (releases + others).take(k)
    }

    /**
     * Hangar: 호스팅된 버전 중
     * 1. 채널 이름이 `Release`(대소문자 무시)이고 `UNSTABLE` 플래그가 없는 최신,
     * 2. 그다음 `UNSTABLE` 이 없는 최신,
     * 3. 그다음 나머지 최신 — 순서로 K 개.
     */
    fun hangar(versions: List<HgVersion>, k: Int): List<HgVersion> {
        if (k <= 0) return emptyList()
        val hosted = versions.filter { it.isHosted() }.sortedWith(newestFirst { it.createdAt })
        val (stable, unstable) = hosted.partition { FLAG_UNSTABLE !in it.channel.flags }
        val (release, otherStable) = stable.partition { it.channel.name.equals(RELEASE_CHANNEL, ignoreCase = true) }
        return (release + otherStable + unstable).take(k)
    }

    /** 게시 시각 내림차순 비교기. 해석 불가는 뒤로. */
    fun <T> newestFirst(timestamp: (T) -> String): Comparator<T> = Comparator { a, b ->
        val ta = parseInstantOrNull(timestamp(a))
        val tb = parseInstantOrNull(timestamp(b))
        when {
            ta != null && tb != null && ta != tb -> tb.compareTo(ta)
            ta != null && tb == null -> -1
            ta == null && tb != null -> 1
            else -> timestamp(b).compareTo(timestamp(a))
        }
    }
}

/** ISO-8601 시각. 해석할 수 없으면 null (예외를 던지지 않는다). */
internal fun parseInstantOrNull(text: String): Instant? =
    try {
        Instant.parse(text)
    } catch (e: IllegalArgumentException) {
        null
    }
