package kr.decacross.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kr.decacross.daemon.process.ServerState
import kr.decacross.ui.state.AppState

/** 서버 상세: 상단 상태바 + 제어 버튼 + 콘솔/플레이어 패널. */
@Composable
fun ServerDetailView(state: AppState, modifier: Modifier = Modifier) {
    val id = state.selectedId ?: return
    val summary = state.servers.firstOrNull { it.id == id }
    val status = state.status
    val serverState = status?.state ?: summary?.state ?: ServerState.STOPPED
    val port = status?.port ?: summary?.port ?: 25565
    val busy = state.pendingAction != null

    var confirmForce by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var localIp by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { localIp = localIpGuess() }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        // ── 헤더: 이름 + 상태 + 버튼 ─────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(stateColor(serverState)))
            Spacer(Modifier.width(8.dp))
            Text(summary?.name ?: id, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text(stateLabel(serverState), color = stateColor(serverState), style = MaterialTheme.typography.titleSmall)
            state.pendingAction?.let {
                Spacer(Modifier.width(12.dp))
                Text(it, style = MaterialTheme.typography.labelMedium, color = DecaColors.amber)
            }
            state.shutdownPhase?.let {
                Spacer(Modifier.width(12.dp))
                Text("종료 단계: $it", style = MaterialTheme.typography.labelMedium, color = DecaColors.amber)
            }
            Spacer(Modifier.weight(1f))
            val canStart = !busy && (serverState == ServerState.STOPPED || serverState == ServerState.CRASHED || serverState == ServerState.CRASH_LOOP)
            val running = serverState == ServerState.RUNNING || serverState == ServerState.STARTING
            Button(onClick = state::start, enabled = canStart) { Text("▶ 시작") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { state.stop(force = false) }, enabled = !busy && running) { Text("■ 정지") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = state::restart, enabled = !busy && running) { Text("↻ 재시작") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = { confirmForce = true },
                enabled = running || serverState == ServerState.STOPPING,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DecaColors.red),
            ) { Text("⚠ 강제 종료") }
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = { confirmDelete = true }, enabled = !busy && !running && serverState != ServerState.STOPPING) { Text("삭제") }
        }

        // ── 상태바 ───────────────────────────────────────
        Surface(Modifier.fillMaxWidth().padding(vertical = 12.dp), tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(14.dp, 10.dp)) {
                AddressRow("접속 주소", "127.0.0.1:$port")
                AddressRow("로컬", localIp?.let { "$it:$port" } ?: "(LAN 주소 없음)", copyable = localIp != null)
                Spacer(Modifier.size(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Metric("플레이어", status?.let { "${it.players.size}${it.maxPlayers?.let { m -> " / $m" } ?: ""}" } ?: "—")
                    Metric("TPS", status?.tps?.let { "%.2f".format(it) } ?: "—")
                    Metric("RAM", status?.let { st -> st.ramUsedMb?.let { u -> "${formatMb(u)} / ${formatMb(st.ramMaxMb ?: summary?.ramMb?.toLong() ?: 0)}" } } ?: (summary?.ramMb?.let { "— / ${formatMb(it.toLong())}" } ?: "—"))
                    Metric("업타임", status?.uptimeSec?.let(::formatUptime) ?: "—")
                    Metric("PID", status?.pid?.toString() ?: "—")
                    if ((status?.crashesRecent ?: 0) > 0) Metric("최근 크래시", status?.crashesRecent.toString(), DecaColors.red)
                    if (!state.streamConnected) Metric("스트림", "재연결 중…", DecaColors.amber)
                }
            }
        }

        // ── 콘솔 + 플레이어 ──────────────────────────────
        Row(Modifier.weight(1f).fillMaxWidth()) {
            ConsolePanel(
                lines = state.console,
                history = state.history,
                inputEnabled = serverState == ServerState.RUNNING || serverState == ServerState.STARTING,
                onSend = state::sendCommand,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            PlayerPanel(status?.players.orEmpty(), Modifier.width(200.dp).fillMaxHeight().padding(start = 12.dp))
        }
    }

    if (confirmForce) {
        AlertDialog(
            onDismissRequest = { confirmForce = false },
            title = { Text("강제 종료") },
            text = { Text("save-all / stop 을 건너뛰고 프로세스를 바로 죽입니다.\n저장 중이던 청크가 깨져 월드가 손상될 수 있습니다.\n\n가능하면 [■ 정지] 를 먼저 시도하세요.") },
            confirmButton = {
                Button(onClick = {
                    confirmForce = false
                    state.stop(force = true)
                }, colors = ButtonDefaults.buttonColors(containerColor = DecaColors.red)) { Text("그래도 강제 종료") }
            },
            dismissButton = { TextButton(onClick = { confirmForce = false }) { Text("취소") } },
        )
    }
    if (confirmDelete) {
        var keepWorld by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("서버 삭제") },
            text = {
                Column {
                    Text("'${summary?.name ?: id}' 를 서버 목록에서 지우고 폴더를 삭제합니다.")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Checkbox(checked = keepWorld, onCheckedChange = { keepWorld = it })
                        Text("월드 폴더는 남긴다 (world, world_nether, world_the_end)")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    state.delete(keepWorld)
                }, colors = ButtonDefaults.buttonColors(containerColor = DecaColors.red)) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun AddressRow(label: String, value: String, copyable: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = DecaColors.gray, modifier = Modifier.width(80.dp))
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        if (copyable) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { copyToClipboard(value) }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("복사") }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = DecaColors.gray)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color, fontFamily = FontFamily.Monospace)
    }
}

/** 플레이어 패널. 지금은 이름만 (ping·좌표·액션은 이후 단계). */
@Composable
private fun PlayerPanel(players: List<String>, modifier: Modifier = Modifier) {
    Surface(modifier, tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp)) {
            Text("플레이어 (${players.size})", style = MaterialTheme.typography.titleSmall)
            if (players.isEmpty()) {
                Text("접속자 없음", style = MaterialTheme.typography.bodySmall, color = DecaColors.gray, modifier = Modifier.padding(top = 8.dp))
            } else {
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(players) { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(DecaColors.green))
                            Spacer(Modifier.width(8.dp))
                            Text(p, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
