package cmp;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static cmp.Scenarios.props;

/** mvn test -Dtest=WideReadOfficialTest -Dperf.seconds=30 -Dwide.template=…/wide-1m.db */
class WideReadOfficialTest {
    @Test
    void wideReads() throws Exception {
        Assumptions.assumeFalse(System.getProperty("perf.seconds", "").isBlank(), "set -Dperf.seconds to run");
        Path db = WideReadBench.copyTemplate("wide-official.db");
        try (ApplicationContext ctx = ApplicationContext.builder().deduceEnvironment(false).properties(props(
                "datasources.default.url", "jdbc:sqlite:" + db + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&synchronous=FULL",
                "datasources.default.driver-class-name", "org.sqlite.JDBC",
                "datasources.default.dialect", "SQLITE",
                "datasources.default.maximum-pool-size", 16,
                "datasources.default.minimum-idle", 16)).start()) {
            WideReadBench.run("official (micronaut-jdbc-hikari + micronaut-jdbc-sqlite + xerial)", ctx, WideReadBench.threadCounts(),
                    Integer.getInteger("perf.warmup"), Integer.getInteger("perf.seconds"));
        }
    }
}
