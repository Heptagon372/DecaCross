package kr.decacross.daemon.process

import kr.decacross.daemon.install.LaunchProfilesLoad
import kr.decacross.daemon.install.LaunchSpec
import kr.decacross.daemon.install.loadLaunchProfiles
import kr.decacross.daemon.testkit.FakeServerJar
import java.nio.file.Files
import java.nio.file.Path

/** 실제 프로세스를 쓰는 테스트 공통 준비물 (가짜 서버 jar + 테스트 JVM). 레포에 jar 를 두지 않는다. */
internal object ServerFixture {
    /** 진짜 코어 이름을 흉내 내지 않는다 (가짜 서버임이 드러나게). */
    const val JAR_NAME: String = "fake-server.jar"

    /** 실제 리소스의 콘솔 패턴 (준비 완료·저장 완료). */
    val patterns: CompiledConsolePatterns by lazy {
        val loaded = loadLaunchProfiles() as? LaunchProfilesLoad.Loaded ?: error("launch-profiles.json 로드 실패")
        CompiledConsolePatterns.from(loaded.profiles.console)
    }

    /** 가짜 서버 jar 하나가 든 임시 서버 폴더. */
    fun createServerDir(prefix: String = "dcx-proc"): Path {
        val dir = Files.createTempDirectory(prefix)
        FakeServerJar.write(dir.resolve(JAR_NAME))
        return dir
    }

    /** 테스트 JVM 으로 가짜 서버를 띄우는 명세 (힙 64MB, 플래그 없음). */
    fun spec(vararg flags: String): LaunchSpec =
        LaunchSpec(
            javaPath = FakeServerJar.testJava().toString(),
            javaFeature = Runtime.version().feature(),
            xmsMb = 64,
            xmxMb = 64,
            // maxLifeMs: 테스트가 실패해도 가짜 서버가 오래 남지 않게
            jvmFlags = listOf("-Dfake.maxLifeMs=60000") + flags,
            jarFileName = JAR_NAME,
        )

    /** `fake-events.txt` 에 [event] 로 시작하는 줄이 나올 때까지 기다린 뒤 전체 사건 목록. */
    fun awaitEvent(serverDir: Path, event: String, timeoutMs: Long = 5_000): List<String> {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var events = FakeServerJar.events(serverDir)
        while (events.none { it.startsWith(event) } && System.nanoTime() < deadline) {
            Thread.sleep(20)
            events = FakeServerJar.events(serverDir)
        }
        return events
    }
}
