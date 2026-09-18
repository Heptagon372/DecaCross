package kr.decacross.cli

import kr.decacross.daemon.install.FetchItemEvent
import kr.decacross.daemon.install.FetchProgress
import kr.decacross.daemon.install.InstallEvent
import kr.decacross.daemon.install.InstallFailure
import kr.decacross.daemon.install.InstallPlan
import kr.decacross.daemon.install.InstallStage
import kr.decacross.daemon.install.InstalledServer
import kr.decacross.daemon.install.describeKo

/** 1 MiB. 진행률·크기 표시는 전부 MiB 정수로 (로캘에 따라 소수점이 달라지지 않게). */
private const val MIB: Long = 1024L * 1024L

/** 단계 표시 줄 (전체 8 단계). */
private const val TOTAL_STAGES: Int = 8

/**
 * [InstallEvent] → 콘솔 줄 (DESIGN2 §2.15 `EventRenderer`).
 *
 * # 불변식
 * - 표식은 `[완료]` `[실패]` `[주의]` `[롤백]` `->` 만 쓴다. `✔ ✘ ⚠ ↩` 는 MS949 콘솔에서 `?` 가 된다 (D-I44).
 *   실제 인코딩 보정은 [ConsoleIo.out] 의 [consoleSafe] 가 한다.
 * - 진행률은 10 % 를 넘길 때만, 그리고 100 % 는 반드시 한 번 출력한다 (초당 10회 사건을 그대로 찍지 않는다).
 * - 흐름이 끝나면 [exitCode] 가 종료 코드다 ([InstallEvent.Ready] 면 0, [InstallEvent.Failed] 면 D-I36 표).
 */
class EventRenderer(private val io: ConsoleIo) {
    /** 지금까지 본 사건으로 정해진 종료 코드. */
    var exitCode: Int = ExitCodes.OK
        private set

    /** 설치가 끝났으면 그 서버 ( `--start` 가 쓴다 ). */
    var installed: InstalledServer? = null
        private set

    /** 마지막으로 출력한 10 % 구간 (0 = 아직 10 % 를 못 넘음). */
    private var lastProgressStep: Int = 0

    fun render(event: InstallEvent) {
        when (event) {
            is InstallEvent.StageEntered -> stageLine(event.stage)?.let { io.out(it) }

            is InstallEvent.Resolved -> {
                val target = event.target
                io.out("  -> ${target.mc.label} / ${target.build.core.name.lowercase()} 빌드 ${target.build.build}")
            }

            is InstallEvent.Planned -> renderPlan(event.plan)

            is InstallEvent.Progress -> renderProgress(event.progress)

            is InstallEvent.FetchNotice -> io.out("  " + noticeLine(event.event))

            is InstallEvent.Verified -> io.out("  검증 완료: ${event.itemId} (${event.size / MIB} MB)")

            is InstallEvent.Warning -> io.out("[주의] ${event.messageKo}")

            is InstallEvent.Ready -> {
                installed = event.server
                exitCode = ExitCodes.OK
                io.out("[완료] 서버를 만들었습니다: ${event.server.dir}")
            }

            is InstallEvent.Failed -> {
                exitCode = exitCodeFor(event.failure)
                val description = event.failure.describeKo()
                io.out("[실패] ${description.messageKo}")
                for (fix in description.fixesKo) io.out("  해결: $fix")
            }

            is InstallEvent.RolledBack -> {
                // ★ "바뀌지 않았습니다" 는 정말로 아무것도 건드리지 않았을 때만 말한다:
                //   LAYOUT 스윕이 이전 설치의 잔해를 지웠거나 치우지 못한 것이 남았으면 사실이 아니다.
                if (event.sweptPaths.isEmpty() && event.cleanedUp) {
                    io.out("[롤백] 완료 - 사용자 폴더는 바뀌지 않았습니다")
                } else {
                    io.out("[롤백] 완료 - 이번 설치가 만든 것은 모두 치웠습니다")
                    for (swept in event.sweptPaths) io.out("  이전 설치의 잔해를 정리했습니다: $swept")
                }
                if (!event.cleanedUp) {
                    for (leftover in event.leftovers) io.out("  남은 항목(다음 실행이 정리): $leftover")
                }
            }
        }
    }

    /** 취소는 파이프라인이 사건으로 내보내지 않는다 — CLI 가 직접 만든다 (critique m1). */
    fun renderCancelled() {
        exitCode = ExitCodes.CANCELLED
        val description = InstallFailure.Cancelled.describeKo()
        io.out("[실패] ${description.messageKo}")
        for (fix in description.fixesKo) io.out("  해결: $fix")
    }

    private fun renderPlan(plan: InstallPlan) {
        val target = plan.target
        io.out("  MC/코어: ${target.mc.label} / ${target.build.core.name.lowercase()} 빌드 ${target.build.build}")
        io.out("  받을 크기: ${plan.totalBytes / MIB} MB (캐시 적중 시 다운로드 없음)")
        io.out("  Java: ${plan.java.javaPath} (${plan.java.feature})")
        io.out("  메모리: ${plan.launch.xmxMb} MB / ${plan.launch.flagProfileId ?: "프로파일 없음"}")
        // ★ 데모 드라이버가 이 줄을 읽는다 (critique m6) — 접두사와 절대경로 형태를 바꾸지 마라
        io.out("  대상 폴더: ${plan.serverDir}")
    }

    private fun renderProgress(progress: FetchProgress) {
        val total = progress.totalBytes
        val percent = if (total <= 0L) 0 else ((progress.doneBytes * 100L) / total).coerceIn(0L, 100L).toInt()
        val step = percent / 10
        if (step <= lastProgressStep) return
        lastProgressStep = step
        val head = "  $percent% ${progress.doneBytes / MIB}/${total / MIB} MB"
        val speed = progress.bytesPerSecond
        if (speed <= 0L) {
            io.out(head)
        } else {
            val remaining = (total - progress.doneBytes).coerceAtLeast(0L)
            io.out("$head, 약 ${remaining / speed}초 남음")
        }
    }
}

/** 단계 머리줄. `IDLE`/`FAILED` 는 UI 상태 이름일 뿐 사건으로 오지 않는다. */
internal fun stageLine(stage: InstallStage): String? =
    when (stage) {
        InstallStage.RESOLVE -> "[1/$TOTAL_STAGES] 버전 확인"
        InstallStage.PLAN -> "[2/$TOTAL_STAGES] 계획"
        InstallStage.FETCH -> "[3/$TOTAL_STAGES] 다운로드"
        InstallStage.VERIFY -> "[4/$TOTAL_STAGES] 검증"
        InstallStage.LAYOUT -> "[5/$TOTAL_STAGES] 조립"
        InstallStage.CONFIG -> "[6/$TOTAL_STAGES] 설정"
        InstallStage.EULA -> "[7/$TOTAL_STAGES] EULA"
        InstallStage.READY -> "[8/$TOTAL_STAGES] 설치 완료"
        InstallStage.ROLLBACK -> "[롤백] 되돌리는 중"
        InstallStage.IDLE, InstallStage.FAILED -> null
    }

/** 개별 다운로드 사건 한 줄. */
internal fun noticeLine(event: FetchItemEvent): String =
    when (event) {
        is FetchItemEvent.Started ->
            "받기 시작: ${event.itemId}" + if (event.resumedFrom > 0L) " (이어받기 ${event.resumedFrom / MIB} MB)" else ""

        is FetchItemEvent.Retrying ->
            "재시도 ${event.attempt}/${event.maxAttempts} (${event.delayMs} ms): ${event.reason}"

        is FetchItemEvent.SourceSwitched -> "미러로 전환"

        is FetchItemEvent.ResumeRejected -> "서버가 이어받기를 거부 - 처음부터 받습니다"

        is FetchItemEvent.Completed ->
            if (event.fromCache) "캐시 적중: ${event.itemId} (다운로드 생략)" else "받기 완료: ${event.itemId}"

        is FetchItemEvent.Failed -> "받기 실패: ${event.itemId}"
    }

/**
 * 실패 → 종료 코드 (D-I36). `when` 을 전부 나열해 새 실패 종류가 생기면 컴파일이 깨지게 둔다.
 */
internal fun exitCodeFor(failure: InstallFailure): Int =
    when (failure) {
        // RESOLVE
        is InstallFailure.UnknownMc,
        is InstallFailure.UnsupportedCore,
        is InstallFailure.NoStableBuild,
        is InstallFailure.NoBuildCollected,
        is InstallFailure.InvalidCatalogData,
        // PLAN
        is InstallFailure.InvalidServerName,
        is InstallFailure.ServerExists,
        is InstallFailure.NameBusy,
        is InstallFailure.ServersRootUnusable,
        is InstallFailure.InvalidRam,
        is InstallFailure.JavaNotFound,
        is InstallFailure.NoFlagProfile,
        is InstallFailure.InsufficientDisk,
        InstallFailure.PlanRejected,
        -> ExitCodes.INPUT

        // FETCH / VERIFY / 파일 작업 / 커밋
        is InstallFailure.DownloadFailed,
        is InstallFailure.IntegrityFailed,
        is InstallFailure.LocalIo,
        is InstallFailure.CommitFailed,
        -> ExitCodes.TRANSFER

        is InstallFailure.EulaDeclined -> ExitCodes.EULA_DECLINED

        InstallFailure.Cancelled -> ExitCodes.CANCELLED
    }
