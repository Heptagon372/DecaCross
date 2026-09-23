package kr.decacross.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kr.decacross.daemon.api.ServerSummary
import kr.decacross.ui.state.AppState
import kr.decacross.ui.state.Connection

/** 좌측 사이드바: 서버 목록 + 새 서버 + 데몬/시스템 상태. */
@Composable
fun Sidebar(state: AppState, onNewServer: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(260.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Text("DecaCross", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 6.dp))
        Text("내 PC가 서버 컴퓨터가 된다", style = MaterialTheme.typography.labelSmall, color = DecaColors.gray, modifier = Modifier.padding(horizontal = 16.dp))
        Button(onClick = onNewServer, enabled = state.endpoint != null, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("+ 새 서버") }
        HorizontalDivider()

        LazyColumn(Modifier.weight(1f)) {
            items(state.servers, key = { it.id }) { s -> ServerRow(s, selected = s.id == state.selectedId) { state.select(s.id) } }
            if (state.servers.isEmpty()) {
                item {
                    Text(
                        if (state.endpoint == null) "데몬 연결 대기 중…" else "서버가 없습니다. [+ 새 서버] 로 만드세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DecaColors.gray,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        HorizontalDivider()
        DaemonStatus(state)
    }
}

@Composable
private fun ServerRow(s: ServerSummary, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).background(bg).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(stateColor(s.state)))
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text("${s.mcLabel} · ${s.core.name.lowercase()} #${s.build} · :${s.port}", style = MaterialTheme.typography.labelSmall, color = DecaColors.gray, maxLines = 1)
        }
        StateBadge(s)
    }
}

@Composable
private fun StateBadge(s: ServerSummary) {
    val c = stateColor(s.state)
    Text(
        stateLabel(s.state),
        style = MaterialTheme.typography.labelSmall,
        color = c,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun DaemonStatus(state: AppState) {
    Column(Modifier.padding(14.dp, 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (color, text) = when (val c = state.connection) {
                is Connection.Connected -> DecaColors.green to "데몬 연결됨 · 포트 ${c.endpoint.port}"
                is Connection.Connecting -> DecaColors.amber to "데몬 찾는 중… (없으면 자동 기동)"
                is Connection.Failed -> DecaColors.red to "데몬 연결 안 됨"
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
        (state.connection as? Connection.Failed)?.let {
            Text(it.messageKo, style = MaterialTheme.typography.labelSmall, color = DecaColors.red, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.height(6.dp))
        val sys = state.system
        if (sys != null) {
            Text("시스템 메모리 ${formatMb(sys.totalMemoryMb)} · 권장 RAM ${formatMb(sys.recommendedRamMb.toLong())}", style = MaterialTheme.typography.labelSmall, color = DecaColors.gray)
            Text("데몬 v${sys.version} · RSS ${sys.daemonRssMb}MB", style = MaterialTheme.typography.labelSmall, color = DecaColors.gray)
        }
    }
}
