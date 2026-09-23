package kr.decacross.daemon.install

import kotlinx.serialization.Serializable
import kr.decacross.dcx.DcxRecipe
import kr.decacross.dcx.DcxSummary
import kr.decacross.dcx.summary
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 웹 브리지가 넣은 설치 요청 대기열. 사용자가 네이티브 다이얼로그에서 [설치] 를 누르기 전에는 아무 파일도 쓰지 않는다.
 * 10분 지나면 자동 만료.
 */
@Serializable
data class PendingInstall(
    val id: String,
    val origin: String,
    val createdAt: String,
    val summary: DcxSummary,
    val recipe: DcxRecipe,
)

class PendingInstalls(private val ttlSeconds: Long = 600) {
    private val map = ConcurrentHashMap<String, PendingInstall>()

    fun add(recipe: DcxRecipe, origin: String): PendingInstall {
        expire()
        val p = PendingInstall(UUID.randomUUID().toString(), origin, Instant.now().toString(), recipe.summary(), recipe)
        map[p.id] = p
        return p
    }

    fun list(): List<PendingInstall> {
        expire()
        return map.values.sortedBy { it.createdAt }
    }

    fun take(id: String): PendingInstall? = map.remove(id)

    private fun expire() {
        val cutoff = Instant.now().minusSeconds(ttlSeconds)
        map.values.filter { Instant.parse(it.createdAt).isBefore(cutoff) }.forEach { map.remove(it.id) }
    }
}
