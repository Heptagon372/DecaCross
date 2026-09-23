// app:launcher-ui — Compose Multiplatform Desktop. UI 만. 로직은 전부 데몬에 있고 HTTP/WS 로 붙는다. -Xmx384m.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    // 데몬과는 HTTP/WS 로만 대화한다. 프로젝트 의존성은 DTO 클래스 공유 + 데몬 프로세스 기동(클래스패스)용.
    implementation(project(":app:daemon"))
    // DTO 가 CoreKey(compat-engine) 를 노출하므로 컴파일에 필요. UI 는 이 모듈의 로직을 호출하지 않는다.
    implementation(project(":core:compat-engine"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

compose.desktop {
    application {
        mainClass = "kr.decacross.ui.MainKt"
        // 메모리 예산 (명세 §8.1): UI 는 창을 닫으면 종료되므로 384MB
        // --enable-native-access: JDK 25 에서 Skiko(네이티브 렌더러) 로드 경고를 막는다
        jvmArgs += listOf("-Xmx384m", "-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "DecaCross"
            packageVersion = "1.0.0"
            description = "DecaCross — Minecraft server builder"
            vendor = "DecaCross"
        }
    }
}
