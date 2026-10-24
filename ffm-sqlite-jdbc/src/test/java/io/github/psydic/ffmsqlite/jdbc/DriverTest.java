package io.github.psydic.ffmsqlite.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverTest {
    @TempDir
    Path dir;

    private static String one(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    @Test
    void registeredThroughServiceLoaderAndOptionsFromUrlAndProperties() throws SQLException {
        String url = "jdbc:ffmsqlite:" + dir.resolve("a.db") + "?journal_mode=WAL&foreign_keys=true";
        try (Connection c = DriverManager.getConnection(url)) {
            assertInstanceOf(JdbcConnection.class, c);
            assertEquals("wal", one(c, "PRAGMA journal_mode"));
            assertEquals("1", one(c, "PRAGMA foreign_keys"));
        }
        Properties p = new Properties();
        p.setProperty("synchronous", "NORMAL");
        p.setProperty("user", "ignored");
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite:" + dir.resolve("a.db"), p)) {
            assertEquals("1", one(c, "PRAGMA synchronous"));
        }
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite::memory:")) {
            assertEquals("main", one(c, "SELECT name FROM pragma_database_list"));
        }
        FfmSqliteDriver d = new FfmSqliteDriver();
        assertTrue(d.acceptsURL("jdbc:ffmsqlite:x.db"));
        assertFalse(d.acceptsURL("jdbc:sqlite:x.db"), "sqlite-jdbc URLs are left to sqlite-jdbc unless opted in");
        assertNull(d.connect("jdbc:postgresql://x", null));
    }

    @Test
    void canTakeOverSqliteUrlsWhenAskedTo() throws SQLException {
        System.setProperty("ffmsqlite.jdbc.acceptSqliteUrls", "true");
        try (Connection c = new FfmSqliteDriver().connect("jdbc:sqlite:" + dir.resolve("b.db"), new Properties())) {
            assertInstanceOf(JdbcConnection.class, c);
        } finally {
            System.clearProperty("ffmsqlite.jdbc.acceptSqliteUrls");
        }
    }

    @Test
    void dataSource() throws SQLException {
        FfmSqliteDataSource ds = new FfmSqliteDataSource(dir.resolve("ds.db").toString());
        try (Connection c = ds.getConnection("u", "p")) {
            assertEquals("1", one(c, "SELECT 1"));
        }
        assertTrue(ds.isWrapperFor(javax.sql.DataSource.class));
    }

    @Test
    void exceptionsAreTranslatedIntoTheSqlExceptionHierarchy() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite::memory:"); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT UNIQUE); INSERT INTO t VALUES (1, 'a')");
            SQLIntegrityConstraintViolationException dup = assertThrows(SQLIntegrityConstraintViolationException.class,
                    () -> st.executeUpdate("INSERT INTO t VALUES (2, 'a')"));
            assertEquals("23505", dup.getSQLState()); // unique violation
            assertEquals(19, dup.getErrorCode());
            assertEquals(2067, ((io.github.psydic.ffmsqlite.db.SQLiteException) dup.getCause()).getExtendedErrorCode());
            SQLSyntaxErrorException syntax = assertThrows(SQLSyntaxErrorException.class, () -> st.executeQuery("SELEC 1"));
            assertEquals("42000", syntax.getSQLState());
            assertThrows(SQLSyntaxErrorException.class, () -> st.executeQuery("SELECT * FROM missing"));
        }
    }

    @Test
    void queryTimeoutAndCancel() throws Exception {
        String slow = "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT count(*) FROM (SELECT x FROM c LIMIT 200000000)";
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite::memory:")) {
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(1);
                long t0 = System.nanoTime();
                SQLTimeoutException e = assertThrows(SQLTimeoutException.class, () -> st.executeQuery(slow).next());
                assertTrue(Duration.ofNanos(System.nanoTime() - t0).toMillis() < 3_000);
                assertEquals("HYT00", e.getSQLState());
            }
            try (Statement st = c.createStatement()) {
                Thread.ofVirtual().start(() -> {
                    try {
                        Thread.sleep(200);
                        st.cancel(); // must not block on the connection lock held by the running query
                    } catch (Exception ignored) {
                        // test fails below if cancel did not happen
                    }
                });
                SQLException e = assertThrows(SQLException.class, () -> st.executeQuery(slow).next());
                assertEquals("HY008", e.getSQLState());
            }
            assertEquals("1", one(c, "SELECT 1"));
        }
    }

    @Test
    void oneConnectionSharedByManyThreadsIsSerializedSafely() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite:" + dir.resolve("mt.db"))) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, thread INTEGER, n INTEGER)");
            }
            ExecutorService pool = Executors.newFixedThreadPool(8);
            AtomicInteger reads = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int thread = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 250; i++) {
                        try (PreparedStatement ps = c.prepareStatement("INSERT INTO t (thread, n) VALUES (?, ?)")) {
                            ps.setInt(1, thread);
                            ps.setInt(2, i);
                            ps.executeUpdate();
                        }
                        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM t WHERE thread = ?")) {
                            ps.setInt(1, thread);
                            try (ResultSet rs = ps.executeQuery()) {
                                rs.next();
                                if (rs.getInt(1) == i + 1) {
                                    reads.incrementAndGet();
                                }
                            }
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
            pool.shutdown();
            assertEquals("2000", one(c, "SELECT count(*) FROM t"));
            assertEquals(2000, reads.get(), "every thread saw its own inserts");
        }
    }

    @Test
    void unwrapGivesTheUnderlyingFfmConnection() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite::memory:")) {
            io.github.psydic.ffmsqlite.db.Connection ffm = c.unwrap(io.github.psydic.ffmsqlite.db.Connection.class);
            ffm.createFunction("twice", 1, args -> args.getLong(0) * 2);
            assertEquals("42", one(c, "SELECT twice(21)"));
            assertTrue(c.isWrapperFor(io.github.psydic.ffmsqlite.db.Connection.class));
            assertNotNull(c.getMetaData().unwrap(io.github.psydic.ffmsqlite.db.DatabaseMetaData.class));
        }
    }
}
