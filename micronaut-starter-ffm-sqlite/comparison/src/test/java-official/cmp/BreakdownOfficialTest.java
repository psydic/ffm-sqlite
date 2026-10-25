package cmp;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static cmp.Scenarios.props;

class BreakdownOfficialTest {
    @Test
    void breakdown() throws Exception {
        Assumptions.assumeFalse(System.getProperty("perf.seconds", "").isBlank(), "set -Dperf.seconds to run");
        Path db = Files.createDirectories(Path.of(System.getProperty("perf.dir"))).resolve("bd-official.db");
        Files.deleteIfExists(db);
        try (ApplicationContext ctx = ApplicationContext.builder().deduceEnvironment(false).properties(props(
                "datasources.default.url", "jdbc:sqlite:" + db + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&synchronous=NORMAL",
                "datasources.default.driver-class-name", "org.sqlite.JDBC",
                "datasources.default.dialect", "SQLITE", "count.calls", System.getProperty("count.calls", "false"))).start()) {
            Breakdown.run("official/xerial", ctx, Integer.getInteger("perf.seconds"));
        }
    }
}
