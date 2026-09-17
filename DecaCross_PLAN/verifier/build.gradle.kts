// verifier — 정적 검증 우선(P2), 실기동은 보조. static_verify_results 를 대량 생산하는 CI 배치.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":core:compat-engine"))
    implementation(project(":core:jvm-analysis"))
    implementation(project(":core:logparse"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("kr.decacross.verifier.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}
