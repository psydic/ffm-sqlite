package cmp;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static cmp.Scenarios.props;

/** mvn test -Dtest=PerfOfficialTest -Dperf.seconds=300 -Dperf.dir=/path (official stack, xerial). */
class PerfOfficialTest {
    @Test
    void perf() throws Exception {
        Assumptions.assumeFalse(System.getProperty("perf.seconds", "").isBlank(), "set -Dperf.seconds to run");
        Path dir = Files.createDirectories(Path.of(System.getProperty("perf.dir", "target/perf")));
        Path db = dir.resolve("official.db");
        Files.deleteIfExists(db);
        try (ApplicationContext ctx = ApplicationContext.builder().deduceEnvironment(false).properties(props(
                "datasources.default.url", "jdbc:sqlite:" + db + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&synchronous=NORMAL",
                "datasources.default.driver-class-name", "org.sqlite.JDBC",
                "datasources.default.dialect", "SQLITE",
                "datasources.default.maximum-pool-size", 10,
                "datasources.default.minimum-idle", 10)).start()) {
            PerfWorkload.run("official (micronaut-jdbc-hikari + micronaut-jdbc-sqlite + xerial)", ctx,
                    Integer.getInteger("perf.threads", 8), Integer.getInteger("perf.warmup", 30), Integer.getInteger("perf.seconds"));
        }
    }
}
