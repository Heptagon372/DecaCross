package kr.decacross.collector.store

import java.nio.file.Path

/**
 * 수집기가 붙을 DB.
 *
 * # 불변식
 * - [Url] 의 [Url.jdbcUrl] 은 항상 `jdbc:postgresql://` 형태다 (CLI 가 `postgresql://` URI 를 변환한다).
 * - [EmbeddedDev] 는 개발용 임베디드 Postgres 다. 운영 데이터를 여기에 두지 않는다.
 */
sealed interface DbTarget {
    /** 외부 Postgres (Supabase 등). 자격 증명은 URL 안이 아니라 별도 필드로 둔다. */
    data class Url(val jdbcUrl: String, val user: String?, val password: String?) : DbTarget {
        /** 비밀번호가 로그·보고서에 새지 않게 가린다. */
        override fun toString(): String = "Url(jdbcUrl=$jdbcUrl, user=$user, password=${if (password == null) "null" else "***"})"
    }

    /** `root` 아래 `pg17-data`(데이터)와 `epg`(바이너리 작업 디렉터리)를 쓰는 임베디드 개발 DB. */
    data class EmbeddedDev(val root: Path, val port: Int = DEFAULT_DEV_DB_PORT) : DbTarget

    companion object {
        const val DEFAULT_DEV_DB_PORT: Int = 54329
    }
}
