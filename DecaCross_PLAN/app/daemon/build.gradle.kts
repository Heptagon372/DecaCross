// app:daemon — ★ 헤드리스 코어. 설치 파이프라인 + 프로세스 감독 + 런타임 + Ktor API. -Xmx192m.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":core:compat-engine"))
    implementation(project(":core:dcx"))
    implementation(project(":core:logparse"))
    implementation(project(":core:jvm-analysis"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
}

application {
    mainClass.set("kr.decacross.daemon.MainKt")
    // 메모리 예산 (명세 §8.1): 데몬은 상주하므로 192MB 상한
    applicationDefaultJvmArgs = listOf("-Xmx192m", "-Dfile.encoding=UTF-8")
}
