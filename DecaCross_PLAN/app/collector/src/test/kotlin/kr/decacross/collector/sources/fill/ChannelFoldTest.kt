package kr.decacross.collector.sources.fill

import kr.decacross.compat.model.Channel
import kotlin.test.Test
import kotlin.test.assertEquals

/** §9.3 ChannelFold (D13, AE-10). */
class ChannelFoldTest {
    @Test
    fun channelFold_table() {
        val table = mapOf(
            "STABLE" to (Channel.STABLE to true),
            "RECOMMENDED" to (Channel.STABLE to true),
            "ALPHA" to (Channel.EXPERIMENTAL to true),
            "BETA" to (Channel.EXPERIMENTAL to true),
            "EXPERIMENTAL" to (Channel.EXPERIMENTAL to false),
            "stable" to (Channel.EXPERIMENTAL to false),
            "" to (Channel.EXPERIMENTAL to false),
            "NIGHTLY" to (Channel.EXPERIMENTAL to false),
        )
        for ((raw, expected) in table) assertEquals(expected, foldFillChannel(raw), "'$raw'")
    }
}
