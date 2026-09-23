package kr.decacross.ui.console

/**
 * 콘솔 로그 링 버퍼. 최근 [capacity] 줄만 유지하고 오래된 줄부터 버린다.
 *
 * 10만 줄에서도 부드러워야 한다(05 성능 요구). 그래서:
 * - 줄은 [chunkSize] 크기의 청크 배열에 append-only 로 쌓는다. 배치 추가는 O(batch + 청크 수) — 전체 복사가 없다.
 * - [snapshot] 은 청크 리스트 + 오프셋만 들고 있는 불변 뷰다. 이후 append 는 스냅샷이 보는 범위 밖(빈 꼬리)에만 쓰므로
 *   스냅샷은 절대 변하지 않는다. `LazyColumn` 에 그대로 넘긴다.
 * - 오래된 줄 삭제는 앞 청크를 통째로 떼어내는 것 — 역시 O(청크 수).
 *
 * 스레드 안전하지 않다. 하나의 코루틴(메인)에서만 쓴다.
 */
class ConsoleBuffer(
    val capacity: Int = DEFAULT_CAPACITY,
    private val chunkSize: Int = DEFAULT_CHUNK,
) {
    init {
        require(capacity > 0 && chunkSize > 0)
    }

    private var chunks: ArrayList<Array<String?>> = ArrayList()
    private var headOffset: Int = 0 // 첫 청크에서 버려진 줄 수
    private var tailFill: Int = 0 // 마지막 청크에 채워진 줄 수
    private var generation: Long = 0

    var size: Int = 0
        private set

    /** 배치 추가. 각 WS `log` 프레임이 배치 하나다 (불변식 14). */
    fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        for (line in lines) {
            if (chunks.isEmpty() || tailFill == chunkSize) {
                chunks.add(arrayOfNulls(chunkSize))
                tailFill = 0
            }
            chunks[chunks.size - 1][tailFill++] = line
        }
        size += lines.size
        trim()
        generation++
    }

    fun clear() {
        chunks = ArrayList()
        headOffset = 0
        tailFill = 0
        size = 0
        generation++
    }

    /** 현재 내용의 불변 스냅샷. O(청크 수). */
    fun snapshot(): ConsoleSnapshot = ConsoleSnapshot(ArrayList(chunks), headOffset, size, chunkSize, generation)

    private fun trim() {
        val excess = size - capacity
        if (excess <= 0) return
        headOffset += excess
        size -= excess
        val dropChunks = headOffset / chunkSize
        if (dropChunks > 0) {
            // 앞 청크를 통째로 버린다. 스냅샷은 자기 리스트를 따로 들고 있어 영향이 없다.
            chunks = ArrayList(chunks.subList(dropChunks, chunks.size))
            headOffset %= chunkSize
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 100_000
        const val DEFAULT_CHUNK = 512
    }
}

/**
 * [ConsoleBuffer.snapshot] 결과. 읽기 전용 `List<String>`; `generation` 은 Compose 에서 변경 감지 키로 쓴다.
 * 인덱스 접근은 O(1) 이라 `LazyColumn` 이 화면에 보이는 줄만 꺼낸다.
 */
class ConsoleSnapshot internal constructor(
    private val chunks: List<Array<String?>>,
    private val headOffset: Int,
    override val size: Int,
    private val chunkSize: Int,
    val generation: Long,
) : AbstractList<String>() {
    override fun get(index: Int): String {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("index=$index size=$size")
        val p = headOffset + index
        return chunks[p / chunkSize][p % chunkSize] ?: ""
    }

    companion object {
        val EMPTY: ConsoleSnapshot = ConsoleSnapshot(emptyList(), 0, 0, 1, 0)
    }
}

/** 로그 줄 색상용 레벨. 파싱은 여기까지 — 이벤트 아이콘은 06 단계. */
enum class LogLevel {
    INFO,
    WARN,
    ERROR,
    ;

    companion object {
        /** `[12:03:41] [Server thread/WARN]:` (1.8+), `[12:03:41 WARN]:` (1.7-), 스택트레이스 줄을 잡는다. */
        fun of(line: String): LogLevel = when {
            line.contains("/ERROR]") || line.contains(" ERROR]") || line.contains("/FATAL]") || line.contains(" FATAL]") -> ERROR
            line.startsWith("\tat ") || line.startsWith("Caused by:") || line.startsWith("\t... ") -> ERROR
            line.contains("/WARN]") || line.contains(" WARN]") -> WARN
            else -> INFO
        }
    }
}
