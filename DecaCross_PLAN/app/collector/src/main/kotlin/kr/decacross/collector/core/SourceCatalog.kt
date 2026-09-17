package kr.decacross.collector.core

import kr.decacross.collector.sources.adoptium.AdoptiumSource
import kr.decacross.collector.sources.fill.FillProject
import kr.decacross.collector.sources.fill.FillSource
import kr.decacross.collector.sources.hangar.HangarSource
import kr.decacross.collector.sources.modrinth.ModrinthSource
import kr.decacross.collector.sources.mojang.MojangSource
import kr.decacross.collector.sources.purpur.PurpurSource

/** 수집 소스 전체 목록. Runner 는 이 목록에서 `--sources=` 로 거른다. 순서는 표시용. */
fun allSources(): List<CollectorSource> = listOf(
    MojangSource(),
    FillSource(FillProject.PAPER),
    FillSource(FillProject.FOLIA),
    PurpurSource(),
    AdoptiumSource(),
    ModrinthSource(),
    HangarSource(),
)
