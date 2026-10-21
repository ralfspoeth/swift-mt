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
import java.time.LocalDateTime;
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
     * Holds the level {@code logging.properties} asks for when the test is run
     * from an IDE, which does not pass the build's system properties and so
     * never reads that file.
     * <p>
     * It used to add a {@link ConsoleHandler} of its own as well, and turn the
     * logger up to {@code ALL} so that the DEBUG line explaining why a feed did
     * not come up would be printed. Both are gone. The handler was a second one
     * on top of the one {@code logging.properties} configures, and a record
     * goes to its logger's handlers and then to every ancestor's regardless of
     * their levels - so under Maven every line the server logged was printed
     * twice. And {@code ALL} bought the DEBUG line at the price of the hundreds
     * of them a one-second scan interval produces; what explains a failure is
     * {@link #diagnosis()}, assembled at the moment the wait gives up.
     * <p>
     * Raising this to {@code ALL} is still the thing to do when a feed will not
     * come up - from here for an IDE run, in {@code logging.properties} for a
     * build.
     */
    @BeforeAll
    static void quietenTheServer() {
        Logger.getLogger("io.github.ralfspoeth.xldr").setLevel(Level.INFO);
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
            // the second table's columns are typed, which is the point of it:
            // a varchar would accept whatever the adapter handed over and prove
            // nothing about the conversion
            stmt.execute("drop table if exists statement_line");
            stmt.execute("""
                    create table statement_line(
                        sender varchar(40), value_date timestamp, side varchar(1),
                        amount decimal(15,2), info varchar(400))""");
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

    /**
     * The worked example of README.md, run.
     *
     * <p>The test above covers the chain and settles for text: every column is a
     * varchar, so it says nothing about what the declared types do. This one is
     * the documented spec instead - {@code TEMPORAL} and {@code DECIMAL}, with
     * the {@code dateFormat}, {@code numberFormat} and {@code locale} that make
     * them readable - landing in columns that would refuse the wrong thing. A
     * {@code timestamp} column will not take {@code "260806"} and a
     * {@code decimal} will not take {@code "1250,00"}, so the assertions are
     * only reached if the conversion happened.
     *
     * <p>It exists because the example was prose and prose does not run. Two
     * things were wrong with it, both found by writing this: it declared
     * {@code "DATE"}, which xldr renamed {@code TEMPORAL} at 0.47 and now
     * refuses by name, and it called block 1 the account.
     *
     * <p>That second one is the more interesting. The account is {@code :25:},
     * and a {@code :61:~:86:} record cannot reach it: a block-4 field selector
     * addresses a tag <em>of the record</em>, and {@code :25:} is a header tag
     * that appears once above the bookings. {@code 1~.*~0} reads the whole
     * basic header, which names the sending institution, so that is what it is
     * called here. Carrying the account down onto every booking row would be a
     * feature this adapter does not have.
     */
    @Test
    @Timeout(60)
    void loadsTheWorkedExampleFromTheReadme() throws Exception {
        var feed = Files.createDirectory(root.resolve("worked-example"));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.sta\n");
        Files.writeString(feed.resolve("spec.json"), WORKED_EXAMPLE);
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        deliver(feed, "statement.sta", MT940);

        await("rows to arrive", () -> statementLines().size() == 2);
        assertEquals(
                List.of("F01BANKDEFFXXXX0000000000|2026-08-06T00:00|D|1250.00",
                        "F01BANKDEFFXXXX0000000000|2026-08-06T00:00|C|8500.00"),
                statementLines());
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
     * Read back as {@code LocalDateTime} and {@code BigDecimal} rather than as
     * strings, so that a column holding the right characters for the wrong
     * reason cannot pass.
     */
    private static List<String> statementLines() {
        var rows = new ArrayList<String>();
        try (var conn = DriverManager.getConnection(JDBC_URL);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("""
                     select sender, value_date, side, amount from statement_line
                     order by value_date, side desc""")) {
            while (rs.next()) {
                rows.add(rs.getString("sender")
                        + "|" + rs.getObject("value_date", LocalDateTime.class)
                        + "|" + rs.getString("side")
                        + "|" + rs.getBigDecimal("amount"));
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

    /**
     * The spec README.md prints, corrected and given the {@code mapping} the
     * prose leaves out. Keep the two in step: the point of this test is that
     * the documented example is one that has run.
     * <p>
     * Read the selectors against the message above. {@code :61:} is tag 0 of
     * the record and {@code :86:} tag 1, so a pattern with no tag in front of
     * it reads the statement line: the first six digits of
     * {@code 2608060806D1250,00NTRF...} are the value date, the ten digits are
     * value date and entry date together, and the letter after them is the
     * side. {@code ~1~.*~0} takes the whole of the information tag, both lines
     * of it, because the patterns are compiled with {@code DOTALL}.
     */
    private static final String WORKED_EXAMPLE = """
            {
              "input": {
                "mimeType": "text/x-swift",
                "properties": {"dateFormat": "yyMMdd", "numberFormat": "#0.00", "locale": "de-DE"},
                "recordSelectors": [
                  {
                    "name": "booking",
                    "selector": ":61:~:86:",
                    "fieldSelectors": [
                      {"name": "sender",    "selector": "1~.*~0",                       "type": "TEXT"},
                      {"name": "valueDate", "selector": "~([0-9]{6}).*~1",              "type": "TEMPORAL"},
                      {"name": "amount",    "selector": "~[0-9]{10}[CD]([0-9,]+)N.*~1", "type": "DECIMAL"},
                      {"name": "side",      "selector": "~[0-9]{10}([CD]).*~1",         "type": "TEXT"},
                      {"name": "info",      "selector": "~1~.*~0",                      "type": "TEXT"}
                    ]
                  }
                ]
              },
              "mapping": [
                {
                  "recordSelector": "booking",
                  "table": "statement_line",
                  "fieldMapping": [
                    {"fieldSelector": "sender",    "column": "sender"},
                    {"fieldSelector": "valueDate", "column": "value_date"},
                    {"fieldSelector": "amount",    "column": "amount"},
                    {"fieldSelector": "side",      "column": "side"},
                    {"fieldSelector": "info",      "column": "info"}
                  ]
                }
              ]
            }
            """;

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
