package kr.decacross.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kr.decacross.daemon.paths.DecaPaths
import kr.decacross.ui.daemon.DaemonClient
import kr.decacross.ui.daemon.DaemonDiscovery
import kr.decacross.ui.daemon.DaemonLauncher
import kr.decacross.ui.state.AppState
import kr.decacross.ui.view.App

/**
 * 런처 UI 진입점.
 *
 * - 창을 닫으면 트레이로 숨는다(트레이 지원 시). 트레이 "종료" 또는 `--no-tray` 에서 창 닫기 → UI 프로세스만 종료.
 * - ★ 어느 경로로도 데몬을 멈추지 않는다. 데몬과 마크 서버는 UI 와 무관하게 산다 (05 완료 판정).
 * - `--no-tray` 인자 또는 환경변수 `DECACROSS_NO_TRAY=1` 로 트레이를 끈다.
 */
fun main(args: Array<String>) {
    val noTray = args.contains("--no-tray") || System.getenv("DECACROSS_NO_TRAY") == "1"
    val paths = DecaPaths.detect()
    val client = DaemonClient()
    // Dispatchers.Main = Swing EDT (kotlinx-coroutines-swing). 상태 갱신은 전부 여기서, I/O 는 Ktor·IO 디스패처에서.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val discovery = DaemonDiscovery(paths.appData, client, spawn = { DaemonLauncher.spawn(paths.logs) })
    val state = AppState(scope, client, discovery)
    state.connect()

    application {
        val trayEnabled = !noTray && isTraySupported
        var visible by remember { mutableStateOf(true) }
        val quit = {
            scope.cancel()
            client.close()
            exitApplication()
        }

        if (trayEnabled) {
            Tray(
                icon = DecaTrayIcon,
                tooltip = "DecaCross",
                onAction = { visible = true },
                menu = {
                    Item("열기", onClick = { visible = true })
                    Separator()
                    Item("종료 (데몬·서버는 계속 실행)", onClick = quit)
                },
            )
        }

        Window(
            onCloseRequest = { if (trayEnabled) visible = false else quit() },
            visible = visible,
            title = "DecaCross",
            state = rememberWindowState(width = 1200.dp, height = 760.dp),
        ) {
            App(state)
        }
    }
}

/** 트레이 아이콘. 리소스 파일 없이 그린다 (초록 둥근 사각형 + 어두운 점). */
private object DecaTrayIcon : Painter() {
    override val intrinsicSize: Size = Size(64f, 64f)

    override fun DrawScope.onDraw() {
        drawRoundRect(color = Color(0xFF3DDC84), cornerRadius = CornerRadius(size.width * 0.22f))
        drawCircle(color = Color(0xFF10261A), radius = size.width * 0.18f)
    }
}
