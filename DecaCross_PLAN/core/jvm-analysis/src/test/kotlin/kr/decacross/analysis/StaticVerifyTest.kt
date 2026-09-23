package kr.decacross.analysis

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.ACC_PRIVATE
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SYNTHETIC
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.GETSTATIC
import org.objectweb.asm.Opcodes.INVOKEINTERFACE
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.Opcodes.INVOKEVIRTUAL
import org.objectweb.asm.Opcodes.POP
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Type
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticVerifyTest {
    private lateinit var dir: Path
    private lateinit var api: Path

    @BeforeTest
    fun setUp() {
        dir = tempDir()
        api = fakeApiJar(dir.resolve("api/paper-api.jar"))
        JarIndexCache.clear()
    }

    @AfterTest
    fun tearDown() {
        JarIndexCache.clear()
        deleteTree(dir)
    }

    private fun plugin(name: String, yml: String? = pluginYml(name, "com.example.$name"), vararg classes: ByteArray): Path {
        val b = JarBuilder()
        yml?.let { b.text("plugin.yml", it) }
        classes.forEach { b.clazz(it) }
        return b.write(dir.resolve("plugins/$name.jar"))
    }

    /** JavaPlugin 을 상속한 메인 클래스. onEnable 본문은 [body]. */
    private fun mainClass(name: String, body: MethodVisitor.() -> Unit, extra: ClassWriter.() -> Unit = {}): ByteArray =
        classBytes(name, superName = JAVA_PLUGIN) {
            defaultCtor(JAVA_PLUGIN)
            method("onEnable", "()V") {
                body()
                visitInsn(RETURN)
            }
            extra()
        }

    private fun verify(jar: Path): StaticVerifyResult = staticVerify(jar, listOf(api), 21)

    // ── (a) 존재하는 API 만 참조 → ok ─────────────────────────────────────

    @Test
    fun `a_existing api only is ok`() {
        val jar = plugin(
            "Good",
            classes = arrayOf(
                mainClass("com/example/Good", {
                    visitVarInsn(ALOAD, 0)
                    visitMethodInsn(INVOKEVIRTUAL, JAVA_PLUGIN, "getLogger", "()Ljava/util/logging/Logger;", false)
                    visitInsn(POP)
                    visitMethodInsn(INVOKESTATIC, BUKKIT, "getServer", "()L$SERVER;", false)
                    visitMethodInsn(INVOKEINTERFACE, SERVER, "getName", "()Ljava/lang/String;", true)
                    visitInsn(POP)
                    // 계층 탐색: Player 에는 sendMessage 가 없고 CommandSender 에 있다
                    visitInsn(Opcodes.ACONST_NULL)
                    visitLdcInsn("hi")
                    visitMethodInsn(INVOKEINTERFACE, PLAYER, "sendMessage", "(Ljava/lang/String;)V", true)
                    // 인터페이스에 대한 Object 메서드
                    visitInsn(Opcodes.ACONST_NULL)
                    visitMethodInsn(INVOKEINTERFACE, SERVER, "hashCode", "()I", true)
                    visitInsn(POP)
                    // JDK: StringBuilder + indy 문자열 결합
                    newAndDrop("java/lang/StringBuilder")
                    visitLdcInsn("x")
                    visitInvokeDynamicInsn("makeConcatWithConstants", "(Ljava/lang/String;)Ljava/lang/String;", STRING_CONCAT_BSM, CONCAT_RECIPE)
                    visitInsn(POP)
                }),
            ),
        )
        val r = verify(jar)
        assertTrue(r.ok, "expected ok but got: ${r.missingClasses} ${r.missingMethods} ${r.missingFields}")
        assertTrue(r.warningRefs.isEmpty(), "unexpected warnings: ${r.warnings}")
        assertTrue(r.analyzed)
    }

    // ── (b) 없는 메서드 → 에러 1건 ────────────────────────────────────────

    @Test
    fun `b_missing method is exactly one error`() {
        val jar = plugin(
            "BadMethod",
            classes = arrayOf(
                mainClass("com/example/BadMethod", {
                    visitVarInsn(ALOAD, 0)
                    visitMethodInsn(INVOKEVIRTUAL, JAVA_PLUGIN, "getDataFolderX", "()Ljava/io/File;", false)
                    visitInsn(POP)
                    // 같은 참조를 두 번 — 한 번만 보고돼야 한다
                    visitVarInsn(ALOAD, 0)
                    visitMethodInsn(INVOKEVIRTUAL, JAVA_PLUGIN, "getDataFolderX", "()Ljava/io/File;", false)
                    visitInsn(POP)
                }),
            ),
        )
        val r = verify(jar)
        assertFalse(r.ok)
        assertEquals(1, r.missingMethods.size, r.toString())
        assertTrue(r.missingClasses.isEmpty())
        assertEquals("$JAVA_PLUGIN.getDataFolderX()Ljava/io/File;", r.missingMethods.single().target)
        assertTrue("2곳" in r.missingMethods.single().detail)
    }

    @Test
    fun `missing class is reported once not per member`() {
        val jar = plugin(
            "BadClass",
            classes = arrayOf(
                mainClass("com/example/BadClass", {
                    newAndDrop("com/other/Missing")
                    visitInsn(Opcodes.ACONST_NULL)
                    visitMethodInsn(INVOKEVIRTUAL, "com/other/Missing", "run", "()V", false)
                    visitFieldInsn(GETSTATIC, "com/other/Missing", "X", "I")
                    visitInsn(POP)
                }),
            ),
        )
        val r = verify(jar)
        assertFalse(r.ok)
        assertEquals(listOf("com/other/Missing"), r.missingClasses.map { it.target })
        assertTrue(r.missingMethods.isEmpty() && r.missingFields.isEmpty(), "members of a missing owner must not be counted: $r")
    }

    // ── (c) try/catch NoClassDefFoundError 안 → 경고, ok ────────────────

    @Test
    fun `c_missing class inside guarded try is a warning`() {
        val jar = plugin(
            "Guarded",
            classes = arrayOf(
                mainClass("com/example/Guarded", {
                    guarded("java/lang/NoClassDefFoundError") { newAndDrop("com/other/Optional") }
                }),
            ),
        )
        val r = verify(jar)
        assertTrue(r.ok, r.toString())
        assertEquals(listOf(WarnReason.GUARDED_TRY), r.warningRefs.map { it.reason })
        assertEquals("com/other/Optional", r.warningRefs.single().target)
    }

    @Test
    fun `try catching unrelated exception does not guard`() {
        val jar = plugin(
            "NotGuarded",
            classes = arrayOf(
                mainClass("com/example/NotGuarded", {
                    guarded("java/io/IOException") { newAndDrop("com/other/Optional") }
                }),
            ),
        )
        assertFalse(verify(jar).ok)
    }

    // ── (d) softdepend 플러그인 소속 → 경고 ───────────────────────────────

    @Test
    fun `d_softdepend owned class is a warning`() {
        val jar = plugin(
            "UsesVault",
            yml = pluginYml("UsesVault", "com.example.UsesVault", "softdepend: [Vault, PlaceholderAPI]\n"),
            classes = arrayOf(
                mainClass("com/example/UsesVault", {
                    visitInsn(Opcodes.ACONST_NULL)
                    visitMethodInsn(INVOKEINTERFACE, "net/milkbowl/vault/economy/Economy", "getBalance", "(Ljava/lang/String;)D", true)
                    visitInsn(Opcodes.POP2)
                    visitMethodInsn(INVOKESTATIC, "me/clip/placeholderapi/PlaceholderAPI", "setPlaceholders", "(Ljava/lang/String;)Ljava/lang/String;", false)
                    visitInsn(POP)
                }),
            ),
        )
        val r = verify(jar)
        assertTrue(r.ok, r.toString())
        assertEquals(setOf(WarnReason.SOFTDEPEND), r.warningRefs.map { it.reason }.toSet())
        assertEquals(2, r.warningRefs.size)
    }

    @Test
    fun `hard depend absent from classpath is a warning but present-and-broken is an error`() {
        val absent = plugin(
            "DependAbsent",
            yml = pluginYml("DependAbsent", "com.example.DependAbsent", "depend: [Vault]\n"),
            classes = arrayOf(mainClass("com/example/DependAbsent", { newAndDrop("net/milkbowl/vault/economy/EconomyResponse") })),
        )
        val r1 = verify(absent)
        assertTrue(r1.ok, r1.toString())
        assertEquals(listOf(WarnReason.DEPEND_ABSENT), r1.warningRefs.map { it.reason })

        // Vault 가 클래스패스에 있는데 클래스가 다르면 에러
        val vault = JarBuilder().clazz(classBytes("net/milkbowl/vault/economy/Economy") { defaultCtor() }).write(dir.resolve("plugins/Vault.jar"))
        val r2 = staticVerify(absent, listOf(api, vault), 21)
        assertFalse(r2.ok, r2.toString())
    }

    @Test
    fun `plugin yml libraries make missing library classes a warning`() {
        val jar = plugin(
            "UsesLib",
            yml = pluginYml("UsesLib", "com.example.UsesLib", "libraries:\n  - com.google.code.gson:gson:2.11.0\n  - org.xerial:sqlite-jdbc:3.46.0.0\n"),
            classes = arrayOf(
                mainClass("com/example/UsesLib", {
                    newAndDrop("com/google/gson/Gson")
                    newAndDrop("org/sqlite/JDBC")
                }),
            ),
        )
        val r = verify(jar)
        assertTrue(r.ok, r.toString())
        assertEquals(setOf(WarnReason.LIBRARY), r.warningRefs.map { it.reason }.toSet())
    }

    // ── (e) Class.forName 문자열 → 경고 ───────────────────────────────────

    @Test
    fun `e_class forName string constant is a warning`() {
        val jar = plugin(
            "Reflective",
            classes = arrayOf(
                mainClass("com/example/Reflective", {
                    visitLdcInsn("com.example.Missing")
                    visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false)
                    visitInsn(POP)
                }),
            ),
        )
        val r = verify(jar)
        assertTrue(r.ok, r.toString())
        assertEquals(listOf(WarnReason.REFLECTION), r.warningRefs.map { it.reason })
        assertEquals("com/example/Missing", r.warningRefs.single().target)
    }

    @Test
    fun `signature-only and annotation-only references are warnings or ignored`() {
        val jar = plugin(
            "Sig",
            classes = arrayOf(
                mainClass("com/example/Sig", {}) {
                    field("hook", "Lcom/other/OptionalHook;", ACC_PRIVATE)
                    abstractMethod("takes", "(Lcom/other/OptionalArg;)V", ACC_PUBLIC or Opcodes.ACC_ABSTRACT)
                    visitAnnotation("Lorg/jetbrains/annotations/NotNull;", false).visitEnd()
                    visitAnnotation("Lcom/other/MyAnno;", true).visitEnd()
                },
            ),
        )
        val r = verify(jar)
        assertTrue(r.ok, r.toString())
        val byTarget = r.warningRefs.associate { it.target to it.reason }
        assertEquals(WarnReason.SIGNATURE_ONLY, byTarget["com/other/OptionalHook"])
        assertEquals(WarnReason.SIGNATURE_ONLY, byTarget["com/other/OptionalArg"])
        assertEquals(WarnReason.ANNOTATION_ONLY, byTarget["com/other/MyAnno"])
        assertFalse("org/jetbrains/annotations/NotNull" in byTarget, "jetbrains annotations must be ignored")
    }

    @Test
    fun `unreadable jar is not analyzed`() {
        val bogus = dir.resolve("plugins/bogus.jar").also {
            java.nio.file.Files.createDirectories(it.parent)
            java.nio.file.Files.write(it, byteArrayOf(1, 2, 3))
        }
        val r = verify(bogus)
        assertFalse(r.analyzed)
        assertFalse(r.ok)
    }

    @Test
    fun `cache is hit on second combination`() {
        val jar = plugin("Cached", classes = arrayOf(mainClass("com/example/Cached", {})))
        verify(jar)
        val before = JarIndexCache.hits
        verify(jar)
        assertTrue(JarIndexCache.hits >= before + 2, "plugin scan and api index should both hit")
    }

    // ── 정확도: 알려진 호환 50건 전부 ok, 알려진 비호환 30건 전부 실패 ────

    @Test
    fun `accuracy_50 known-good jars have zero false positives`() {
        val rnd = Random(42)
        var falsePositives = 0
        val details = ArrayList<String>()
        repeat(50) { i ->
            val jar = plugin("Good$i", yml = pluginYml("Good$i", "com.example.good$i.Main", "softdepend: [Vault]\n"), classes = generatedClasses("com/example/good$i", 8, 10, rnd, bad = null))
            val r = verify(jar)
            if (!r.ok) {
                falsePositives++
                details += "Good$i: ${r.missingClasses} ${r.missingMethods} ${r.missingFields}"
            }
        }
        println("ACCURACY known-good: FP=$falsePositives/50 (${falsePositives * 2}%)")
        assertEquals(0, falsePositives, details.joinToString("\n"))
    }

    @Test
    fun `accuracy_30 known-bad jars are all caught`() {
        val rnd = Random(7)
        var falseNegatives = 0
        val details = ArrayList<String>()
        repeat(30) { i ->
            val kind = BadKind.entries[i % BadKind.entries.size]
            val jar = plugin("Bad$i", yml = pluginYml("Bad$i", "com.example.bad$i.Main"), classes = generatedClasses("com/example/bad$i", 8, 10, rnd, bad = kind))
            val r = verify(jar)
            if (r.ok) {
                falseNegatives++
                details += "Bad$i ($kind) passed"
            }
        }
        println("ACCURACY known-bad: FN=$falseNegatives/30")
        assertEquals(0, falseNegatives, details.joinToString("\n"))
    }

    // ── 성능: 300 클래스 × 20 참조, warm < 500ms ──────────────────────────

    @Test
    fun `timing_300 classes x 20 refs under 500ms warm`() {
        val rnd = Random(1)
        val jar = plugin("Big", yml = pluginYml("Big", "com.example.big.Main", "softdepend: [Vault]\n"), classes = generatedClasses("com/example/big", 300, 20, rnd, bad = null))
        val cold = verify(jar)
        assertTrue(cold.ok, cold.toString())
        val warmRuns = (1..5).map {
            val t0 = System.nanoTime()
            verify(jar)
            (System.nanoTime() - t0) / 1_000_000
        }
        println("TIMING 300x20: cold=${cold.elapsedMillis}ms warm=${warmRuns}ms (min ${warmRuns.min()}ms)")
        assertTrue(warmRuns.min() < 500, "warm run too slow: $warmRuns ms")
    }

    // ── 생성기 ─────────────────────────────────────────────────────────────

    private enum class BadKind { MISSING_CLASS, MISSING_METHOD, MISSING_FIELD, MISSING_SUPER, MISSING_INTERFACE, WRONG_DESC, MISSING_INTERFACE_METHOD }

    /** 알려진 정상 참조 풀에서 [refsPerClass] 개를 뽑아 클래스 [count] 개를 만든다. [bad] 가 있으면 무작위 한 클래스에 주입. */
    private fun generatedClasses(pkg: String, count: Int, refsPerClass: Int, rnd: Random, bad: BadKind?): Array<ByteArray> {
        val badAt = if (bad == null) -1 else rnd.nextInt(count)
        return Array(count) { i ->
            val name = if (i == 0) "$pkg/Main" else "$pkg/C$i"
            val superName = when {
                i == 0 -> JAVA_PLUGIN
                bad == BadKind.MISSING_SUPER && i == badAt -> "$pkg/NoSuchSuper"
                else -> "java/lang/Object"
            }
            val interfaces = when {
                bad == BadKind.MISSING_INTERFACE && i == badAt -> listOf(LISTENER, "$pkg/NoSuchIface")
                else -> listOf(LISTENER)
            }
            classBytes(name, superName = superName, interfaces = interfaces) {
                defaultCtor(superName)
                visitAnnotation("Lorg/jetbrains/annotations/NotNull;", false).visitEnd()
                method("run", "()V") {
                    repeat(refsPerClass) { goodRef(this, name, rnd) }
                    if (i == badAt && bad != null) badRef(this, bad)
                    visitInsn(RETURN)
                }
                method("lambda\$run\$0", "()V", ACC_PRIVATE or ACC_STATIC or ACC_SYNTHETIC) { visitInsn(RETURN) }
            }
        }
    }

    private fun goodRef(mv: MethodVisitor, self: String, rnd: Random) = with(mv) {
        when (rnd.nextInt(12)) {
            0 -> {
                visitMethodInsn(INVOKESTATIC, BUKKIT, "getServer", "()L$SERVER;", false)
                visitMethodInsn(INVOKEINTERFACE, SERVER, "getVersion", "()Ljava/lang/String;", true)
                visitInsn(POP)
            }

            1 -> {
                visitFieldInsn(GETSTATIC, BUKKIT, "NAME", "Ljava/lang/String;")
                visitInsn(POP)
            }

            2 -> newAndDrop("java/util/ArrayList")

            3 -> {
                visitLdcInsn("abc")
                visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)
                visitInsn(POP)
            }

            4 -> {
                visitLdcInsn("x")
                visitInvokeDynamicInsn("makeConcatWithConstants", "(Ljava/lang/String;)Ljava/lang/String;", STRING_CONCAT_BSM, CONCAT_RECIPE)
                visitInsn(POP)
            }

            5 -> {
                // 람다: Runnable ← lambda$run$0
                visitInvokeDynamicInsn(
                    "run",
                    "()Ljava/lang/Runnable;",
                    LAMBDA_BSM,
                    Type.getMethodType("()V"),
                    Handle(Opcodes.H_INVOKESTATIC, self, "lambda\$run\$0", "()V", false),
                    Type.getMethodType("()V"),
                )
                visitInsn(POP)
            }

            6 -> guarded("java/lang/NoClassDefFoundError") { newAndDrop("com/optional/Thing") }

            7 -> {
                visitLdcInsn("com.optional.Reflected")
                visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false)
                visitInsn(POP)
            }

            8 -> {
                visitInsn(Opcodes.ACONST_NULL)
                visitMethodInsn(INVOKEINTERFACE, "net/milkbowl/vault/economy/Economy", "getName", "()Ljava/lang/String;", true)
                visitInsn(POP)
            }

            9 -> {
                visitInsn(Opcodes.ACONST_NULL)
                visitLdcInsn("m")
                visitMethodInsn(INVOKEINTERFACE, PLAYER, "sendMessage", "(Ljava/lang/String;)V", true)
            }

            10 -> {
                visitLdcInsn(Type.getObjectType(LISTENER))
                visitInsn(POP)
            }

            else -> {
                visitTypeInsn(Opcodes.ANEWARRAY, PLAYER)
                visitInsn(POP)
            }
        }
    }

    private fun badRef(mv: MethodVisitor, kind: BadKind) = with(mv) {
        when (kind) {
            BadKind.MISSING_CLASS -> newAndDrop("org/bukkit/NoSuchClass")

            BadKind.MISSING_METHOD -> {
                visitMethodInsn(INVOKESTATIC, BUKKIT, "getNoSuchThing", "()V", false)
            }

            BadKind.MISSING_FIELD -> {
                visitFieldInsn(GETSTATIC, BUKKIT, "NO_SUCH", "I")
                visitInsn(POP)
            }

            BadKind.WRONG_DESC -> {
                visitMethodInsn(INVOKESTATIC, BUKKIT, "getServer", "()Ljava/lang/String;", false)
                visitInsn(POP)
            }

            BadKind.MISSING_INTERFACE_METHOD -> {
                visitInsn(Opcodes.ACONST_NULL)
                visitMethodInsn(INVOKEINTERFACE, PLAYER, "getPing", "()I", true)
                visitInsn(POP)
            }

            BadKind.MISSING_SUPER, BadKind.MISSING_INTERFACE -> Unit // 클래스 헤더에서 주입됨
        }
    }
}
