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
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("kr.decacross.cli.MainKt")
    // installDist 의 bin/cli(.bat) 에도 들어간다. stdout.encoding 은 여기 넣지 않는다:
    // 실제 콘솔(코드페이지 949)에서 UTF-8 로 강제하면 한글이 깨진다 (research codebase-windows §6).
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    // `run` 이 표준입력(EULA 프롬프트·서버 콘솔)을 받을 수 있게
    standardInput = System.`in`
    // Gradle run 은 출력이 파이프라서 JDK 기본(native.encoding=MS949)이 된다 → 수집기와 같은 방식으로 UTF-8 고정.
    // 입력도: Gradle 클라이언트가 콘솔 입력을 UTF-8 로 다시 인코딩해 넘기므로 CLI 가 UTF-8 로 읽게 한다 (critique windows #8, ConsoleIo 가 stdin.encoding 을 읽는다)
    jvmArgumentProviders.add(
        org.gradle.process.CommandLineArgumentProvider {
            listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dstdin.encoding=UTF-8")
        },
    )
    // CLI 가 "Gradle run 에서는 Ctrl+C 가 서버를 강제 종료한다" 안내를 띄우는 근거 (Gradle 9.7 은 취소 시 프로세스 트리를 죽인다)
    systemProperty("decacross.launcher", "gradle-run")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
