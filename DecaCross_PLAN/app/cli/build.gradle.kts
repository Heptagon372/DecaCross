// app:cli — 3주차 데모 + 개발용 CLI. `./gradlew :app:cli:run --args="lookup 1.21.8"`
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":core:compat-engine"))
    implementation(project(":app:daemon"))

    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("kr.decacross.cli.MainKt")
    // JDK 18+ 는 stdout 인코딩이 file.encoding 과 별개다 — 한글 콘솔 출력 깨짐 방지
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// `run` 이 표준입력(EULA 프롬프트)을 받을 수 있게
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
