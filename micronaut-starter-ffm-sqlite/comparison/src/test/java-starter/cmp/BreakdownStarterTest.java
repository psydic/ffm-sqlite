package cmp;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static cmp.Scenarios.props;

class BreakdownStarterTest {
    @Test
    void breakdown() throws Exception {
        Assumptions.assumeFalse(System.getProperty("perf.seconds", "").isBlank(), "set -Dperf.seconds to run");
        Path db = Files.createDirectories(Path.of(System.getProperty("perf.dir"))).resolve("bd-starter.db");
        Files.deleteIfExists(db);
        try (ApplicationContext ctx = ApplicationContext.builder().deduceEnvironment(false).properties(props(
                "ffm-sqlite.datasources.default.path", db.toString(),
                "datasources.default.dialect", "SQLITE", "count.calls", System.getProperty("count.calls", "false"))).start()) {
            Breakdown.run("starter/ffm-sqlite-jdbc", ctx, Integer.getInteger("perf.seconds"));
        }
    }
}
