// core:jvm-analysis — ★ JVM 전용. ASM/jar/NBT/JMX. core/* 중 유일하게 파일 I/O 가 허용된 모듈(jar 읽기가 존재 이유).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // PackDecl / PackFormat / Capability 를 compat-engine 과 같은 타입으로 쓴다 (명세 §2 단일 진실 소스).
    // 공개 API(JarAnalysis·PackMeta·CapabilityRules)가 이 타입을 노출하므로 api.
    api(project(":core:compat-engine"))

    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.commons)
    // CapabilityInfer: Analyzer<SourceValue> 로 ServicesManager.register 인자 출처를 추적한다
    implementation(libs.asm.analysis)
    // plugin.yml 파싱 — Bukkit 이 쓰는 구현과 동일
    implementation(libs.snakeyaml)
    // pack.mcmeta / fabric.mod.json 파싱 (JsonElement 트리만 사용, @Serializable 불필요)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
}
