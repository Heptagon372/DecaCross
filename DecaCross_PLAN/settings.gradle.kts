// 데카크로스 Gradle 멀티모듈 루트
// 모듈 소유권은 CLAUDE.md "모듈 소유권" 표를 따른다. web/ 은 Gradle 밖(pnpm).
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    // JDK 자동 프로비저닝(CI 등 로컬에 JDK 25 가 없을 때). 로컬 JDK 는 그대로 탐지된다.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "decacross"

// core — 순수 로직. I/O 금지 (jvm-analysis 만 예외)
include(":core:pubgrub-kt")
include(":core:compat-engine")
include(":core:dcx")
include(":core:logparse")
include(":core:jvm-analysis")

// app — 실행 파일
include(":app:daemon")
include(":app:launcher-ui")
include(":app:cli")
include(":app:collector")

// verifier — 정적 검증 우선 CI
include(":verifier")
