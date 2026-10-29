package io.github.revsnai.ffmsqlite.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static io.github.revsnai.ffmsqlite.db.ReviewFixes2Test.count;
import static io.github.revsnai.ffmsqlite.db.ReviewFixes3Test.exec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Bounding the native memory SQLite allocates: run-time limits, heap limits, per-connection usage. */
class MemoryLimitsTest {

    @AfterEach
    void removeHeapLimits() {
        SQLite.setHardHeapLimit(0);
        SQLite.setSoftHeapLimit(0);
    }

    private static SQLiteException fails(Connection c, String sql) {
        return assertThrows(SQLiteException.class, () -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                rs.next();
            }
        }, sql);
    }

    private static long longValue(Connection c, String sql) throws SQLiteException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------ sqlite3_limit

    @Test
    void theLengthLimitStopsHugeValuesBeforeTheyAreAllocated() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:", SQLite.Config.DEFAULT.withLimit(SQLite.Limit.LENGTH, 1_000_000))) {
            assertEquals(1_000_000, c.getLimit(SQLite.Limit.LENGTH));
            assertEquals(999_999, longValue(c, "SELECT length(randomblob(999999))"));
            for (String sql : new String[] {"SELECT randomblob(2000000)", "SELECT zeroblob(2000000)",
                    "SELECT length(replace(hex(randomblob(4000)), 'A', hex(randomblob(4000))))",
                    "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 200000) "
                            + "SELECT length(group_concat(printf('%010d', i))) FROM n"}) {
                assertEquals(Native.SQLITE_TOOBIG, fails(c, sql).getErrorCode(), sql);
            }
            assertEquals(1, longValue(c, "SELECT 1"));   // the connection is unaffected
        }
    }

    @Test
    void limitsCanBeReadAndChangedPerConnection() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            int previous = c.setLimit(SQLite.Limit.SQL_LENGTH, 100);
            assertTrue(previous > 100);
            assertEquals(100, c.getLimit(SQLite.Limit.SQL_LENGTH));
            assertEquals(Native.SQLITE_TOOBIG, fails(c, "SELECT '" + "x".repeat(200) + "'").getErrorCode());
            c.setLimit(SQLite.Limit.SQL_LENGTH, previous);
            assertEquals(1, longValue(c, "SELECT 1 /* " + "x".repeat(200) + " */"));

            c.setLimit(SQLite.Limit.ATTACHED, 0);
            fails(c, "ATTACH ':memory:' AS other");
            assertThrows(IllegalArgumentException.class, () -> c.setLimit(SQLite.Limit.COLUMN, -1));
        }
        try (Connection other = SQLite.getConnection(":memory:")) {
            assertTrue(other.getLimit(SQLite.Limit.SQL_LENGTH) > 100, "limits are per connection");
        }
    }

    @Test
    void limitsAboveTheCompileTimeMaximumAreLoweredToIt() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            int max = c.getLimit(SQLite.Limit.VARIABLE_NUMBER);
            c.setLimit(SQLite.Limit.VARIABLE_NUMBER, Integer.MAX_VALUE);
            assertTrue(c.getLimit(SQLite.Limit.VARIABLE_NUMBER) <= Math.max(max, 250_000) * 4);
        }
    }

    @Test
    void configValidatesLimits() {
        assertThrows(IllegalArgumentException.class, () -> SQLite.Config.DEFAULT.withLimit(SQLite.Limit.LENGTH, -1));
        assertThrows(IllegalArgumentException.class, () -> SQLite.Config.DEFAULT.withCacheSizeKib(0));
        SQLite.Config c = SQLite.Config.DEFAULT.withLimit(SQLite.Limit.LENGTH, 5).withLimit(SQLite.Limit.LENGTH, 6);
        assertEquals(Map.of(SQLite.Limit.LENGTH, 6), c.limits());
        assertEquals("-512", SQLite.Config.DEFAULT.withCacheSizeKib(512).pragmas().get("cache_size"));
    }

    // ------------------------------------------------------------------ per-connection usage

    @Test
    void memoryUsageReportsCacheSchemaAndStatementsAndReleaseMemoryEmptiesTheCache(@TempDir Path dir) throws SQLiteException {
        try (Connection c = SQLite.getConnection(dir.resolve("m.db").toString())) {
            exec(c, "CREATE TABLE t (v BLOB); INSERT INTO t WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 "
                    + "FROM n WHERE i < 500) SELECT randomblob(1000) FROM n");
            assertEquals(500, count(c, "t"));
            Connection.MemoryUsage before = c.memoryUsage();
            assertTrue(before.pageCache() > 400_000, before.toString());
            assertTrue(before.schema() > 0, before.toString());
            assertEquals(before.pageCache() + before.schema() + before.statements(), before.total());
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM t WHERE v > ?")) {
                assertTrue(c.memoryUsage().statements() > 0);
            }
            c.releaseMemory();
            Connection.MemoryUsage after = c.memoryUsage();
            assertTrue(after.pageCache() < before.pageCache() / 4, before + " -> " + after);
            assertEquals(500, count(c, "t"));
        }
    }

    @Test
    void cacheSizeBoundsThePageCache(@TempDir Path dir) throws SQLiteException {
        try (Connection c = SQLite.getConnection(dir.resolve("c.db").toString(), SQLite.Config.DEFAULT.withCacheSizeKib(256))) {
            exec(c, "CREATE TABLE t (v BLOB); INSERT INTO t WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 "
                    + "FROM n WHERE i < 2000) SELECT randomblob(1000) FROM n");
            assertEquals(2000, count(c, "t"));
            long cache = c.memoryUsage().pageCache();
            assertTrue(cache < 600_000, "page cache " + cache + " for a 256 KiB cache_size");
        }
    }

    // ------------------------------------------------------------------ heap limits

    @Test
    void theHardHeapLimitFailsTheStatementWithNomemAndTheConnectionRecovers() throws SQLiteException {
        assertTrue(SQLite.memoryStatisticsEnabled(), "tests run with -Dffmsqlite.memstatus=true");
        try (Connection c = SQLite.getConnection(":memory:")) {
            exec(c, "CREATE TABLE t (x)");
            long limit = SQLite.memoryUsed() + 16 * 1024 * 1024;
            assertEquals(0, SQLite.setHardHeapLimit(limit));
            assertEquals(limit, SQLite.hardHeapLimit());

            assertEquals(Native.SQLITE_NOMEM, fails(c, "SELECT length(randomblob(64000000))").getErrorCode());
            assertTrue(SQLite.memoryUsed() <= limit);
            assertEquals(1_000_000, longValue(c, "SELECT length(randomblob(1000000))"));

            c.setAutoCommit(false);
            exec(c, "INSERT INTO t VALUES (1)");
            fails(c, "SELECT length(randomblob(64000000))");
            try {
                c.commit();
                assertEquals(1, count(c, "t"));
            } catch (SQLiteTransactionRolledBackException e) {
                c.rollback();   // SQLite may roll back on SQLITE_NOMEM; the loss is reported, not hidden
            }
            c.setAutoCommit(true);

            assertEquals(limit, SQLite.setHardHeapLimit(0));
            assertEquals(64_000_000, longValue(c, "SELECT length(randomblob(64000000))"));
        }
    }

    @Test
    void aHeapLimitPragmaInTheConfigIsAppliedAndChecked() throws SQLiteException {
        long limit = SQLite.memoryUsed() + 256L * 1024 * 1024;
        try (Connection c = SQLite.getConnection(":memory:", SQLite.Config.DEFAULT.withPragma("hard_heap_limit", limit))) {
            assertEquals(limit, SQLite.hardHeapLimit());
        }
        assertThrows(SQLiteException.class,
                () -> SQLite.getConnection(":memory:", SQLite.Config.DEFAULT.withPragma("soft_heap_limit", "lots")));
    }

    @Test
    void theSoftHeapLimitCapsCachesWithoutFailingStatements(@TempDir Path dir) throws SQLiteException {
        try (Connection c = SQLite.getConnection(dir.resolve("s.db").toString(), SQLite.Config.DEFAULT.withCacheSizeKib(64 * 1024))) {
            exec(c, "CREATE TABLE t (v BLOB)");
            SQLite.setSoftHeapLimit(SQLite.memoryUsed() + 2 * 1024 * 1024);
            exec(c, "INSERT INTO t WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 "
                    + "FROM n WHERE i < 20000) SELECT randomblob(1000) FROM n");
            assertEquals(20000, count(c, "t"));   // ~20 MB of pages through a cache held near 2 MB
            assertTrue(c.memoryUsage().pageCache() < 8 * 1024 * 1024, c.memoryUsage().toString());
        }
    }

    /** Every allocation SQLite makes can fail under a hard limit; each failure must surface as SQLiteException. */
    @Test
    void outOfMemoryAnywhereIsAnSQLiteExceptionNeverACrash(@TempDir Path dir) {
        String path = dir.resolve("oom.db").toString();
        int completed = 0;
        for (long headroom = 0; headroom <= 1_200_000; headroom += 6_000) {
            SQLite.setHardHeapLimit(SQLite.memoryUsed() + headroom);
            try (Connection c = SQLite.getConnection(path)) {
                exec(c, "CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY, s TEXT, b BLOB)");
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO t (s, b) VALUES (?, ?)")) {
                    ps.setString(1, "x".repeat(5000));
                    ps.setBytes(2, new byte[20_000]);
                    ps.executeUpdate();
                }
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT id, s, b, printf('%d-%s', id, s) FROM t ORDER BY s DESC")) {
                    while (rs.next()) {
                        rs.getString(2);
                        rs.getBytes(3);
                        rs.getString(4);
                    }
                }
                completed++;
            } catch (SQLiteException expected) {
                // SQLITE_NOMEM (or a consequence of it), from open, prepare, bind, step or a column read
            } catch (RuntimeException | Error e) {
                fail("headroom " + headroom + ": " + e, e);
            } finally {
                SQLite.setHardHeapLimit(0);
            }
        }
        assertTrue(completed > 0, "the largest headroom should have been enough");
    }
}
