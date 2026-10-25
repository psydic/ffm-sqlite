package io.github.revsnai.ffmsqlite.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the issues found in the second (0.2.0) code review. */
class ReviewFixes2Test {
    static final String SLOW_INSERT = "INSERT INTO t SELECT x FROM (WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL "
            + "SELECT x + 1 FROM c) SELECT x FROM c LIMIT 200000000)";

    static long count(Connection c, String table) throws SQLiteException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------ auto-rollback is reported, not swallowed

    @Test
    void transactionRolledBackBySqliteFailsUntilRollback() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE t (v INTEGER)");
            }
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO t VALUES (-1)");                       // 'A'
                st.setQueryTimeout(Duration.ofMillis(100));
                assertThrows(SQLiteTimeoutException.class, () -> st.executeUpdate(SLOW_INSERT));   // rolls back the transaction
            }
            try (Statement st = c.createStatement()) {
                SQLiteTransactionRolledBackException later = assertThrows(SQLiteTransactionRolledBackException.class,
                        () -> st.executeUpdate("INSERT INTO t VALUES (-3)"));         // 'C' must not auto-commit
                assertInstanceOf(SQLiteTimeoutException.class, later.getCause());
                assertThrows(SQLiteTransactionRolledBackException.class, () -> st.executeQuery("SELECT 1").next());
            }
            assertThrows(SQLiteTransactionRolledBackException.class, c::commit);   // reported, never silently "committed"
            // commit() started a new transaction, as after any failed commit
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO t VALUES (2)");
            }
            c.commit();
            assertEquals(1, count(c, "t"));
            assertFalse(c.getAutoCommit());
        }
    }

    @Test
    void rollbackClearsTheRolledBackState() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE t (v INTEGER)");
            }
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(Duration.ofMillis(100));
                assertThrows(SQLiteTimeoutException.class, () -> st.executeUpdate(SLOW_INSERT));
            }
            c.rollback();
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO t VALUES (1)");
            }
            c.rollback();
            assertEquals(0, count(c, "t"), "the insert was inside a transaction again");
            assertThrows(SQLiteTransactionRolledBackException.class, () -> {
                try (Statement st = c.createStatement()) {
                    st.setQueryTimeout(Duration.ofMillis(100));
                    try {
                        st.executeUpdate(SLOW_INSERT);
                    } catch (SQLiteTimeoutException expected) {
                        // the transaction is gone now
                    }
                }
                c.setAutoCommit(true);   // would otherwise commit nothing and claim success
            });
            assertFalse(c.getAutoCommit());
        }
    }

    @Test
    void commitSentAsSqlStartsTheNextTransaction() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE t (v INTEGER)");
            }
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO t VALUES (1)");
                st.executeUpdate("COMMIT");
                st.executeUpdate("INSERT INTO t VALUES (2)");   // back inside a transaction...
            }
            c.rollback();                                       // ...so only this one is undone
            assertEquals(1, count(c, "t"));
        }
    }

    // ------------------------------------------------------------------ cached statements after a schema change

    @Test
    void cachedStatementSeesColumnsAfterSchemaChange() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE t (a, b, c); INSERT INTO t VALUES (1, 2, 3)");
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM t"); ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals("3", rs.getString("c"));
            }
            try (Statement st = c.createStatement()) {
                st.executeUpdate("ALTER TABLE t DROP COLUMN b; ALTER TABLE t ADD COLUMN d; UPDATE t SET d = 4");
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM t"); ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("3", rs.getString("c"));
                assertEquals("4", rs.getString("d"));
                assertEquals(3, rs.getMetaData().getColumnCount());
                assertEquals("d", rs.getMetaData().getColumnLabel(3));
                assertThrows(SQLiteException.class, () -> rs.getString("b"));
            }
        }
    }

    @Test
    void cachedStatementSeesSchemaChangesFromAnotherConnection(@TempDir Path dir) throws SQLiteException {
        String path = dir.resolve("s.db").toString();
        try (Connection c = SQLite.getConnection(path); Connection other = SQLite.getConnection(path)) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE t (a, b); INSERT INTO t VALUES (1, 2)");
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM t"); ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
            try (Statement st = other.createStatement()) {
                st.executeUpdate("ALTER TABLE t ADD COLUMN extra DEFAULT 'x'");
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM t"); ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals("x", rs.getString("extra"));
                assertEquals(3, rs.getMetaData().getColumnCount());
            }
        }
    }

    // ------------------------------------------------------------------ function names: SQLite folds ASCII only

    @Test
    void functionsDifferingInNonAsciiCaseAreSeparate() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:"); Statement st = c.createStatement()) {
            c.createFunction("Äf", 0, args -> "upper");
            c.createFunction("äf", 0, args -> "lower");   // a different function to SQLite: must not free Äf's stubs
            c.createFunction("Kelvin", 0, args -> "kelvin sign");
            c.createFunction("kelvin", 0, args -> "ascii");
            try (ResultSet rs = st.executeQuery("SELECT Äf(), äf(), Kelvin(), kelvin(), KELVIN()")) {
                rs.next();
                assertEquals(List.of("upper", "lower", "kelvin sign", "ascii", "ascii"),
                        List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
            }
            c.createFunction("KELVIN", 0, args -> "replaced");   // ASCII case: the same function, replaced
            try (ResultSet rs = st.executeQuery("SELECT kelvin(), Kelvin()")) {
                rs.next();
                assertEquals("replaced", rs.getString(1));
                assertEquals("kelvin sign", rs.getString(2));
            }
        }
    }

    // ------------------------------------------------------------------ NUL bytes

    @Test
    void nulBytesNeitherHangNorTruncateSilently() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:"); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (x);\0\0\n");                       // trailing padding: fine
            try (PreparedStatement ps = c.prepareStatement("SELECT 1\0"); ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
            }
            assertThrows(SQLiteException.class, () -> st.executeUpdate("INSERT INTO t VALUES (1);\0 DROP TABLE t"));
            assertThrows(SQLiteException.class, () -> c.prepareStatement("SELECT '\0'"));
            assertEquals(0, count(c, "t"));
        }
    }

    // ------------------------------------------------------------------ getTables with same-named trigger

    @Test
    void triggerNamedLikeItsTableIsNotASecondTable() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:"); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE audit (x); CREATE TRIGGER audit AFTER INSERT ON audit BEGIN SELECT 1; END");
            List<DatabaseMetaData.TableInfo> tables = c.getMetaData().getTables();
            assertEquals(1, tables.size());
            assertTrue(tables.getFirst().sql().startsWith("CREATE TABLE"), tables.getFirst().sql());
        }
    }

    // ------------------------------------------------------------------ query timeouts bound lock waits

    @Test
    void queryTimeoutBoundsTheBusyTimeout(@TempDir Path dir) throws SQLiteException {
        String path = dir.resolve("lock.db").toString();
        try (Connection holder = SQLite.getConnection(path);
             Connection waiter = SQLite.getConnection(path, SQLite.Config.DEFAULT.withBusyTimeoutMillis(5_000))) {
            try (Statement st = holder.createStatement()) {
                st.executeUpdate("CREATE TABLE t (v)");
            }
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement()) {
                st.executeUpdate("INSERT INTO t VALUES (1)");   // holds the write lock
            }
            try (Statement st = waiter.createStatement()) {
                st.setQueryTimeout(Duration.ofMillis(300));
                long t0 = System.nanoTime();
                assertThrows(SQLiteTimeoutException.class, () -> st.executeUpdate("INSERT INTO t VALUES (2)"));
                long ms = (System.nanoTime() - t0) / 1_000_000;
                assertTrue(ms < 2_000, "waited " + ms + " ms for a 300 ms timeout");
                st.setQueryTimeout(Duration.ZERO);
            }
            // without a query timeout the full busy timeout applies again
            waiter.setBusyTimeout(200);
            try (Statement st = waiter.createStatement()) {
                SQLiteException busy = assertThrows(SQLiteException.class, () -> st.executeUpdate("INSERT INTO t VALUES (3)"));
                assertFalse(busy instanceof SQLiteTimeoutException);
                assertEquals(5, busy.getErrorCode());
            }
            holder.rollback();
        }
    }

    @Test
    void queryTimeoutAlsoStopsACustomBusyHandler(@TempDir Path dir) throws SQLiteException {
        String path = dir.resolve("lock2.db").toString();
        try (Connection holder = SQLite.getConnection(path); Connection waiter = SQLite.getConnection(path)) {
            try (Statement st = holder.createStatement()) {
                st.executeUpdate("CREATE TABLE t (v)");
            }
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement()) {
                st.executeUpdate("INSERT INTO t VALUES (1)");
            }
            waiter.setBusyHandler(attempts -> {
                Thread.sleep(5);
                return true;   // would wait forever
            });
            try (Statement st = waiter.createStatement()) {
                st.setQueryTimeout(Duration.ofMillis(200));
                assertThrows(SQLiteTimeoutException.class, () -> st.executeUpdate("INSERT INTO t VALUES (2)"));
            }
            holder.rollback();
        }
    }

    @Test
    void hugeQueryTimeoutMeansNoLimit() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:"); Statement st = c.createStatement()) {
            st.setQueryTimeout(Integer.MAX_VALUE);
            try (ResultSet rs = st.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
            }
            st.setQueryTimeout(Duration.ofDays(365_000));
            try (ResultSet rs = st.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
            }
        }
    }

    // ------------------------------------------------------------------ integer narrowing

    @Test
    void outOfRangeIntegersFailInsteadOfWrapping() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:"); Statement st = c.createStatement()) {
            c.createFunction("as_int", 1, args -> args.getInt(0));
            try (ResultSet rs = st.executeQuery("SELECT 3000000000, -2147483648, 70000")) {
                rs.next();
                assertEquals(3_000_000_000L, rs.getLong(1));
                SQLiteException e = assertThrows(SQLiteException.class, () -> rs.getInt(1));
                assertEquals(20, e.getErrorCode());
                assertThrows(SQLiteException.class, () -> rs.getObject(1, Integer.class));
                assertEquals(Integer.MIN_VALUE, rs.getInt(2));
                assertThrows(SQLiteException.class, () -> rs.getObject(3, Short.class));
            }
            assertThrows(SQLiteException.class, () -> st.executeQuery("SELECT as_int(3000000000)").next());
        }
    }

    @Test
    void inTransactionSeesSqlStartedTransactions() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:"); Statement st = c.createStatement()) {
            assertFalse(c.inTransaction());
            st.executeUpdate("BEGIN");
            assertTrue(c.inTransaction());
            assertTrue(c.getAutoCommit(), "getAutoCommit is the API's mode, not SQLite's state");
            st.executeUpdate("ROLLBACK");
            st.executeUpdate("SAVEPOINT s");
            assertTrue(c.inTransaction());
            st.executeUpdate("RELEASE s");
            c.setAutoCommit(false);
            assertTrue(c.inTransaction());
            c.setAutoCommit(true);
            assertFalse(c.inTransaction());
            c.close();
            assertThrows(SQLiteException.class, c::inTransaction);
        }
    }

    @Test
    void setReadOnlyRejectsWritesPerStatement() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:"); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (v); INSERT INTO t VALUES (1)");
            PreparedStatement select = c.prepareStatement("SELECT count(*) FROM t");
            c.setReadOnly(true);
            assertTrue(c.isReadOnly());
            for (String write : new String[]{"INSERT INTO t VALUES (2)", "UPDATE t SET v = 3", "DELETE FROM t",
                    "CREATE TABLE u (x)", "DROP TABLE t", "PRAGMA user_version = 7", "CREATE TEMP TABLE tmp (x)"}) {
                SQLiteException e = assertThrows(SQLiteException.class, () -> st.executeUpdate(write), write);
                assertEquals(8, e.getErrorCode(), write);   // SQLITE_READONLY
            }
            // a write in the middle of a script stops it there
            assertThrows(SQLiteException.class, () -> st.executeUpdate("SELECT 1; INSERT INTO t VALUES (9)"));
            // reads and transaction control still run
            c.setAutoCommit(false);
            try (ResultSet rs = select.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
            c.commit();
            c.setAutoCommit(true);
            c.setReadOnly(false);
            assertEquals(1, st.executeUpdate("INSERT INTO t VALUES (2)"));
            try (ResultSet rs = select.executeQuery()) {   // the cached statement still works after switching
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
            select.close();
        }
    }
}
