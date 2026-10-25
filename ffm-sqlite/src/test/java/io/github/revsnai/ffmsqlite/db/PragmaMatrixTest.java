package io.github.revsnai.ffmsqlite.db;

import io.github.revsnai.ffmsqlite.db.SQLite.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every connection-level PRAGMA SQLite has, with a range of values, set through {@link Config#withPragma}: the value
 * must read back, and the whole driver (prepare with arena-allocated SQL, binding, reading text and blobs out of
 * SQLite's memory, UDF and collation upcalls, backup, serialize/deserialize) must keep working under it.
 */
class PragmaMatrixTest {
    @TempDir
    Path dir;

    static final int ROWS = 120;

    /** pragma, value, expected read-back ({@code null}: the pragma has no getter or reports something else). */
    static Stream<Arguments> settable() {
        List<Arguments> a = new ArrayList<>();
        add(a, "analysis_limit", 0, "0", 100, "100");
        add(a, "application_id", 0, "0", -1, "-1", Integer.MAX_VALUE, "2147483647");
        add(a, "auto_vacuum", "NONE", "0", "FULL", "1", "INCREMENTAL", "2");
        add(a, "automatic_index", true, "1", false, "0");
        add(a, "busy_timeout", 0, "0", 10_000, "10000");
        add(a, "cache_size", 0, "0", 100, "100", -2000, "-2000", -100_000, "-100000");
        add(a, "cache_spill", true, null, false, "0", 50, null);   // when on, reports the spill threshold in pages
        add(a, "case_sensitive_like", true, null, false, null);
        add(a, "cell_size_check", true, "1");
        add(a, "checkpoint_fullfsync", true, "1");
        add(a, "default_cache_size", 500, "500");
        add(a, "defer_foreign_keys", true, "1");
        add(a, "empty_result_callbacks", true, "1");
        add(a, "encoding", "UTF-8", "UTF-8", "UTF-16le", "UTF-16le", "UTF-16be", "UTF-16be", "UTF-16", null);
        add(a, "foreign_keys", true, "1", false, "0");
        add(a, "fullfsync", true, "1");
        add(a, "ignore_check_constraints", true, "1");
        add(a, "journal_mode", "DELETE", "delete", "TRUNCATE", "truncate", "PERSIST", "persist", "MEMORY", "memory",
                "WAL", "wal", "OFF", "off");
        add(a, "journal_size_limit", -1, "-1", 0, "0", 67_108_864, "67108864");
        add(a, "legacy_alter_table", true, "1");
        add(a, "locking_mode", "NORMAL", "normal", "EXCLUSIVE", "exclusive");
        add(a, "max_page_count", 1_000_000, "1000000");
        add(a, "mmap_size", 0, "0", 268_435_456, null);   // read-back is capped by SQLITE_MAX_MMAP_SIZE
        add(a, "read_uncommitted", true, "1");
        add(a, "recursive_triggers", true, "1");
        add(a, "reverse_unordered_selects", true, "1");
        add(a, "secure_delete", "ON", "1", "FAST", "2", "OFF", "0");
        add(a, "synchronous", "OFF", "0", "NORMAL", "1", "FULL", "2", "EXTRA", "3");
        add(a, "temp_store", "DEFAULT", "0", "FILE", "1", "MEMORY", "2");
        add(a, "threads", 0, "0", 4, null);   // capped by SQLITE_MAX_WORKER_THREADS
        add(a, "trusted_schema", true, "1", false, "0");
        add(a, "user_version", 7, "7", -1, "-1");
        add(a, "wal_autocheckpoint", 0, "0", 100, "100");
        add(a, "writable_schema", true, "1");
        // Pragmas that act rather than set: "= value" is the same as "(value)", and their rows are discarded.
        add(a, "optimize", "0x10002", null);
        add(a, "wal_checkpoint", "TRUNCATE", null);
        add(a, "shrink_memory", 1, null);
        add(a, "incremental_vacuum", 10, null);
        add(a, "integrity_check", 1, null);
        add(a, "quick_check", 1, null);
        return a.stream();
    }

    private static void add(List<Arguments> a, String pragma, Object... valueExpected) {
        for (int i = 0; i < valueExpected.length; i += 2) {
            a.add(Arguments.of(pragma, valueExpected[i], valueExpected[i + 1]));
        }
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("settable")
    void fileDatabase(String pragma, Object value, String expected) throws SQLiteException {
        check(dir.resolve("p.db").toString(), pragma, value, expected);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("settable")
    void walFileDatabase(String pragma, Object value, String expected) throws SQLiteException {
        Config config = Config.DEFAULT.withPragma("journal_mode", "WAL").withPragma(pragma, value);
        if (pragma.equals("journal_mode") || pragma.equals("locking_mode")) {
            config = Config.DEFAULT.withPragma(pragma, value);
        }
        workout(dir.resolve("w.db").toString(), config, pragma, expected);
        workout(dir.resolve("w.db").toString(), config, pragma, expected);   // reopen an existing database
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("settable")
    void memoryDatabase(String pragma, Object value, String expected) throws SQLiteException {
        if (pragma.equals("journal_mode") && !value.equals("MEMORY") && !value.equals("OFF")) {
            expected = "memory";   // in-memory databases support only MEMORY and OFF
        }
        if (pragma.equals("mmap_size")) {
            expected = null;   // in-memory databases are never memory-mapped: no row
        }
        check(":memory:", pragma, value, expected);
    }

    private void check(String path, String pragma, Object value, String expected) throws SQLiteException {
        workout(path, Config.DEFAULT.withPragma(pragma, value), pragma, expected);
    }

    /** All pragmas at once, in an order where the file-format ones come last (the driver must run them first). */
    @Test
    void allTogether() throws SQLiteException {
        Config config = Config.DEFAULT
                .withPragma("journal_mode", "WAL").withPragma("synchronous", "NORMAL").withPragma("foreign_keys", true)
                .withPragma("cache_size", -8000).withPragma("mmap_size", 268_435_456).withPragma("temp_store", "MEMORY")
                .withPragma("secure_delete", "FAST").withPragma("journal_size_limit", 1 << 20)
                .withPragma("wal_autocheckpoint", 200).withPragma("automatic_index", false).withPragma("threads", 2)
                .withPragma("recursive_triggers", true).withPragma("trusted_schema", false)
                .withPragma("application_id", 0x46464D51).withPragma("user_version", 3)
                .withPragma("page_size", 8192).withPragma("auto_vacuum", "INCREMENTAL").withPragma("encoding", "UTF-16le");
        String path = dir.resolve("all.db").toString();
        for (int i = 0; i < 3; i++) {
            workout(path, config, "page_size", "8192");
            try (Connection c = SQLite.getConnection(path, config)) {
                assertEquals("UTF-16le", one(c, "PRAGMA encoding"));
                assertEquals("2", one(c, "PRAGMA auto_vacuum"));
                assertEquals("wal", one(c, "PRAGMA journal_mode"));
            }
        }
    }

    @ParameterizedTest(name = "page_size = {0}")
    @MethodSource("pageSizes")
    void pageSizes(int size) throws SQLiteException {
        workout(dir.resolve("ps" + size + ".db").toString(),
                Config.DEFAULT.withPragma("journal_mode", "WAL").withPragma("page_size", size), "page_size", String.valueOf(size));
    }

    static Stream<Integer> pageSizes() {
        return Stream.of(512, 1024, 4096, 16384, 65536);
    }

    // ------------------------------------------------------------------ pragmas that make the workout fail on purpose

    @Test
    void queryOnlyRejectsWritesButReadsWork() throws SQLiteException {
        String path = dir.resolve("q.db").toString();
        workout(path, Config.DEFAULT, null, null);
        try (Connection c = SQLite.getConnection(path, Config.DEFAULT.withPragma("query_only", true))) {
            assertEquals("1", one(c, "PRAGMA query_only"));
            verifyRows(c);
            try (Statement st = c.createStatement()) {
                assertThrows(SQLiteException.class, () -> st.executeUpdate("DELETE FROM t"));
            }
            verifyRows(c);
        }
    }

    @Test
    void maxPageCountTooSmallFailsWithFullAndTheConnectionRecovers() throws SQLiteException {
        try (Connection c = SQLite.getConnection(dir.resolve("full.db").toString(), Config.DEFAULT.withPragma("max_page_count", 8));
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (b BLOB)");
            c.setAutoCommit(false);
            SQLiteException e = assertThrows(SQLiteException.class,
                    () -> st.executeUpdate("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 10) INSERT INTO t SELECT randomblob(10000) FROM n"));
            assertEquals(13, e.getErrorCode() & 0xff, "SQLITE_FULL");
            c.rollback();
            c.setAutoCommit(true);
            assertEquals("0", one(c, "SELECT count(*) FROM t"));
            assertEquals(1, st.executeUpdate("INSERT INTO t VALUES (x'00')"));
        }
    }

    @Test
    void readOnlyConnectionWithWritingPragmas() throws SQLiteException {
        String path = dir.resolve("ro.db").toString();
        workout(path, Config.DEFAULT, null, null);
        // Settings that only affect this connection work on a read-only one.
        Config ro = Config.DEFAULT.withReadOnly(true).withPragma("cache_size", -1000).withPragma("mmap_size", 1 << 24)
                .withPragma("temp_store", "MEMORY").withPragma("query_only", true);
        try (Connection c = SQLite.getConnection(path, ro)) {
            verifyRows(c);
        }
        // One that writes the file fails the open with SQLite's error, and leaks nothing (the handle is closed).
        assertThrows(SQLiteException.class,
                () -> SQLite.getConnection(path, Config.DEFAULT.withReadOnly(true).withPragma("user_version", 9)));
    }

    // ------------------------------------------------------------------ process-wide pragmas

    /**
     * soft/hard_heap_limit are process-wide (all connections, in every thread): setting them in a Config changes
     * them for the whole JVM. Reset at the end so other tests are unaffected.
     */
    @Test
    void heapLimitsAreProcessWide() throws SQLiteException {
        try {
            try (Connection a = SQLite.getConnection(":memory:", Config.DEFAULT.withPragma("soft_heap_limit", 64 << 20)
                    .withPragma("hard_heap_limit", 256 << 20))) {
                assertEquals(String.valueOf(64 << 20), one(a, "PRAGMA soft_heap_limit"));
                try (Connection b = SQLite.getConnection(":memory:")) {
                    assertEquals(String.valueOf(256 << 20), one(b, "PRAGMA hard_heap_limit"), "seen by another connection");
                }
            }
            workout(dir.resolve("h.db").toString(), Config.DEFAULT.withPragma("soft_heap_limit", 4 << 20), null, null);
        } finally {
            resetHeapLimits();
        }
    }

    /**
     * Out of memory while SQLite converts a value for reading (here UTF-16 text to UTF-8) must be an error, not a
     * silent SQL NULL: sqlite3_column_text returns NULL then.
     */
    @Test
    void outOfMemoryWhileReadingTextIsNotNull() throws SQLiteException {
        String big = "é".repeat(4_000_000);
        try (Connection c = SQLite.getConnection(dir.resolve("oom.db").toString(), Config.DEFAULT.withPragma("encoding", "UTF-16le"));
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (s TEXT)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (?)")) {
                ps.setString(1, big);
                ps.executeUpdate();
            }
            c.close();
            try (Connection r = SQLite.getConnection(dir.resolve("oom.db").toString());
                 PreparedStatement ps = r.prepareStatement("SELECT s FROM t")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());   // the 8 MB UTF-16 value is now in SQLite's memory
                    hardHeapLimit(memoryUsed() + 1_000_000);   // too little for the 8 MB UTF-8 copy
                    String value;
                    try {
                        value = rs.getString(1);
                    } catch (SQLiteException e) {
                        return;   // reported: fine
                    } finally {
                        hardHeapLimit(0);
                    }
                    assertNotNull(value, "out of memory was returned as SQL NULL");
                    assertEquals(big.length(), value.length());
                }
            }
        } finally {
            resetHeapLimits();
        }
    }

    private static void resetHeapLimits() {
        hardHeapLimit(0);
        try {
            HEAP.softLimit.invokeExact(0L);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** Direct calls: once the hard limit is reached, no SQL (not even the PRAGMA to lift it) can run. */
    private static void hardHeapLimit(long bytes) {
        try {
            long ignored = (long) HEAP.hardLimit.invokeExact(bytes);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    private static long memoryUsed() {
        try {
            return (long) HEAP.memoryUsed.invokeExact();
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    private record Heap(MethodHandle hardLimit, MethodHandle softLimit, MethodHandle memoryUsed) {
    }

    private static final Heap HEAP = heap();

    private static Heap heap() {
        try {
            Field f = Native.class.getDeclaredField("LIB");
            f.setAccessible(true);
            SymbolLookup lib = (SymbolLookup) f.get(null);
            Linker linker = Linker.nativeLinker();
            return new Heap(
                    linker.downcallHandle(lib.find("sqlite3_hard_heap_limit64").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, JAVA_LONG)),
                    linker.downcallHandle(lib.find("sqlite3_soft_heap_limit64").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, JAVA_LONG))
                            .asType(MethodType.methodType(void.class, long.class)),
                    linker.downcallHandle(lib.find("sqlite3_memory_used").orElseThrow(), FunctionDescriptor.of(JAVA_LONG)));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------ interactions with the driver's own settings

    /** A busy_timeout PRAGMA must survive statements that run with a query timeout. */
    @Test
    void busyTimeoutPragmaSurvivesQueryTimeouts() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:", Config.DEFAULT.withPragma("busy_timeout", 10_000))) {
            assertEquals("10000", one(c, "PRAGMA busy_timeout"));
            try (PreparedStatement ps = c.prepareStatement("SELECT 1")) {
                ps.setQueryTimeout(5);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                }
            }
            assertEquals("10000", one(c, "PRAGMA busy_timeout"));
        }
    }

    /** The same through SQL at run time, from a script, a prepared statement, and replacing a custom busy handler. */
    @Test
    void busyTimeoutSetBySqlSurvivesQueryTimeouts() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:"); Statement st = c.createStatement()) {
            st.setQueryTimeout(5);
            st.execute("CREATE TABLE t (v); PRAGMA busy_timeout = 7000; INSERT INTO t VALUES (1)");
            assertEquals("7000", one(c, "SELECT 1") == null ? null : timeoutAfterTimedQuery(c));
            try (PreparedStatement ps = c.prepareStatement("pragma Busy_Timeout(8000)")) {
                ps.executeQuery().close();
            }
            assertEquals("8000", timeoutAfterTimedQuery(c));
            c.setBusyHandler(count -> false);
            st.executeQuery("PRAGMA main.busy_timeout = 9000").close();   // SQLite drops the handler for its timeout
            assertEquals("9000", timeoutAfterTimedQuery(c));
            st.executeUpdate("INSERT INTO t VALUES ('PRAGMA busy_timeout = 1')");   // only mentions it
            assertEquals("9000", timeoutAfterTimedQuery(c));
        }
    }

    private static String timeoutAfterTimedQuery(Connection c) throws SQLiteException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM t")) {
            ps.setQueryTimeout(5);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
            }
        }
        return one(c, "PRAGMA busy_timeout");
    }

    /** Zero-length text and blobs read as empty values, not NULL. */
    @Test
    void emptyValuesAreNotNull() throws SQLiteException {
        for (String encoding : List.of("UTF-8", "UTF-16le")) {
            try (Connection c = SQLite.getConnection(":memory:", Config.DEFAULT.withPragma("encoding", encoding));
                 Statement st = c.createStatement()) {
                c.createFunction("len_text", 1, args -> args.getString(0) == null ? -1 : args.getString(0).length());
                c.createFunction("len_blob", 1, args -> args.getBytes(0) == null ? -1 : args.getBytes(0).length);
                try (ResultSet rs = st.executeQuery("SELECT '', x'', zeroblob(0), len_text(''), len_text(x''), len_blob(''), len_blob(x''), NULL")) {
                    assertTrue(rs.next());
                    assertEquals("", rs.getString(1));
                    assertEquals("", rs.getString(2));
                    assertArrayEquals(new byte[0], rs.getBytes(3));
                    assertArrayEquals(new byte[0], rs.getBytes(1));
                    for (int i = 4; i <= 7; i++) {
                        assertEquals(0, rs.getInt(i), encoding + " column " + i);
                    }
                    assertEquals(null, rs.getString(8));
                    assertEquals(null, rs.getBytes(8));
                }
            }
        }
    }

    /** count_changes (deprecated) makes INSERT/UPDATE/DELETE return a row: update counts must stay right. */
    @Test
    void countChanges() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:", Config.DEFAULT.withPragma("count_changes", true));
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (v)");
            assertEquals(3, st.executeUpdate("INSERT INTO t VALUES (1), (2), (3)"));
            try (PreparedStatement ps = c.prepareStatement("UPDATE t SET v = v + 1 WHERE v > ?")) {
                ps.setInt(1, 1);
                assertEquals(2, ps.executeUpdate());
                ps.setInt(1, 0);
                ps.addBatch();
                ps.setInt(1, 100);
                ps.addBatch();
                assertArrayEquals(new int[] {3, 0}, ps.executeBatch());
            }
        }
    }

    /** full_column_names / short_column_names (deprecated) change column names; reading by label still works. */
    @Test
    void columnNamePragmas() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:", Config.DEFAULT.withPragma("short_column_names", false)
                .withPragma("full_column_names", true)); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (v); INSERT INTO t VALUES (5)");
            try (ResultSet rs = st.executeQuery("SELECT v FROM t")) {
                assertTrue(rs.next());
                assertEquals("t.v", rs.getMetaData().getColumnName(1));
                assertEquals(5, rs.getInt("t.v"));
            }
        }
    }

    @Test
    void schemaQualifiedPragmaName() throws SQLiteException {
        Config c = Config.DEFAULT.withPragma("main.cache_size", -3000);
        try (Connection conn = SQLite.getConnection(":memory:", c)) {
            assertEquals("-3000", one(conn, "PRAGMA main.cache_size"));
        }
    }

    // ------------------------------------------------------------------ the workout

    private static void workout(String path, Config config, String pragma, String expected) throws SQLiteException {
        try (Connection c = SQLite.getConnection(path, config)) {
            if (expected != null) {
                assertEquals(expected.toLowerCase(Locale.ROOT), one(c, "PRAGMA " + pragma).toLowerCase(Locale.ROOT));
            }
            c.createFunction("rev", 1, args -> args.getString(0) == null ? null : new StringBuilder(args.getString(0)).reverse().toString());
            c.createCollation("BACKWARDS", Comparator.<String>naturalOrder().reversed());
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DROP TABLE IF EXISTS t; CREATE TABLE t (id INTEGER PRIMARY KEY, s TEXT, b BLOB, r REAL, n INTEGER)");
            }
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t (id, s, b, r, n) VALUES (?, ?, ?, ?, ?)")) {
                for (int i = 1; i <= ROWS; i++) {
                    ps.setInt(1, i);
                    ps.setString(2, text(i));
                    ps.setBytes(3, blob(i));
                    ps.setDouble(4, i / 3.0);
                    ps.setLong(5, (long) i << 40);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
            c.setAutoCommit(true);
            verifyRows(c);

            assertEquals(new StringBuilder(text(7)).reverse().toString(), one(c, "SELECT rev(s) FROM t WHERE id = 7"));
            assertEquals("cba", one(c, "SELECT group_concat(x, '') FROM (SELECT column1 AS x FROM (VALUES ('a'), ('c'), ('b')) "
                    + "ORDER BY x COLLATE BACKWARDS)"));
            assertEquals(String.valueOf(ROWS * (ROWS + 1) / 2), one(c,
                    "SELECT sum(id) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) FROM t LIMIT 1"));

            try (Connection copy = SQLite.getConnection(":memory:")) {
                c.backup(copy);
                verifyRows(copy);
            }
            if (!path.equals(":memory:") || !"journal_mode".equals(pragma) || !"off".equals(expected)) {
                byte[] image = c.serialize();
                try (Connection copy = SQLite.getConnection(":memory:")) {
                    copy.deserialize(image);
                    verifyRows(copy);
                }
            }
        }
    }

    private static void verifyRows(Connection c) throws SQLiteException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, s, b, r, n FROM t ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            int i = 0;
            while (rs.next()) {
                i++;
                assertEquals(i, rs.getInt(1));
                assertEquals(text(i), rs.getString(2), "text row " + i);
                assertArrayEquals(blob(i), rs.getBytes(3), "blob row " + i);
                assertEquals(i / 3.0, rs.getDouble(4));
                assertEquals((long) i << 40, rs.getLong(5));
            }
            assertEquals(ROWS, i);
        }
    }

    /** Mixed 1-, 2-, 3- and 4-byte UTF-8, some rows long enough for overflow pages. */
    static String text(int i) {
        String unit = "aé中😀" + i;
        return unit.repeat(i % 10 == 0 ? 2_000 : i);
    }

    static byte[] blob(int i) {
        byte[] b = new byte[i % 10 == 0 ? 100_000 + i : i * 37];
        for (int k = 0; k < b.length; k++) {
            b[k] = (byte) (k * 31 + i);
        }
        return b;
    }

    private static String one(Connection c, String sql) throws SQLiteException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getString(1);
        }
    }
}
