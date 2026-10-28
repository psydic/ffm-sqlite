package io.github.revsnai.ffmsqlite.micronaut;

import io.micronaut.context.ApplicationContext;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthResult;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the issues found in the third (0.2.4) review. */
class ReviewFixes3Test {
    private static final String P = "ffm-sqlite.datasources.default.";

    @Test
    void healthDoesNotQueueWhenEveryConnectionIsHeldAndNobodyWaits() throws Exception {
        try (ApplicationContext ctx = ApplicationContext.builder().deduceEnvironment(false)
                .properties(Map.of(P + "path", ":memory:", P + "maximum-pool-size", "1", P + "connection-timeout", "5s"))
                .start()) {
            FfmSqliteHealthIndicator indicator = ctx.getBean(FfmSqliteHealthIndicator.class);
            DataSource pool = ctx.getBean(FfmSqliteDatabase.class).getDataSource();
            try (Connection held = pool.getConnection()) {   // a long transaction; no other caller is queued
                long start = System.nanoTime();
                HealthResult result = indicator.check();
                assertTrue(System.nanoTime() - start < 1_000_000_000L, "joined the queue for connection-timeout");
                assertEquals(HealthStatus.UP, result.getStatus());
                assertEquals("skipped: all connections in use",
                        ((Map<?, ?>) ((Map<?, ?>) result.getDetails()).get("default")).get("check"));
            }
            assertEquals(HealthStatus.UP, indicator.check().getStatus());
            assertTrue(((Map<?, ?>) ((Map<?, ?>) indicator.check().getDetails()).get("default")).get("check") == null,
                    "a pool with an idle connection is probed");
        }
    }
}
