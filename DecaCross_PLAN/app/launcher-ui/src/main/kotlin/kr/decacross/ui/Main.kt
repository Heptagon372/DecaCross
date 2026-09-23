package kr.decacross.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/** 런처 UI 진입점. 창을 닫으면 UI 프로세스만 종료된다 — 데몬과 서버는 유지(05). */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "DecaCross") {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface {
                Text("DecaCross")
            }
        }
    }
}
