package kr.decacross.compat

import kr.decacross.compat.db.ConfidenceEntry
import kr.decacross.compat.db.InMemoryCompatDb
import kr.decacross.compat.model.Arch
import kr.decacross.compat.model.Capability
import kr.decacross.compat.model.Channel
import kr.decacross.compat.model.Content
import kr.decacross.compat.model.ContentKind
import kr.decacross.compat.model.ContentVersion
import kr.decacross.compat.model.CoreBuild
import kr.decacross.compat.model.CoreKey
import kr.decacross.compat.model.Dep
import kr.decacross.compat.model.DepKind
import kr.decacross.compat.model.DepTarget
import kr.decacross.compat.model.LoaderFamily
import kr.decacross.compat.model.McOrdinal
import kr.decacross.compat.model.McVersion
import kr.decacross.compat.model.Os
import kr.decacross.compat.model.PackFormat
import kr.decacross.compat.model.Source
import kotlin.time.Instant

/** 테스트용 미니 생태계. 실데이터가 아니라 "알려진 관계"를 모델링한 것 — 골든 케이스의 기준. */
object ResolveFixture {
    val MC_1_20_4 = McOrdinal(1000)
    val MC_1_21_8 = McOrdinal(1010)
    val MC_26_3 = McOrdinal(1020)

    private fun mc(o: McOrdinal, label: String, java: Int, dp: String?) =
        McVersion(o, label, Instant.parse("2025-01-01T00:00:00Z"), false, java, java, null, dp?.let { PackFormat.parse(it) })

    private fun build(o: McOrdinal, n: String, channel: Channel = Channel.STABLE) =
        CoreBuild(CoreKey.PAPER, o, n, channel, "https://example/paper-$n.jar", "0".repeat(64), 50_000_000)

    private fun content(slug: String, name: String = slug, kind: ContentKind = ContentKind.PLUGIN) =
        Content(slug, name, kind, Source.MODRINTH, "MIT", redistributable = true)

    private fun require(slug: String, range: String = "*") = Dep(DepKind.REQUIRE, DepTarget.Slug(slug), range)

    private fun optional(slug: String) = Dep(DepKind.OPTIONAL, DepTarget.Slug(slug))

    private fun provides(cap: Capability) = Dep(DepKind.PROVIDES, DepTarget.Cap(cap))

    private fun v(
        slug: String,
        version: String,
        java: Int?,
        min: McOrdinal?,
        max: McOrdinal?,
        api: String? = "1.13",
        deps: List<Dep> = emptyList(),
        size: Long = 1_000_000,
    ) = ContentVersion(slug, version, "https://example/$slug-$version.jar", null, size, java, api, setOf(LoaderFamily.BUKKIT), min, max, null, deps)

    val mcs = listOf(
        mc(MC_1_20_4, "1.20.4", 17, "26"),
        mc(MC_1_21_8, "1.21.8", 21, "81"),
        mc(MC_26_3, "26.3", 25, "121.0"),
    )

    val builds = listOf(build(MC_1_20_4, "499"), build(MC_1_21_8, "60"), build(MC_26_3, "124"))

    val contents = listOf(
        content("vault", "Vault"), content("essentialsx", "EssentialsX"), content("protocollib", "ProtocolLib"),
        content("worldedit", "WorldEdit"), content("worldguard", "WorldGuard"), content("itemsadder", "ItemsAdder"),
        content("oraxen", "Oraxen"), content("luckperms", "LuckPerms"), content("placeholderapi", "PlaceholderAPI"),
        content("oldplugin", "OldPlugin"), content("mypack", "MyPack", ContentKind.RESOURCE_PACK),
    ) + (1..30).map { content("p%02d".format(it)) }

    val versions = listOf(
        v("vault", "1.7.3", 8, null, null),
        v("essentialsx", "2.20.1", 17, MC_1_20_4, MC_1_21_8, deps = listOf(require("vault", ">=1.7"))),
        v("essentialsx", "2.21.0", 21, MC_1_21_8, MC_26_3, deps = listOf(require("vault", ">=1.7"))),
        v("protocollib", "5.1.0", 17, MC_1_20_4, MC_1_21_8),
        v("protocollib", "5.4.0", 21, MC_1_21_8, MC_26_3),
        v("worldedit", "7.3.0", 17, MC_1_20_4, MC_26_3),
        v("worldguard", "7.0.9", 17, MC_1_20_4, MC_26_3, deps = listOf(require("worldedit", ">=7.3"), optional("protocollib"))),
        v("itemsadder", "4.0.0", 17, MC_1_20_4, MC_26_3, deps = listOf(provides(Capability.CustomItemFramework), require("protocollib", ">=5.0"))),
        v("oraxen", "1.190.0", 17, MC_1_20_4, MC_26_3, deps = listOf(provides(Capability.CustomItemFramework), require("protocollib", ">=5.0"))),
        v("luckperms", "5.4.0", 17, MC_1_20_4, MC_26_3, deps = listOf(provides(Capability.PermissionProvider))),
        v("placeholderapi", "2.11.6", 17, MC_1_20_4, MC_26_3),
        v("oldplugin", "1.0", 8, null, MC_1_20_4),
        ContentVersion("mypack", "1.0", null, null, 10_000, null, null, emptySet(), null, null, kr.decacross.compat.model.PackDecl.Single(PackFormat(81)), emptyList()),
    ) + (1..30).map { i ->
        // 30개 벤치용: p01 → p02 → … 체인 의존 + 각 3버전
        val deps = if (i < 30) listOf(require("p%02d".format(i + 1), ">=1.0")) else emptyList()
        listOf("1.0.0", "1.1.0", "2.0.0").map { ver -> v("p%02d".format(i), ver, 17, MC_1_20_4, MC_26_3, deps = deps) }
    }.flatten()

    val confidence = listOf(
        ConfidenceEntry("essentialsx", "2.21.0", MC_1_21_8, CoreKey.PAPER, Confidence.GREEN),
        ConfidenceEntry("oldplugin", "1.0", MC_1_20_4, CoreKey.PAPER, Confidence.RED),
    )

    val db = InMemoryCompatDb(mcs, builds, contents, versions, confidence)

    fun req(
        mc: McSelector = McSelector.Any,
        vararg wants: Want,
        ramMb: Int = 16 * 1024,
        hostOverheadMb: Int = 0,
    ) = ResolveRequest(mc = mc, core = CoreKey.PAPER, wants = wants.toList(), os = Os.WINDOWS, arch = Arch.X64, ramMb = ramMb, hostOverheadMb = hostOverheadMb)

    fun want(slug: String, version: String? = null, pinned: Boolean = false) = Want(slug, ContentKind.PLUGIN, version, pinned)
}
