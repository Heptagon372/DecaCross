// app:collector — 수집 배치 (Kotlin/JVM). 외부 API → DB. 분석한 jar 는 즉시 폐기.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

/** 임베디드 Postgres 바이너리 선택용 (구성 시점에 읽는다). */
val hostOs: String = System.getProperty("os.name").lowercase()

dependencies {
    implementation(project(":core:compat-engine"))
    implementation(project(":core:jvm-analysis"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.logback.classic)

    // DB — plain JDBC. 스키마의 단일 진실 소스는 infra/supabase/migrations 의 SQL 이다 (Exposed 테이블 객체 중복 정의 안 함).
    implementation(libs.postgresql)
    // 개발용 임베디드 Postgres. 기본으로 딸려오는 14.x 바이너리 4종은 빼고 17.x 를 명시한다.
    implementation(libs.embedded.postgres) {
        exclude(group = "io.zonky.test.postgres")
    }
    // 바이너리 jar 는 15~62 MB 라서 이 머신 OS 것 하나만 받는다 (배포 산출물이 아니라 개발 DB 용).
    when {
        hostOs.startsWith("windows") -> runtimeOnly(libs.embedded.pgbin.windows.amd64)
        hostOs.startsWith("mac") -> runtimeOnly(libs.embedded.pgbin.darwin.arm64v8)
        else -> runtimeOnly(libs.embedded.pgbin.linux.amd64)
    }

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    // 통합 테스트가 메모리 안에서 플러그인 클래스(ServicesManager.register 호출)를 만든다. jar 파일을 레포에 두지 않는다.
    testImplementation(libs.asm)
}

/** 레포 루트의 SQL 마이그레이션 디렉터리. 작업 디렉터리(app/collector)와 무관하게 절대경로로 넘긴다. */
val migrationsDir: String = rootDir.resolve("infra/supabase/migrations").absolutePath

application {
    mainClass.set("kr.decacross.collector.MainKt")
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "-Duser.language=en",
    )
}

tasks.named<JavaExec>("run") {
    systemProperty("decacross.migrations.dir", migrationsDir)
}

tasks.withType<Test>().configureEach {
    systemProperty("decacross.migrations.dir", migrationsDir)
    // SQL 만 바뀌어도(예: 0008 → 0004 개명) 테스트가 UP-TO-DATE·빌드 캐시로 건너뛰지 않게 입력으로 선언한다
    inputs.dir(migrationsDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("migrations")
}

// application 플러그인의 zip/tar 배포본은 만들지 않는다: build 때마다 임베디드 PG 바이너리가
// OneDrive 아래 build/distributions 에 두 번 복사된다. 실행은 `run` 으로 한다.
tasks.named("distZip") { enabled = false }
tasks.named("distTar") { enabled = false }
