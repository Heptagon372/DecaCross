package kr.decacross.daemon.process

/**
 * 종료 프로토콜 테스트용 가짜 마크 서버. 실제 JVM 프로세스로 뜬다.
 * - 기동 후 "Done (0.1s)! For help" 출력
 * - stdin "save-all" → "Saved the game", "stop" → 종료(exit 0)
 * - 인자 "ignore-stop" 이면 stop 을 무시한다 (강제 종료 경로 테스트)
 */
object FakeServer {
    @JvmStatic
    fun main(args: Array<String>) {
        val ignoreStop = args.contains("ignore-stop")
        println("[00:00:00 INFO]: Starting minecraft server version fake")
        println("[00:00:00 INFO]: Done (0.1s)! For help, type \"help\"")
        System.out.flush()
        while (true) {
            val line = readlnOrNull() ?: break
            when (line.trim()) {
                "save-all" -> println("[00:00:01 INFO]: Saved the game")

                "stop" -> {
                    if (ignoreStop) {
                        println("[00:00:01 INFO]: ignoring stop")
                    } else {
                        println("[00:00:01 INFO]: Stopping the server")
                        System.out.flush()
                        return
                    }
                }

                else -> println("[00:00:01 INFO]: echo $line")
            }
            System.out.flush()
        }
    }
}
