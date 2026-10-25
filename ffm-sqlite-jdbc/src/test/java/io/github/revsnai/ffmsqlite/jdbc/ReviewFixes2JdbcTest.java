package io.github.revsnai.ffmsqlite.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the second (0.2.0) code review, through the java.sql API. */
class ReviewFixes2JdbcTest {
    static final String SLOW = "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) "
            + "SELECT count(*) FROM (SELECT x FROM c LIMIT 2000000000)";

    @Test
    void isBeforeFirstIsFalseForAnEmptyResult() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite::memory:"); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT 1 WHERE 0")) {
                assertFalse(rs.isBeforeFirst());
                assertFalse(rs.next());
                assertFalse(rs.isAfterLast());
            }
            try (ResultSet rs = st.executeQuery("SELECT 7 UNION ALL SELECT 8")) {
                assertTrue(rs.isBeforeFirst());
                assertTrue(rs.isBeforeFirst());   // asking twice does not skip a row
                assertTrue(rs.next());
                assertEquals(7, rs.getInt(1));
                assertFalse(rs.isBeforeFirst());
                assertTrue(rs.next());
                assertEquals(8, rs.getInt(1));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void upsertUpdatePathReportsNoGeneratedKey() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite::memory:"); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE a (id INTEGER PRIMARY KEY, k TEXT UNIQUE, v TEXT); CREATE TABLE other (id INTEGER PRIMARY KEY)");
            String upsert = "INSERT INTO a (k, v) VALUES (?, ?) ON CONFLICT (k) DO UPDATE SET v = excluded.v";
            try (PreparedStatement ps = c.prepareStatement(upsert, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, "x");
                ps.setString(2, "first");
                assertEquals(1, ps.executeUpdate());
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertTrue(keys.next());
                    assertEquals(1, keys.getLong(1));   // inserted: its rowid
                }
                st.executeUpdate("INSERT INTO other (id) VALUES (999)");
                ps.setString(1, "x");
                ps.setString(2, "second");
                assertEquals(1, ps.executeUpdate());   // updated, nothing inserted
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertFalse(keys.next(), "999 belongs to another table");
                }
            }
        }
    }

    @Test
    void autoRollbackIsASqlTransactionRollbackException() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite::memory:")) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE t (v INTEGER)");
            }
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO t VALUES (1)");
                st.setQueryTimeout(1);
                assertThrows(SQLTimeoutException.class, () -> st.executeUpdate("INSERT INTO t " + SLOW.replace("count(*)", "x")));
            }
            SQLTransactionRollbackException e = assertThrows(SQLTransactionRollbackException.class, c::commit);
            assertEquals("40000", e.getSQLState());
            c.rollback();
        }
    }

    @Test
    void abortStopsTheRunningQueryAndClosesAtOnce() throws Exception {
        Connection c = DriverManager.getConnection("jdbc:ffmsqlite::memory:");
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Throwable> query = CompletableFuture.supplyAsync(() -> {
            try (Statement st = c.createStatement()) {
                started.countDown();
                st.executeQuery(SLOW).next();
                return null;
            } catch (Throwable t) {
                return t;
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Thread.sleep(100);
        var executor = Executors.newSingleThreadExecutor();
        try {
            c.abort(executor);
            assertTrue(c.isClosed(), "closed as soon as abort returns");
            Throwable failure = query.get(5, TimeUnit.SECONDS);   // interrupted, not left running for minutes
            assertInstanceOf(SQLException.class, failure);
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void configuredQueryOnlySurvivesSetReadOnlyFalse(@TempDir Path dir) throws SQLException {
        String file = dir.resolve("ro.db").toString();
        try (Connection w = DriverManager.getConnection("jdbc:ffmsqlite:" + file); Statement st = w.createStatement()) {
            st.executeUpdate("CREATE TABLE t (v)");
        }
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite:" + file + "?query_only=true")) {
            assertTrue(c.isReadOnly());
            c.setReadOnly(true);    // what Spring does for @Transactional(readOnly = true)...
            c.setReadOnly(false);   // ...and after it, unconditionally
            assertTrue(c.isReadOnly());
            try (Statement st = c.createStatement()) {
                assertThrows(SQLException.class, () -> st.executeUpdate("INSERT INTO t VALUES (1)"));
            }
        }
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite:" + file)) {
            assertFalse(c.isReadOnly());
            c.setReadOnly(true);
            try (Statement st = c.createStatement(); PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (?)")) {
                assertThrows(SQLException.class, () -> st.executeUpdate("INSERT INTO t VALUES (1)"));
                ps.setInt(1, 1);
                assertThrows(SQLException.class, ps::executeUpdate);
                ps.addBatch();
                assertThrows(SQLException.class, ps::executeBatch);
                c.setAutoCommit(false);   // a read-only transaction, as frameworks run it
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM t")) {
                    assertTrue(rs.next());
                }
                c.commit();
                c.setAutoCommit(true);
            }
            c.setReadOnly(false);
            try (Statement st = c.createStatement()) {
                assertEquals(1, st.executeUpdate("INSERT INTO t VALUES (1)"));
            }
        }
    }

    @Test
    void integerMaxQueryTimeoutWorks() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite::memory:"); Statement st = c.createStatement()) {
            st.setQueryTimeout(Integer.MAX_VALUE);
            try (ResultSet rs = st.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT ?")) {
                ps.setQueryTimeout(Integer.MAX_VALUE);
                ps.setInt(1, 5);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                }
            }
        }
    }

    @Test
    void outOfRangeNarrowingIsASqlDataException() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite::memory:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 3000000000, 40000, 300")) {
            rs.next();
            assertEquals("22003", assertThrows(SQLDataException.class, () -> rs.getInt(1)).getSQLState());
            assertEquals("22003", assertThrows(SQLDataException.class, () -> rs.getShort(2)).getSQLState());
            assertEquals("22003", assertThrows(SQLDataException.class, () -> rs.getByte(3)).getSQLState());
            assertEquals(3_000_000_000L, rs.getLong(1));
        }
    }
}
