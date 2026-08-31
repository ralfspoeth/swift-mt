package io.github.ralfspoeth.xldr.swift.mt.it;

import io.github.ralfspoeth.xldr.server.Config;
import io.github.ralfspoeth.xldr.server.ServerMXBean;
import io.github.ralfspoeth.xldr.server.Watcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.management.JMX;
import javax.management.ObjectName;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole chain, from a file appearing in a feed to rows in a table: xldr's
 * server, this adapter found through {@code ServiceLoader}, an MT940 statement.
 * <p>
 * This test exists because nothing else covers the seam. The unit tests build
 * an adapter directly and never ask whether the server would have found it, and
 * xldr's own integration tests cannot reach for this module - the dependency
 * has to point this way round or the two projects could not be released in any
 * order. So the downstream project tests the integration, which is also where
 * the failure would be felt.
 * <p>
 * The connection source is a {@link DriverManager} call rather than a pool, on
 * purpose: it demonstrates the thing the {@code server} / {@code app} split was
 * for, that the watcher can be driven without the distribution's choices about
 * pooling, configuration or the command line.
 */
class SwiftFeedIT {

    /**
     * The file that makes a directory a feed. Written out rather than taken from
     * {@code server}, which stopped exporting the type at 0.51: the name is part
     * of what a deployment is promised, and a test that drives the server through
     * the file system should say it the way an operator does.
     */
    private static final String DELIVERY = "delivery.properties";

    private static final String JDBC_URL = "jdbc:h2:mem:swiftit;DB_CLOSE_DELAY=-1";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private Path root;
    private Watcher watcher;

    /**
     * The server says why a feed did not come up at DEBUG, and JUL's console
     * handler starts at INFO, so the one line worth having is the one thrown
     * away. This is done in code rather than left to {@code logging.properties}
     * alone so that it holds when the test is run from an IDE too, which does
     * not pass the build's system properties.
     */
    @BeforeAll
    static void showWhatTheServerIsThinking() {
        var handler = new ConsoleHandler();
        handler.setLevel(Level.ALL);
        var log = Logger.getLogger("io.github.ralfspoeth.xldr");
        log.addHandler(handler);
        log.setLevel(Level.ALL);
    }

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("swift-root");
        // the same file system as the feed, so ATOMIC_MOVE works
        Path staging = Files.createTempDirectory("swift-staging");
        try (var conn = DriverManager.getConnection(JDBC_URL);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists booking");
            stmt.execute("create table booking(value_date varchar(6), side varchar(1), info varchar(400))");
        }
        var props = new Properties();
        props.setProperty("xldr.roots", root.toString());
        props.setProperty("xldr.scanInterval", "1");
        props.setProperty("xldr.maxConcurrentLoads", "1");
        props.setProperty("jdbc.url", JDBC_URL);

        // ConnectionSource is a functional interface, so the whole of the
        // "bring your own database access" story is this lambda
        watcher = Watcher.watch(Config.of(props), () -> DriverManager.getConnection(JDBC_URL));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (watcher != null) {
            watcher.close();
        }
    }

    /**
     * One row per booking, from a statement dropped into a feed.
     */
    @Test
    @Timeout(60)
    void loadsAnMt940StatementDroppedIntoAfeed() throws Exception {
        var feed = Files.createDirectory(root.resolve("statements"));
        // two files now: how the statements arrive, and what to do with them.
        // The delivery file is what makes the directory a feed at all.
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.sta\n");
        Files.writeString(feed.resolve("spec.json"), SPEC);
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        deliver(feed, "statement.sta", MT940);

        await("rows to arrive", () -> bookings().size() == 2);
        assertEquals(
                List.of("260806|D|INVOICE 998234 FEES FOR JULY\nSUPPLIER XYZ SERVICES",
                        "260806|C|CREDIT RECEIVED FROM CLIENT ABC\nINV-2026-4412"),
                bookings());

        await("the input to be archived", () -> !archived(feed).isEmpty());
        assertTrue(archived(feed).getFirst().getFileName().toString().startsWith("statement"));
    }

    // ---- helpers ------------------------------------------------------------

    private static void deliver(Path feed, String name, String content) throws IOException {
        var staged = Files.writeString(Files.createTempDirectory("deliver").resolve(name), content);
        Files.move(staged, feed.resolve("in").resolve(name), ATOMIC_MOVE);
    }

    private static List<Path> archived(Path feed) {
        try (var files = Files.walk(feed.resolve("archive"))) {
            return files.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<String> bookings() {
        var rows = new ArrayList<String>();
        try (var conn = DriverManager.getConnection(JDBC_URL);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select value_date, side, info from booking order by value_date, side desc")) {
            while (rs.next()) {
                rows.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    /**
     * The timeout carries what the server made of the feed, because otherwise
     * every way of failing to bring one up looks the same from here - a
     * stopwatch running out - and the reason is somewhere in a log.
     */
    private void await(String what, BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(Duration.ofMillis(50));
        }
        throw new AssertionError("timed out waiting for " + what + "\n" + diagnosis());
    }

    /**
     * What is on disk, and what the server thinks it has. Between them these
     * separate the three ways this can fail: the files are not where the test
     * believes (the tree is wrong), they are there and the server has no feed
     * (it read one of them and refused), or it has the feed as PENDING (it read
     * the delivery file and refused the spec).
     */
    private String diagnosis() {
        var out = new StringBuilder("  root: ").append(root).append('\n');
        try (var tree = Files.walk(root)) {
            tree.sorted().forEach(p -> out.append("    ")
                    .append(root.relativize(p))
                    .append(Files.isDirectory(p) ? "/" : "")
                    .append('\n'));
        } catch (IOException e) {
            out.append("    cannot walk it: ").append(e).append('\n');
        }
        try {
            var status = JMX.newMXBeanProxy(
                    ManagementFactory.getPlatformMBeanServer(),
                    new ObjectName("io.github.ralfspoeth.xldr:type=Server"),
                    ServerMXBean.class);
            out.append("  activeFeeds=").append(status.getActiveFeeds())
                    .append(" filesWaiting=").append(status.getFilesWaiting()).append('\n');
            var feeds = status.getFeeds();
            if (feeds.isEmpty()) {
                out.append("    the server has registered no feed at all\n");
            }
            feeds.forEach((name, feed) -> out.append("    ").append(name)
                    .append(" state=").append(feed.state())
                    .append(" waiting=").append(feed.filesWaiting()).append('\n'));
        } catch (Exception e) {
            out.append("  no server bean: ").append(e).append('\n');
        }
        return out.toString();
    }

    private static final String MT940 = """
            {1:F01BANKDEFFXXXX0000000000}{2:O9401044260806BICODEMMXXXX00000000002608061044N}{4:
            :20:STMT20260806
            :25:DE12345678901234567890
            :60F:C260806EUR54320,50
            :61:2608060806D1250,00NTRFBRF-998234//NONREF
            :86:INVOICE 998234 FEES FOR JULY
            SUPPLIER XYZ SERVICES
            :61:2608060806C8500,00NTRFBRF-998235//NONREF
            :86:CREDIT RECEIVED FROM CLIENT ABC
            INV-2026-4412
            :62F:C260806EUR61570,50
            -}""";

    private static final String SPEC = """
            {
              "input": {
                "mimeType": "text/x-swift",
                "recordSelectors": [
                  {
                    "name": "booking",
                    "selector": ":61:~:86:",
                    "fieldSelectors": [
                      {"name": "valueDate", "selector": "~([0-9]{6}).*~1",      "type": "TEXT"},
                      {"name": "side",      "selector": "~[0-9]{10}([CD]).*~1", "type": "TEXT"},
                      {"name": "info",      "selector": "~1~.*~0",              "type": "TEXT"}
                    ]
                  }
                ]
              },
              "mapping": [
                {
                  "recordSelector": "booking",
                  "table": "booking",
                  "fieldMapping": [
                    {"fieldSelector": "valueDate", "column": "value_date"},
                    {"fieldSelector": "side",      "column": "side"},
                    {"fieldSelector": "info",      "column": "info"}
                  ]
                }
              ]
            }
            """;
}
