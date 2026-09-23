// core:pubgrub-kt — PubGrub 의존성 해결 알고리즘 포팅. 순수 알고리즘, 도메인 지식 0, 플랫폼 의존성 0.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
