package kr.decacross.analysis

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.ACC_ABSTRACT
import org.objectweb.asm.Opcodes.ACC_INTERFACE
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACONST_NULL
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Type
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

// ── 테스트 픽스처: 테스트 시점에 ASM 으로 jar 를 생성한다 ─────────────────
// 실제 플러그인/Paper jar 는 재배포할 수 없으므로(CLAUDE.md 불변식 5) 테스트 리소스로 두지 않는다.

/** 항목을 모아 jar 파일로 쓴다. 클래스 이름은 바이트에서 읽어 경로를 만든다. */
internal class JarBuilder {
    private val entries = LinkedHashMap<String, ByteArray>()
    var multiRelease: Boolean = false

    fun text(path: String, content: String): JarBuilder = apply { entries[path] = content.toByteArray(Charsets.UTF_8) }

    fun raw(path: String, bytes: ByteArray): JarBuilder = apply { entries[path] = bytes }

    fun clazz(bytes: ByteArray, versionDir: Int? = null): JarBuilder = apply {
        val name = org.objectweb.asm.ClassReader(bytes).className
        val path = if (versionDir == null) "$name.class" else "META-INF/versions/$versionDir/$name.class"
        entries[path] = bytes
    }

    fun write(path: Path): Path {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            if (multiRelease) mainAttributes[Attributes.Name.MULTI_RELEASE] = "true"
        }
        Files.createDirectories(path.parent)
        JarOutputStream(Files.newOutputStream(path), manifest).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return path
    }

    fun bytes(): ByteArray {
        val bos = ByteArrayOutputStream()
        JarOutputStream(bos).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}

/** ClassWriter DSL. 코드를 실행하지 않으므로 프레임은 계산하지 않는다 (COMPUTE_MAXS 만). */
internal fun classBytes(
    name: String,
    version: Int = Opcodes.V17,
    superName: String = "java/lang/Object",
    interfaces: List<String> = emptyList(),
    access: Int = ACC_PUBLIC,
    body: ClassWriter.() -> Unit = {},
): ByteArray {
    val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
    cw.visit(version, access, name, null, superName, interfaces.toTypedArray())
    cw.body()
    cw.visitEnd()
    return cw.toByteArray()
}

internal fun ClassWriter.method(name: String, desc: String, access: Int = ACC_PUBLIC, code: MethodVisitor.() -> Unit) {
    val mv = visitMethod(access, name, desc, null, null)
    mv.visitCode()
    mv.code()
    mv.visitMaxs(0, 0)
    mv.visitEnd()
}

internal fun ClassWriter.abstractMethod(name: String, desc: String, access: Int = ACC_PUBLIC or ACC_ABSTRACT) {
    visitMethod(access, name, desc, null, null).visitEnd()
}

internal fun ClassWriter.field(name: String, desc: String, access: Int = ACC_PUBLIC) {
    visitField(access, name, desc, null, null).visitEnd()
}

internal fun ClassWriter.defaultCtor(superName: String = "java/lang/Object") = method("<init>", "()V") {
    visitVarInsn(ALOAD, 0)
    visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false)
    visitInsn(RETURN)
}

/** null 을 돌려주는 메서드 (반환 타입이 참조형일 때). */
internal fun ClassWriter.nullMethod(name: String, desc: String, access: Int = ACC_PUBLIC) = method(name, desc, access) {
    visitInsn(ACONST_NULL)
    visitInsn(ARETURN)
}

internal fun ClassWriter.voidMethod(name: String, desc: String = "()V", access: Int = ACC_PUBLIC) = method(name, desc, access) {
    visitInsn(RETURN)
}

/** `try { body } catch (handlerType) { pop }` 를 감싼다. body 는 스택을 비운 채 끝나야 한다. */
internal fun MethodVisitor.guarded(handlerType: String, body: MethodVisitor.() -> Unit) {
    val start = Label()
    val end = Label()
    val handler = Label()
    val after = Label()
    visitTryCatchBlock(start, end, handler, handlerType)
    visitLabel(start)
    body()
    visitLabel(end)
    visitJumpInsn(Opcodes.GOTO, after)
    visitLabel(handler)
    visitInsn(Opcodes.POP)
    visitLabel(after)
}

/** `new Owner()` 를 만들고 버린다. */
internal fun MethodVisitor.newAndDrop(owner: String) {
    visitTypeInsn(Opcodes.NEW, owner)
    visitInsn(Opcodes.DUP)
    visitMethodInsn(INVOKESPECIAL, owner, "<init>", "()V", false)
    visitInsn(Opcodes.POP)
}

internal val STRING_CONCAT_BSM = Handle(
    Opcodes.H_INVOKESTATIC,
    "java/lang/invoke/StringConcatFactory",
    "makeConcatWithConstants",
    "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
    false,
)

/** `makeConcatWithConstants` 레시피: "a" 뒤에 인자 자리표시자(U+0001). 이스케이프 없이 만든다. */
internal val CONCAT_RECIPE: String = "a" + 1.toChar()

internal val LAMBDA_BSM = Handle(
    Opcodes.H_INVOKESTATIC,
    "java/lang/invoke/LambdaMetafactory",
    "metafactory",
    "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
    false,
)

// ── 가짜 Bukkit API jar ─────────────────────────────────────────────────────

internal const val JAVA_PLUGIN = "org/bukkit/plugin/java/JavaPlugin"
internal const val BUKKIT = "org/bukkit/Bukkit"
internal const val SERVER = "org/bukkit/Server"
internal const val COMMAND_SENDER = "org/bukkit/command/CommandSender"
internal const val PLAYER = "org/bukkit/entity/Player"
internal const val LISTENER = "org/bukkit/event/Listener"
internal const val EVENT_HANDLER = "org/bukkit/event/EventHandler"
internal const val SERVICES_MANAGER = "org/bukkit/plugin/ServicesManager"

/**
 * 최소 Bukkit API: JavaPlugin(onEnable/getLogger/getServer), Bukkit.getServer(), Server(getName/getVersion),
 * CommandSender.sendMessage ← Player (계층 탐색 검증용), Listener, @EventHandler, ServicesManager.
 */
internal fun fakeApiJar(path: Path): Path = JarBuilder()
    .clazz(
        classBytes(JAVA_PLUGIN) {
            defaultCtor()
            voidMethod("onEnable")
            voidMethod("onDisable")
            nullMethod("getLogger", "()Ljava/util/logging/Logger;")
            nullMethod("getServer", "()L$SERVER;")
            nullMethod("getDataFolder", "()Ljava/io/File;")
        },
    )
    .clazz(
        classBytes(BUKKIT) {
            defaultCtor()
            field("NAME", "Ljava/lang/String;", ACC_PUBLIC or ACC_STATIC)
            nullMethod("getServer", "()L$SERVER;", ACC_PUBLIC or ACC_STATIC)
            nullMethod("getServicesManager", "()L$SERVICES_MANAGER;", ACC_PUBLIC or ACC_STATIC)
        },
    )
    .clazz(
        classBytes(SERVER, access = ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT) {
            abstractMethod("getName", "()Ljava/lang/String;")
            abstractMethod("getVersion", "()Ljava/lang/String;")
        },
    )
    .clazz(
        classBytes(COMMAND_SENDER, access = ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT) {
            abstractMethod("sendMessage", "(Ljava/lang/String;)V")
        },
    )
    .clazz(
        classBytes(PLAYER, interfaces = listOf(COMMAND_SENDER), access = ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT) {
            abstractMethod("getDisplayName", "()Ljava/lang/String;")
        },
    )
    .clazz(classBytes(LISTENER, access = ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT))
    .clazz(
        classBytes(EVENT_HANDLER, interfaces = listOf("java/lang/annotation/Annotation"), access = ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT or Opcodes.ACC_ANNOTATION),
    )
    .clazz(
        classBytes(SERVICES_MANAGER, access = ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT) {
            abstractMethod("register", "(Ljava/lang/Class;Ljava/lang/Object;Lorg/bukkit/plugin/Plugin;Lorg/bukkit/plugin/ServicePriority;)V")
        },
    )
    .write(path)

internal fun pluginYml(name: String, main: String, extra: String = ""): String =
    "name: $name\nversion: 1.0.0\nmain: $main\napi-version: '1.21'\n$extra"

internal fun tempDir(prefix: String = "dcx-analysis-"): Path = Files.createTempDirectory(prefix)

internal fun deleteTree(dir: Path) {
    if (!Files.exists(dir)) return
    Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } } }
}

internal fun typeDesc(internalName: String): String = Type.getObjectType(internalName).descriptor
