package kr.decacross.ui.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.decacross.ui.state.AppState

/** 루트 화면. 다크 테마 기본. 사이드바 + 상세. */
@Composable
fun App(state: AppState) {
    var showNewServer by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.lastError) {
        val msg = state.lastError ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        state.clearError()
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    Sidebar(state, onNewServer = { showNewServer = true })
                    VerticalDivider()
                    if (state.selectedId != null) {
                        ServerDetailView(state, Modifier.weight(1f))
                    } else {
                        EmptyPane(Modifier.weight(1f))
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp)) { data ->
                    Snackbar(data, containerColor = DecaColors.red.copy(alpha = 0.9f))
                }
            }
        }
        if (showNewServer) NewServerDialog(state, onClose = { showNewServer = false })
    }
}

@Composable
private fun EmptyPane(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("서버를 선택하거나 새로 만드세요", style = MaterialTheme.typography.titleMedium, color = DecaColors.gray)
            Text("창을 닫아도 데몬과 서버는 계속 돕니다. 트레이 아이콘에서 다시 열 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = DecaColors.gray, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
