package kr.decacross.cli

import kr.decacross.cli.testkit.Fixtures
import kr.decacross.cli.testkit.RecordingIo
import kr.decacross.compat.model.CoreKey
import kr.decacross.daemon.install.ArtifactKind
import kr.decacross.daemon.install.AttemptRecord
import kr.decacross.daemon.install.FetchError
import kr.decacross.daemon.install.FetchItem
import kr.decacross.daemon.install.FetchItemEvent
import kr.decacross.daemon.install.FetchProgress
import kr.decacross.daemon.install.InstallEvent
import kr.decacross.daemon.install.InstallFailure
import kr.decacross.daemon.install.InstallPlan
import kr.decacross.daemon.install.InstallStage
import kr.decacross.daemon.install.InstalledServer
import kr.decacross.daemon.install.ItemProgress
import kr.decacross.daemon.install.ItemState
import kr.decacross.daemon.install.VerifyOutcome
import kr.decacross.daemon.install.describeKo
import kr.decacross.daemon.runtime.JavaCandidate
import kr.decacross.daemon.runtime.JavaOrigin
import kr.decacross.daemon.runtime.JavaSelection
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventRendererTest {
    private val serverDir: Path = Path.of("/tmp/servers/paper-1.21.8").toAbsolutePath()

    private val plan = InstallPlan(
        installId = "t-1",
        target = Fixtures.target,
        serverName = "paper-1.21.8",
        serverDir = serverDir,
        items = listOf(
            FetchItem("core:paper-1.21.8-60.jar", listOf("https://example.invalid/a.jar"), "0".repeat(64), 55_312_384L, ArtifactKind.SERVER_JAR),
        ),
        totalBytes = 55_312_384L,
        java = JavaSelection(Path.of("/opt/java/bin/java"), 21, "21.0.8", JavaOrigin.JAVA_HOME, emptyList()),
        launch = Fixtures.spec,
        warningsKo = emptyList(),
    )

    private val allFailures: List<Pair<InstallFailure, Int>> = listOf(
        InstallFailure.UnknownMc("1.99", listOf("1.21.8")) to ExitCodes.INPUT,
        InstallFailure.UnsupportedCore(CoreKey.FABRIC) to ExitCodes.INPUT,
        InstallFailure.NoStableBuild(CoreKey.PAPER, "1.21.8", "61") to ExitCodes.INPUT,
        InstallFailure.NoBuildCollected(CoreKey.PAPER, "1.21.8") to ExitCodes.INPUT,
        InstallFailure.InvalidCatalogData("sha256 길이") to ExitCodes.INPUT,
        InstallFailure.InvalidServerName("a/b", "경로 구분자") to ExitCodes.INPUT,
        InstallFailure.ServerExists(serverDir) to ExitCodes.INPUT,
        InstallFailure.NameBusy("demo") to ExitCodes.INPUT,
        InstallFailure.ServersRootUnusable(serverDir, "! 포함") to ExitCodes.INPUT,
        InstallFailure.InvalidRam(512, "하한 미만") to ExitCodes.INPUT,
        InstallFailure.JavaNotFound(
            21,
            21,
            listOf(JavaCandidate(Path.of("/usr/bin/java"), JavaOrigin.PATH, "17.0.1", 17, "버전 부족")),
        ) to ExitCodes.INPUT,
        InstallFailure.NoFlagProfile("Java 29") to ExitCodes.INPUT,
        InstallFailure.InsufficientDisk(serverDir, 100L, 10L) to ExitCodes.INPUT,
        InstallFailure.PlanRejected to ExitCodes.INPUT,
        InstallFailure.DownloadFailed(
            "core",
            FetchError.SourcesExhausted(listOf(AttemptRecord("https://example.invalid/a.jar", 1, "503"))),
        ) to ExitCodes.TRANSFER,
        InstallFailure.DownloadFailed(
            "core",
            FetchError.SizeMismatch("https://example.invalid/a.jar", 10L, 20L, "Content-Length"),
        ) to ExitCodes.TRANSFER,
        InstallFailure.DownloadFailed("core", FetchError.UnexpectedContent("https://example.invalid/a.jar", "text/html")) to
            ExitCodes.TRANSFER,
        InstallFailure.DownloadFailed("core", FetchError.LocalIo(serverDir, "디스크 가득")) to ExitCodes.TRANSFER,
        InstallFailure.DownloadFailed("core", FetchError.Aborted("other")) to ExitCodes.TRANSFER,
        InstallFailure.IntegrityFailed("core", VerifyOutcome.HashMismatch("a".repeat(64), "b".repeat(64))) to ExitCodes.TRANSFER,
        InstallFailure.IntegrityFailed("core", VerifyOutcome.CorruptArchive("CRC 불일치")) to ExitCodes.TRANSFER,
        InstallFailure.LocalIo(serverDir, "권한 없음") to ExitCodes.TRANSFER,
        InstallFailure.CommitFailed("대상이 이미 있음") to ExitCodes.TRANSFER,
        InstallFailure.EulaDeclined("입력 없음(EOF)") to ExitCodes.EULA_DECLINED,
        InstallFailure.Cancelled to ExitCodes.CANCELLED,
    )

    @Test
    fun `단계마다 머리줄을 찍는다`() {
        val recording = RecordingIo()
        val renderer = EventRenderer(recording.io)
        for (stage in InstallStage.entries) renderer.render(InstallEvent.StageEntered(stage))
        val text = recording.text()
        for (expected in listOf(
            "[1/8] 버전 확인",
            "[2/8] 계획",
            "[3/8] 다운로드",
            "[4/8] 검증",
            "[5/8] 조립",
            "[6/8] 설정",
            "[7/8] EULA",
            "[8/8] 설치 완료",
        )) {
            assertTrue(text.contains(expected), "$expected 가 없다:\n$text")
        }
        assertEquals(null, stageLine(InstallStage.IDLE))
        assertEquals(null, stageLine(InstallStage.FAILED))
    }

    @Test
    fun `계획 요약은 다섯 줄이고 대상 폴더 줄이 절대경로다`() {
        val recording = RecordingIo()
        EventRenderer(recording.io).render(InstallEvent.Planned(plan))
        assertEquals(5, recording.lines.size, recording.text())
        assertTrue(recording.lines[0].startsWith("  MC/코어: "), recording.lines[0])
        assertTrue(recording.lines[1].startsWith("  받을 크기: "), recording.lines[1])
        assertTrue(recording.lines[2].startsWith("  Java: "), recording.lines[2])
        assertTrue(recording.lines[3].startsWith("  메모리: "), recording.lines[3])
        assertEquals("  대상 폴더: $serverDir", recording.lines[4])
    }

    @Test
    fun `진행률은 10퍼센트 구간을 넘길 때만 그리고 100퍼센트는 반드시 찍는다`() {
        val recording = RecordingIo()
        val renderer = EventRenderer(recording.io)
        val total = 100L * 1024 * 1024
        for (percent in listOf(0, 5, 9, 10, 12, 19, 20, 21, 99, 100)) {
            renderer.render(InstallEvent.Progress(progress(total * percent / 100, total, 1024L * 1024L)))
        }
        assertEquals(4, recording.lines.size, recording.text())
        assertTrue(recording.lines[0].startsWith("  10% "), recording.lines[0])
        assertTrue(recording.lines[1].startsWith("  20% "), recording.lines[1])
        assertTrue(recording.lines[2].startsWith("  99% "), recording.lines[2])
        assertTrue(recording.lines[3].startsWith("  100% "), recording.lines[3])
        assertTrue(recording.lines[0].contains("초 남음"), recording.lines[0])
    }

    @Test
    fun `속도를 모르면 남은 시간을 생략한다`() {
        val recording = RecordingIo()
        val total = 100L * 1024 * 1024
        EventRenderer(recording.io).render(InstallEvent.Progress(progress(total / 2, total, 0L)))
        assertEquals("  50% 50/100 MB", recording.lines.single())
    }

    @Test
    fun `다운로드 사건마다 한 줄씩 찍는다`() {
        val recording = RecordingIo()
        val renderer = EventRenderer(recording.io)
        renderer.render(InstallEvent.FetchNotice(FetchItemEvent.Started("core", "https://a", 0L)))
        renderer.render(InstallEvent.FetchNotice(FetchItemEvent.Started("core", "https://a", 8L * 1024 * 1024)))
        renderer.render(InstallEvent.FetchNotice(FetchItemEvent.Retrying("core", 2, 4, 1000L, "503")))
        renderer.render(InstallEvent.FetchNotice(FetchItemEvent.SourceSwitched("core", 0, 1)))
        renderer.render(InstallEvent.FetchNotice(FetchItemEvent.ResumeRejected("core", 100L)))
        renderer.render(InstallEvent.FetchNotice(FetchItemEvent.Completed("core", 10L, fromCache = true)))
        renderer.render(InstallEvent.FetchNotice(FetchItemEvent.Completed("core", 10L, fromCache = false)))
        renderer.render(InstallEvent.FetchNotice(FetchItemEvent.Failed("core", FetchError.Aborted("other"))))
        assertEquals(8, recording.lines.size)
        assertEquals("  받기 시작: core", recording.lines[0])
        assertTrue(recording.lines[1].contains("이어받기 8 MB"), recording.lines[1])
        assertEquals("  재시도 2/4 (1000 ms): 503", recording.lines[2])
        assertEquals("  미러로 전환", recording.lines[3])
        assertTrue(recording.lines[4].contains("이어받기를 거부"), recording.lines[4])
        assertEquals("  캐시 적중: core (다운로드 생략)", recording.lines[5])
        assertEquals("  받기 완료: core", recording.lines[6])
        assertEquals("  받기 실패: core", recording.lines[7])
    }

    @Test
    fun `실패는 메시지와 모든 해결책을 찍고 종료 코드를 정한다`() {
        for ((failure, expected) in allFailures) {
            val recording = RecordingIo()
            val renderer = EventRenderer(recording.io)
            renderer.render(InstallEvent.Failed(InstallStage.FETCH, failure))
            assertEquals(expected, renderer.exitCode, "$failure 의 종료 코드")
            assertEquals(expected, exitCodeFor(failure), "$failure 의 매핑")
            val description = failure.describeKo()
            assertTrue(recording.lines.first().startsWith("[실패] "), recording.text())
            val fixLines = recording.lines.filter { it.startsWith("  해결: ") }
            assertEquals(description.fixesKo.size, fixLines.size, "$failure 의 해결책 줄 수")
            assertTrue(fixLines.isNotEmpty(), "$failure 에 해결책이 없다")
        }
    }

    @Test
    fun `취소는 CLI 가 직접 만들어 130 으로 끝낸다`() {
        val recording = RecordingIo()
        val renderer = EventRenderer(recording.io)
        renderer.renderCancelled()
        assertEquals(ExitCodes.CANCELLED, renderer.exitCode)
        assertTrue(recording.text().contains("취소"), recording.text())
    }

    @Test
    fun `완료와 롤백 줄`() {
        val recording = RecordingIo()
        val renderer = EventRenderer(recording.io)
        val server = InstalledServer("paper-1.21.8", serverDir, Fixtures.spec, Fixtures.manifest())
        renderer.render(InstallEvent.Ready(server))
        assertEquals(server, renderer.installed)
        assertEquals(ExitCodes.OK, renderer.exitCode)
        renderer.render(InstallEvent.RolledBack(InstallStage.EULA, cleanedUp = false, leftovers = listOf(serverDir)))
        val text = recording.text()
        assertTrue(text.contains("[완료]"), text)
        assertTrue(text.contains("[롤백] 완료"), text)
        assertTrue(text.contains("남은 항목"), text)
    }

    @Test
    fun `스윕이 이전 설치의 잔해를 지웠으면 폴더가 그대로라고 말하지 않는다`() {
        // ★ 회귀: "[롤백] 완료 - 사용자 폴더는 바뀌지 않았습니다" 는 무조건 찍혔다. LAYOUT 스윕이 이전 설치의
        //   조각(.staging/*, INCOMPLETE 서버 폴더)을 지운 실행에서는 거짓말이다.
        val recording = RecordingIo()
        val swept = serverDir.resolveSibling(".staging").resolve("old-1")
        EventRenderer(recording.io).render(
            InstallEvent.RolledBack(InstallStage.EULA, cleanedUp = true, leftovers = emptyList(), sweptPaths = listOf(swept)),
        )
        val text = recording.text()
        assertFalse(text.contains("사용자 폴더는 바뀌지 않았습니다"), text)
        assertTrue(text.contains("[롤백] 완료"), text)
        assertTrue(text.contains(swept.toString()), text)
    }

    @Test
    fun `아무것도 건드리지 않은 롤백만 폴더가 그대로라고 말한다`() {
        val recording = RecordingIo()
        EventRenderer(recording.io).render(
            InstallEvent.RolledBack(InstallStage.EULA, cleanedUp = true, leftovers = emptyList()),
        )
        assertTrue(recording.text().contains("사용자 폴더는 바뀌지 않았습니다"), recording.text())
    }

    @Test
    fun `경고는 주의 표식으로 찍는다`() {
        val recording = RecordingIo()
        EventRenderer(recording.io).render(InstallEvent.Warning("여유 메모리가 적습니다"))
        assertEquals("[주의] 여유 메모리가 적습니다", recording.lines.single())
    }

    @Test
    fun `모든 출력이 MS949 로 인코딩된다`() {
        assumeTrue(Charset.isSupported("MS949"), "MS949 를 지원하지 않는 JVM")
        val encoder = Charset.forName("MS949").newEncoder()
        val recording = RecordingIo()
        val renderer = EventRenderer(recording.io)
        for (stage in InstallStage.entries) renderer.render(InstallEvent.StageEntered(stage))
        renderer.render(InstallEvent.Resolved(Fixtures.target))
        renderer.render(InstallEvent.Planned(plan))
        renderer.render(InstallEvent.Progress(progress(50L * 1024 * 1024, 100L * 1024 * 1024, 1024L * 1024L)))
        renderer.render(InstallEvent.Verified("core", "0".repeat(64), 55_312_384L))
        renderer.render(InstallEvent.Warning("여유 메모리가 적습니다"))
        renderer.render(
            InstallEvent.FetchNotice(FetchItemEvent.Retrying("core", 2, 4, 1000L, "503 Service Unavailable")),
        )
        renderer.render(InstallEvent.Ready(InstalledServer("paper-1.21.8", serverDir, Fixtures.spec, Fixtures.manifest())))
        renderer.render(InstallEvent.RolledBack(InstallStage.EULA, cleanedUp = false, leftovers = listOf(serverDir)))
        renderer.renderCancelled()
        for ((failure, _) in allFailures) renderer.render(InstallEvent.Failed(InstallStage.FETCH, failure))
        for (line in recording.lines) {
            assertTrue(encoder.canEncode(line), "MS949 로 인코딩할 수 없는 줄: $line")
        }
    }

    private fun progress(done: Long, total: Long, speed: Long): FetchProgress =
        FetchProgress(done, total, speed, listOf(ItemProgress("core", done, total, ItemState.DOWNLOADING)))
}
