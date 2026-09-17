package kr.decacross.collector.core

import kr.decacross.collector.config.CollectorSettings
import kr.decacross.collector.http.Http
import kr.decacross.collector.store.CollectorStore
import kotlin.time.Clock
import kotlin.time.Duration

/** 수집 소스 식별자. `--sources=` 인자의 키이기도 하다. */
enum class SourceId(val key: String) {
    MOJANG("mojang"),
    PAPER("paper"),
    FOLIA("folia"),
    PURPUR("purpur"),
    ADOPTIUM("adoptium"),
    MODRINTH("modrinth"),
    HANGAR("hangar"),
    ;

    companion object {
        fun fromKey(key: String): SourceId? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

enum class RunMode { ONCE, LOOP }

/** 소스 실행 결과 상태. */
enum class SourceStatus {
    /** 전부 성공 */
    OK,

    /** 일부 항목 실패·예산 초과로 건너뜀 (다음 사이클에서 이어짐) */
    PARTIAL,

    /** 소스 자체 실패 (매니페스트를 못 받음 등) */
    FAILED,

    /** 설정으로 꺼짐 */
    SKIPPED,
}

/** 소스 한 번 실행의 보고서. counters 키는 소스별 자유 (`issued`, `skipped.SLOT_OVERFLOW` …). */
data class SourceReport(
    val source: SourceId,
    val status: SourceStatus,
    val counters: Map<String, Long>,
    val warnings: List<String>,
    val error: String? = null,
)

/** 소스 실행에 주입되는 전부. 소스는 이것 밖의 I/O 경로를 만들지 않는다. */
class CollectContext(
    val mode: RunMode,
    val http: Http,
    val store: CollectorStore,
    val settings: CollectorSettings,
    val analyzer: JarAnalyzer,
    val clock: Clock = Clock.System,
)

/**
 * 수집 소스 공통 인터페이스.
 *
 * # 불변식
 * - [collect] 는 [kotlinx.coroutines.CancellationException] 을 삼키지 않는다 (구조적 동시성).
 * - 항목 단위 실패는 [SourceReport] 로 보고하고, 소스 전체를 죽이지 않는다.
 * - 외부 호출은 전부 [CollectContext.http] 로만 한다 (UA·레이트리밋·재시도가 거기 있다).
 */
interface CollectorSource {
    val id: SourceId

    /** `--loop` 실행 주기. */
    val interval: Duration

    /** 같은 실행에서 먼저 끝나야 하는 소스 (예: 코어·콘텐츠는 Mojang 뒤). */
    val dependsOn: Set<SourceId>

    /** true 면 `--once` 에서 FAILED 일 때 종료 코드가 0 이 아니다. */
    val required: Boolean

    suspend fun collect(ctx: CollectContext): SourceReport
}

/** 스레드 안전한 보고서 누적기. */
class ReportBuilder(private val source: SourceId) {
    private val lock = Any()
    private val counters = LinkedHashMap<String, Long>()
    private val warnings = ArrayList<String>()

    fun inc(key: String, by: Long = 1) {
        synchronized(lock) { counters[key] = (counters[key] ?: 0L) + by }
    }

    /** 현재 카운터 값 (없으면 0). 상태(PARTIAL 등)를 카운터에서 파생할 때 쓴다. */
    fun count(key: String): Long = synchronized(lock) { counters[key] ?: 0L }

    /** 경고는 최대 [MAX_WARNINGS] 개만 보관하고 나머지는 `warnings.suppressed` 로 센다. */
    fun warn(message: String) {
        synchronized(lock) {
            if (warnings.size < MAX_WARNINGS) warnings += message else counters["warnings.suppressed"] = (counters["warnings.suppressed"] ?: 0L) + 1
        }
    }

    fun build(status: SourceStatus, error: String? = null): SourceReport =
        synchronized(lock) { SourceReport(source, status, LinkedHashMap(counters), ArrayList(warnings), error) }

    private companion object {
        const val MAX_WARNINGS = 50
    }
}
