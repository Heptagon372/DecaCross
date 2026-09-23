// app:launcher-ui — Compose Multiplatform Desktop. UI 만. 로직은 전부 데몬에 있고 HTTP/WS 로 붙는다. -Xmx384m.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(libs.kotlin.test)
}

compose.desktop {
    application {
        mainClass = "kr.decacross.ui.MainKt"
        // 메모리 예산 (명세 §8.1): UI 는 창을 닫으면 종료되므로 384MB
        jvmArgs += listOf("-Xmx384m", "-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "DecaCross"
            packageVersion = "1.0.0"
            description = "DecaCross — Minecraft server builder"
            vendor = "DecaCross"
        }
    }
}
