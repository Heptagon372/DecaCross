package kr.decacross.daemon.install

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kr.decacross.daemon.install.fetch.CHECKPOINT_BYTES
import kr.decacross.daemon.install.fetch.CROSS_PROCESS_LOCK_POLL_MS
import kr.decacross.daemon.install.fetch.CROSS_PROCESS_LOCK_WAIT_MS
import kr.decacross.daemon.install.fetch.LocalIoException
import kr.decacross.daemon.install.fetch.PARTIAL_MAX_AGE_MS
import kr.decacross.daemon.install.fetch.PartMeta
import kr.decacross.daemon.install.fetch.PartialLock
import kr.decacross.daemon.install.fetch.PartialStore
import kr.decacross.daemon.install.fetch.ProgressTracker
import kr.decacross.daemon.install.fetch.RETRYABLE_STATUSES
import kr.decacross.daemon.install.fetch.STREAM_BUFFER_BYTES
import kr.decacross.daemon.install.fetch.backoffDelayMs
import kr.decacross.daemon.install.fetch.chooseValidator
import kr.decacross.daemon.install.fetch.isRetryableFetchException
import kr.decacross.daemon.install.fetch.isTextContentType
import kr.decacross.daemon.install.fetch.isTransformingEncoding
import kr.decacross.daemon.install.fetch.parseContentRange
import kr.decacross.daemon.install.fetch.parseContentRangeTotal
import kr.decacross.daemon.install.fetch.parseRetryAfterMillis
import kr.decacross.daemon.install.fetch.strongEtag
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 받은 파일을 어떻게 검사할지. */
enum class ArtifactKind {
    /** 서버 코어 jar: zip CRC 전수 검사 + `Main-Class` 또는 `META-INF/main-class` 필수 */
    SERVER_JAR,

    /** 일반 jar: zip CRC 전수 검사 */
    JAR,

    /** zip: zip CRC 전수 검사 */
    ZIP,

    /** 크기·해시만 */
    OTHER,
}

/**
 * 받을 파일 하나.
 *
 * # 불변식 (PLAN 이 검사한 뒤에만 만든다)
 * - [sources] 는 비어 있지 않다. 첫 번째가 원본, 나머지는 미러(Phase 1 은 비어 있음, SCP-F1).
 * - [sha256] 은 소문자 hex 64자, [size] > 0 (core_builds 의 NOT NULL 계약).
 */
data class FetchItem(
    /** 이벤트 키. 예: `core:paper-1.21.8-60.jar` */
    val id: String,
    val sources: List<String>,
    val sha256: String,
    val size: Long,
    val kind: ArtifactKind,
)

/** 시도 한 번의 기록 (전송 실패 설명용). */
data class AttemptRecord(val source: String, val attempt: Int, val outcome: String)

/** 다운로드 실패. [SizeMismatch] 만 무결성 실패(즉시 중단·부분 파일 삭제), 나머지는 전송/로컬 실패다. */
sealed interface FetchError {
    /** 모든 소스에서 재시도를 소진. 부분 파일은 남긴다 (다음 실행이 이어받는다). */
    data class SourcesExhausted(val attempts: List<AttemptRecord>) : FetchError

    /** 선언·스트림·Content-Range 크기가 기대와 다름. ★ 재시도·미러 없음. */
    data class SizeMismatch(val source: String, val observed: Long?, val expected: Long, val where: String) : FetchError

    /** 모든 소스가 `text/html` 같은 엉뚱한 내용을 줌 (포털·프록시). 변조로 분류하지 않는다. */
    data class UnexpectedContent(val source: String, val detail: String) : FetchError

    /** 디스크 가득 참 등 로컬 I/O. 재시도하지 않는다. */
    data class LocalIo(val path: Path, val message: String) : FetchError

    /** 다른 항목의 무결성 실패로 이 항목을 중단했다 (부분 파일은 남긴다). */
    data class Aborted(val causeItemId: String) : FetchError
}

/** 항목별 결과. 취소는 결과가 아니라 [kotlin.coroutines.cancellation.CancellationException] 전파다. */
sealed interface FetchResult {
    val item: FetchItem

    /** 완료. [file] 은 캐시 안의 파일(아직 검증 전). [resumedFrom] 은 이어받기 시작 오프셋, [source] 는 null 이면 캐시 적중. */
    data class Fetched(override val item: FetchItem, val file: Path, val resumedFrom: Long, val source: String?) : FetchResult

    data class Failed(override val item: FetchItem, val error: FetchError) : FetchResult
}

/**
 * 재시도 정책 (SCP-I11: 소스당 1회 시도 + 3회 재시도, 새 바이트를 받은 시도는 실패로 세지 않음, 상한 12회).
 * 지연 = `min(base·2^(n-1), maxDelay) + [0, maxJitter)`, `Retry-After` 가 [maxRetryAfterMs] 이하면 그 값을 따른다.
 */
data class RetryPolicy(
    val maxRetriesPerSource: Int = 3,
    val maxAttemptsPerSource: Int = 12,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 8_000,
    val maxJitterMs: Long = 500,
    val maxRetryAfterMs: Long = 30_000,
)

/** 항목 상태 (진행률 표시용). */
enum class ItemState { QUEUED, DOWNLOADING, WAITING_RETRY, DONE, FAILED }

/** 항목 하나의 진행률. */
data class ItemProgress(val id: String, val doneBytes: Long, val totalBytes: Long, val state: ItemState)

/**
 * 진행률 스냅샷. 초당 최대 10회로 합쳐서 보낸다 (Flow.sample 금지: 마지막 값을 버린다).
 * [doneBytes] 는 [FetchItemEvent.ResumeRejected] 직후에만 줄어들 수 있다.
 */
data class FetchProgress(val doneBytes: Long, val totalBytes: Long, val bytesPerSecond: Long, val items: List<ItemProgress>)

/** 드물게 일어나는 개별 사건. 절대 버리지 않는다. */
sealed interface FetchItemEvent {
    val itemId: String

    data class Started(override val itemId: String, val source: String, val resumedFrom: Long) : FetchItemEvent

    data class Retrying(
        override val itemId: String,
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val reason: String,
    ) : FetchItemEvent

    data class SourceSwitched(override val itemId: String, val fromIndex: Int, val toIndex: Int) : FetchItemEvent

    /** 서버가 Range 를 무시(200)했거나 검증자가 달라져 처음부터 다시 받음. */
    data class ResumeRejected(override val itemId: String, val discardedBytes: Long) : FetchItemEvent

    data class Completed(override val itemId: String, val bytes: Long, val fromCache: Boolean) : FetchItemEvent

    data class Failed(override val itemId: String, val error: FetchError) : FetchItemEvent
}

/** 진행 보고 수신자. 다운로드 코루틴·티커가 직접 호출한다 (suspend: 이벤트를 버리지 않기 위해). */
interface FetchListener {
    suspend fun onProgress(progress: FetchProgress)

    suspend fun onEvent(event: FetchItemEvent)
}

/** 파이프라인이 보는 다운로드 경계. 테스트는 가짜로 대체한다. */
interface ArtifactFetcher {
    /**
     * [items] 를 최대 N 개 병렬로 받는다. 결과 순서 = [items] 순서.
     * 무결성 실패([FetchError.SizeMismatch])가 하나라도 나면 나머지를 취소하고 결과를 돌려준다.
     */
    suspend fun fetchAll(items: List<FetchItem>, listener: FetchListener): List<FetchResult>

    /**
     * 캐시 파일·메타데이터를 지운다. 무결성 실패 뒤(오염된 조각을 이어받지 않게)와 설치 성공(READY) 뒤에 부른다.
     * 같은 sha256 을 다른 프로세스가 받는 중이면(잠금을 못 잡으면) 아무것도 지우지 않는다. 던지지 않는다.
     */
    suspend fun discard(item: FetchItem)
}

/**
 * Ktor Client 기반 기본 구현 (research fetch-verify §2): 동시 [maxParallel]개, HTTP Range 이어받기(If-Range),
 * 소스당 재시도 → 다음 소스(미러) → 실패. 부분 파일은 `[partialDir]/{sha256}.part` + `.part.json` + `.lock`.
 *
 * # 불변식
 * - 모든 `catch (e: Exception)` 는 먼저 `currentCoroutineContext().ensureActive()` (Ktor 가 취소를 IOException 으로 바꿔 던질 수 있다, F12).
 * - 부분 파일은 취소·전송 실패·EULA 거부에는 남기고, 무결성 실패와 설치 성공 뒤 [discard] 로만 지운다.
 * - [client] 는 [installHttpClient] 로 만든 것 (requestTimeout 없음, socketTimeout 있음, UA 필수).
 */
class Fetcher(
    private val client: HttpClient,
    private val partialDir: Path,
    private val policy: RetryPolicy = RetryPolicy(),
    private val maxParallel: Int = 4,
    private val progressIntervalMs: Long = 100,
) : ArtifactFetcher {
    private val store = PartialStore(partialDir)

    /** sha256 별 프로세스 내 잠금. 프로세스 간 잠금(`{sha}.lock`)은 같은 JVM 에서 겹쳐 잡으면 예외라 이게 먼저다. */
    private val shaMutexes = ConcurrentHashMap<String, Mutex>()

    private val swept = AtomicBoolean(false)

    override suspend fun fetchAll(items: List<FetchItem>, listener: FetchListener): List<FetchResult> {
        sweepOnce()
        if (items.isEmpty()) return emptyList()
        val tracker = ProgressTracker(items)
        val results = arrayOfNulls<FetchResult>(items.size)
        var abortCauseId: String? = null
        coroutineScope {
            // 티커: 초당 최대 (1000/progressIntervalMs) 회만 진행률을 합쳐 보낸다
            val ticker = launch {
                while (isActive) {
                    delay(progressIntervalMs)
                    tracker.snapshotIfChanged()?.let { listener.onProgress(it) }
                }
            }
            val gate = Semaphore(maxParallel)
            val jobs = ArrayList<Deferred<Unit>>(items.size)
            items.forEachIndexed { i, item ->
                // LAZY: 본문에서 jobs 를 참조하므로 목록이 다 찬 뒤에 시작해야 한다
                jobs += async(start = CoroutineStart.LAZY) {
                    val result = fetchOne(i, item, gate, listener, tracker)
                    results[i] = result
                    if (result is FetchResult.Failed && result.error is FetchError.SizeMismatch) {
                        // 무결성 실패는 형제를 취소한다 (같은 설치가 어차피 실패한다 — 바이트를 더 쓰지 않는다)
                        synchronized(jobs) {
                            if (abortCauseId == null) {
                                abortCauseId = item.id
                                jobs.forEachIndexed { j, other -> if (j != i) other.cancel() }
                            }
                        }
                    }
                }
            }
            jobs.forEach { it.start() }
            // join: 취소된 형제를 await 하면 CancellationException 이 부모까지 올라간다
            jobs.joinAll()
            ticker.cancelAndJoin()
            listener.onProgress(tracker.snapshot())
        }
        val cause = abortCauseId
        return items.mapIndexed { i, item ->
            results[i] ?: FetchResult.Failed(item, FetchError.Aborted(cause ?: item.id))
        }
    }

    override suspend fun discard(item: FetchItem) {
        val mutex = shaMutexes.computeIfAbsent(item.sha256) { Mutex() }
        mutex.withLock {
            withContext(NonCancellable + Dispatchers.IO) {
                val lock = store.tryLock(item.sha256) ?: return@withContext
                try {
                    store.deleteFiles(item)
                } finally {
                    lock.close()
                }
                // 잠금을 놓은 뒤에야 잠금 파일을 지울 수 있다 (Windows 는 열린 파일을 못 지운다 — sweepOldPartials 와 같은 순서).
                // 남기면 설치 한 번마다 0 바이트 `{sha}.lock` 이 캐시에 영원히 쌓인다.
                store.deleteLockFile(item.sha256)
            }
        }
    }

    /** 7일 지난 부분 파일 청소는 Fetcher 당 한 번 (D-I8). 실패해도 다운로드를 막지 않는다. */
    private suspend fun sweepOnce() {
        if (!swept.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) { store.sweepOldPartials(PARTIAL_MAX_AGE_MS) }
    }

    private suspend fun fetchOne(
        index: Int,
        item: FetchItem,
        gate: Semaphore,
        listener: FetchListener,
        tracker: ProgressTracker,
    ): FetchResult {
        val mutex = shaMutexes.computeIfAbsent(item.sha256) { Mutex() }
        return mutex.withLock {
            val lock = awaitCrossProcessLock(item.sha256)
            if (lock == null) {
                val error = FetchError.LocalIo(store.lockFile(item.sha256), "다른 프로세스가 같은 파일을 받는 중")
                return@withLock failWith(index, item, error, listener, tracker)
            }
            try {
                fetchLocked(index, item, gate, listener, tracker)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { lock.close() }
            }
        }
    }

    /** `{sha}.lock` 을 최대 [CROSS_PROCESS_LOCK_WAIT_MS] 동안 기다린다. 못 잡으면 null. */
    private suspend fun awaitCrossProcessLock(sha256: String): PartialLock? {
        val deadlineNanos = System.nanoTime() + CROSS_PROCESS_LOCK_WAIT_MS * 1_000_000L
        while (true) {
            // ★ 취득은 취소되지 않는 블록에서 한다: 취소가 여기서 터지면 이미 잡은 FileLock 이 호출자에게 닿지 못해
            //   (05) 데몬에서는 그 sha 가 JVM 이 살아 있는 동안 영영 잠긴다. 잡았는데 취소됐으면 직접 닫고 던진다.
            val lock = withContext(NonCancellable + Dispatchers.IO) { store.tryLock(sha256) }
            if (!currentCoroutineContext().isActive) {
                if (lock != null) withContext(NonCancellable + Dispatchers.IO) { lock.close() }
                currentCoroutineContext().ensureActive()
            }
            if (lock != null) return lock
            if (System.nanoTime() >= deadlineNanos) return null
            delay(CROSS_PROCESS_LOCK_POLL_MS)
        }
    }

    /** 잠금을 쥔 상태의 본체: 캐시 적중 확인 → 소스별 재시도 루프 → 미러 → 실패. */
    private suspend fun fetchLocked(
        index: Int,
        item: FetchItem,
        gate: Semaphore,
        listener: FetchListener,
        tracker: ProgressTracker,
    ): FetchResult {
        val part = store.partFile(item)
        val startOffset = withContext(Dispatchers.IO) { store.prepare(item) }
        tracker.setDone(index, startOffset)
        if (startOffset == item.size) {
            // 지난 실행이 이미 다 받아 뒀다 — 요청 0회
            tracker.setState(index, ItemState.DONE)
            listener.onEvent(FetchItemEvent.Completed(item.id, item.size, fromCache = true))
            return FetchResult.Fetched(item, part, resumedFrom = item.size, source = null)
        }

        val records = ArrayList<AttemptRecord>()
        var permanentCount = 0
        var unexpectedCount = 0
        var lastUnexpected: Pair<String, String>? = null

        for (sourceIndex in item.sources.indices) {
            val url = item.sources[sourceIndex]
            if (sourceIndex > 0) listener.onEvent(FetchItemEvent.SourceSwitched(item.id, sourceIndex - 1, sourceIndex))
            // 이 소스로 실제로 이어받을 오프셋 (미러로 넘어왔거나 검증자가 없으면 0 — 조각 길이가 아니다)
            val resumeHint = withContext(Dispatchers.IO) {
                resumeOffset(store.readMeta(item)?.takeIf { it.url == url }, store.partLength(item), item.size)
            }
            listener.onEvent(FetchItemEvent.Started(item.id, url, resumeHint))
            var failures = 0
            var attempts = 0
            var restarts = 0
            while (attempts < policy.maxAttemptsPerSource) {
                attempts++
                tracker.setState(index, ItemState.DOWNLOADING)
                // 권한은 시도 동안만 쥔다 — 백오프 대기 중에는 다른 항목이 내려받을 수 있어야 한다
                val outcome = gate.withPermit { attempt(index, item, url, listener, tracker) }
                records.add(AttemptRecord(url, attempts, describeOutcome(outcome)))
                when (outcome) {
                    is AttemptOutcome.Complete -> {
                        tracker.setState(index, ItemState.DONE)
                        listener.onEvent(FetchItemEvent.Completed(item.id, item.size, fromCache = false))
                        return FetchResult.Fetched(item, part, outcome.resumedFrom, url)
                    }

                    is AttemptOutcome.Integrity -> {
                        // 오염된 조각을 다음 실행이 이어받지 않게 지운다. 재시도·미러 없음.
                        withContext(NonCancellable + Dispatchers.IO) { store.deleteFiles(item) }
                        return failWith(index, item, outcome.error, listener, tracker)
                    }

                    is AttemptOutcome.LocalIo -> return failWith(index, item, outcome.error, listener, tracker)

                    // 206 이 자기 마지막 바이트에서 끝났다 (RFC 9110 §15.3.7) — 실패가 아니다
                    AttemptOutcome.ContinueRange -> Unit

                    is AttemptOutcome.Restart -> {
                        withContext(Dispatchers.IO) { store.deleteFiles(item) }
                        restarts++
                        if (restarts > 1) {
                            permanentCount++
                            break
                        }
                    }

                    is AttemptOutcome.Permanent -> {
                        permanentCount++
                        if (outcome.unexpectedContent) {
                            unexpectedCount++
                            lastUnexpected = url to outcome.reason
                        }
                        break
                    }

                    is AttemptOutcome.Transient -> {
                        failures = if (outcome.progressed) 0 else failures + 1
                        if (failures > policy.maxRetriesPerSource) break
                        val retryAfter = outcome.retryAfterMs
                        if (retryAfter != null && retryAfter > policy.maxRetryAfterMs) break
                        val waitMs = retryAfter ?: backoffDelayMs(failures, policy, outcome.progressed)
                        listener.onEvent(
                            FetchItemEvent.Retrying(item.id, attempts, policy.maxAttemptsPerSource, waitMs, outcome.reason),
                        )
                        tracker.setState(index, ItemState.WAITING_RETRY)
                        delay(waitMs)
                    }
                }
            }
        }

        val unexpected = lastUnexpected
        val error = if (permanentCount > 0 && permanentCount == unexpectedCount && unexpected != null) {
            // 모든 소스가 HTML 을 줬다 — 포털·프록시지 변조가 아니다 (D-I27)
            FetchError.UnexpectedContent(unexpected.first, unexpected.second)
        } else {
            FetchError.SourcesExhausted(records)
        }
        return failWith(index, item, error, listener, tracker)
    }

    private suspend fun failWith(
        index: Int,
        item: FetchItem,
        error: FetchError,
        listener: FetchListener,
        tracker: ProgressTracker,
    ): FetchResult {
        tracker.setState(index, ItemState.FAILED)
        listener.onEvent(FetchItemEvent.Failed(item.id, error))
        return FetchResult.Failed(item, error)
    }

    /**
     * 요청 한 번. 응답 상태·헤더로 분류하고, 스트리밍하며 `.part` 에 이어 쓴다.
     *
     * # 불변식
     * - `finally` 는 `NonCancellable` 에서 `force` → `close` → 메타 갱신을 한다 (취소돼도 오프셋이 정확히 남는다).
     * - 로컬 쓰기 실패는 [LocalIoException] 으로 감싸 네트워크 오류와 섞이지 않게 한다.
     * - `catch (e: Exception)` 은 `ensureActive()` 부터 — Ktor 가 형제 취소를 IOException 으로 바꿔 던진다 (F12).
     */
    private suspend fun attempt(
        index: Int,
        item: FetchItem,
        url: String,
        listener: FetchListener,
        tracker: ProgressTracker,
    ): AttemptOutcome = withContext(Dispatchers.IO) {
        val part = store.partFile(item)
        val storedMeta = store.readMeta(item)?.takeIf { it.url == url }
        val ifRange = storedMeta?.let { chooseValidator(it.etag, it.lastModified, it.date) }
        val partLength = store.partLength(item)
        // 검증자가 없으면 Range 를 보낼 수 없다 → 처음부터 (먼저 자른다)
        val resume = resumeOffset(storedMeta, partLength, item.size)
        if (resume == 0L && partLength > 0L) {
            // 이어받을 수 없는 조각을 버린다 (미러 전환·검증자 없음·메타 불일치).
            // [FetchProgress.doneBytes] 가 줄어드는 경우는 이 이벤트 직후뿐이라는 계약을 지킨다.
            listener.onEvent(FetchItemEvent.ResumeRejected(item.id, partLength))
        }

        var metaEtag = storedMeta?.etag
        var metaLastModified = storedMeta?.lastModified
        var metaDate = storedMeta?.date
        var written = resume
        var effectiveResume = resume
        var lastCheckpoint = resume

        tracker.setDone(index, resume)

        val channel = try {
            FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (e: IOException) {
            return@withContext AttemptOutcome.LocalIo(FetchError.LocalIo(part, e.toString()))
        }

        fun persistMeta() {
            store.writeMeta(
                item,
                PartMeta(1, item.sha256, item.size, url, metaEtag, metaLastModified, metaDate, written),
            )
        }

        try {
            try {
                channel.truncate(resume)
                channel.position(resume)
            } catch (e: IOException) {
                throw LocalIoException(part, e)
            }
            client.prepareGet(url) {
                header(HttpHeaders.AcceptEncoding, "identity")
                if (resume > 0 && ifRange != null) {
                    header(HttpHeaders.Range, "bytes=$resume-")
                    header(HttpHeaders.IfRange, ifRange)
                }
            }.execute { response ->
                val status = response.status.value
                val encoding = response.headers[HttpHeaders.ContentEncoding]
                if (isTransformingEncoding(encoding)) {
                    return@execute AttemptOutcome.Permanent("Content-Encoding: $encoding")
                }
                val contentType = response.headers[HttpHeaders.ContentType]
                if ((status == 200 || status == 206) && isTextContentType(contentType)) {
                    return@execute AttemptOutcome.Permanent("Content-Type: $contentType", unexpectedContent = true)
                }
                var rangeEnd: Long? = null
                when (status) {
                    200 -> {
                        if (resume > 0) {
                            // 서버가 Range 를 무시했다 — 같은 응답을 처음부터 쓰면 된다 (F11: 여기서 끊으면 본문을 버린다)
                            try {
                                channel.truncate(0)
                                channel.position(0)
                            } catch (e: IOException) {
                                throw LocalIoException(part, e)
                            }
                            written = 0
                            effectiveResume = 0
                            lastCheckpoint = 0
                            tracker.setDone(index, 0)
                            listener.onEvent(FetchItemEvent.ResumeRejected(item.id, resume))
                        }
                        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                        if (declared != null && declared != item.size) {
                            return@execute AttemptOutcome.Integrity(
                                FetchError.SizeMismatch(url, declared, item.size, "Content-Length"),
                            )
                        }
                        metaEtag = strongEtag(response.headers[HttpHeaders.ETag])
                        metaLastModified = response.headers[HttpHeaders.LastModified]
                        metaDate = response.headers[HttpHeaders.Date]
                        persistMeta()
                    }

                    206 -> {
                        if (contentType?.trim()?.startsWith("multipart/byteranges", ignoreCase = true) == true) {
                            return@execute AttemptOutcome.Restart("multipart/byteranges")
                        }
                        val raw = response.headers[HttpHeaders.ContentRange]
                            ?: return@execute AttemptOutcome.Restart("206 인데 Content-Range 없음")
                        val parsed = parseContentRange(raw)
                            ?: return@execute AttemptOutcome.Restart("Content-Range 해석 불가: $raw")
                        if (parsed.start != resume || parsed.endInclusive < parsed.start) {
                            return@execute AttemptOutcome.Restart("Content-Range $raw 가 오프셋 $resume 과 다름")
                        }
                        val total = parsed.total
                        if (total != null && total != item.size) {
                            return@execute AttemptOutcome.Integrity(
                                FetchError.SizeMismatch(url, total, item.size, "Content-Range"),
                            )
                        }
                        rangeEnd = parsed.endInclusive + 1
                    }

                    416 -> {
                        val total = response.headers[HttpHeaders.ContentRange]?.let { parseContentRangeTotal(it) }
                        return@execute when {
                            total != null && total != item.size ->
                                AttemptOutcome.Integrity(FetchError.SizeMismatch(url, total, item.size, "Content-Range"))

                            total == item.size && resume == item.size -> AttemptOutcome.Complete(effectiveResume)

                            else -> AttemptOutcome.Restart("416 total=$total offset=$resume")
                        }
                    }

                    in RETRYABLE_STATUSES -> {
                        val retryAfter = response.headers[HttpHeaders.RetryAfter]
                            ?.let { parseRetryAfterMillis(it, System.currentTimeMillis()) }
                        return@execute AttemptOutcome.Transient(written > effectiveResume, retryAfter, "HTTP $status")
                    }

                    else -> return@execute AttemptOutcome.Permanent("HTTP $status")
                }

                val body = response.bodyAsChannel()
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                while (true) {
                    val n = body.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) continue
                    if (written + n > item.size) {
                        return@execute AttemptOutcome.Integrity(
                            FetchError.SizeMismatch(url, written + n, item.size, "stream overflow"),
                        )
                    }
                    try {
                        val slice = ByteBuffer.wrap(buffer, 0, n)
                        while (slice.hasRemaining()) channel.write(slice)
                    } catch (e: IOException) {
                        throw LocalIoException(part, e)
                    }
                    written += n
                    tracker.add(index, n)
                    if (written - lastCheckpoint >= CHECKPOINT_BYTES) {
                        try {
                            channel.force(false)
                        } catch (e: IOException) {
                            throw LocalIoException(part, e)
                        }
                        persistMeta()
                        lastCheckpoint = written
                    }
                }
                val end = rangeEnd
                when {
                    written == item.size -> AttemptOutcome.Complete(effectiveResume)

                    end != null && written == end -> AttemptOutcome.ContinueRange

                    else -> AttemptOutcome.Transient(
                        written > effectiveResume,
                        null,
                        "본문이 짧다 ($written/${item.size})",
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocalIoException) {
            AttemptOutcome.LocalIo(FetchError.LocalIo(e.path, e.toString()))
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            val reason = "${e::class.simpleName}: ${e.message}"
            if (isRetryableFetchException(e)) {
                AttemptOutcome.Transient(written > effectiveResume, null, reason)
            } else {
                AttemptOutcome.Permanent(reason)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    channel.force(false)
                } catch (e: IOException) {
                    // 이미 끊긴 채널 — 아래에서 닫는다
                }
                try {
                    channel.close()
                } catch (e: IOException) {
                    // 무시
                }
                persistMeta()
            }
        }
    }
}

/** 시도 한 번의 분류 (설계 §2.11 표). */
private sealed interface AttemptOutcome {
    /** 파일을 다 받았다. [resumedFrom] 은 이 시도가 실제로 이어받기 시작한 오프셋. */
    data class Complete(val resumedFrom: Long) : AttemptOutcome

    /** 크기가 어긋났다 — 재시도·미러 없이 중단하고 조각을 버린다. */
    data class Integrity(val error: FetchError) : AttemptOutcome

    data class LocalIo(val error: FetchError) : AttemptOutcome

    /** 206 이 자기 마지막 바이트에서 끝났다. 같은 소스로 이어서 더 받는다. */
    data object ContinueRange : AttemptOutcome

    /** 이어받기가 불가능해졌다. 조각을 버리고 처음부터 (두 번째면 이 소스를 포기). */
    data class Restart(val reason: String) : AttemptOutcome

    /** 이 소스로는 안 된다. 다음 소스(미러)로. */
    data class Permanent(val reason: String, val unexpectedContent: Boolean = false) : AttemptOutcome

    /** 잠깐 실패. [progressed] 면 실패 횟수를 세지 않는다. */
    data class Transient(val progressed: Boolean, val retryAfterMs: Long?, val reason: String) : AttemptOutcome
}

/**
 * 이 소스로 이어받을 수 있는 오프셋. 메타가 없거나(미러 전환 포함) 쓸 수 있는 검증자가 없으면 0 = 처음부터.
 *
 * `Started.resumedFrom` 과 실제 요청이 같은 값을 쓰도록 한 곳에만 둔다 (알림과 동작이 어긋나지 않게).
 */
private fun resumeOffset(meta: PartMeta?, partLength: Long, size: Long): Long {
    if (meta == null || chooseValidator(meta.etag, meta.lastModified, meta.date) == null) return 0L
    return minOf(partLength, meta.durableLength).coerceIn(0L, size)
}

private fun describeOutcome(outcome: AttemptOutcome): String = when (outcome) {
    is AttemptOutcome.Complete -> "완료"
    is AttemptOutcome.Integrity -> "무결성 실패: ${outcome.error}"
    is AttemptOutcome.LocalIo -> "로컬 I/O 실패: ${outcome.error}"
    AttemptOutcome.ContinueRange -> "범위 이어받기"
    is AttemptOutcome.Restart -> "처음부터: ${outcome.reason}"
    is AttemptOutcome.Permanent -> "이 소스 중단: ${outcome.reason}"
    is AttemptOutcome.Transient -> "일시 실패: ${outcome.reason}"
}
