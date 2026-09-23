package kr.decacross.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.decacross.ui.console.CommandHistory
import kr.decacross.ui.console.ConsoleSnapshot
import kr.decacross.ui.console.LogLevel

/**
 * 콘솔 패널: 가상 스크롤 로그 + 명령 입력.
 *
 * - [lines] 는 링 버퍼의 불변 스냅샷. `LazyColumn` 이 보이는 줄만 꺼내므로 10만 줄에서도 렌더 비용은 화면 크기에 비례한다.
 * - 새 줄이 오면 맨 아래로 따라간다. 사용자가 위로 스크롤하면 따라가기를 멈추고 "↓ 최신" 칩을 띄운다.
 * - 줄마다 하는 일은 레벨 색 판정(`contains`) 뿐이다. 이벤트 파싱·아이콘은 06 단계.
 */
@Composable
fun ConsolePanel(
    lines: ConsoleSnapshot,
    history: CommandHistory,
    inputEnabled: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth().background(DecaColors.consoleBg)) {
            LogList(lines)
        }
        CommandInput(history, inputEnabled, onSend)
    }
}

@Composable
private fun LogList(lines: ConsoleSnapshot) {
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }

    // 사용자가 위로 스크롤(내용이 아래로 이동 = 양의 y) 하면 자동 따라가기 해제
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y > 0f) follow = false
                return Offset.Zero
            }
        }
    }
    // 다시 맨 아래에 닿으면 따라가기 복귀
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canForward -> if (!canForward) follow = true }
    }
    // 새 배치가 오면 맨 아래로
    LaunchedEffect(lines.generation) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().nestedScroll(nested).padding(horizontal = 8.dp, vertical = 4.dp)) {
        items(count = lines.size) { i -> LogLine(lines[i]) }
    }

    if (!follow && lines.isNotEmpty()) {
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.BottomEnd) {
            AssistChip(onClick = { follow = true }, label = { Text("↓ 최신") })
        }
    }
    // follow 가 true 로 바뀌었을 때 즉시 이동
    LaunchedEffect(follow) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
}

@Composable
private fun LogLine(line: String) {
    val color = when (LogLevel.of(line)) {
        LogLevel.WARN -> DecaColors.amber
        LogLevel.ERROR -> DecaColors.red
        LogLevel.INFO -> DecaColors.consoleText
    }
    Text(
        text = line,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 명령 입력. Enter 전송, ↑/↓ 히스토리. 타이핑을 시작하면 히스토리 커서는 끝으로 돌아간다. */
@Composable
private fun CommandInput(history: CommandHistory, enabled: Boolean, onSend: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    fun set(text: String) {
        value = TextFieldValue(text, selection = TextRange(text.length))
    }
    fun send() {
        val line = value.text.trim()
        if (line.isEmpty()) return
        onSend(line)
        set("")
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = { v ->
                if (v.text != value.text) history.reset()
                value = v
            },
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(if (enabled) "명령 입력 (Enter 전송, ↑↓ 히스토리)" else "서버가 실행 중일 때 명령을 보낼 수 있습니다") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f).onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionUp -> {
                        history.up()?.let(::set)
                        true
                    }

                    Key.DirectionDown -> {
                        set(history.down())
                        true
                    }

                    Key.Enter, Key.NumPadEnter -> {
                        send()
                        true
                    }

                    else -> false
                }
            },
        )
        Button(onClick = ::send, enabled = enabled, modifier = Modifier.padding(start = 8.dp)) { Text("전송") }
    }
}
