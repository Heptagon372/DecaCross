package kr.decacross.collector.content

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.decacross.analysis.EvidenceConfidence
import kr.decacross.analysis.JarAnalysisResult
import kr.decacross.collector.core.CollectContext
import kr.decacross.collector.core.ReportBuilder
import kr.decacross.collector.http.DigestAlgo
import kr.decacross.collector.http.DownloadRequest
import kr.decacross.collector.http.HttpResult
import kr.decacross.collector.store.dbKey
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * 콘텐츠 jar 분석 파이프라인: 다운로드 → 분석 → 삭제 → 기록 (D31, §10.3).
 *
 * # 불변식
 * - 같은 유효 분석기 버전으로 이미 분석된 버전은 요청 없이 건너뛴다 (Hangar 다운로드 부풀리기 방지).
 * - [analyze] 가 돌아온 뒤 임시 디렉터리에 `dl-*.part` 가 남지 않는다 (`use {}` 가 삭제; 끝내 못 지우면 Runner 가 쓸어 담는다).
 * - CANDIDATE Capability 는 로그에만 남기고 어디에도 저장하지 않는다 (D33).
 * - 다운로드는 소스 안에서 순차 실행한다 (호출자 책임 — 병렬로 부르지 않는다).
 */
internal class ContentPipeline(
    private val ctx: CollectContext,
    private val report: ReportBuilder,
    private val budget: DownloadBudget,
    private val analyzerVersion: String,
) {
    /** 분석 대상 1건: 이미 분석됐으면 건너뛰고, sha256 중복이면 복사, 아니면 다운로드→분석→삭제→기록. */
    suspend fun analyze(target: AnalysisTarget) {
        // 1. 이미 이 유효 버전으로 분석됨 → 요청 없음
        if (target.storedAnalyzerVersion == analyzerVersion) {
            report.inc("analysis.upToDate")
            return
        }

        // 2. 같은 jar(sha256)를 같은 버전으로 이미 분석했으면 복사 (Modrinth↔Hangar 동일 jar)
        target.knownSha256?.let { sha ->
            val found = ctx.store.findAnalysisBySha256(sha, analyzerVersion)
            if (found != null) {
                ctx.store.recordAnalysis(target.contentVersionId, found.copy(analyzedAt = ctx.clock.now()))
                report.inc("analysis.dedupedBySha256")
                return
            }
        }

        // 3. 예산
        when (budget.admit(target.size)) {
            DownloadBudget.Admit.TOO_LARGE -> {
                report.inc("analysis.tooLarge")
                return
            }

            DownloadBudget.Admit.EXHAUSTED -> {
                report.inc("analysis.budgetExhausted")
                return
            }

            DownloadBudget.Admit.OK -> Unit
        }

        // 4. 다운로드 (sha256 은 항상 계산; 플랫폼 해시가 있으면 검증)
        val request = DownloadRequest(
            maxBytes = ctx.settings.content.maxJarBytes,
            algorithms = setOf(DigestAlgo.SHA256) + target.expected.keys,
            expected = target.expected,
        )
        val ok = when (val r = ctx.http.download(target.url, request)) {
            is HttpResult.Ok -> r
            is HttpResult.NotModified -> return downloadFailed(target, "예상치 못한 304")
            is HttpResult.Status -> return downloadFailed(target, "HTTP ${r.meta.status}")
            is HttpResult.Failure -> return downloadFailed(target, "${r.kind} ${r.message}")
        }

        // 5. 분석 후 삭제. 결과는 use {} 안에서 계산한다 — TempDownload.close() 는 throw 하지 않으므로 삭제 실패로 결과가 사라지지 않는다.
        val outcome = ok.value.use { d ->
            if (target.size != null && d.size != target.size) {
                // 검증 안 된 전송(잘린 본문)일 수 있다 → 분석하지 않고 기록하지 않는다 (다음 사이클 재시도)
                Outcome.SizeMismatch(d.size)
            } else {
                val sha = d.digests[DigestAlgo.SHA256]
                if (sha == null) {
                    Outcome.NoDigest
                } else {
                    withContext(Dispatchers.IO) {
                        try {
                            Outcome.Analyzed(sha, d.size, ctx.analyzer.analyze(d.path), crashed = false)
                        } catch (e: StackOverflowError) {
                            // 분석기 결함 — 이 분석기 버전에서는 다시 받지 않는다
                            Outcome.Analyzed(sha, d.size, JarAnalysisResult.Unreadable("analyzer crashed: $e"), crashed = true)
                        } catch (e: LinkageError) {
                            Outcome.Analyzed(sha, d.size, JarAnalysisResult.Unreadable("analyzer crashed: $e"), crashed = true)
                        }
                    }
                }
            }
        } // TempDownload.close() 가 jar 삭제 (재시도, throw 없음)

        // 6. 기록 만들기
        when (outcome) {
            is Outcome.SizeMismatch -> {
                report.inc("analysis.sizeMismatch")
                report.warn("분석 건너뜀(크기 불일치 ${outcome.actual} != ${target.size}): ${target.url}")
                return
            }

            Outcome.NoDigest -> {
                report.inc("analysis.downloadFailed")
                report.warn("다운로드 sha256 누락: ${target.url}")
                return
            }

            is Outcome.Analyzed -> record(target, outcome)
        }
    }

    private suspend fun record(target: AnalysisTarget, outcome: Outcome.Analyzed) {
        val result = outcome.result
        if (outcome.crashed) {
            report.inc("analysis.crashed")
            report.warn("분석기 충돌: ${target.url} ${(result as? JarAnalysisResult.Unreadable)?.reason}")
        }
        if (result is JarAnalysisResult.Unreadable) {
            // 해시나 크기로 전송이 검증된 경우에만 "읽을 수 없음"을 영구 기록한다 (조용히 잘린 전송은 재시도)
            if (!transferVerified(target.expected, target.size, outcome.size)) {
                report.inc("analysis.unreadableUnverified")
                report.warn("검증 안 된 전송의 Unreadable 은 기록하지 않음: ${target.url}")
                return
            }
        }
        if (result is JarAnalysisResult.Ok) {
            for (e in result.analysis.capabilities) {
                if (e.confidence == EvidenceConfidence.CANDIDATE) {
                    log.info("capability 후보(저장 안 함): {} {}", e.capability.dbKey(), e.detail)
                }
            }
        }
        val record = analysisRecordOf(result, outcome.sha256, outcome.size, ctx.clock.now(), analyzerVersion)
        ctx.store.recordAnalysis(target.contentVersionId, record)
        report.inc("analysis.recorded")
    }

    private fun downloadFailed(target: AnalysisTarget, detail: String) {
        report.inc("analysis.downloadFailed")
        report.warn("jar 다운로드 실패: ${target.url} $detail")
    }

    private sealed interface Outcome {
        data class SizeMismatch(val actual: Long) : Outcome

        data object NoDigest : Outcome

        data class Analyzed(val sha256: String, val size: Long, val result: JarAnalysisResult, val crashed: Boolean) : Outcome
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(ContentPipeline::class.java)
    }
}

/**
 * 받은 jar 의 전송이 검증됐는가: 플랫폼 해시를 대조했거나(Http 가 불일치면 이미 실패시킨다),
 * 플랫폼이 알려준 크기와 받은 크기가 같다. 둘 다 아니면 조용히 잘린 전송일 수 있다.
 */
internal fun transferVerified(expected: Map<DigestAlgo, String>, expectedSize: Long?, actualSize: Long): Boolean =
    expected.isNotEmpty() || (expectedSize != null && expectedSize == actualSize)

/**
 * 분석 대상 1건.
 *
 * @property storedAnalyzerVersion DB 에 기록된 유효 분석기 버전 (분석된 적 없으면 null).
 * @property size 플랫폼이 알려준 파일 크기 (예산·전송 검증용).
 * @property knownSha256 플랫폼이 준 sha256 (Hangar) — 다운로드 전 중복 조회 키.
 * @property expected 다운로드 때 검증할 플랫폼 해시 (Modrinth sha1, Hangar sha256).
 */
internal data class AnalysisTarget(
    val contentVersionId: Long,
    val storedAnalyzerVersion: String?,
    val url: String,
    val size: Long?,
    val knownSha256: String?,
    val expected: Map<DigestAlgo, String>,
)
