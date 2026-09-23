package kr.decacross.pubgrub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DerivationTreeTest {
    private fun r(lo: Int, hi: Int): VersionSet<Int> = Range.between(lo, hi)

    private fun noSolution(p: DependencyProvider<String, Int>): DerivationTree<String, Int> {
        val result = resolve(p, "root", 1)
        assertIs<SolverResult.NoSolution<String, Int>>(result, "해가 없어야 한다: $result")
        return result.tree
    }

    @Test
    fun linearCase_isCompactAndRootTermsUseAllowedConvention() {
        val tree =
            noSolution(
                MapDependencyProvider.build {
                    add("root", 1, mapOf("foo" to r(100, 200), "baz" to r(100, 200)))
                    add("foo", 100, mapOf("bar" to r(200, 300)))
                    add("bar", 200, mapOf("baz" to r(300, 400)))
                    add("baz", 100)
                    add("baz", 300)
                },
            )
        assertIs<DerivationTree.Derived<String, Int>>(tree)
        // 루트 노드의 결론은 "root 1 은 성립할 수 없다"
        assertEquals(mapOf("root" to Range.singleton(1)), tree.terms)
        assertEquals(setOf("root", "foo", "bar", "baz"), tree.packages())
        // 문서의 선형 보고: 외부 사실 4개(root→foo, root→baz, foo→bar, bar→baz)로 충분해야 한다
        val externals = tree.externals()
        assertTrue(externals.all { it is ExternalKind.FromDependencyOf<String, Int> }, externals.toString())
        assertEquals(4, externals.size, externals.toString())
        assertTrue(tree.derivedCount() <= 4, "유도 단계가 너무 많다: ${tree.derivedCount()}\n${tree.render()}")
        val text = tree.render()
        assertTrue("root" in text && "foo" in text && "bar" in text && "baz" in text)
    }

    @Test
    fun dependencyExternals_areMergedAcrossVersions() {
        val tree =
            noSolution(
                MapDependencyProvider.build {
                    add("root", 1, mapOf("foo" to Range.full()))
                    add("foo", 100, mapOf("bar" to r(100, 200)))
                    add("foo", 110, mapOf("bar" to r(100, 200)))
                    add("foo", 120, mapOf("bar" to r(100, 200)))
                    add("bar", 200)
                },
            )
        val deps = tree.externals().filterIsInstance<ExternalKind.FromDependencyOf<String, Int>>().filter { it.pkg == "foo" }
        assertEquals(1, deps.size, "foo→bar 의존성은 한 진술로 병합되어야 한다:\n${tree.render()}")
        val range = deps.single().range
        assertTrue(range.contains(100) && range.contains(110) && range.contains(120), "병합 범위: $range")
        assertEquals(r(100, 200), deps.single().depRange)
        val noVersions = tree.externals().filterIsInstance<ExternalKind.NoVersions<String, Int>>()
        assertEquals(1, noVersions.size)
        assertEquals("bar", noVersions.single().pkg)
    }

    @Test
    fun unrelatedPackages_neverAppear() {
        val tree =
            noSolution(
                MapDependencyProvider.build {
                    add("root", 1, mapOf("a" to Range.full(), "b" to Range.full(), "c" to Range.full()))
                    add("a", 100, mapOf("x" to r(100, 200)))
                    add("b", 100, mapOf("y" to Range.full()))
                    add("b", 200)
                    add("c", 100)
                    add("y", 100)
                    add("x", 200)
                },
            )
        assertEquals(setOf("root", "a", "x"), tree.packages(), tree.render())
    }

    @Test
    fun sharedSubtrees_areSameObjectAndRenderOnce() {
        // 두 갈래가 같은 사실(z 1 만 존재)에 의존 → External 객체는 하나만 만들어진다
        val tree =
            noSolution(
                MapDependencyProvider.build {
                    add("root", 1, mapOf("a" to Range.full(), "b" to Range.full()))
                    add("a", 100, mapOf("z" to r(200, 300)))
                    add("b", 100, mapOf("z" to r(300, 400)))
                    add("z", 100)
                },
            )
        val externals = tree.externals()
        assertEquals(externals.size, externals.toSet().size, "중복 External 이 없어야 한다")
        val text = tree.render()
        assertTrue(text.lines().size >= 3, text)
    }

    @Test
    fun externalsDescribe_isReadable() {
        assertEquals("foo >=1 <2 은(는) bar 3 에 의존한다", ExternalKind.FromDependencyOf("foo", r(1, 2), "bar", Range.singleton(3)).describe())
        assertEquals("foo 에는 >=1 <2 에 맞는 버전이 없다", ExternalKind.NoVersions<String, Int>("foo", r(1, 2)).describe())
        assertEquals("foo 5 은(는) 쓸 수 없다: 사유", ExternalKind.Unavailable<String, Int>("foo", Range.singleton(5), "사유").describe())
        assertEquals("root 1 은(는) 루트다", ExternalKind.NotRoot<String, Int>("root", 1).describe())
    }
}
