package kr.decacross.compat.resolve

/**
 * 권장 서버 RAM (명세 §8.1):
 * `권장 = min(시스템메모리 × 0.5, 상한) − hostOverheadMb − 클라이언트예약(기본 2048)`, 최소 [floorMb].
 *
 * # 불변식 (I5)
 * - [hostOverheadMb] 가 커지면 결과는 단조 감소한다. 런처/데몬이 쓰는 메모리를 서버에 주지 않는다.
 * - 결과는 512MB 단위로 내림한다 (Xmx 값으로 바로 쓰기 좋게).
 */
public fun recommendedRamMb(
    systemTotalMb: Int,
    hostOverheadMb: Int,
    clientReserveMb: Int = 2048,
    capMb: Int = 10 * 1024,
    floorMb: Int = 1024,
): Int {
    val half = systemTotalMb / 2
    val raw = minOf(half, capMb) - hostOverheadMb - clientReserveMb
    val floored = (raw / 512) * 512
    return maxOf(floorMb, floored)
}
