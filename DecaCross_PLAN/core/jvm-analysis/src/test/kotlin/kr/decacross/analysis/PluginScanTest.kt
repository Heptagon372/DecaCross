package kr.decacross.analysis

import kr.decacross.analysis.testutil.classBytes
import kr.decacross.analysis.testutil.jar
import kr.decacross.analysis.testutil.method
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** PluginScan (WP0 프로토타입) 의 참조·catch·리플렉션 수집과 두 가지 버그 수정 (설계 §7.8). */
class PluginScanTest {
    private val cnfe = "java/lang/ClassNotFoundException"
    private val ncdfe = "java/lang/NoClassDefFoundError"
    private val owner = "p/Scanned"

    private fun scanOf(methodName: String, code: MethodVisitor.() -> Unit): ClassScan {
        val bytes = classBytes(owner) { method(methodName, code = code) }
        val scan = scanPluginJar(jar("$owner.class" to bytes))
        return scan.base[owner] ?: fail("스캔 결과에 $owner 없음: ${scan.base.keys}")
    }

    private fun ClassScan.ref(kind: RefKind, owner: String, name: String? = null): ScannedRef =
        refs.firstOrNull { it.target.kind == kind && it.target.owner == owner && (name == null || it.target.name == name) }
            ?: fail("$kind $owner.$name 참조 없음: $refs")

    /** 설계 §7.8 의 기본 메서드: forName(try/catch CNFE), NEW missing/Foo(try/catch NCDFE), qux(밖). */
    private val runMethod: MethodVisitor.() -> Unit = {
        val t1Start = Label()
        val t1End = Label()
        val t1Handler = Label()
        val after1 = Label()
        val t2Start = Label()
        val t2End = Label()
        val t2Handler = Label()
        val after2 = Label()
        visitTryCatchBlock(t1Start, t1End, t1Handler, cnfe)
        visitTryCatchBlock(t2Start, t2End, t2Handler, ncdfe)

        visitLabel(t1Start)
        visitLdcInsn("com.sk89q.worldedit.WorldEdit")
        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false)
        visitInsn(Opcodes.POP)
        visitLabel(t1End)
        visitJumpInsn(Opcodes.GOTO, after1)
        visitLabel(t1Handler)
        visitInsn(Opcodes.POP)
        visitLabel(after1)

        visitLabel(t2Start)
        visitTypeInsn(Opcodes.NEW, "missing/Foo")
        visitInsn(Opcodes.DUP)
        visitMethodInsn(Opcodes.INVOKESPECIAL, "missing/Foo", "<init>", "()V", false)
        visitInsn(Opcodes.POP)
        visitLabel(t2End)
        visitJumpInsn(Opcodes.GOTO, after2)
        visitLabel(t2Handler)
        visitInsn(Opcodes.POP)
        visitLabel(after2)

        visitMethodInsn(Opcodes.INVOKESTATIC, "missing/Baz", "qux", "()V", false)
        visitInsn(Opcodes.RETURN)
    }

    @Test
    fun refs_caughtSets() {
        val scan = scanOf("run", runMethod)
        assertEquals(setOf(ncdfe), scan.ref(RefKind.TYPE_INSN, "missing/Foo").caught)
        assertEquals(emptySet(), scan.ref(RefKind.METHOD_INVOKE, "missing/Baz", "qux").caught)
        assertEquals("run()V", scan.ref(RefKind.METHOD_INVOKE, "missing/Baz", "qux").fromMember)
    }

    @Test
    fun reflective_lookup() {
        val scan = scanOf("run", runMethod)
        val lookup = scan.reflective.singleOrNull() ?: fail("리플렉션 조회 1건이어야 함: ${scan.reflective}")
        assertEquals("com/sk89q/worldedit/WorldEdit", lookup.className)
        assertEquals("java/lang/Class.forName", lookup.api)
        assertTrue(cnfe in lookup.caught, lookup.caught.toString())
    }

    @Test
    fun reflective_noFalsePositive_afterUnrelatedLdc() {
        val scan = scanOf("load") {
            visitLdcInsn("a.b.C")
            visitMethodInsn(Opcodes.INVOKESTATIC, "p/Log", "info", "(Ljava/lang/String;)V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false)
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
        }
        assertTrue(scan.reflective.isEmpty(), scan.reflective.toString())
    }

    @Test
    fun reflective_threeArgForm_stillDetected() {
        val scan = scanOf("load") {
            visitLdcInsn("a.b.C")
            visitInsn(Opcodes.ICONST_0)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false)
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/Class",
                "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
                false,
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
        }
        assertEquals(listOf("a/b/C"), scan.reflective.map { it.className })

        // 정적 호출로 로더를 올리는 형태: Thread.currentThread().getContextClassLoader()
        val viaThread = scanOf("loadViaThread") {
            visitLdcInsn("a.b.D")
            visitInsn(Opcodes.ICONST_1)
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader", "()Ljava/lang/ClassLoader;", false)
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/Class",
                "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
                false,
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
        }
        assertEquals(listOf("a/b/D"), viaThread.reflective.map { it.className })
    }

    @Test
    fun reflective_noFalsePositive_stringBelowOneArgLookup() {
        // map.put("a.b.Unrelated", Class.forName(name)) — 문자열은 put 의 인자이고 forName 의 인자는 name 이다 (리뷰 JA-R3)
        val scan = scanOf("load") {
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("a.b.Unrelated")
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/util/HashMap",
                "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                false,
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
        }
        assertTrue(scan.reflective.isEmpty(), scan.reflective.toString())
    }

    @Test
    fun reflective_loadClass_receiverBelowString_detected() {
        // loader.loadClass("a.b.C") — 수신자는 문자열 아래에 있으므로 세지 않는다
        val scan = scanOf("load") {
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn("a.b.C")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader", "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;", false)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
        }
        assertEquals(listOf("a/b/C" to "java/lang/ClassLoader.loadClass"), scan.reflective.map { it.className to it.api })
    }

    @Test
    fun catch_finallyOnly_emptyCaught() {
        val scan = scanOf("run") {
            val start = Label()
            val end = Label()
            val handler = Label()
            val after = Label()
            visitTryCatchBlock(start, end, handler, null)
            visitLabel(start)
            visitMethodInsn(Opcodes.INVOKESTATIC, "missing/InFinally", "x", "()V", false)
            visitLabel(end)
            visitJumpInsn(Opcodes.GOTO, after)
            visitLabel(handler)
            visitInsn(Opcodes.ATHROW)
            visitLabel(after)
            visitInsn(Opcodes.RETURN)
        }
        assertEquals(emptySet(), scan.ref(RefKind.METHOD_INVOKE, "missing/InFinally", "x").caught)
    }

    @Test
    fun catch_refAfterEndLabel_empty() {
        val scan = scanOf("run") {
            val start = Label()
            val end = Label()
            val handler = Label()
            val after = Label()
            visitTryCatchBlock(start, end, handler, ncdfe)
            visitLabel(start)
            visitMethodInsn(Opcodes.INVOKESTATIC, "missing/Inside", "x", "()V", false)
            visitLabel(end)
            visitMethodInsn(Opcodes.INVOKESTATIC, "missing/After", "x", "()V", false)
            visitJumpInsn(Opcodes.GOTO, after)
            visitLabel(handler)
            visitInsn(Opcodes.POP)
            visitLabel(after)
            visitInsn(Opcodes.RETURN)
        }
        assertEquals(setOf(ncdfe), scan.ref(RefKind.METHOD_INVOKE, "missing/Inside", "x").caught)
        assertEquals(emptySet(), scan.ref(RefKind.METHOD_INVOKE, "missing/After", "x").caught)
    }

    @Test
    fun catch_labelEndsOneTryStartsAnother() {
        val scan = scanOf("run") {
            val first = Label()
            val boundary = Label()
            val second = Label()
            val handler1 = Label()
            val handler2 = Label()
            val after = Label()
            visitTryCatchBlock(first, boundary, handler1, cnfe)
            visitTryCatchBlock(boundary, second, handler2, ncdfe)
            visitLabel(first)
            visitMethodInsn(Opcodes.INVOKESTATIC, "missing/First", "x", "()V", false)
            visitLabel(boundary)
            visitMethodInsn(Opcodes.INVOKESTATIC, "missing/Second", "x", "()V", false)
            visitLabel(second)
            visitJumpInsn(Opcodes.GOTO, after)
            visitLabel(handler1)
            visitInsn(Opcodes.POP)
            visitJumpInsn(Opcodes.GOTO, after)
            visitLabel(handler2)
            visitInsn(Opcodes.POP)
            visitLabel(after)
            visitInsn(Opcodes.RETURN)
        }
        assertEquals(setOf(cnfe), scan.ref(RefKind.METHOD_INVOKE, "missing/First", "x").caught)
        assertEquals(setOf(ncdfe), scan.ref(RefKind.METHOD_INVOKE, "missing/Second", "x").caught)
    }

    @Test
    fun catch_nestedTries_union() {
        val scan = scanOf("run") {
            val outerStart = Label()
            val innerStart = Label()
            val innerEnd = Label()
            val outerEnd = Label()
            val innerHandler = Label()
            val outerHandler = Label()
            val after = Label()
            visitTryCatchBlock(innerStart, innerEnd, innerHandler, ncdfe)
            visitTryCatchBlock(outerStart, outerEnd, outerHandler, cnfe)
            visitLabel(outerStart)
            visitMethodInsn(Opcodes.INVOKESTATIC, "missing/Outer", "x", "()V", false)
            visitLabel(innerStart)
            visitMethodInsn(Opcodes.INVOKESTATIC, "missing/Inner", "x", "()V", false)
            visitLabel(innerEnd)
            visitMethodInsn(Opcodes.INVOKESTATIC, "missing/OuterTail", "x", "()V", false)
            visitLabel(outerEnd)
            visitJumpInsn(Opcodes.GOTO, after)
            visitLabel(innerHandler)
            visitInsn(Opcodes.POP)
            visitJumpInsn(Opcodes.GOTO, after)
            visitLabel(outerHandler)
            visitInsn(Opcodes.POP)
            visitLabel(after)
            visitInsn(Opcodes.RETURN)
        }
        assertEquals(setOf(cnfe, ncdfe), scan.ref(RefKind.METHOD_INVOKE, "missing/Inner", "x").caught)
        assertEquals(setOf(cnfe), scan.ref(RefKind.METHOD_INVOKE, "missing/Outer", "x").caught)
        assertEquals(setOf(cnfe), scan.ref(RefKind.METHOD_INVOKE, "missing/OuterTail", "x").caught)
    }

    @Test
    fun maxBaseMajor_usesBytecodeExclusions() {
        val jar = jar(
            "org/bukkit/Foo.class" to classBytes("org/bukkit/Foo", version = Opcodes.V21),
            "a/A.class" to classBytes("a/A", version = Opcodes.V1_8),
        )
        val scan = scanPluginJar(jar)
        assertEquals(52, scan.maxBaseMajor)
        assertEquals(bytecodeProfile(jar).outerMaxMajor, scan.maxBaseMajor)
    }

    @Test
    fun index_equals_indexJarMembers() {
        val jar = jar(
            "a/A.class" to classBytes("a/A"),
            "a/b/B.class" to classBytes("a/b/B", superName = "a/A"),
            "META-INF/versions/17/a/A.class" to classBytes("a/A", version = Opcodes.V17),
            "module-info.class" to classBytes("module-info", version = Opcodes.V9),
            manifest = mapOf("Multi-Release" to "true"),
        )
        val scan = scanPluginJar(jar)
        val index = indexJarMembers(jar)
        assertEquals(index.classNames, scan.index.classNames)
        assertEquals(setOf("a/A", "a/b/B"), scan.index.classNames)
        assertEquals(index.versioned.keys, scan.index.versioned.keys)
    }

    @Test
    fun multiRelease_effectiveClasses() {
        val jar = jar(
            "a/A.class" to classBytes("a/A", version = Opcodes.V1_8),
            "META-INF/versions/17/a/A.class" to classBytes("a/A", version = Opcodes.V17),
            manifest = mapOf("Multi-Release" to "true"),
        )
        val scan = scanPluginJar(jar)
        assertEquals(61, scan.effectiveClasses(17).single { it.name == "a/A" }.majorVersion)
        assertEquals(52, scan.effectiveClasses(8).single { it.name == "a/A" }.majorVersion)
        assertEquals(52, scan.maxBaseMajor)
    }
}
