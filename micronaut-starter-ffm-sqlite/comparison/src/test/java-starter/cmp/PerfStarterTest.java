package cmp;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static cmp.Scenarios.props;

/** mvn test -Dstarter -Dtest=PerfStarterTest -Dperf.seconds=300 -Dperf.dir=/path (micronaut-starter-ffm-sqlite). */
class PerfStarterTest {
    @Test
    void perf() throws Exception {
        Assumptions.assumeFalse(System.getProperty("perf.seconds", "").isBlank(), "set -Dperf.seconds to run");
        Path dir = Files.createDirectories(Path.of(System.getProperty("perf.dir", "target/perf")));
        Path db = dir.resolve("starter.db");
        Files.deleteIfExists(db);
        try (ApplicationContext ctx = ApplicationContext.builder().deduceEnvironment(false).properties(props(
                "ffm-sqlite.datasources.default.path", db.toString(),   // defaults: WAL, NORMAL, foreign keys, 5 s
                "ffm-sqlite.datasources.default.maximum-pool-size", 10,
                "datasources.default.dialect", "SQLITE")).start()) {
            PerfWorkload.run("starter (micronaut-starter-ffm-sqlite + ffm-sqlite-jdbc)", ctx,
                    Integer.getInteger("perf.threads", 8), Integer.getInteger("perf.warmup", 30), Integer.getInteger("perf.seconds"));
        }
    }
}
