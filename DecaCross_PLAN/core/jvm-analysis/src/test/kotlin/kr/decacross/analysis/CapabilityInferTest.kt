package kr.decacross.analysis

import kr.decacross.analysis.testutil.ImplSource
import kr.decacross.analysis.testutil.ManagerSource
import kr.decacross.analysis.testutil.PrioritySource
import kr.decacross.analysis.testutil.RegisterSpec
import kr.decacross.analysis.testutil.ServiceSource
import kr.decacross.analysis.testutil.VAULT_ECONOMY
import kr.decacross.analysis.testutil.VAULT_PERMISSION
import kr.decacross.analysis.testutil.classBytes
import kr.decacross.analysis.testutil.implClass
import kr.decacross.analysis.testutil.interfaceClass
import kr.decacross.analysis.testutil.jar
import kr.decacross.analysis.testutil.mergedLocalsRegisterClass
import kr.decacross.analysis.testutil.method
import kr.decacross.analysis.testutil.registerCallClass
import kr.decacross.analysis.testutil.zipBytes
import kr.decacross.compat.model.Capability
import org.objectweb.asm.Opcodes
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Capability 추론 — 서비스 등록 규칙 C1~C7 + 정체성 규칙 (설계 §7.6). */
class CapabilityInferTest {
    private val mainName = "com/example/plugin/Main"
    private val permsImpl = "com/example/plugin/PermsImpl"
    private val ecoImpl = "com/example/plugin/EcoImpl"

    private fun meta(name: String = "TestPlugin"): JarMeta =
        JarMeta(name = name, version = "1", apiVersion = null, main = "com.example.plugin.Main", descriptor = JarMeta.Descriptor.PLUGIN_YML)

    private val identityRules = CapabilityRules(
        identities = listOf(
            CapabilityRules.Identity("ItemsAdder", "dev/lone/itemsadder/", Capability.CustomItemFramework),
            CapabilityRules.Identity("Oraxen", "io/th0rgal/oraxen/", Capability.CustomItemFramework),
            CapabilityRules.Identity("Nexo", "com/nexomc/nexo/", Capability.CustomItemFramework),
        ),
    )

    private class Inference(val evidence: List<CapabilityEvidence>, val notes: List<String>) {
        fun of(capability: Capability): CapabilityEvidence? = evidence.firstOrNull { it.capability == capability }

        fun stores(capability: Capability): Boolean = of(capability)?.confidence == EvidenceConfidence.STORE
    }

    private fun infer(
        vararg entries: Pair<String, ByteArray>,
        meta: JarMeta? = meta(),
        rules: CapabilityRules = CapabilityRules(),
    ): Inference {
        val notes = ArrayList<String>()
        val evidence = withZipFile(jar(*entries)) { zip -> inferCapabilities(zip, meta, rules, notes) }
        return Inference(evidence, notes)
    }

    /** 후보 class 하나를 pass 2 로만 훑어 기록된 register 호출을 돌려준다 (호출이 기록은 됐는지 확인용). */
    private fun registerCallsOf(classBytes: ByteArray): List<RegisterCall> {
        val scan = ServiceCallScan()
        val notes = ArrayList<String>()
        scanServiceCalls(mainName, classBytes, scan, notes)
        assertTrue(notes.isEmpty(), notes.toString())
        return scan.registerCalls
    }

    /** [block] 을 데몬 스레드에서 돌리고 [seconds] 안에 끝나지 않으면 실패한다 (지수 시간 회귀가 테스트를 멈추지 않게). */
    private fun <T> withinSeconds(seconds: Long, block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable).apply { isDaemon = true } }
        try {
            return executor.submit(Callable { block() }).get(seconds, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun permissionProviderJar(spec: RegisterSpec = RegisterSpec(VAULT_PERMISSION, permsImpl)): Array<Pair<String, ByteArray>> = arrayOf(
        "$mainName.class" to registerCallClass(spec),
        "$permsImpl.class" to implClass(permsImpl, interfaces = listOf(VAULT_PERMISSION)),
    )

    @Test
    fun ci01_economyProvider_defaultCandidate() {
        val entries = arrayOf(
            "$mainName.class" to registerCallClass(RegisterSpec(VAULT_ECONOMY, ecoImpl)),
            "$ecoImpl.class" to implClass(ecoImpl, interfaces = listOf(VAULT_ECONOMY)),
        )
        val byDefault = infer(*entries).of(Capability.EconomyProvider)
        assertEquals(EvidenceConfidence.CANDIDATE, byDefault?.confidence)
        assertEquals("SERVICE_PROVIDER", byDefault?.rule)
        assertFalse(byDefault?.detail.orEmpty().startsWith("failed"), byDefault?.detail)

        val stored = infer(*entries, rules = CapabilityRules(storeEconomyProviders = true)).of(Capability.EconomyProvider)
        assertEquals(EvidenceConfidence.STORE, stored?.confidence)
    }

    @Test
    fun ci02_permissionProvider_store() {
        val result = infer(*permissionProviderJar(RegisterSpec(VAULT_PERMISSION, permsImpl, manager = ManagerSource.BUKKIT_STATIC)))
        val evidence = result.of(Capability.PermissionProvider)
        assertEquals(EvidenceConfidence.STORE, evidence?.confidence)
        assertEquals("SERVICE_PROVIDER", evidence?.rule)
        assertTrue(evidence?.detail.orEmpty().contains(permsImpl), evidence?.detail)
        assertEquals(1, result.evidence.size)
    }

    @Test
    fun ci02b_pathOverload_sameResult() {
        val jar = jar(*permissionProviderJar())
        val evidence = inferCapabilities(jar, meta(), CapabilityRules())
        assertEquals(listOf(EvidenceConfidence.STORE), evidence.map { it.confidence })
    }

    @Test
    fun ci03_implViaInJarAbstractSuper() {
        val abstractPerms = "com/example/plugin/AbstractPerms"
        val result = infer(
            "$mainName.class" to registerCallClass(RegisterSpec(VAULT_PERMISSION, permsImpl)),
            "$abstractPerms.class" to classBytes(
                abstractPerms,
                interfaces = listOf(VAULT_PERMISSION),
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
            ),
            "$permsImpl.class" to implClass(permsImpl, superName = abstractPerms),
        )
        assertTrue(result.stores(Capability.PermissionProvider), result.evidence.toString())
    }

    @Test
    fun ci04_lowestPriority() {
        val result = infer(*permissionProviderJar(RegisterSpec(VAULT_PERMISSION, permsImpl, priority = PrioritySource.Static("Lowest"))))
        val evidence = result.of(Capability.PermissionProvider)
        assertEquals(EvidenceConfidence.CANDIDATE, evidence?.confidence)
        assertTrue(evidence?.detail.orEmpty().contains("C3"), evidence?.detail)
    }

    @Test
    fun ci05_priorityFromLocal() {
        val result = infer(*permissionProviderJar(RegisterSpec(VAULT_PERMISSION, permsImpl, priority = PrioritySource.Local)))
        assertFalse(result.stores(Capability.PermissionProvider), result.evidence.toString())
        assertTrue(result.of(Capability.PermissionProvider)?.detail.orEmpty().contains("C3"))

        for (priority in listOf(PrioritySource.Field, PrioritySource.Parameter)) {
            val other = infer(*permissionProviderJar(RegisterSpec(VAULT_PERMISSION, permsImpl, priority = priority)))
            assertFalse(other.stores(Capability.PermissionProvider), "$priority → ${other.evidence}")
        }
    }

    @Test
    fun ci06_alsoConsumer() {
        val result = infer(
            "$mainName.class" to registerCallClass(RegisterSpec(VAULT_PERMISSION, permsImpl), consumerOf = VAULT_PERMISSION),
            "$permsImpl.class" to implClass(permsImpl, interfaces = listOf(VAULT_PERMISSION)),
        )
        assertFalse(result.stores(Capability.PermissionProvider))
        assertTrue(result.of(Capability.PermissionProvider)?.detail.orEmpty().contains("C5"))
    }

    @Test
    fun ci07_definesApi() {
        val result = infer(*permissionProviderJar(), "$VAULT_PERMISSION.class" to interfaceClass(VAULT_PERMISSION))
        assertFalse(result.stores(Capability.PermissionProvider))
        assertTrue(result.of(Capability.PermissionProvider)?.detail.orEmpty().contains("C4"))
    }

    @Test
    fun ci08_twoImpls() {
        val otherImpl = "com/example/plugin/OtherPerms"
        val result = infer(
            "$mainName.class" to registerCallClass(RegisterSpec(VAULT_PERMISSION, permsImpl), RegisterSpec(VAULT_PERMISSION, otherImpl)),
            "$permsImpl.class" to implClass(permsImpl, interfaces = listOf(VAULT_PERMISSION)),
            "$otherImpl.class" to implClass(otherImpl, interfaces = listOf(VAULT_PERMISSION)),
        )
        assertFalse(result.stores(Capability.PermissionProvider))
        assertTrue(result.of(Capability.PermissionProvider)?.detail.orEmpty().contains("C6"))
    }

    @Test
    fun ci09_serviceFromParameter() {
        val spec = RegisterSpec(VAULT_PERMISSION, permsImpl, serviceSource = ServiceSource.PARAMETER)
        val result = infer(*permissionProviderJar(spec))
        assertFalse(result.stores(Capability.PermissionProvider))
        // 호출은 기록되지만 서비스 타입을 못 풀어 C1 에 걸린다 (근거 없음)
        val call = registerCallsOf(registerCallClass(spec)).single()
        assertNull(call.service, call.toString())
        assertEquals(permsImpl, call.impl)
        assertNull(result.of(Capability.PermissionProvider), result.evidence.toString())
    }

    @Test
    fun ci10_forName() {
        val spec = RegisterSpec(VAULT_PERMISSION, permsImpl, serviceSource = ServiceSource.FOR_NAME)
        val result = infer(*permissionProviderJar(spec))
        assertFalse(result.stores(Capability.PermissionProvider))
        val call = registerCallsOf(registerCallClass(spec)).single()
        assertNull(call.service, call.toString())
        assertEquals(permsImpl, call.impl)
        assertNull(result.of(Capability.PermissionProvider), result.evidence.toString())
    }

    @Test
    fun ci11_consumerOnly() {
        val result = infer("$mainName.class" to registerCallClass(consumerOf = VAULT_PERMISSION))
        assertTrue(result.evidence.isEmpty(), result.evidence.toString())
    }

    @Test
    fun ci12_insideNestedJarinjar() {
        val nested = zipBytes(*permissionProviderJar())
        val result = infer("META-INF/libs/core.jarinjar" to nested)
        assertTrue(result.stores(Capability.PermissionProvider), result.evidence.toString())
    }

    @Test
    fun ci13_nonVaultService() {
        val service = "com/example/api/MyService"
        val result = infer(
            "$mainName.class" to registerCallClass(RegisterSpec(service, permsImpl)),
            "$permsImpl.class" to implClass(permsImpl, interfaces = listOf(service)),
        )
        assertTrue(result.evidence.isEmpty(), result.evidence.toString())
    }

    @Test
    fun ci14_invokeChain_viaGetServer() {
        val viaServer = infer(*permissionProviderJar(RegisterSpec(VAULT_PERMISSION, permsImpl, manager = ManagerSource.GET_SERVER)))
        val viaStatic = infer(*permissionProviderJar(RegisterSpec(VAULT_PERMISSION, permsImpl, manager = ManagerSource.BUKKIT_STATIC)))
        assertEquals(viaStatic.evidence.map { it.capability to it.confidence }, viaServer.evidence.map { it.capability to it.confidence })
        assertTrue(viaServer.stores(Capability.PermissionProvider))
    }

    @Test
    fun ci15_simpleServicesManager_invokevirtual() {
        val result = infer(*permissionProviderJar(RegisterSpec(VAULT_PERMISSION, permsImpl, manager = ManagerSource.SIMPLE_MANAGER)))
        assertTrue(result.stores(Capability.PermissionProvider), result.evidence.toString())
    }

    @Test
    fun ci16_identityNexo() {
        val result = infer(
            "com/nexomc/nexo/NexoPlugin.class" to classBytes("com/nexomc/nexo/NexoPlugin"),
            meta = meta("Nexo"),
            rules = identityRules,
        )
        val evidence = result.of(Capability.CustomItemFramework)
        assertEquals(EvidenceConfidence.STORE, evidence?.confidence)
        assertEquals("IDENTITY", evidence?.rule)
    }

    @Test
    fun ci17_addonReferencesOnly() {
        val addon = classBytes("com/example/addon/Addon") {
            method("hook") {
                visitMethodInsn(Opcodes.INVOKESTATIC, "com/nexomc/nexo/api/NexoItems", "itemNames", "()Ljava/util/Set;", false)
                visitInsn(Opcodes.POP)
                visitInsn(Opcodes.RETURN)
            }
        }
        val result = infer("com/example/addon/Addon.class" to addon, meta = meta("NexoAddon"), rules = identityRules)
        assertTrue(result.evidence.isEmpty(), result.evidence.toString())
    }

    @Test
    fun ci18_nameWithoutPackage() {
        val result = infer("com/fake/nexo/Main.class" to classBytes("com/fake/nexo/Main"), meta = meta("Nexo"), rules = identityRules)
        assertTrue(result.evidence.isEmpty(), result.evidence.toString())
        assertTrue(result.notes.contains("이름 일치하나 패키지 미정의: Nexo"), result.notes.toString())
    }

    @Test
    fun ci19_modelEngine() {
        val result = infer(
            "com/ticxo/modelengine/core/ModelEngine.class" to classBytes("com/ticxo/modelengine/core/ModelEngine"),
            meta = meta("ModelEngine"),
            rules = identityRules,
        )
        assertTrue(result.evidence.isEmpty(), result.evidence.toString())
    }

    @Test
    fun ci20_worldGeneratorOverride() {
        val generator = "com/example/plugin/Gen"
        val main = classBytes(mainName, superName = "org/bukkit/plugin/java/JavaPlugin") {
            method("getDefaultWorldGenerator", "(Ljava/lang/String;Ljava/lang/String;)Lorg/bukkit/generator/ChunkGenerator;") {
                visitTypeInsn(Opcodes.NEW, generator)
                visitInsn(Opcodes.DUP)
                visitMethodInsn(Opcodes.INVOKESPECIAL, generator, "<init>", "()V", false)
                visitInsn(Opcodes.ARETURN)
            }
        }
        val result = infer(
            "$mainName.class" to main,
            "$generator.class" to classBytes(generator, superName = "org/bukkit/generator/ChunkGenerator"),
        )
        assertTrue(result.evidence.isEmpty(), result.evidence.toString())
    }

    @Test
    fun ci21_anticheatName() {
        for (name in listOf("Vulcan", "GrimAC", "NoCheatPlus")) {
            val result = infer("$mainName.class" to classBytes(mainName), meta = meta(name), rules = identityRules)
            assertTrue(result.evidence.none { it.capability == Capability.AntiCheat }, "$name → ${result.evidence}")
            assertTrue(result.evidence.isEmpty(), "$name → ${result.evidence}")
        }

        // 규칙 데이터가 금지 Capability 를 말해도 내지 않는다 (설계 §7.6 "Never emitted", D34)
        val generatorService = "com/example/api/GeneratorService"
        val generatorImpl = "com/example/plugin/GenImpl"
        val forbiddenRules = CapabilityRules(
            identities = listOf(CapabilityRules.Identity("Vulcan", "me/frep/vulcan/", Capability.AntiCheat)),
            serviceTypes = CapabilityRules.DEFAULT_SERVICE_TYPES + (generatorService to Capability.ChunkGenerator),
        )
        val forbidden = infer(
            "me/frep/vulcan/spigot/Vulcan.class" to classBytes("me/frep/vulcan/spigot/Vulcan"),
            "$mainName.class" to registerCallClass(RegisterSpec(generatorService, generatorImpl)),
            "$generatorImpl.class" to implClass(generatorImpl, interfaces = listOf(generatorService)),
            meta = meta("Vulcan"),
            rules = forbiddenRules,
        )
        assertTrue(forbidden.evidence.isEmpty(), forbidden.evidence.toString())
        assertTrue(forbidden.notes.contains("추론 금지 Capability 규칙 무시: AntiCheat"), forbidden.notes.toString())
        assertTrue(forbidden.notes.contains("추론 금지 Capability 규칙 무시: ChunkGenerator"), forbidden.notes.toString())
    }

    @Test
    fun ci22_noDescriptor() {
        val result = infer(*permissionProviderJar(), meta = null)
        assertFalse(result.stores(Capability.PermissionProvider))
        assertTrue(result.of(Capability.PermissionProvider)?.detail.orEmpty().contains("C7"))
    }

    @Test
    fun ci23_extendsVaultAbstractEconomy() {
        val result = infer(
            "$mainName.class" to registerCallClass(RegisterSpec(VAULT_ECONOMY, ecoImpl)),
            "$ecoImpl.class" to implClass(ecoImpl, superName = "net/milkbowl/vault/economy/AbstractEconomy"),
            rules = CapabilityRules(storeEconomyProviders = true),
        )
        assertTrue(result.stores(Capability.EconomyProvider), result.evidence.toString())

        val withoutExternalHint = infer(
            "$mainName.class" to registerCallClass(RegisterSpec(VAULT_ECONOMY, ecoImpl)),
            "$ecoImpl.class" to implClass(ecoImpl, superName = "net/milkbowl/vault/economy/AbstractEconomy"),
            rules = CapabilityRules(storeEconomyProviders = true, externalSupertypes = emptyMap()),
        )
        assertFalse(withoutExternalHint.stores(Capability.EconomyProvider))
    }

    @Test
    fun ci24_oneImplUntraceable() {
        val result = infer(
            "$mainName.class" to registerCallClass(
                RegisterSpec(VAULT_PERMISSION, permsImpl),
                RegisterSpec(VAULT_PERMISSION, permsImpl, implSource = ImplSource.METHOD_RETURN),
            ),
            "$permsImpl.class" to implClass(permsImpl, interfaces = listOf(VAULT_PERMISSION)),
        )
        assertFalse(result.stores(Capability.PermissionProvider))
        assertTrue(result.of(Capability.PermissionProvider)?.detail.orEmpty().contains("C6"))
    }

    @Test
    fun ci25_dupX1_untraceable() {
        val spec = RegisterSpec(VAULT_PERMISSION, permsImpl, implSource = ImplSource.DUP_X1)
        val result = infer(*permissionProviderJar(spec))
        assertFalse(result.stores(Capability.PermissionProvider), result.evidence.toString())
        // 호출은 기록되고 구현체만 추적 불가 → C6 로 CANDIDATE
        val evidence = result.of(Capability.PermissionProvider)
        assertEquals(EvidenceConfidence.CANDIDATE, evidence?.confidence, result.evidence.toString())
        assertTrue(evidence?.detail.orEmpty().contains("C6"), evidence?.detail)
        val call = registerCallsOf(registerCallClass(spec)).single()
        assertEquals(VAULT_PERMISSION, call.service)
        assertNull(call.impl, call.toString())
    }

    @Test
    fun ci26_storeWinsOverCandidate_perCapability() {
        // vault(STORE) + vault2(CANDIDATE: Lowest) 둘 다 PermissionProvider → STORE 하나만 남는다
        val vault2 = "net/milkbowl/vault2/permission/Permission"
        val otherImpl = "com/example/plugin/Perms2"
        val main = registerCallClass(
            RegisterSpec(vault2, otherImpl, priority = PrioritySource.Static("Lowest")),
            RegisterSpec(VAULT_PERMISSION, permsImpl),
        )
        val result = infer(
            "$mainName.class" to main,
            "$permsImpl.class" to implClass(permsImpl, interfaces = listOf(VAULT_PERMISSION)),
            "$otherImpl.class" to implClass(otherImpl, interfaces = listOf(vault2)),
        )
        assertEquals(1, result.evidence.size, result.evidence.toString())
        assertTrue(result.stores(Capability.PermissionProvider))
        assertNull(result.of(Capability.EconomyProvider))
    }

    @Test
    fun ci27_implFromLocal_store() {
        // `Perms perms = new Perms(this); sm.register(Permission.class, perms, this, Normal)` — ALOAD → ASTORE → 스택 top
        val spec = RegisterSpec(VAULT_PERMISSION, permsImpl, implSource = ImplSource.LOCAL)
        val result = infer(*permissionProviderJar(spec))
        assertTrue(result.stores(Capability.PermissionProvider), result.evidence.toString())
        assertEquals(permsImpl, registerCallsOf(registerCallClass(spec)).single().impl)
    }

    @Test
    fun ci28_implViaDupAstore_store() {
        // `...; dup; astore 3` 뒤 남은 값(DUP 출처)을 인자로 — DUP → 그 시점 스택 top
        val spec = RegisterSpec(VAULT_PERMISSION, permsImpl, implSource = ImplSource.DUP_ASTORE)
        val result = infer(*permissionProviderJar(spec))
        assertTrue(result.stores(Capability.PermissionProvider), result.evidence.toString())
        assertEquals(permsImpl, registerCallsOf(registerCallClass(spec)).single().impl)
    }

    @Test
    fun ci29_mergedLocalsChain_tracedWithoutPathExplosion() {
        // 7 단계 × 21 갈래 병합 지역변수 사슬: 경로 수 21^7. 메모 없는 경로 탐색은 90 초 안에 끝나지 않았다 (리뷰 JA-R1)
        val result = withinSeconds(20) {
            infer(
                "$mainName.class" to mergedLocalsRegisterClass(permsImpl, VAULT_PERMISSION, fanOut = 20, levels = 7),
                "$permsImpl.class" to implClass(permsImpl, interfaces = listOf(VAULT_PERMISSION)),
            )
        }
        assertTrue(result.stores(Capability.PermissionProvider), result.evidence.toString())
        assertTrue(result.notes.isEmpty(), result.notes.toString())
    }

    @Test
    fun ci30_traceBudgetExceeded_untraceableNoStore() {
        // 작업 상한(인자당 4096)을 넘는 사슬(단계마다 31×31 방문)은 끝까지 풀지 않고 추적 불가로 본다 → C6 CANDIDATE + note (보수적)
        val result = withinSeconds(20) {
            infer(
                "$mainName.class" to mergedLocalsRegisterClass(permsImpl, VAULT_PERMISSION, fanOut = 30, levels = 7),
                "$permsImpl.class" to implClass(permsImpl, interfaces = listOf(VAULT_PERMISSION)),
            )
        }
        val evidence = result.of(Capability.PermissionProvider)
        assertEquals(EvidenceConfidence.CANDIDATE, evidence?.confidence, result.evidence.toString())
        assertTrue(evidence?.detail.orEmpty().contains("C6"), evidence?.detail)
        assertTrue(result.notes.any { it.startsWith("값 추적 작업 상한 초과") }, result.notes.toString())
    }
}
