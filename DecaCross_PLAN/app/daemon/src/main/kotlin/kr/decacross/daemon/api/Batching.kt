package kr.decacross.daemon.api

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * 시간 윈도우 + 최대 크기 배칭 (CLAUDE.md 불변식 14).
 * 로그 라인마다 WS 프레임을 보내면 대량 로그(플러그인 100개 로딩)에서 UI 가 죽는다. 50ms 모아서 한 프레임.
 *
 * 첫 원소가 들어온 뒤 [window] 안에 도착한 것들을 한 배치로 묶고, [maxSize] 에 닿으면 즉시 내보낸다.
 * 원소가 없으면 빈 배치를 내보내지 않는다.
 */
fun <T> Flow<T>.chunkedTimeout(window: Duration, maxSize: Int): Flow<List<T>> = flow {
    coroutineScope {
        val ch = Channel<T>(Channel.UNLIMITED)
        val producer = launch {
            try {
                collect { ch.send(it) }
            } finally {
                ch.close()
            }
        }
        val batch = ArrayList<T>(maxSize)
        var closed = false
        while (!closed) {
            // 첫 원소는 무기한 대기
            val first = ch.receiveCatching().getOrNull()
            if (first == null) {
                closed = true
                break
            }
            batch += first
            val deadline = System.nanoTime() + window.inWholeNanoseconds
            while (batch.size < maxSize) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMs <= 0) break
                val next = withTimeoutOrNull(remainingMs) { ch.receiveCatching() } ?: break
                val v = next.getOrNull()
                if (v == null) {
                    closed = true
                    break
                }
                batch += v
            }
            emit(batch.toList())
            batch.clear()
        }
        producer.cancel()
    }
}
