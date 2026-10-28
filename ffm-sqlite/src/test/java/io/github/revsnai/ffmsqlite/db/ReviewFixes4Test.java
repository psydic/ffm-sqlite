package io.github.revsnai.ffmsqlite.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.revsnai.ffmsqlite.db.ReviewFixes2Test.count;
import static io.github.revsnai.ffmsqlite.db.ReviewFixes3Test.exec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the memory-safety review of 0.2.5. Several of these were use-after-frees reproduced under
 * AddressSanitizer; here they assert the refusal that now prevents them.
 */
class ReviewFixes4Test {
    private static final String WINDOW_QUERY =
            "SELECT w(x) OVER (ORDER BY x ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) FROM t";

    /** A window function whose result(), when armed, runs {@code action}; SQLite calls it while resetting the query. */
    private static void windowFunction(Connection c, AtomicBoolean armed, Runnable action) throws SQLiteException {
        c.createWindowFunction("w", 1, () -> new WindowFunction() {
            public void step(FunctionArgs a) {
            }

            public void inverse(FunctionArgs a) {
            }

            public Object value() {
                return 0;
            }

            public Object result() {
                if (armed.getAndSet(false)) {
                    action.run();
                }
                return 0;
            }
        });
    }

    // ------------------------------------------------------------------ user code inside sqlite3_reset / finalize

    @Test
    void closingTheConnectionFromAnAggregateRunDuringResetIsRefused() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            exec(c, "CREATE TABLE t (x); INSERT INTO t VALUES (1), (2), (3), (4), (5)");
            AtomicBoolean armed = new AtomicBoolean();
            AtomicReference<RuntimeException> refused = new AtomicReference<>();
            windowFunction(c, armed, () -> {
                try {
                    c.close();
                } catch (RuntimeException e) {
                    refused.set(e);
                    throw e;
                }
            });
            try (PreparedStatement ps = c.prepareStatement(WINDOW_QUERY)) {
                ResultSet rs = ps.executeQuery();
                rs.next();
                armed.set(true);
                rs.close();   // sqlite3_reset finalizes the pending window aggregate: result() runs here
            }
            assertFalse(armed.get(), "result() did not run during the reset");
            assertInstanceOf(IllegalStateException.class, refused.get());
            assertFalse(c.isClosed());
            assertEquals(5, count(c, "t"));
        }
    }

    @Test
    void closingTheStatementFromAnAggregateRunDuringResetIsRefused() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            exec(c, "CREATE TABLE t (x); INSERT INTO t VALUES (1), (2), (3), (4), (5)");
            PreparedStatement[] ps = new PreparedStatement[1];
            AtomicBoolean armed = new AtomicBoolean();
            AtomicReference<RuntimeException> refused = new AtomicReference<>();
            windowFunction(c, armed, () -> {
                try {
                    ps[0].close();
                } catch (RuntimeException e) {
                    refused.set(e);
                    throw e;
                }
            });
            ps[0] = c.prepareStatement(WINDOW_QUERY);
            ResultSet rs = ps[0].executeQuery();
            rs.next();
            armed.set(true);
            rs.close();
            assertInstanceOf(IllegalStateException.class, refused.get());
            try (ResultSet again = ps[0].executeQuery()) {   // the statement still works
                assertTrue(again.next());
            }
            ps[0].close();
        }
    }

    // ------------------------------------------------------------------ registration from inside callbacks

    @Test
    void functionsCannotRemoveThemselvesWhileSqliteRunsThem() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            c.createFunction("f", 1, a -> {
                c.removeFunction("f", 1);
                return 1;
            });
            SQLiteException e = assertThrows(SQLiteException.class, () -> {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT f(1)")) {
                    rs.next();
                }
            });
            assertTrue(String.valueOf(e.getMessage()).contains("not possible from inside a function"), e.getMessage());
            c.createFunction("g", 0, a -> {
                c.createCollation("c", String::compareTo);
                return 1;
            });
            assertThrows(SQLiteException.class, () -> {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT g()")) {
                    rs.next();
                }
            });
            c.removeFunction("f", 1);   // fine outside
        }
    }

    // ------------------------------------------------------------------ backup destinations

    @Test
    void theBackupDestinationCannotBeUsedFromTheProgressCallback(@TempDir Path dir) throws SQLiteException {
        String src = dir.resolve("src.db").toString();
        try (Connection s = SQLite.getConnection(src)) {
            exec(s, "CREATE TABLE big (v BLOB); INSERT INTO big WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL "
                    + "SELECT i + 1 FROM n WHERE i < 200) SELECT randomblob(3000) FROM n");
        }
        try (Connection dest = SQLite.getConnection(dir.resolve("dest.db").toString())) {
            exec(dest, "CREATE TABLE x (v)");
            AtomicReference<SQLiteException> refused = new AtomicReference<>();
            dest.restore(src, BackupOptions.DEFAULT.withPagesPerStep(1).withProgress((remaining, total) -> {
                try {
                    exec(dest, "INSERT INTO x VALUES (1)");
                } catch (SQLiteException e) {
                    refused.compareAndSet(null, e);
                }
                return true;
            }));
            assertTrue(refused.get().getMessage().contains("destination of a running backup"), refused.get().getMessage());
            try (Statement st = dest.createStatement(); ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                rs.next();
                assertEquals("ok", rs.getString(1));
            }
            assertEquals(200, count(dest, "big"));
            exec(dest, "CREATE TABLE y (v)");   // usable again afterwards
        }
    }

    @Test
    void theBackupSourceStaysUsableFromTheProgressCallback(@TempDir Path dir) throws SQLiteException {
        try (Connection src = SQLite.getConnection(dir.resolve("src.db").toString())) {
            exec(src, "CREATE TABLE big (v BLOB); INSERT INTO big WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL "
                    + "SELECT i + 1 FROM n WHERE i < 100) SELECT randomblob(3000) FROM n");
            long[] seen = {-1};
            src.backup(dir.resolve("copy.db").toString(), BackupOptions.DEFAULT.withPagesPerStep(5).withProgress((r, t) -> {
                try {
                    seen[0] = count(src, "big");
                } catch (SQLiteException e) {
                    throw new IllegalStateException(e);
                }
                return true;
            }));
            assertEquals(100, seen[0]);
        }
    }

    // ------------------------------------------------------------------ fts3_tokenizer

    @Test
    void fts3TokenizerCannotInstallOrLeakNativePointers() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            // Builds without FTS3 have no such function at all; with FTS3 it must be disabled.
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT fts3_tokenizer('simple')")) {
                rs.next();
                assertNull(rs.getObject(1), "the address of a native tokenizer module leaked to SQL");
            } catch (SQLiteException expected) {
                // "no such function" or "fts3tokenize disabled"
            }
            assertThrows(SQLiteException.class, () -> {
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT fts3_tokenizer('evil', x'4141414141414141')")) {
                    rs.next();
                }
            });
        }
    }
}
