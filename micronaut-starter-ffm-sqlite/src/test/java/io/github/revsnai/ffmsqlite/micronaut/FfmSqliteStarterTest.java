package io.github.revsnai.ffmsqlite.micronaut;

import io.github.revsnai.ffmsqlite.db.PreparedStatement;
import io.github.revsnai.ffmsqlite.db.ResultSet;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.exceptions.BeanInstantiationException;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import io.micronaut.health.HealthStatus;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.management.health.indicator.HealthResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.github.revsnai.ffmsqlite.micronaut.MicronautDataTest.one;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmSqliteStarterTest {

    @TempDir
    Path dir;

    private static ApplicationContext run(Object... keyValues) {
        Map<String, Object> props = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            props.put((String) keyValues[i], keyValues[i + 1]);
        }
        // No "test" environment, so application-test.yml (used by MicronautDataTest) stays out.
        return ApplicationContext.builder().deduceEnvironment(false).properties(props).start();
    }

    private static DataSource pool(ApplicationContext ctx) {
        return ctx.getBean(FfmSqliteDatabase.class).getDataSource();
    }

    private static DataSource pool(ApplicationContext ctx, String name) {
        return ctx.getBean(FfmSqliteDatabase.class, Qualifiers.byName(name)).getDataSource();
    }

    @Test
    void fileDatabaseGetsTheDefaultsAndPragmas() throws Exception {
        Path file = dir.resolve("nested/dirs/app.db");
        try (ApplicationContext ctx = run(
                "ffm-sqlite.datasources.default.path", file.toString(),
                "ffm-sqlite.datasources.default.busy-timeout", "2s",
                "ffm-sqlite.datasources.default.pragmas.cache_size", "-20000",
                "ffm-sqlite.datasources.default.pragmas.analysis-limit", "400")) {
            FfmSqliteDatabase db = ctx.getBean(FfmSqliteDatabase.class);
            DataSource ds = pool(ctx);
            // Micronaut Data (on the test class path) wraps DataSource beans to hand out the current transaction's
            // connection; underneath is the starter's pool.
            assertSame(db.getDataSource(), DelegatingDataSource.unwrapDataSource(ctx.getBean(DataSource.class)));
            assertTrue(Files.exists(file), "parent directories created, database opened at startup");
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                assertEquals("wal", one(st, "PRAGMA journal_mode"));
                assertEquals("1", one(st, "PRAGMA synchronous"));
                assertEquals("1", one(st, "PRAGMA foreign_keys"));
                assertEquals("2000", one(st, "PRAGMA busy_timeout"));
                assertEquals("-20000", one(st, "PRAGMA cache_size"));
                assertEquals("400", one(st, "PRAGMA analysis_limit"));
            }
            // The core API gets the same settings, pooled or not.
            assertEquals(-20000L, (long) db.<Long>withConnection(conn -> queryLong(conn, "PRAGMA cache_size")));
            try (var conn = db.openConnection()) {
                assertEquals(400L, queryLong(conn, "PRAGMA analysis_limit"));
                assertEquals(2000L, queryLong(conn, "PRAGMA busy_timeout"));
            }
        }
    }

    private static long queryLong(io.github.revsnai.ffmsqlite.db.Connection conn, String sql) throws io.github.revsnai.ffmsqlite.db.SQLiteException {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    @Test
    void typedSettingsOverrideTheDefaults() throws Exception {
        try (ApplicationContext ctx = run(
                "ffm-sqlite.datasources.default.path", dir.resolve("a.db").toString(),
                "ffm-sqlite.datasources.default.journal-mode", "TRUNCATE",
                "ffm-sqlite.datasources.default.synchronous", "FULL",
                "ffm-sqlite.datasources.default.foreign-keys", "false")) {
            try (Connection c = pool(ctx).getConnection(); Statement st = c.createStatement()) {
                assertEquals("truncate", one(st, "PRAGMA journal_mode"));
                assertEquals("2", one(st, "PRAGMA synchronous"));
                assertEquals("0", one(st, "PRAGMA foreign_keys"));
            }
        }
    }

    @Test
    void namedDatasources() throws Exception {
        try (ApplicationContext ctx = run(
                "ffm-sqlite.datasources.default.path", dir.resolve("main.db").toString(),
                "ffm-sqlite.datasources.reports.path", dir.resolve("reports.db").toString(),
                "ffm-sqlite.datasources.reports.maximum-pool-size", "2")) {
            DataSource main = pool(ctx);
            DataSource reports = pool(ctx, "reports");
            assertSame(reports, DelegatingDataSource.unwrapDataSource(ctx.getBean(DataSource.class, Qualifiers.byName("reports"))));
            assertFalse(main == reports);
            assertEquals(2, ctx.getBeansOfType(DataSource.class).size());
            assertEquals(dir.resolve("reports.db").toString(),
                    ctx.getBean(FfmSqliteDatabase.class, Qualifiers.byName("reports")).getPath());
            try (Connection c = reports.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE r (x)");
            }
            try (Connection c = main.getConnection()) {
                assertFalse(c.getMetaData().getTables(null, null, "r", null).next());
            }
        }
    }

    @Test
    void memoryDatabaseIsSharedByThePool() throws Exception {
        try (ApplicationContext ctx = run("ffm-sqlite.datasources.default.path", ":memory:",
                "ffm-sqlite.datasources.default.minimum-idle", "0",
                "ffm-sqlite.datasources.default.idle-timeout", "10s")) {
            DataSource ds = pool(ctx);
            try (Connection a = ds.getConnection(); Connection b = ds.getConnection()) {
                try (Statement st = a.createStatement()) {
                    st.executeUpdate("CREATE TABLE t (x); INSERT INTO t VALUES (1), (2)");
                }
                try (Statement st = b.createStatement()) {
                    assertEquals("2", one(st, "SELECT count(*) FROM t"));
                }
            }
            // Survives the pool closing all its connections: the anchor keeps the database.
            ctx.getBean(FfmSqliteDatabase.class).getDataSource().getHikariPoolMXBean().softEvictConnections();
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                assertEquals("2", one(st, "SELECT count(*) FROM t"));
            }
        }
        // A new context gets a new, empty database.
        try (ApplicationContext ctx = run("ffm-sqlite.datasources.default.path", ":memory:");
             Connection c = pool(ctx).getConnection()) {
            assertFalse(c.getMetaData().getTables(null, null, "t", null).next());
        }
    }

    @Test
    void readOnly() throws Exception {
        Path file = dir.resolve("ro.db");
        try (ApplicationContext ctx = run("ffm-sqlite.datasources.default.path", file.toString());
             Connection c = pool(ctx).getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (x); INSERT INTO t VALUES (1)");
        }
        try (ApplicationContext ctx = run("ffm-sqlite.datasources.default.path", file.toString(),
                "ffm-sqlite.datasources.default.read-only", "true");
             Connection c = pool(ctx).getConnection(); Statement st = c.createStatement()) {
            assertEquals("1", one(st, "SELECT count(*) FROM t"));
            assertThrows(SQLException.class, () -> st.executeUpdate("INSERT INTO t VALUES (2)"));
        }
    }

    @Test
    void driverPropertiesPassThrough() throws Exception {
        try (ApplicationContext ctx = run("ffm-sqlite.datasources.default.path", ":memory:",
                "ffm-sqlite.datasources.default.properties.date_class", "TEXT");
             Connection c = pool(ctx).getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (at)");
            try (var ps = c.prepareStatement("INSERT INTO t VALUES (?)")) {
                ps.setTimestamp(1, Timestamp.valueOf("2026-10-25 12:30:00"));
                ps.executeUpdate();
            }
            assertEquals("text", one(st, "SELECT typeof(at) FROM t"));
        }
    }

    @Test
    void startupFailsOnBadConfiguration() {
        Exception missing = assertThrows(BeanInstantiationException.class,
                () -> run("ffm-sqlite.datasources.default.read-only", "true").close());
        assertTrue(missing.getMessage().contains("ffm-sqlite.datasources.default.path is required"), missing.getMessage());

        Exception badPragma = assertThrows(BeanInstantiationException.class,
                () -> run("ffm-sqlite.datasources.default.path", ":memory:",
                        "ffm-sqlite.datasources.default.pragmas.1st", "1").close());
        assertTrue(badPragma.getMessage().contains("not a PRAGMA name: 1st"), badPragma.getMessage());

        Exception noFile = assertThrows(BeanInstantiationException.class,
                () -> run("ffm-sqlite.datasources.default.path", dir.resolve("missing.db").toString(),
                        "ffm-sqlite.datasources.default.read-only", "true").close());
        assertTrue(noFile.getMessage().contains("cannot open database default"), noFile.getMessage());
    }

    @Test
    void withConnectionUsesTheCoreApiAndRejectsOpenTransactions() throws Exception {
        try (ApplicationContext ctx = run("ffm-sqlite.datasources.default.path", ":memory:")) {
            FfmSqliteDatabase db = ctx.getBean(FfmSqliteDatabase.class);
            db.withConnection(conn -> {
                conn.createStatement().executeUpdate("CREATE TABLE t (x)");
                conn.createFunction("twice", 1, args -> args.getLong(0) * 2);
                return null;
            });
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> db.withConnection(conn -> {
                conn.setAutoCommit(false);
                conn.createStatement().executeUpdate("INSERT INTO t VALUES (1)");
                return null;
            }));
            assertTrue(e.getMessage().contains("rolled back"), e.getMessage());
            assertThrows(ArithmeticException.class, () -> db.withConnection(conn -> {
                conn.setAutoCommit(false);
                conn.createStatement().executeUpdate("INSERT INTO t VALUES (2)");
                throw new ArithmeticException();
            }));
            try (Connection c = db.getDataSource().getConnection(); Statement st = c.createStatement()) {
                assertEquals("0", one(st, "SELECT count(*) FROM t"));
                assertTrue(c.getAutoCommit());
            }
        }
    }

    @Test
    void concurrentWritersWaitForEachOther() throws Exception {
        try (ApplicationContext ctx = run("ffm-sqlite.datasources.default.path", dir.resolve("w.db").toString(),
                "ffm-sqlite.datasources.default.maximum-pool-size", "8")) {
            DataSource ds = pool(ctx);
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE t (thread INTEGER, n INTEGER)");
            }
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<?>> done = new ArrayList<>();
                for (int t = 0; t < 8; t++) {
                    int thread = t;
                    done.add(pool.submit(() -> {
                        for (int i = 0; i < 200; i++) {
                            try (Connection c = ds.getConnection(); var ps = c.prepareStatement("INSERT INTO t VALUES (?, ?)")) {
                                ps.setInt(1, thread);
                                ps.setInt(2, i);
                                ps.executeUpdate();
                            }
                        }
                        return null;
                    }));
                }
                for (Future<?> f : done) {
                    f.get();
                }
            } finally {
                pool.shutdown();
            }
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                assertEquals("1600", one(st, "SELECT count(*) FROM t"));
            }
        }
    }

    @Test
    void healthIndicator() {
        try (ApplicationContext ctx = run("ffm-sqlite.datasources.default.path", ":memory:",
                "ffm-sqlite.datasources.files.path", dir.resolve("h.db").toString())) {
            FfmSqliteHealthIndicator indicator = ctx.getBean(FfmSqliteHealthIndicator.class);
            HealthResult up = indicator.check();
            assertEquals(HealthStatus.UP, up.getStatus());
            Map<?, ?> details = assertInstanceOf(Map.class, up.getDetails());
            Map<?, ?> files = (Map<?, ?>) details.get("files");
            assertEquals("wal", files.get("journalMode"));
            assertEquals("memory", ((Map<?, ?>) details.get("default")).get("journalMode"));
            assertTrue(((Map<?, ?>) files.get("pool")).containsKey("active"));

            ctx.getBean(FfmSqliteDatabase.class, Qualifiers.byName("files")).getDataSource().close();
            HealthResult down = indicator.check();
            assertEquals(HealthStatus.DOWN, down.getStatus());
            assertEquals("DOWN", ((Map<?, ?>) ((Map<?, ?>) down.getDetails()).get("files")).get("status"));
        }
        try (ApplicationContext ctx = run("ffm-sqlite.datasources.default.path", ":memory:",
                "endpoints.health.ffm-sqlite.enabled", "false")) {
            assertFalse(ctx.containsBean(FfmSqliteHealthIndicator.class));
        }
    }
}
