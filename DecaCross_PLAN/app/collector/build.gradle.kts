// app:collector — 수집 배치 (Kotlin/JVM). 외부 API → 스냅샷 JSON(CompatFixture) / Postgres. 분석한 jar 는 즉시 폐기.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":core:compat-engine"))
    implementation(project(":core:jvm-analysis"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // CLI 플래그
    implementation(libs.clikt)

    // 선택: DATABASE_URL 이 있을 때만 쓰는 Postgres 싱크 (db/PostgresSink.kt 한 곳에만)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.json)
    implementation(libs.postgresql)

    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

application {
    mainClass.set("kr.decacross.collector.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
