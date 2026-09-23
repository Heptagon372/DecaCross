package kr.decacross.ui.view

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.decacross.daemon.process.ServerState
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.Inet4Address
import java.net.NetworkInterface

/** 다크 테마 위에 얹는 의미 색. Material 팔레트와 별개로 상태·로그 레벨에만 쓴다. */
object DecaColors {
    val green = Color(0xFF4CAF50)
    val amber = Color(0xFFE0A526)
    val red = Color(0xFFEF5350)
    val gray = Color(0xFF8A8F98)
    val consoleBg = Color(0xFF111418)
    val consoleText = Color(0xFFD5D9DE)
}

fun stateColor(s: ServerState): Color = when (s) {
    ServerState.STOPPED -> DecaColors.gray
    ServerState.STARTING, ServerState.STOPPING -> DecaColors.amber
    ServerState.RUNNING -> DecaColors.green
    ServerState.CRASHED, ServerState.CRASH_LOOP -> DecaColors.red
}

fun stateLabel(s: ServerState): String = when (s) {
    ServerState.STOPPED -> "정지됨"
    ServerState.STARTING -> "시작 중"
    ServerState.RUNNING -> "실행 중"
    ServerState.STOPPING -> "정지 중"
    ServerState.CRASHED -> "크래시"
    ServerState.CRASH_LOOP -> "크래시 루프"
}

fun formatUptime(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun formatMb(mb: Long): String = if (mb >= 1024) "%.1fG".format(mb / 1024.0) else "${mb}M"

/** AWT 시스템 클립보드. Compose 의 Clipboard API 는 버전마다 바뀌어서 JDK 표준을 직접 쓴다. */
fun copyToClipboard(text: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}

/** LAN 접속 주소 추정: 활성·비루프백·비가상 인터페이스의 첫 사설 IPv4. 없으면 null. */
suspend fun localIpGuess(): String? = withContext(Dispatchers.IO) {
    runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
