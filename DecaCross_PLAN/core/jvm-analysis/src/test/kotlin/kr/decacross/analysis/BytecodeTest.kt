package kr.decacross.analysis

import kr.decacross.analysis.testutil.classBytes
import kr.decacross.analysis.testutil.jar
import kr.decacross.analysis.testutil.method
import kr.decacross.analysis.testutil.withVersion
import kr.decacross.analysis.testutil.zipBytes
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 클래스 파일 헤더 기반 Java 요구 버전 + NMS 신호 (설계 §7.4). */
class BytecodeTest {
    private val mrManifest = mapOf("Multi-Release" to "true")

    private fun profile(jar: Path): BytecodeProfile = bytecodeProfile(jar)

    /** 최소 class 파일을 손으로 만든다: Utf8 #1 = [firstUtf8], Utf8 #2 = "A", Class #3 → #2. */
    private fun rawClass(major: Int, minor: Int = 0, firstUtf8: ByteArray = "x".toByteArray()): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(0xCAFEBABE.toInt())
            out.writeShort(minor)
            out.writeShort(major)
            out.writeShort(4)
            out.writeByte(1)
            out.writeShort(firstUtf8.size)
            out.write(firstUtf8)
            out.writeByte(1)
            out.writeShort(1)
            out.write("A".toByteArray())
            out.writeByte(7)
            out.writeShort(2)
            out.writeShort(Opcodes.ACC_PUBLIC)
            out.writeShort(3)
            out.writeShort(0)
            repeat(4) { out.writeShort(0) }
        }
        return bytes.toByteArray()
    }

    @Test
    fun bc01_java8() {
        val result = profile(jar("a/A.class" to classBytes("a/A", version = Opcodes.V1_8)))
        assertEquals(8, result.requiredJavaFeature)
        assertEquals(52, result.outerMaxMajor)
        assertEquals(mapOf(52 to 1), result.majorHistogram)
        assertEquals(8, requiredJavaFeature(jar("a/A.class" to classBytes("a/A"))))
    }

    @Test
    fun bc02_max() {
        val result = profile(
            jar("a/A.class" to classBytes("a/A", version = Opcodes.V1_8), "a/B.class" to classBytes("a/B", version = Opcodes.V17)),
        )
        assertEquals(17, result.requiredJavaFeature)
        assertEquals(mapOf(52 to 1, 61 to 1), result.majorHistogram)
    }

    @Test
    fun bc03_multiReleaseIgnored() {
        val result = profile(
            jar(
                "a/A.class" to classBytes("a/A", version = Opcodes.V1_8),
                "META-INF/versions/17/a/A.class" to classBytes("a/A", version = Opcodes.V17),
                manifest = mrManifest,
            ),
        )
        assertEquals(8, result.requiredJavaFeature)
        assertEquals(setOf(17), result.versionedFeatures)
        assertTrue(result.multiRelease)
    }

    @Test
    fun bc04_versionsWithoutManifest() {
        val result = profile(
            jar(
                "a/A.class" to classBytes("a/A", version = Opcodes.V1_8),
                "META-INF/versions/17/a/A.class" to classBytes("a/A", version = Opcodes.V17),
            ),
        )
        assertEquals(8, result.requiredJavaFeature)
        assertFalse(result.multiRelease)
    }

    @Test
    fun bc05_moduleInfoRoot() {
        val result = profile(
            jar("module-info.class" to classBytes("module-info", version = Opcodes.V9), "a/A.class" to classBytes("a/A")),
        )
        assertEquals(8, result.requiredJavaFeature)
    }

    @Test
    fun bc06_moduleInfoVersioned() {
        val result = profile(
            jar(
                "META-INF/versions/9/module-info.class" to classBytes("module-info", version = Opcodes.V9),
                "a/A.class" to classBytes("a/A"),
                manifest = mrManifest,
            ),
        )
        assertEquals(8, result.requiredJavaFeature)
        assertEquals(setOf(9), result.versionedFeatures)
    }

    @Test
    fun bc07_noClasses() {
        val result = profile(jar("plugin.yml" to "name: A".toByteArray()))
        assertNull(result.requiredJavaFeature)
        assertNull(result.outerMaxMajor)
        assertNull(result.nestedMaxMajor)
        assertEquals(0, result.invalidClassEntries)
    }

    @Test
    fun bc08_garbageClass() {
        val result = profile(jar("a/A.class" to "hello".toByteArray(), "a/B.class" to classBytes("a/B")))
        assertEquals(8, result.requiredJavaFeature)
        assertEquals(1, result.invalidClassEntries)
    }

    @Test
    fun bc09_truncated() {
        val result = profile(jar("a/A.class" to classBytes("a/A").copyOf(6)))
        assertNull(result.requiredJavaFeature)
        assertEquals(1, result.invalidClassEntries)
    }

    @Test
    fun bc10_java28HeaderOnly() {
        val result = profile(jar("a/A.class" to withVersion(classBytes("a/A"), major = 72)))
        assertEquals(28, result.requiredJavaFeature)
    }

    @Test
    fun bc11_absurdMajor() {
        val result = profile(
            jar(
                "a/A.class" to withVersion(classBytes("a/A"), major = 300),
                "a/B.class" to withVersion(classBytes("a/B"), major = 30),
                "a/C.class" to classBytes("a/C"),
            ),
        )
        assertEquals(8, result.requiredJavaFeature)
        assertEquals(2, result.invalidClassEntries)
        assertTrue(result.notes.any { it.contains("비정상 major 300") }, result.notes.toString())
        assertTrue(result.notes.any { it.contains("비정상 major 30 ") }, result.notes.toString())
    }

    @Test
    fun bc12_preview() {
        val result = profile(jar("a/A.class" to withVersion(classBytes("a/A"), major = 65, minor = 0xFFFF)))
        assertEquals(21, result.requiredJavaFeature)
        assertEquals(65, result.previewMajor)
        assertTrue(result.notes.any { it.contains("프리뷰") }, result.notes.toString())
    }

    @Test
    fun bc13_oldMinorOk() {
        val result = profile(jar("a/A.class" to withVersion(classBytes("a/A"), major = 55, minor = 3)))
        assertEquals(11, result.requiredJavaFeature)
        assertEquals(0, result.invalidClassEntries)
    }

    @Test
    fun bc14_jvmsInvalidMinor() {
        val result = profile(
            jar("a/A.class" to withVersion(classBytes("a/A"), major = 60, minor = 1), "a/B.class" to classBytes("a/B")),
        )
        assertEquals(8, result.requiredJavaFeature)
        assertEquals(1, result.invalidClassEntries)
        assertTrue(result.notes.any { it.contains("JVMS 위반 minor") }, result.notes.toString())
    }

    @Test
    fun bc15_nestedJarinjar() {
        val nested = zipBytes("b/B.class" to classBytes("b/B", version = Opcodes.V11))
        val result = profile(jar("a/A.class" to classBytes("a/A", version = Opcodes.V1_8), "x.jarinjar" to nested))
        assertEquals(11, result.requiredJavaFeature)
        assertEquals(52, result.outerMaxMajor)
        assertEquals(55, result.nestedMaxMajor)
        assertTrue(result.notes.any { it.contains("중첩 jar 가 더 높은 Java 요구") }, result.notes.toString())
    }

    @Test
    fun bc15b_nestedTwoLevels_deeperIgnored() {
        val deeper = zipBytes("c/C.class" to classBytes("c/C", version = Opcodes.V21))
        val nested = zipBytes("b/B.class" to classBytes("b/B", version = Opcodes.V11), "deep.jar" to deeper)
        val result = profile(jar("a/A.class" to classBytes("a/A"), "META-INF/jars/lib.jar" to nested))
        assertEquals(11, result.requiredJavaFeature)
    }

    @Test
    fun bc16_nestedNotZip() {
        val result = profile(jar("a/A.class" to classBytes("a/A"), "lib.jar" to "not a zip at all".toByteArray()))
        assertEquals(8, result.requiredJavaFeature)
        assertNull(result.nestedMaxMajor)
        assertEquals(0, result.invalidClassEntries)
    }

    @Test
    fun bc17_directoryEntryNamedClass() {
        val result = profile(jar("weird.class/" to ByteArray(0), "a/A.class" to classBytes("a/A")))
        assertEquals(8, result.requiredJavaFeature)
        assertEquals(0, result.invalidClassEntries)
    }

    @Test
    fun bc18_bundledBukkitIgnored() {
        val result = profile(
            jar("org/bukkit/Foo.class" to classBytes("org/bukkit/Foo", version = Opcodes.V21), "a/A.class" to classBytes("a/A")),
        )
        assertEquals(8, result.requiredJavaFeature)
        assertTrue(result.notes.contains("로더가 정의하지 않는 네임스페이스 클래스 무시"), result.notes.toString())
    }

    @Test
    fun bc19_nmsSignals() {
        val nmsClass = classBytes("a/Nms") {
            method("run") {
                visitLdcInsn("net.minecraft.server.MinecraftServer")
                visitInsn(Opcodes.POP)
                visitMethodInsn(Opcodes.INVOKESTATIC, "org/bukkit/craftbukkit/v1_20_R3/CraftServer", "foo", "()V", false)
                visitInsn(Opcodes.RETURN)
            }
        }
        val result = profile(jar("a/Nms.class" to nmsClass, manifest = mapOf("paperweight-mappings-namespace" to "mojang")))
        assertTrue(result.nms.binaryRefs)
        assertTrue(result.nms.reflectiveStrings)
        assertEquals(setOf("v1_20_R3"), result.nms.versionedTokens)
        assertEquals("mojang", result.nms.mappingsNamespace)
    }

    @Test
    fun bc19b_noNms() {
        val result = profile(jar("a/A.class" to classBytes("a/A")))
        assertEquals(NmsSignal(binaryRefs = false, versionedTokens = emptySet(), reflectiveStrings = false, mappingsNamespace = null), result.nms)
    }

    @Test
    fun bc20_paperNamespaceIgnored() {
        val result = profile(
            jar(
                "io/papermc/paper/X.class" to classBytes("io/papermc/paper/X", version = Opcodes.V21),
                "com/destroystokoyo/paper/Y.class" to classBytes("com/destroystokoyo/paper/Y", version = Opcodes.V21),
                "a/A.class" to classBytes("a/A"),
            ),
        )
        assertEquals(8, result.requiredJavaFeature)
    }

    @Test
    fun bc21_minorFFFF_below56_notPreview() {
        val result = profile(jar("a/A.class" to withVersion(classBytes("a/A"), major = 55, minor = 0xFFFF)))
        assertEquals(11, result.requiredJavaFeature)
        assertNull(result.previewMajor)
    }

    @Test
    fun bc22_malformedUtf8_headerNull_noThrow() {
        val bytes = rawClass(major = 52, firstUtf8 = byteArrayOf(0xC0.toByte(), 0x00, 0xFF.toByte()))
        assertNull(readClassHeader(bytes))
        assertNotNull(readClassHeader(rawClass(major = 52)))
        val result = profile(jar("a/A.class" to bytes))
        assertEquals(8, result.requiredJavaFeature)
        assertEquals(0, result.invalidClassEntries)
    }

    @Test
    fun isJavaRequirementClass_rules() {
        assertTrue(isJavaRequirementClass("a/A.class"))
        assertFalse(isJavaRequirementClass("META-INF/versions/17/a/A.class"))
        assertFalse(isJavaRequirementClass("META-INF/Foo.class"))
        assertFalse(isJavaRequirementClass("module-info.class"))
        assertFalse(isJavaRequirementClass("org/bukkit/Foo.class"))
        assertFalse(isJavaRequirementClass("net/minecraft/Foo.class"))
        assertTrue(isJavaRequirementClass("org/bukkitx/Foo.class"))
    }

    @Test
    fun constantPool_parsesAllTags() {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "p/AllTags", null, "p/Base", arrayOf("p/I1", "p/I2"))
        val bootstrap = Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                "Ljava/lang/invoke/CallSite;",
            false,
        )
        writer.method("constants") {
            visitLdcInsn(1234567890123L)
            visitInsn(Opcodes.POP2)
            visitLdcInsn(3.25)
            visitInsn(Opcodes.POP2)
            visitLdcInsn(100_000)
            visitInsn(Opcodes.POP)
            visitLdcInsn(1.5f)
            visitInsn(Opcodes.POP)
            visitLdcInsn("a string")
            visitInsn(Opcodes.POP)
            visitLdcInsn(Handle(Opcodes.H_INVOKESTATIC, "p/Target", "target", "()V", false))
            visitInsn(Opcodes.POP)
            visitLdcInsn(Type.getMethodType("(I)J"))
            visitInsn(Opcodes.POP)
            visitLdcInsn(ConstantDynamic("condy", "Ljava/lang/Object;", Handle(Opcodes.H_INVOKESTATIC, "p/Boot", "boot", "()Ljava/lang/Object;", false)))
            visitInsn(Opcodes.POP)
            visitFieldInsn(Opcodes.GETSTATIC, "p/Other", "FIELD", "Ljava/lang/Object;")
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "p/I1", "iface", "()V", true)
            visitInvokeDynamicInsn(
                "run",
                "()Ljava/lang/Runnable;",
                bootstrap,
                Type.getMethodType("()V"),
                Handle(Opcodes.H_INVOKESTATIC, "p/AllTags", "lambda\$0", "()V", false),
                Type.getMethodType("()V"),
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
        }
        writer.visitEnd()
        val bytes = writer.toByteArray()

        val header = assertNotNull(readClassHeader(bytes))
        val reader = ClassReader(bytes)
        assertEquals(reader.className, header.thisName)
        assertEquals(reader.superName, header.superName)
        assertEquals(reader.interfaces.toList(), header.interfaces)
        assertEquals(61, header.major)
        assertEquals(0, header.minor)
        assertTrue(header.utf8.containsAll(listOf("constants", "iface", "target", "run", "condy", "a string")), header.utf8.toString())

        // Module(19)·Package(20) 상수는 module-info 에만 나온다
        val module = ClassWriter(0)
        module.visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null)
        val moduleVisitor = module.visitModule("com.example", 0, null)
        moduleVisitor.visitRequire("java.base", Opcodes.ACC_MANDATED, null)
        moduleVisitor.visitExport("com/example/api", 0)
        moduleVisitor.visitEnd()
        module.visitEnd()
        val moduleHeader = assertNotNull(readClassHeader(module.toByteArray()))
        assertEquals("module-info", moduleHeader.thisName)
        assertNull(moduleHeader.superName)
    }

    @Test
    fun classHeader_rejectsBadInput() {
        assertNull(readClassHeader(ByteArray(0)))
        assertNull(readClassHeader("hello world".toByteArray()))
        assertNull(readClassHeader(classBytes("a/A").copyOf(20)))
        // 모르는 상수 태그 (2)
        val unknownTag = rawClass(major = 52).also { it[10] = 2 }
        assertNull(readClassHeader(unknownTag))
    }
}
