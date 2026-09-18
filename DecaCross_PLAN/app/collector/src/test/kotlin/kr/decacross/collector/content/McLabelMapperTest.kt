package kr.decacross.collector.content

import kr.decacross.compat.model.McOrdinal
import kotlin.test.Test
import kotlin.test.assertEquals

class McLabelMapperTest {
    @Test
    fun minMax_byOrdinal_notStringOrder() {
        val mapper = McLabelMapper(listOf(mcRow("1.8", 1470), mcRow("1.21.2", 1880), mcRow("1.21.10", 1950)))
        val expected = McLabelMapper.Mapped(McOrdinal(1470), McOrdinal(1950), unknown = 0)

        // 문자열 정렬이면 "1.21.10" < "1.21.2" < "1.8" 이 된다 — 서수로만 계산해야 한다
        assertEquals(expected, mapper.map(listOf("1.21.10", "1.21.2", "1.8")))
        // 입력 순서는 무관
        assertEquals(expected, mapper.map(listOf("1.8", "1.21.10", "1.21.2")))
        assertEquals(expected, mapper.map(setOf("1.21.2", "1.8", "1.21.10")))
    }

    @Test
    fun unknownCounted_allUnknown_nulls() {
        val mapper = McLabelMapper(listOf(mcRow("1.20.1", 1810), mcRow("1.21", 1860)))

        assertEquals(McLabelMapper.Mapped(McOrdinal(1810), McOrdinal(1860), unknown = 2), mapper.map(listOf("1.21", "1.20.1", "1.99", "b1.7.3")))
        assertEquals(McLabelMapper.Mapped(null, null, unknown = 2), mapper.map(listOf("9.9", "8.8")))
        assertEquals(McLabelMapper.Mapped(null, null, unknown = 0), mapper.map(emptyList()))
        assertEquals(McLabelMapper.Mapped(null, null, unknown = 1), McLabelMapper(emptyList()).map(listOf("1.21")))
    }

    @Test
    fun snapshotLabelsMapWhenPresent() {
        val mapper = McLabelMapper(
            listOf(mcRow("1.21", 1860), mcRow("24w14a", 1851, snapshot = true), mcRow("26.3-snapshot-1", 2011, snapshot = true), mcRow("26.3", 2020)),
        )

        assertEquals(McLabelMapper.Mapped(McOrdinal(1851), McOrdinal(2011), unknown = 0), mapper.map(listOf("26.3-snapshot-1", "24w14a", "1.21")))
        // 인덱스에 없는 스냅샷은 모르는 라벨로만 센다
        assertEquals(McLabelMapper.Mapped(McOrdinal(2020), McOrdinal(2020), unknown = 1), mapper.map(listOf("26.3", "26.3-snapshot-12")))
    }
}
