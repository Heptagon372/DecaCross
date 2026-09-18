package kr.decacross.daemon.testkit

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/** [FakeMinecraftServer] 를 실행 가능한 jar 로 묶는다. 레포에 jar 파일을 두지 않는다. */
object FakeServerJar {
    private const val MAIN_CLASS = "kr.decacross.daemon.testkit.FakeMinecraftServer"

    /** [target] 에 `Main-Class` 가 가짜 서버인 jar 를 쓴다. 부모 디렉터리는 만든다. */
    fun write(target: Path): Path {
        val resource = MAIN_CLASS.replace('.', '/') + ".class"
        val bytes = FakeServerJar::class.java.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: error("테스트 클래스 $resource 를 찾을 수 없다 (src/test/java 컴파일 확인)")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = MAIN_CLASS
        }
        target.parent?.let { Files.createDirectories(it) }
        JarOutputStream(Files.newOutputStream(target), manifest).use { jar ->
            jar.putNextEntry(JarEntry(resource))
            jar.write(bytes)
            jar.closeEntry()
        }
        return target
    }

    /** 서버 역할로 띄울 java 실행 파일 = 테스트 JVM 의 java (테스트 전용; 제품 코드는 불변식 9 로 금지). */
    fun testJava(): Path {
        val fromHandle = ProcessHandle.current().info().command().orElse(null)
        if (fromHandle != null) return Path.of(fromHandle)
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", exe)
    }

    /** `fake-events.txt` 의 줄들 (없으면 빈 목록). */
    fun events(serverDir: Path): List<String> {
        val f = serverDir.resolve("fake-events.txt")
        return if (Files.exists(f)) Files.readAllLines(f, Charsets.UTF_8) else emptyList()
    }
}
