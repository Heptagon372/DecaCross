// 루트 빌드 — 플러그인 버전 선언(apply false) + 모든 Kotlin 모듈 공통 설정.
// 버전 문자열은 여기에도 쓰지 않는다. 전부 gradle/libs.versions.toml.
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.shadow) apply false
}

/** JVM target 은 명세 §0 대로 25. 모든 모듈이 같은 toolchain 을 쓴다. */
val jvmToolchainVersion = 25

subprojects {
    group = "kr.decacross"
    version = "0.1.0-SNAPSHOT"

    // Kotlin 플러그인(jvm 또는 multiplatform)이 적용되는 모든 모듈 공통
    plugins.withType<org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin> {
        extensions.configure<KotlinProjectExtension> {
            jvmToolchain(jvmToolchainVersion)
        }

        // core/* 는 explicit API mode 필수 (공개 API 를 실수로 넓히지 않게)
        if (path.startsWith(":core:")) {
            extensions.configure<KotlinProjectExtension> { explicitApi() }
        }

        // kotlin.time.Instant/Clock 을 표준 라이브러리에서 그대로 쓴다 (kotlinx-datetime 불필요)
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
            compilerOptions {
                optIn.add("kotlin.time.ExperimentalTime")
            }
        }

        // 테스트는 전부 JUnit Platform (kotlin-test-junit5)
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            systemProperty("file.encoding", "UTF-8")
            testLogging {
                events("failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showStandardStreams = false
            }
        }

        // 린트: ktlint (스타일은 .editorconfig 의 ktlint_official)
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        extensions.configure<KtlintExtension> {
            version.set(libs.versions.ktlint.asProvider())
            android.set(false)
            filter {
                exclude { it.file.path.contains("${File.separator}build${File.separator}") }
                exclude { it.file.path.contains("${File.separator}generated${File.separator}") }
            }
        }
    }
}
