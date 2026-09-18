package kr.decacross.collector.store

import kr.decacross.collector.testkit.RecordingStore

/** 메모리 참조 구현(testkit)에 대한 [CollectorStoreContract]. */
class RecordingStoreContractTest : CollectorStoreContract() {
    override fun newStore(): CollectorStore = RecordingStore()
}
