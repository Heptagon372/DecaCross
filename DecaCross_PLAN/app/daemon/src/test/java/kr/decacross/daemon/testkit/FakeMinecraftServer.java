package kr.decacross.daemon.testkit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 테스트용 가짜 마크 서버 (jar 로 묶어 `java -jar fake.jar nogui` 로 띄운다). 표준 라이브러리만 쓴다 (Kotlin 런타임 없음).
 * 익명·내부 클래스를 만들지 마라: FakeServerJar 는 이 클래스 파일 하나만 jar 에 넣는다 (람다는 괜찮다).
 *
 * <p>출력은 Paper 콘솔 형식 {@code [HH:mm:ss INFO]: ...} 을 흉내 낸다. 작업 디렉터리의 {@code fake-events.txt} 에 사건을 적는다
 * (start, eula-missing, done, stdin:&lt;줄&gt;, stdin-eof, stop-exit, hook-start, hook-end, max-life-exit).
 *
 * <p>시스템 프로퍼티:
 * <ul>
 *   <li>{@code fake.startupMs} (0) Done 전 지연</li>
 *   <li>{@code fake.requireEula} (false) eula.txt 에 eula=true 가 없으면 Paper 문구 출력 후 종료 코드 0</li>
 *   <li>{@code fake.noSaveReply} (false) save-all 에 "Saved the game" 을 찍지 않음</li>
 *   <li>{@code fake.ignoreStop} (false) stop 무시</li>
 *   <li>{@code fake.stopMs} (200) stop 후 종료까지 지연</li>
 *   <li>{@code fake.hookMs} (300) 셧다운 훅 지연 (월드 저장 흉내)</li>
 *   <li>{@code fake.exitOnEof} (false) stdin EOF 면 종료 코드 4 (기본은 Paper 처럼 계속 실행)</li>
 *   <li>{@code fake.maxLifeMs} (120000) 안전장치: 이 시간 뒤 종료 코드 3 (고아 프로세스 방지)</li>
 * </ul>
 */
public final class FakeMinecraftServer {
    private static final Object LOCK = new Object();
    private static final Path EVENTS = Paths.get("fake-events.txt").toAbsolutePath();
    private static final PrintStream OUT = System.out;

    private FakeMinecraftServer() {
    }

    public static void main(String[] args) throws Exception {
        long maxLifeMs = Long.getLong("fake.maxLifeMs", 120_000L);
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(maxLifeMs);
            } catch (InterruptedException e) {
                return;
            }
            event("max-life-exit");
            System.exit(3);
        }, "fake-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        long hookMs = Long.getLong("fake.hookMs", 300L);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            event("hook-start");
            try {
                Thread.sleep(hookMs);
            } catch (InterruptedException ignored) {
                // 훅 지연 중단은 무시한다
            }
            event("hook-end");
        }, "fake-shutdown-hook"));

        event("start pid=" + ProcessHandle.current().pid());
        log("Starting minecraft server version fake");
        log("fake.cwd=" + Paths.get("").toAbsolutePath());
        log("fake.maxMemoryMb=" + (Runtime.getRuntime().maxMemory() / (1024 * 1024)));
        log("fake.args=" + String.join(" ", args));
        log("fake.fileEncoding=" + System.getProperty("file.encoding") + " stdoutEncoding=" + System.getProperty("stdout.encoding"));
        log("fake.korean=한글 출력 확인");

        if (Boolean.getBoolean("fake.requireEula") && !eulaAccepted()) {
            event("eula-missing");
            log("You need to agree to the EULA in order to run the server. Go to eula.txt for more info.");
            return;
        }

        Thread.sleep(Long.getLong("fake.startupMs", 0L));
        log("Done preparing level \"world\" (0.001s)");
        log("Done (0.123s)! For help, type \"help\"");
        event("done");

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            event("stdin:" + line);
            String cmd = line.trim();
            if (cmd.equals("save-all")) {
                log("Saving the game (this may take a moment!)");
                if (!Boolean.getBoolean("fake.noSaveReply")) {
                    log("Saved the game");
                }
            } else if (cmd.equals("stop")) {
                if (Boolean.getBoolean("fake.ignoreStop")) {
                    continue;
                }
                log("Stopping server");
                Thread.sleep(Long.getLong("fake.stopMs", 200L));
                event("stop-exit");
                System.exit(0);
            } else if (cmd.startsWith("say ")) {
                log("[Server] " + cmd.substring(4));
            } else {
                log("Unknown or incomplete command. Type \"/help\" for help.");
            }
        }
        event("stdin-eof");
        if (Boolean.getBoolean("fake.exitOnEof")) {
            System.exit(4);
        }
        // Paper 처럼 stdin 이 끝나도 서버는 계속 돈다 (watchdog 이 maxLifeMs 뒤 종료)
        Thread.sleep(Long.MAX_VALUE);
    }

    private static boolean eulaAccepted() {
        try {
            List<String> lines = Files.readAllLines(Paths.get("eula.txt"), StandardCharsets.ISO_8859_1);
            for (String l : lines) {
                if (l.trim().equalsIgnoreCase("eula=true")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private static void log(String message) {
        synchronized (LOCK) {
            OUT.println("[12:00:00 INFO]: " + message);
            OUT.flush();
        }
    }

    private static void event(String what) {
        synchronized (LOCK) {
            try {
                Files.writeString(EVENTS, what + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // 사건 기록 실패는 테스트 판정에서 드러난다
            }
        }
    }
}
