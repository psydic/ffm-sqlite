package io.github.revsnai.ffmsqlite.micronaut;

import io.github.revsnai.ffmsqlite.db.ResultSet;
import io.github.revsnai.ffmsqlite.db.SQLiteException;
import io.github.revsnai.ffmsqlite.db.Statement;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the memory-safety review of 0.2.5. */
class ReviewFixes4Test {
    private static final String P = "ffm-sqlite.datasources.default.";

    @TempDir
    Path dir;

    /**
     * Closing the pool (as on shutdown) aborts and closes in-use connections from another thread. It used to free
     * the native connection under a running withConnection callback (a use-after-free under AddressSanitizer); now
     * the close waits for the callback, which the abort interrupts.
     */
    @Test
    void shuttingDownDuringWithConnectionInterruptsTheCallbackInsteadOfFreeingItsConnection() throws Exception {
        ApplicationContext ctx = ApplicationContext.builder().deduceEnvironment(false)
                .properties(Map.of(P + "path", dir.resolve("a.db").toString(), P + "maximum-pool-size", "1"))
                .start();
        FfmSqliteDatabase db = ctx.getBean(FfmSqliteDatabase.class);
        CountDownLatch stepping = new CountDownLatch(1);
        CompletableFuture<Throwable> outcome = CompletableFuture.supplyAsync(() -> {
            try {
                db.withConnection(conn -> {
                    long rows = 0;
                    for (int round = 0; round < 1000; round++) {
                        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                                "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 200000) "
                                        + "SELECT i, printf('%050d', i) FROM n")) {
                            while (rs.next()) {
                                rs.getString(2);
                                if (++rows == 1000) {
                                    stepping.countDown();
                                }
                            }
                        }
                    }
                    return rows;
                });
                return null;
            } catch (Throwable t) {
                return t;
            }
        });
        assertTrue(stepping.await(30, TimeUnit.SECONDS));
        ctx.close();   // FfmSqliteDatabase.close -> HikariDataSource.close -> abort + close of the in-use connection
        Throwable t = outcome.get(60, TimeUnit.SECONDS);
        assertInstanceOf(SQLiteException.class, t, String.valueOf(t));
        assertTrue(t.getMessage().contains("interrupt"), t.getMessage());
    }
}
