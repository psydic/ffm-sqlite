package io.github.revsnai.ffmsqlite.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.revsnai.ffmsqlite.db.ReviewFixes2Test.count;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the issues found in the third (0.2.4) review: hotspots, resilience, lifecycle, security. */
class ReviewFixes3Test {

    static void exec(Connection c, String sql) throws SQLiteException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    // ------------------------------------------------------------------ the commit veto outlives its listener

    @Test
    void removingTheFailedListenerDoesNotLiftTheCommitVeto() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            exec(c, "CREATE TABLE t (v INTEGER)");
            c.setAutoCommit(false);
            UpdateListener failing = (type, db, table, rowId) -> {
                throw new IllegalStateException("listener failed");
            };
            c.addUpdateListener(failing);
            // A single-row INSERT completes before SQLite checks the interrupt: the row is in the transaction.
            assertThrows(SQLiteException.class, () -> exec(c, "INSERT INTO t VALUES (1)"));
            c.removeUpdateListener(failing);   // the usual finally block
            assertThrows(SQLiteException.class, c::commit);
            assertEquals(0, count(c, "t"), "the row whose listener failed was committed");
            exec(c, "INSERT INTO t VALUES (2)");   // the veto ended with that transaction
            c.commit();
            assertEquals(1, count(c, "t"));
        }
    }

    // ------------------------------------------------------------------ a failing collation cannot commit its answer

    @Test
    void failingCollationInAnAutocommitWriteIsRolledBack() throws SQLiteException {
        AtomicBoolean fail = new AtomicBoolean();
        try (Connection c = SQLite.getConnection(":memory:")) {
            c.createCollation("flaky", (a, b) -> {
                if (fail.get()) {
                    throw new IllegalStateException("comparator failed");
                }
                return a.compareTo(b);
            });
            exec(c, "CREATE TABLE t (s TEXT COLLATE flaky)");
            exec(c, "CREATE INDEX t_s ON t (s)");
            exec(c, "INSERT INTO t VALUES ('m')");
            fail.set(true);
            assertThrows(SQLiteException.class, () -> exec(c, "INSERT INTO t VALUES ('a')"));
            fail.set(false);
            assertEquals(1, count(c, "t"), "an index entry placed by the failed comparison was committed");
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                rs.next();
                assertEquals("ok", rs.getString(1));
            }
            exec(c, "INSERT INTO t VALUES ('a')");
            assertEquals(2, count(c, "t"));
        }
    }

    @Test
    void failingCollationInAReadDoesNotPoisonTheNextWrite() throws SQLiteException {
        AtomicBoolean fail = new AtomicBoolean();
        try (Connection c = SQLite.getConnection(":memory:")) {
            c.createCollation("flaky", (a, b) -> {
                if (fail.get()) {
                    throw new IllegalStateException("comparator failed");
                }
                return a.compareTo(b);
            });
            exec(c, "CREATE TABLE t (s TEXT)");
            exec(c, "INSERT INTO t VALUES ('b'), ('a')");
            c.setAutoCommit(false);
            fail.set(true);
            assertThrows(SQLiteException.class, () -> {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT s FROM t ORDER BY s COLLATE flaky")) {
                    while (rs.next()) {
                        rs.getString(1);
                    }
                }
            });
            fail.set(false);
            c.rollback();   // the read transaction ends without a commit or rollback hook
            exec(c, "INSERT INTO t VALUES ('c')");
            c.commit();
            assertEquals(3, count(c, "t"));
        }
    }

    // ------------------------------------------------------------------ backup progress callbacks

    @Test
    void closingTheSourceFromABackupCallbackThrowsInsteadOfFreeingIt(@TempDir Path dir) throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            exec(c, "CREATE TABLE t (v BLOB)");
            exec(c, "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 50) INSERT INTO t SELECT randomblob(4000) FROM n");
            BackupOptions closeMidway = new BackupOptions("main", 1, Duration.ZERO, (remaining, total) -> {
                c.close();
                return true;
            });
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> c.backup(dir.resolve("copy.db").toString(), closeMidway));
            assertTrue(e.getMessage().contains("backup"), e.getMessage());
            assertFalse(c.isClosed());
            assertEquals(50, count(c, "t"));
        }
    }

    @Test
    void deserializingIntoTheDestinationFromABackupCallbackFails() throws SQLiteException {
        try (Connection source = SQLite.getConnection(":memory:"); Connection dest = SQLite.getConnection(":memory:")) {
            exec(source, "CREATE TABLE t (v BLOB)");
            exec(source, "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 50) INSERT INTO t SELECT randomblob(4000) FROM n");
            byte[] image = dest.serialize();
            AtomicReference<Exception> refused = new AtomicReference<>();
            BackupOptions deserializeMidway = new BackupOptions("main", 1, Duration.ZERO, (remaining, total) -> {
                try {
                    dest.deserialize(image);
                } catch (SQLiteException e) {
                    refused.set(e);
                }
                return true;
            });
            source.backup(dest, deserializeMidway);
            assertTrue(refused.get() instanceof SQLiteException, String.valueOf(refused.get()));
            assertEquals(50, count(dest, "t"));   // the backup itself completed
        }
    }

    // ------------------------------------------------------------------ conversions of untrusted text

    @Test
    @Timeout(10)
    void hugeExponentsDoNotBecomeHugeBigIntegers() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT '1e600000000', '1e-600000000', ' 12345.9 ', '-7E+3', 9e999")) {
            rs.next();
            SQLiteException e = assertThrows(SQLiteException.class, () -> rs.getObject(1, BigInteger.class));
            assertTrue(e.getMessage().contains("BigInteger"), e.getMessage());
            assertEquals(BigInteger.ZERO, rs.getObject(2, BigInteger.class));
            assertEquals(BigInteger.valueOf(12345), rs.getObject(3, BigInteger.class));
            assertEquals(BigInteger.valueOf(-7000), rs.getObject(4, BigInteger.class));
            assertThrows(SQLiteException.class, () -> rs.getObject(5, BigDecimal.class));   // Infinity
        }
    }

    @Test
    @Timeout(10)
    void hugeExponentsInFunctionArgumentsAreRejected() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            c.createFunction("big", 1, args -> args.getObject(0, BigInteger.class).toString());
            SQLiteException e = assertThrows(SQLiteException.class, () -> {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT big('1e600000000')")) {
                    rs.next();
                }
            });
            assertTrue(String.valueOf(e.getCause()).contains("BigInteger") || e.getMessage().contains("BigInteger"),
                    e.getMessage());
        }
    }

    // ------------------------------------------------------------------ PRAGMA values are one statement

    @Test
    void pragmaValueCannotSmuggleASecondStatement(@TempDir Path dir) throws SQLiteException {
        String path = dir.resolve("p.db").toString();
        SQLite.Config injected = new SQLite.Config(false, 0, 64,
                Map.of("cache_size", "1; CREATE TABLE injected (x)"));
        assertThrows(SQLiteException.class, () -> SQLite.getConnection(path, injected).close());
        try (Connection c = SQLite.getConnection(path);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM sqlite_schema WHERE name = 'injected'")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
        try (Connection c = SQLite.getConnection(path, new SQLite.Config(false, 0, 64, Map.of("cache_size", "-777")));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA cache_size")) {
            rs.next();
            assertEquals(-777, rs.getInt(1));
        }
    }

    // ------------------------------------------------------------------ scripts are linear in their length

    @Test
    @Timeout(30)
    void longScriptRunsInLinearTime() throws SQLiteException {
        StringBuilder script = new StringBuilder("CREATE TABLE t (v INTEGER, pad TEXT);\n");
        String pad = "x".repeat(40);
        for (int i = 0; i < 20_000; i++) {
            script.append("INSERT INTO t VALUES (").append(i).append(", '").append(pad).append("');\n");
        }
        try (Connection c = SQLite.getConnection(":memory:")) {
            long start = System.nanoTime();
            exec(c, "BEGIN;\n" + script + "COMMIT;");
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertEquals(20_000, count(c, "t"));
            assertTrue(millis < 5_000, "20,000-statement script took " + millis + " ms");
        }
    }

    @Test
    void busyTimeoutInAScriptIsStillAdopted() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t (v); PRAGMA busy_timeout = 4321; INSERT INTO t VALUES (1)");
            try (ResultSet rs = st.executeQuery("PRAGMA busy_timeout")) {
                rs.next();
                assertEquals(4321, rs.getInt(1));
            }
        }
    }
}
