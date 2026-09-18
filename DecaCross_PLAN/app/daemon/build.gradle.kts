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
    // 03 Fetch 테스트: 대부분의 전송 로직은 MockEngine 으로, 실제 소켓 동작은 JDK HttpServer(testkit)로 검증한다
    testImplementation(libs.ktor.client.mock)
}

application {
    mainClass.set("kr.decacross.daemon.MainKt")
    // 메모리 예산 (명세 §8.1): 데몬은 상주하므로 192MB 상한.
    // --enable-native-access: Windows 알려진 폴더(문서) 조회·콘솔 Ctrl+C 헬퍼가 FFM 을 쓴다 (JDK 25 경고 억제, 이후 JDK 차단 대비)
    applicationDefaultJvmArgs = listOf("-Xmx192m", "-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED")
}

tasks.withType<Test>().configureEach {
    // 테스트 JVM 도 FFM 을 쓴다. 자식 프로세스(가짜 서버) 출력은 UTF-8 로 읽으므로 테스트 JVM 출력도 UTF-8 로 맞춘다.
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// DESIGN2 §5.2 #6 (CliCtrlCIntegrationTest) 은 설치된 CLI(`app:cli:installDist`)를 별도 프로세스로 띄운다.
// ★ 그 디렉터리를 Gradle 입력으로 **선언해야** 한다: 선언하지 않으면 app:cli 만 고친 뒤에도 :app:daemon:test 가
//   FROM-CACHE 로 복원돼 옛 CLI 를 시험한 초록 결과가 그대로 남는다 (게이트가 거짓으로 통과한다).
//   경로는 시스템 속성으로 넘긴다 — 테스트가 `user.dir` 에서 상대 경로를 추측하지 않게.
val cliInstallLib: Provider<Directory> = project(":app:cli").layout.buildDirectory.dir("install/cli/lib")

tasks.named<Test>("test") {
    dependsOn(":app:cli:installDist")
    inputs.dir(cliInstallLib)
        .withPropertyName("cliInstallDist")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("decacross.cli.lib", cliInstallLib.get().asFile.absolutePath)
}
