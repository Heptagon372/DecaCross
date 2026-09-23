package kr.decacross.ui.console

/**
 * 콘솔 명령 입력 히스토리 (↑/↓). 셸과 같은 동작:
 * - [push] 로 실행한 명령을 기록. 빈 줄·직전과 같은 줄은 기록하지 않는다. 최대 [max] 개.
 * - [up] 은 과거로, [down] 은 최신 쪽으로 이동. 가장 최신을 지나면 빈 문자열(입력 중이던 자리).
 * - [reset] 은 커서를 끝으로 되돌린다 (사용자가 직접 타이핑하기 시작했을 때).
 */
class CommandHistory(private val max: Int = 200) {
    private val entries = ArrayDeque<String>()
    private var cursor: Int = 0 // entries.size == "입력 중" 자리

    val size: Int get() = entries.size

    fun push(line: String) {
        val t = line.trim()
        if (t.isNotEmpty() && entries.lastOrNull() != t) {
            entries.addLast(t)
            while (entries.size > max) entries.removeFirst()
        }
        cursor = entries.size
    }

    fun up(): String? {
        if (entries.isEmpty() || cursor == 0) return if (entries.isEmpty()) null else entries[0]
        cursor--
        return entries[cursor]
    }

    fun down(): String {
        if (cursor >= entries.size) return ""
        cursor++
        return if (cursor == entries.size) "" else entries[cursor]
    }

    fun reset() {
        cursor = entries.size
    }

    fun toList(): List<String> = entries.toList()
}
