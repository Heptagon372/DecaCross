package kr.decacross.analysis

import kr.decacross.compat.model.Capability
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilityInferTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = tempDir()
        JarIndexCache.clear()
    }

    @AfterTest
    fun tearDown() {
        JarIndexCache.clear()
        deleteTree(dir)
    }

    private fun jar(name: String, main: String, vararg classes: ByteArray): Path {
        val b = JarBuilder().text("plugin.yml", pluginYml(name, main))
        classes.forEach { b.clazz(it) }
        return b.write(dir.resolve("$name.jar"))
    }

    /** `Bukkit.getServicesManager().register(Service.class, null, null, null)` 를 호출하는 메인 클래스. */
    private fun registering(className: String, serviceType: String): ByteArray = classBytes(className, superName = JAVA_PLUGIN) {
        defaultCtor(JAVA_PLUGIN)
        method("onEnable", "()V") {
            visitMethodInsn(Opcodes.INVOKESTATIC, BUKKIT, "getServicesManager", "()L$SERVICES_MANAGER;", false)
            visitLdcInsn(Type.getObjectType(serviceType))
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ACONST_NULL)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, SERVICES_MANAGER, "register", "(Ljava/lang/Class;Ljava/lang/Object;Lorg/bukkit/plugin/Plugin;Lorg/bukkit/plugin/ServicePriority;)V", true)
            visitInsn(Opcodes.RETURN)
        }
    }

    @Test
    fun `custom item frameworks by name or package`() {
        assertEquals(setOf(Capability.CustomItemFramework), inferCapabilities(jar("ItemsAdder", "dev.lone.itemsadder.Main", classBytes("dev/lone/itemsadder/Main"))).caps)
        assertEquals(setOf(Capability.CustomItemFramework), inferCapabilities(jar("Renamed", "io.th0rgal.oraxen.OraxenPlugin", classBytes("io/th0rgal/oraxen/OraxenPlugin"))).caps)
        assertEquals(setOf(Capability.CustomItemFramework), inferCapabilities(jar("Nexo", "com.nexomc.nexo.NexoPlugin")).caps)
    }

    @Test
    fun `vault economy registration in bytecode`() {
        val r = inferCapabilities(jar("CoolEco", "com.cool.Main", registering("com/cool/Main", "net/milkbowl/vault/economy/Economy")))
        assertEquals(setOf(Capability.EconomyProvider), r.caps)
        assertTrue(r.notes.any { "EconomyProvider" in it })
    }

    @Test
    fun `vault permission registration in bytecode`() {
        val r = inferCapabilities(jar("MyPerms", "com.perms.Main", registering("com/perms/Main", "net/milkbowl/vault/permission/Permission")))
        assertEquals(setOf(Capability.PermissionProvider), r.caps)
    }

    @Test
    fun `name-only economy hint is not stored`() {
        val r = inferCapabilities(jar("SuperEconomy", "com.eco.Main", classBytes("com/eco/Main", superName = JAVA_PLUGIN) { defaultCtor(JAVA_PLUGIN) }))
        assertTrue(r.caps.isEmpty(), r.toString())
        assertTrue(r.notes.any { "저장 안 함" in it }, r.notes.toString())
    }

    @Test
    fun `anti cheat world gen and permission by name`() {
        assertEquals(setOf(Capability.AntiCheat), inferCapabilities(jar("Vulcan", "me.frep.vulcan.Main")).caps)
        assertEquals(setOf(Capability.AntiCheat), inferCapabilities(jar("GrimAC", "ac.grim.Main")).caps)
        assertEquals(setOf(Capability.ChunkGenerator), inferCapabilities(jar("Terra", "com.dfsek.terra.Main")).caps)
        assertEquals(setOf(Capability.PermissionProvider), inferCapabilities(jar("LuckPerms", "me.lucko.luckperms.Main")).caps)
        assertTrue(inferCapabilities(jar("Vault", "net.milkbowl.vault.Vault")).caps.isEmpty())
    }
}
