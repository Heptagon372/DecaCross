package kr.decacross.daemon.install

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 진행 중/완료된 설치 작업. 이벤트를 replay 해 늦게 붙은 WS 도 처음부터 본다. */
class InstallJob(val id: String) {
    private val _events = MutableSharedFlow<InstallEvent>(replay = 10_000)
    val events: SharedFlow<InstallEvent> = _events

    @Volatile
    var finished: Boolean = false
        internal set
    internal var job: Job? = null

    internal suspend fun emit(e: InstallEvent) = _events.emit(e)
}

class InstallJobs(private val scope: CoroutineScope) {
    private val jobs = ConcurrentHashMap<String, InstallJob>()

    fun get(id: String): InstallJob? = jobs[id]

    /** [run] 이 만드는 이벤트 흐름을 잡으로 감싼다. */
    fun submit(run: suspend (emit: suspend (InstallEvent) -> Unit) -> Unit): InstallJob {
        val job = InstallJob(UUID.randomUUID().toString())
        jobs[job.id] = job
        job.job = scope.launch {
            try {
                run { job.emit(it) }
            } catch (e: Exception) {
                job.emit(InstallEvent.Failed(InstallStage.RESOLVE, InstallError.Io(e.message ?: e.toString())))
            } finally {
                job.finished = true
            }
        }
        return job
    }

    fun cancel(id: String): Boolean = jobs[id]?.job?.also { it.cancel() } != null
}
