package kr.decacross.collector.store

/** SQL 구현에 대한 같은 [CollectorStoreContract] (TestPg, 테스트마다 스키마 초기화). */
class PgCollectorStoreContractTest : CollectorStoreContract() {
    override fun newStore(): CollectorStore {
        TestPg.resetAndMigrate()
        return TestPg.openStore()
    }
}
