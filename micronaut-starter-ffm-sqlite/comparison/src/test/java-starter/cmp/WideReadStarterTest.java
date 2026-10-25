package cmp;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static cmp.Scenarios.props;

/** mvn test -Dstarter [-Dcaps] -Dtest=WideReadStarterTest -Dperf.seconds=30 -Dwide.template=…/wide-1m.db */
class WideReadStarterTest {
    @Test
    void wideReads() throws Exception {
        Assumptions.assumeFalse(System.getProperty("perf.seconds", "").isBlank(), "set -Dperf.seconds to run");
        boolean caps = Boolean.getBoolean("caps");
        Path db = WideReadBench.copyTemplate(caps ? "wide-starter-caps.db" : "wide-starter.db");
        try (ApplicationContext ctx = ApplicationContext.builder().deduceEnvironment(false).properties(props(
                "ffm-sqlite.datasources.default.path", db.toString(),
                "ffm-sqlite.datasources.default.synchronous", "FULL",   // journal-mode WAL, foreign keys, 5 s: defaults
                "ffm-sqlite.datasources.default.maximum-pool-size", 16,
                "datasources.default.dialect", "SQLITE")).start()) {
            WideReadBench.run(caps ? "starter + micronaut-jdbc-sqlite (no read-only toggle)" : "starter (micronaut-starter-ffm-sqlite " + System.getProperty("ffm-sqlite.version", "?") + ")",
                    ctx, WideReadBench.threadCounts(), Integer.getInteger("perf.warmup"), Integer.getInteger("perf.seconds"));
        }
    }
}
