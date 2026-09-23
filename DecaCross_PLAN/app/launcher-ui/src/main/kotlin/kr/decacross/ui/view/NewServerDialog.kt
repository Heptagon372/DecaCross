package kr.decacross.ui.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kr.decacross.compat.model.CoreKey
import kr.decacross.daemon.api.InstallRequest
import kr.decacross.daemon.api.McVersionDto
import kr.decacross.ui.state.AppState
import kr.decacross.ui.state.InstallProgress

private const val MIN_RAM_MB = 512
private const val RAM_STEP_MB = 256

/**
 * 새 서버 다이얼로그. 입력값을 `InstallRequest` 로 만들어 데몬에 넘기고, 진행 스트림을 보여준다.
 *
 * ★ EULA 는 사용자가 체크박스를 직접 켜야만 [설치] 가 활성화된다. 어떤 경로로도 자동 동의하지 않는다 (F-05).
 */
@Composable
fun NewServerDialog(state: AppState, onClose: () -> Unit) {
    val sys = state.system
    val recommended = (sys?.recommendedRamMb ?: 2048).coerceAtLeast(MIN_RAM_MB)
    val maxRam = recommended.coerceAtLeast(MIN_RAM_MB + RAM_STEP_MB)

    var name by remember { mutableStateOf("") }
    var mc by remember { mutableStateOf<McVersionDto?>(null) }
    var showSnapshots by remember { mutableStateOf(false) }
    var ramMb by remember { mutableStateOf(recommended) }
    var portText by remember { mutableStateOf("25565") }
    var eula by remember { mutableStateOf(false) }

    val progress = state.install
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
    val nameOk = name.isNotBlank() && name.trim().length <= 32
    val canInstall = eula && nameOk && mc != null && port != null && progress == null && state.endpoint != null

    fun close() {
        if (progress?.finished != false) {
            state.dismissInstall()
            onClose()
        }
    }

    Dialog(onDismissRequest = ::close) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, modifier = Modifier.width(560.dp)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Text("새 서버", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("서버 이름 (폴더 이름)") },
                    singleLine = true,
                    enabled = progress == null,
                    isError = name.isNotEmpty() && !nameOk,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                McVersionPicker(
                    versions = state.mcVersions.filter { showSnapshots || !it.isSnapshot },
                    selected = mc,
                    onSelect = { mc = it },
                    enabled = progress == null,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showSnapshots, onCheckedChange = { showSnapshots = it }, enabled = progress == null)
                    Text("스냅샷 표시", style = MaterialTheme.typography.bodySmall)
                    mc?.let {
                        Spacer(Modifier.width(16.dp))
                        Text("Java ${it.javaRecommended} 권장 (최소 ${it.javaMin}) — 런타임은 설치 시 자동 준비", style = MaterialTheme.typography.bodySmall, color = DecaColors.gray)
                    }
                }
                Spacer(Modifier.height(12.dp))

                Text("코어", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.padding(top = 4.dp)) {
                    FilterChip(selected = true, onClick = {}, label = { Text("Paper") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = false, onClick = {}, enabled = false, label = { Text("Purpur (준비 중)") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = false, onClick = {}, enabled = false, label = { Text("Folia (준비 중)") })
                }
                Spacer(Modifier.height(12.dp))

                Text("RAM  ${formatMb(ramMb.toLong())}   (권장 ${formatMb(recommended.toLong())}${sys?.let { " · 시스템 ${formatMb(it.totalMemoryMb)}" } ?: ""})", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = ramMb.toFloat(),
                    onValueChange = { ramMb = (it / RAM_STEP_MB).toInt() * RAM_STEP_MB },
                    valueRange = MIN_RAM_MB.toFloat()..maxRam.toFloat(),
                    steps = ((maxRam - MIN_RAM_MB) / RAM_STEP_MB - 1).coerceAtLeast(0),
                    enabled = progress == null,
                )

                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text("포트") },
                    singleLine = true,
                    enabled = progress == null,
                    isError = port == null,
                    modifier = Modifier.width(160.dp),
                )
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(checked = eula, onCheckedChange = { eula = it }, enabled = progress == null)
                    Text(
                        "Mojang EULA (https://aka.ms/MinecraftEULA) 를 읽고 동의합니다 — eula.txt 에 eula=true 로 기록됩니다",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                progress?.let { InstallProgressView(it) }

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (progress != null && !progress.finished) {
                        TextButton(onClick = { state.cancelInstall() }) { Text("설치 취소") }
                    } else {
                        TextButton(onClick = ::close) { Text(if (progress?.completed != null) "닫기" else "취소") }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (progress?.completed == null) {
                        Button(
                            onClick = {
                                val v = mc ?: return@Button
                                val p = port ?: return@Button
                                state.install(
                                    InstallRequest(name = name.trim(), mc = v.label, core = CoreKey.PAPER, ramMb = ramMb, port = p, acceptEula = eula, allowExperimental = !v.hasStableBuild),
                                )
                            },
                            enabled = canInstall,
                        ) { Text("설치") }
                    } else {
                        Button(onClick = {
                            state.select(progress.completed.name)
                            close()
                        }) { Text("서버 열기") }
                    }
                }
            }
        }
    }
}

@Composable
private fun McVersionPicker(versions: List<McVersionDto>, selected: McVersionDto?, onSelect: (McVersionDto) -> Unit, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled && versions.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    versions.isEmpty() -> "MC 버전 목록 불러오는 중…"
                    selected == null -> "MC 버전 선택"
                    else -> "MC ${selected.label}" + (if (!selected.hasStableBuild) "  (안정 빌드 없음)" else "")
                },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            versions.forEach { v ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(v.label, fontFamily = FontFamily.Monospace, modifier = Modifier.width(90.dp))
                            Text("Java ${v.javaRecommended}", style = MaterialTheme.typography.bodySmall, color = DecaColors.gray)
                            if (v.isSnapshot) Text("  스냅샷", style = MaterialTheme.typography.bodySmall, color = DecaColors.amber)
                            if (!v.hasStableBuild) Text("  안정 빌드 없음", style = MaterialTheme.typography.bodySmall, color = DecaColors.red)
                        }
                    },
                    onClick = {
                        onSelect(v)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun InstallProgressView(p: InstallProgress) {
    Column(Modifier.padding(top = 16.dp)) {
        val stageKo = when (p.stage) {
            "RESOLVE" -> "버전 확인"
            "PLAN" -> "설치 계획"
            "FETCH" -> "다운로드"
            "VERIFY" -> "무결성 검증"
            "LAYOUT" -> "파일 배치"
            "CONFIG" -> "설정 작성"
            "EULA" -> "EULA 기록"
            "READY" -> "완료"
            null -> "대기 중"
            else -> p.stage
        }
        val mb = p.doneBytes?.let { d -> "%.1f MB".format(d / 1048576.0) + (p.totalBytes?.let { t -> " / %.1f MB".format(t / 1048576.0) } ?: "") }
        Text("단계: $stageKo${mb?.let { "  ·  $it" } ?: ""}", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        val frac = p.doneBytes?.let { d -> p.totalBytes?.takeIf { it > 0 }?.let { t -> (d.toFloat() / t).coerceIn(0f, 1f) } }
        when {
            p.completed != null -> LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
            p.failed != null -> Unit
            frac != null -> LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        p.messages.takeLast(6).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = DecaColors.gray) }
        p.failed?.let { Text("설치 실패: $it", color = DecaColors.red, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
        p.completed?.let { Text("설치 완료: ${it.name} (${it.mcLabel} ${it.core.name.lowercase()} #${it.build})", color = DecaColors.green, modifier = Modifier.padding(top = 6.dp)) }
    }
}
