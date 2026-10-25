package io.github.revsnai.ffmsqlite.micronaut;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.exceptions.BeanInstantiationException;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.revsnai.ffmsqlite.micronaut.MicronautDataTest.one;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the findings of the starter's code review. */
class ReviewFixesTest {
    private static final String P = "ffm-sqlite.datasources.default.";

    @TempDir
    Path dir;

    private static ApplicationContext run(Object... keyValues) {
        Map<String, Object> props = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            props.put((String) keyValues[i], keyValues[i + 1]);
        }
        return ApplicationContext.builder().deduceEnvironment(false).properties(props).start();
    }

    private static String failure(Object... keyValues) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = assertThrows(BeanInstantiationException.class, () -> run(keyValues).close()); t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }

    @Test
    void rawBeginInACallbackIsRolledBack() throws Exception {
        try (ApplicationContext ctx = run(P + "path", dir.resolve("a.db").toString(), P + "maximum-pool-size", "1",
                P + "busy-timeout", "300ms")) {
            FfmSqliteDatabase db = ctx.getBean(FfmSqliteDatabase.class);
            db.withConnection(c -> c.createStatement().executeUpdate("CREATE TABLE t (x)"));
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> db.withConnection(c -> {
                c.createStatement().executeUpdate("BEGIN");
                return c.createStatement().executeUpdate("INSERT INTO t VALUES (1)");
            }));
            assertTrue(e.getMessage().contains("BEGIN"), e.getMessage());
            try (Connection c = db.getDataSource().getConnection(); Statement st = c.createStatement()) {
                assertEquals("0", one(st, "SELECT count(*) FROM t"));
            }
            try (var other = db.openConnection()) {   // used to fail: the stale transaction held the write lock
                other.createStatement().executeUpdate("INSERT INTO t VALUES (2)");
            }
        }
    }

    @Test
    void aConnectionClosedByTheCallbackLeavesThePool() throws Exception {
        try (ApplicationContext ctx = run(P + "path", ":memory:", P + "maximum-pool-size", "1")) {
            FfmSqliteDatabase db = ctx.getBean(FfmSqliteDatabase.class);
            db.withConnection(c -> {
                c.close();
                return null;
            });
            assertEquals(1L, (long) db.<Long>withConnection(c -> {
                try (var ps = c.prepareStatement("SELECT 1"); var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }));
        }
    }

    @Test
    void memoryDatabasesAreNotSharedBetweenContexts() throws Exception {
        try (ApplicationContext a = run(P + "path", ":memory:"); ApplicationContext b = run(P + "path", ":memory:")) {
            DataSource da = a.getBean(FfmSqliteDatabase.class).getDataSource();
            DataSource dbB = b.getBean(FfmSqliteDatabase.class).getDataSource();
            try (Connection c = da.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE from_a (x)");
            }
            try (Connection c = dbB.getConnection()) {
                assertFalse(c.getMetaData().getTables(null, null, "from_a", null).next());
            }
        }
    }

    @Test
    void invalidSettingsNameTheProperty() {
        assertTrue(failure(P + "path", ":memory:", P + "maximum-pool-size", "0").contains(P + "maximum-pool-size"));
        assertTrue(failure(P + "path", ":memory:", P + "busy-timeout", "30d").contains(P + "busy-timeout"));
        assertTrue(failure(P + "path", ":memory:", P + "statement-cache-size", "-1").contains(P + "statement-cache-size"));
        assertTrue(failure(P + "path", ":memory:", P + "connection-timeout", "10ms").contains(P + "connection-timeout"));
        assertTrue(failure(P + "path", ":memory:", P + "max-lifetime", "10s").contains(P + "max-lifetime"));
        assertTrue(failure(P + "path", "file::memory:?cache=shared").contains("use :memory:"));
        String owned = failure(P + "path", ":memory:", P + "properties.foreign_keys", "false");
        assertTrue(owned.contains(P + "properties.foreign"), owned);
    }

    @Test
    void openConnectionMatchesThePool() throws Exception {
        try (ApplicationContext ctx = run(P + "path", ":memory:", P + "busy-timeout", "1234ms",
                P + "pragmas.cache_size", "-777")) {
            FfmSqliteDatabase db = ctx.getBean(FfmSqliteDatabase.class);
            try (var c = db.openConnection(); var ps = c.prepareStatement("SELECT (SELECT * FROM pragma_busy_timeout), (SELECT * FROM pragma_cache_size)");
                 var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1234, rs.getInt(1));
                assertEquals(-777, rs.getInt(2));
            }
        }
    }

    @Test
    void healthRunsOffTheCallingThreadAndDoesNotQueueOnASaturatedPool() throws Exception {
        try (ApplicationContext ctx = run(P + "path", ":memory:", P + "maximum-pool-size", "1",
                P + "connection-timeout", "5s")) {
            FfmSqliteHealthIndicator indicator = ctx.getBean(FfmSqliteHealthIndicator.class);
            AtomicReference<String> thread = new AtomicReference<>();
            CompletableFuture<HealthResult> result = new CompletableFuture<>();
            indicator.getResult().subscribe(new Subscriber<>() {
                public void onSubscribe(Subscription s) {
                    s.request(1);
                }

                public void onNext(HealthResult r) {
                    thread.set(Thread.currentThread().getName());
                    result.complete(r);
                }

                public void onError(Throwable t) {
                    result.completeExceptionally(t);
                }

                public void onComplete() {
                }
            });
            assertEquals(HealthStatus.UP, result.get(5, TimeUnit.SECONDS).getStatus());
            assertNotEquals(Thread.currentThread().getName(), thread.get());

            DataSource pool = ctx.getBean(FfmSqliteDatabase.class).getDataSource();
            try (Connection held = pool.getConnection()) {
                CountDownLatch waiting = new CountDownLatch(1);
                Thread waiter = Thread.ofVirtual().start(() -> {
                    waiting.countDown();
                    try (Connection c = pool.getConnection()) {
                        c.isValid(0);
                    } catch (Exception ignored) {
                        // timed out: fine
                    }
                });
                waiting.await();
                while (ctx.getBean(FfmSqliteDatabase.class).getDataSource().getHikariPoolMXBean().getThreadsAwaitingConnection() == 0) {
                    Thread.sleep(5);
                }
                long start = System.nanoTime();
                HealthResult saturated = indicator.check();
                assertTrue(System.nanoTime() - start < 1_000_000_000L, "did not wait for a connection");
                assertEquals(HealthStatus.UP, saturated.getStatus());
                assertEquals("skipped: all connections in use",
                        ((Map<?, ?>) ((Map<?, ?>) saturated.getDetails()).get("default")).get("check"));
                held.close();
                waiter.join();
            }
        }
    }
}
