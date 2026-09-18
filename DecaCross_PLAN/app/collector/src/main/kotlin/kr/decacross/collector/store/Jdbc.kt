package kr.decacross.collector.store

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

// ── plain JDBC 도우미 (수집기 내부 전용) ───────────────────────────────────
// pgjdbc 는 java.time.Instant 를 setObject 로 받지 못한다 → OffsetDateTime(UTC) 로 쓰고 읽는다 (TimeProbe 확인).

/** SQLSTATE 23505 (unique_violation). */
internal const val SQLSTATE_UNIQUE_VIOLATION: String = "23505"

internal fun SQLException.isUniqueViolation(): Boolean = sqlState == SQLSTATE_UNIQUE_VIOLATION

/**
 * [block] 을 한 트랜잭션으로 실행한다. 정상 종료면 commit, 어떤 Throwable 이든 rollback 후 다시 던진다.
 * autoCommit 은 원래 값으로 되돌린다.
 */
internal inline fun <T> Connection.inTransaction(block: (Connection) -> T): T {
    val previous = autoCommit
    autoCommit = false
    try {
        val result = block(this)
        commit()
        return result
    } catch (t: Throwable) {
        try {
            rollback()
        } catch (e: SQLException) {
            t.addSuppressed(e)
        }
        throw t
    } finally {
        try {
            autoCommit = previous
        } catch (e: SQLException) {
            // 연결이 끊긴 경우. 다음 대여 때 isValid 가 걸러낸다.
        }
    }
}

internal inline fun <T> Connection.prepared(sql: String, block: (PreparedStatement) -> T): T = prepareStatement(sql).use(block)

/** 한 행 한 열 조회. 행이 없으면 null. */
internal fun Connection.queryScalarLong(sql: String, bind: (PreparedStatement) -> Unit = {}): Long? = prepared(sql) { ps ->
    bind(ps)
    ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1).takeUnless { rs.wasNull() } else null }
}

internal fun <T> ResultSet.mapRows(transform: (ResultSet) -> T): List<T> {
    val out = ArrayList<T>()
    use { while (it.next()) out += transform(it) }
    return out
}

internal fun PreparedStatement.setInstant(index: Int, value: Instant?) {
    if (value == null) {
        setNull(index, Types.TIMESTAMP_WITH_TIMEZONE)
    } else {
        setObject(index, OffsetDateTime.ofInstant(value.toJavaInstant(), ZoneOffset.UTC))
    }
}

internal fun ResultSet.getInstant(index: Int): Instant? = getObject(index, OffsetDateTime::class.java)?.toInstant()?.toKotlinInstant()

internal fun PreparedStatement.setIntOrNull(index: Int, value: Int?) {
    if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
}

internal fun PreparedStatement.setShortOrNull(index: Int, value: Int?) {
    if (value == null) setNull(index, Types.SMALLINT) else setShort(index, value.toShort())
}

internal fun PreparedStatement.setLongOrNull(index: Int, value: Long?) {
    if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
}

internal fun PreparedStatement.setStringOrNull(index: Int, value: String?) {
    if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
}

internal fun ResultSet.getIntOrNull(index: Int): Int? = getInt(index).takeUnless { wasNull() }

internal fun ResultSet.getLongOrNull(index: Int): Long? = getLong(index).takeUnless { wasNull() }

internal fun Connection.textArray(values: Collection<String>): java.sql.Array = createArrayOf("text", values.toTypedArray())

internal fun Connection.intArray(values: Collection<Int>): java.sql.Array = createArrayOf("int4", values.toTypedArray())
