package io.github.revsnai.ffmsqlite.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookTest {
    @TempDir
    Path dir;
    private Connection conn;

    @BeforeEach
    void open() throws SQLiteException {
        conn = SQLite.getConnection(":memory:");
        exec(conn, "CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)");
    }

    @AfterEach
    void close() {
        conn.close();
    }

    private static void exec(Connection c, String sql) throws SQLiteException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private static long count(Connection c, String table) throws SQLiteException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------ update hook

    @Test
    void updateListenerSeesInsertsUpdatesAndDeletes() throws SQLiteException {
        List<String> events = new ArrayList<>();
        conn.addUpdateListener((type, db, table, rowId) -> events.add(type + " " + db + "." + table + " " + rowId));
        exec(conn, "INSERT INTO t (id, v) VALUES (1, 'a'), (2, 'b'); UPDATE t SET v = 'B' WHERE id = 2; DELETE FROM t WHERE id = 1");
        exec(conn, "ATTACH ':memory:' AS aux; CREATE TABLE aux.n (x); INSERT INTO aux.n VALUES ('y')");
        exec(conn, "CREATE TEMP TABLE tmp (x); INSERT INTO tmp VALUES (1)");
        assertEquals(List.of("INSERT main.t 1", "INSERT main.t 2", "UPDATE main.t 2", "DELETE main.t 1",
                "INSERT aux.n 1", "INSERT temp.tmp 1"), events);
    }

    @Test
    void updatesSqliteDoesNotReport() throws SQLiteException {
        AtomicInteger n = new AtomicInteger();
        exec(conn, "INSERT INTO t (v) VALUES ('a'), ('b'), ('c'); CREATE TABLE wr (k TEXT PRIMARY KEY, v) WITHOUT ROWID");
        conn.addUpdateListener((type, db, table, rowId) -> n.incrementAndGet());
        exec(conn, "INSERT INTO wr VALUES ('k', 1)");       // WITHOUT ROWID
        exec(conn, "DELETE FROM t");                        // truncate optimization
        assertEquals(0, n.get());
        exec(conn, "INSERT INTO t (v) VALUES ('d'); DELETE FROM t WHERE 1");
        assertEquals(2, n.get());
    }

    @Test
    void multipleListenersInOrderAndRemoval() throws SQLiteException {
        List<String> events = new ArrayList<>();
        UpdateListener first = (type, db, table, rowId) -> events.add("first");
        UpdateListener second = (type, db, table, rowId) -> events.add("second");
        conn.addUpdateListener(first);
        conn.addUpdateListener(second);
        exec(conn, "INSERT INTO t (v) VALUES ('a')");
        conn.removeUpdateListener(first);
        exec(conn, "INSERT INTO t (v) VALUES ('b')");
        conn.removeUpdateListener(second);
        exec(conn, "INSERT INTO t (v) VALUES ('c')");
        assertEquals(List.of("first", "second", "second"), events);
    }

    @Test
    void failingUpdateListenerInAutoCommitPersistsNothing() throws SQLiteException {
        conn.addUpdateListener((type, db, table, rowId) -> {
            if (rowId == 500) {
                throw new IllegalStateException("row 500 rejected");
            }
        });
        SQLiteException e = assertThrows(SQLiteException.class, () -> exec(conn,
                "INSERT INTO t (id) WITH RECURSIVE s(v) AS (SELECT 1 UNION ALL SELECT v + 1 FROM s WHERE v < 1000) SELECT v FROM s"));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertTrue(e.getMessage().contains("update listener: row 500 rejected"), e.getMessage());
        assertEquals(0, count(conn, "t"));
        // A single-row statement completes before SQLite notices the interrupt: the commit veto still stops it.
        e = assertThrows(SQLiteException.class, () -> exec(conn, "INSERT INTO t (id) VALUES (500)"));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertEquals(0, count(conn, "t"));
        exec(conn, "INSERT INTO t (id) VALUES (1)");
        assertEquals(1, count(conn, "t"));
    }

    @Test
    void failingUpdateListenerMakesAManualTransactionRollbackOnly() throws SQLiteException {
        conn.addUpdateListener((type, db, table, rowId) -> {
            if (rowId == 2) {
                throw new IllegalArgumentException("no row 2");
            }
        });
        conn.setAutoCommit(false);
        exec(conn, "INSERT INTO t (id) VALUES (1)");
        assertThrows(SQLiteException.class, () -> exec(conn, "INSERT INTO t (id) VALUES (2)"));
        SQLiteException veto = assertThrows(SQLiteException.class, conn::commit);
        assertEquals(19, veto.getErrorCode()); // SQLITE_CONSTRAINT (commit hook)
        assertEquals(0, count(conn, "t"), "the whole transaction was rolled back, including row 1");
        assertTrue(!conn.getAutoCommit(), "still in manual-commit mode, with a fresh transaction");
        exec(conn, "INSERT INTO t (id) VALUES (3)");
        conn.commit();
        conn.setAutoCommit(true);
        assertEquals(1, count(conn, "t"));
    }

    @Test
    void listenersCannotUseTheirConnection() throws SQLiteException {
        conn.addUpdateListener((type, db, table, rowId) -> count(conn, "t"));
        SQLiteException e = assertThrows(SQLiteException.class, () -> exec(conn, "INSERT INTO t (v) VALUES ('a')"));
        assertInstanceOf(SQLiteException.class, e.getCause());
        assertTrue(e.getCause().getMessage().contains("cannot be used inside its own update"), e.getCause().getMessage());
        assertEquals(0, count(conn, "t"));

        Connection other = conn;
        UpdateListener closer = (type, db, table, rowId) -> other.close();
        conn.addUpdateListener(closer);
        SQLiteException closing = assertThrows(SQLiteException.class, () -> exec(conn, "INSERT INTO t (v) VALUES ('b')"));
        assertTrue(!conn.isClosed());
        assertTrue(closing.getMessage().contains("update listener"), closing.getMessage());
    }

    // ------------------------------------------------------------------ commit and rollback hooks

    static final class Recorder implements CommitListener {
        final List<String> events = new ArrayList<>();
        RuntimeException failCommit, failRollback;

        @Override
        public void onCommit() {
            events.add("commit");
            if (failCommit != null) {
                throw failCommit;
            }
        }

        @Override
        public void onRollback() {
            events.add("rollback");
            if (failRollback != null) {
                throw failRollback;
            }
        }
    }

    @Test
    void commitListenerSeesAutoCommitAndManualTransactions() throws SQLiteException {
        Recorder r = new Recorder();
        conn.addCommitListener(r);
        exec(conn, "INSERT INTO t (v) VALUES ('a')");
        count(conn, "t"); // reads commit nothing
        conn.setAutoCommit(false);
        exec(conn, "INSERT INTO t (v) VALUES ('b')");
        conn.commit();
        exec(conn, "INSERT INTO t (v) VALUES ('c')");
        conn.rollback();
        conn.setAutoCommit(true);
        assertEquals(List.of("commit", "commit", "rollback"), r.events);
        assertEquals(2, count(conn, "t"));
    }

    @Test
    void commitListenerVetoesAnAutoCommitStatement() throws SQLiteException {
        Recorder r = new Recorder();
        r.failCommit = new IllegalStateException("not today");
        conn.addCommitListener(r);
        SQLiteException e = assertThrows(SQLiteException.class, () -> exec(conn, "INSERT INTO t (v) VALUES ('a')"));
        assertEquals(19, e.getErrorCode());
        assertEquals(531, e.getExtendedErrorCode()); // SQLITE_CONSTRAINT_COMMITHOOK
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertTrue(e.getMessage().contains("commit vetoed by listener: not today"), e.getMessage());
        assertEquals(List.of("commit", "rollback"), r.events);
        conn.removeCommitListener(r);
        assertEquals(0, count(conn, "t"));
    }

    @Test
    void commitListenerVetoesAManualCommit() throws SQLiteException {
        Recorder r = new Recorder();
        conn.addCommitListener(r);
        conn.setAutoCommit(false);
        exec(conn, "INSERT INTO t (v) VALUES ('a')");
        r.failCommit = new IllegalStateException("veto");
        SQLiteException e = assertThrows(SQLiteException.class, conn::commit);
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertEquals(0, count(conn, "t"));
        r.failCommit = null;
        exec(conn, "INSERT INTO t (v) VALUES ('b')"); // still inside a (new) manual transaction
        conn.commit();
        conn.setAutoCommit(true);
        assertEquals(1, count(conn, "t"));
    }

    @Test
    void failingRollbackListenerIsReportedButTheRollbackHappens() throws SQLiteException {
        Recorder r = new Recorder();
        r.failRollback = new IllegalStateException("rollback listener broke");
        conn.addCommitListener(r);
        conn.setAutoCommit(false);
        exec(conn, "INSERT INTO t (v) VALUES ('a')");
        SQLiteException e = assertThrows(SQLiteException.class, conn::rollback);
        assertInstanceOf(IllegalStateException.class, e.getCause());
        r.failRollback = null;
        assertEquals(0, count(conn, "t"));
        assertTrue(!conn.getAutoCommit());
        conn.setAutoCommit(true);
    }

    @Test
    void closingWithAnOpenTransactionDoesNotCallOnRollback() throws SQLiteException {
        Recorder r = new Recorder();
        Connection c = SQLite.getConnection(":memory:");
        exec(c, "CREATE TABLE x (y)");
        c.addCommitListener(r);
        c.setAutoCommit(false);
        exec(c, "INSERT INTO x VALUES (1)");
        c.close();
        assertEquals(List.of(), r.events);
    }

    // ------------------------------------------------------------------ busy handler (real lock contention)

    /** Two connections to one file; {@code holder} keeps a write lock until committed. */
    private Connection[] lockedPair() throws SQLiteException {
        String path = dir.resolve("busy.db").toString();
        Connection holder = SQLite.getConnection(path);
        exec(holder, "CREATE TABLE IF NOT EXISTS w (x)");
        Connection waiter = SQLite.getConnection(path);
        holder.setAutoCommit(false);
        exec(holder, "INSERT INTO w VALUES ('holder')"); // RESERVED lock until commit
        return new Connection[]{holder, waiter};
    }

    @Test
    void busyHandlerDecidesHowLongToWait() throws SQLiteException {
        Connection[] pair = lockedPair();
        try (Connection holder = pair[0]; Connection waiter = pair[1]) {
            List<Integer> attempts = new ArrayList<>();
            waiter.setBusyHandler(n -> {
                attempts.add(n);
                return n < 3;
            });
            SQLiteException e = assertThrows(SQLiteException.class, () -> exec(waiter, "INSERT INTO w VALUES ('waiter')"));
            assertEquals(5, e.getErrorCode()); // SQLITE_BUSY
            assertEquals(List.of(0, 1, 2, 3), attempts);
            holder.rollback();
        }
    }

    @Test
    void busyHandlerMayUseOtherConnectionsToReleaseTheLock() throws SQLiteException {
        Connection[] pair = lockedPair();
        try (Connection holder = pair[0]; Connection waiter = pair[1]) {
            waiter.setBusyHandler(n -> {
                if (n == 2) {
                    holder.commit();
                }
                return true;
            });
            exec(waiter, "INSERT INTO w VALUES ('waiter')");
            holder.setAutoCommit(true);
            assertEquals(2, count(waiter, "w"));
        }
    }

    @Test
    void busyHandlerFailuresAndMisuse() throws SQLiteException {
        Connection[] pair = lockedPair();
        try (Connection holder = pair[0]; Connection waiter = pair[1]) {
            waiter.setBusyHandler(n -> {
                throw new IllegalStateException("handler gave up");
            });
            SQLiteException e = assertThrows(SQLiteException.class, () -> exec(waiter, "INSERT INTO w VALUES (1)"));
            assertEquals(5, e.getErrorCode());
            assertInstanceOf(IllegalStateException.class, e.getCause());
            assertTrue(e.getMessage().contains("busy handler: handler gave up"), e.getMessage());

            waiter.setBusyHandler(n -> count(waiter, "w") >= 0); // uses its own connection: refused
            SQLiteException misuse = assertThrows(SQLiteException.class, () -> exec(waiter, "INSERT INTO w VALUES (2)"));
            assertEquals(5, misuse.getErrorCode());
            assertTrue(misuse.getCause().getMessage().contains("cannot be used inside"), misuse.getCause().getMessage());
            holder.rollback();
        }
    }

    @Test
    void noHandlerFailsAtOnceAndTimeoutWaits() throws SQLiteException {
        Connection[] pair = lockedPair();
        try (Connection holder = pair[0]; Connection waiter = pair[1]) {
            waiter.setBusyHandler(null);
            long t0 = System.nanoTime();
            assertThrows(SQLiteException.class, () -> exec(waiter, "INSERT INTO w VALUES (1)"));
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 100, "no waiting without a handler");

            waiter.setBusyTimeout(300);
            t0 = System.nanoTime();
            SQLiteException e = assertThrows(SQLiteException.class, () -> exec(waiter, "INSERT INTO w VALUES (1)"));
            long waited = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(5, e.getErrorCode());
            assertTrue(waited >= 250, "waited " + waited + " ms");
            holder.rollback();
        }
    }
}
