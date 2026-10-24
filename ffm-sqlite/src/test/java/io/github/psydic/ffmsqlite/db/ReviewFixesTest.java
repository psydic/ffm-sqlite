package io.github.psydic.ffmsqlite.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the issues found in the 0.1.0 code review. */
class ReviewFixesTest {

    // ------------------------------------------------------------------ metadata after close (was a SIGSEGV)

    @Test
    void resultSetMetaDataStaysValidAfterTheResultSetCloses() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:"); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (name TEXT, n INTEGER)");
            ResultSet rs = st.executeQuery("SELECT name, n AS count, 1 + 1 FROM t");
            ResultSetMetaData md = rs.getMetaData();
            rs.close();
            for (int i = 0; i < 20; i++) {
                st.executeQuery("SELECT 1 AS x, 2 AS y, 3 AS z").close();   // reuse the freed memory
            }
            assertEquals(3, md.getColumnCount());
            assertEquals("TEXT", md.getColumnTypeName(1));
            assertEquals("count", md.getColumnLabel(2));
            assertEquals("n", md.getColumnOriginName(2));
            assertEquals("t", md.getTableName(1));
            assertEquals("main", md.getSchemaName(1));
            assertNull(md.getColumnTypeName(3));
            assertThrows(SQLiteException.class, () -> md.getColumnLabel(4));
        }
    }

    @Test
    void preparedStatementMetaDataStaysValidAfterTheConnectionCloses() throws SQLiteException {
        ResultSetMetaData md;
        try (Connection c = SQLite.getConnection(":memory:")) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT)");
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT id, label FROM t WHERE id = ?")) {
                md = ps.getMetaData();
            }
        }
        assertEquals("TEXT", md.getColumnTypeName(2));
        assertEquals("label", md.getColumnLabel(2));
    }

    // ------------------------------------------------------------------ interrupt racing close (was a use-after-free)

    @Test
    void interruptConcurrentWithCloseIsSafe() throws Exception {
        for (int round = 0; round < 300; round++) {
            Connection c = SQLite.getConnection(":memory:");
            AtomicBoolean stop = new AtomicBoolean();
            Thread canceller = Thread.ofPlatform().start(() -> {
                while (!stop.get()) {
                    c.interrupt();
                }
            });
            Thread.onSpinWait();
            c.close();
            stop.set(true);
            canceller.join();
            assertTrue(c.isClosed());
        }
    }

    // ------------------------------------------------------------------ failed COMMIT in setAutoCommit(true)

    static final class Veto implements CommitListener {
        volatile boolean veto;

        @Override
        public void onCommit() {
            if (veto) {
                throw new IllegalStateException("vetoed");
            }
        }
    }

    @Test
    void vetoedCommitInSetAutoCommitTrueKeepsManualCommitMode() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE t (v INTEGER)");
            }
            Veto veto = new Veto();
            c.addCommitListener(veto);
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO t VALUES (1)");
            }
            veto.veto = true;
            SQLiteException e = assertThrows(SQLiteException.class, () -> c.setAutoCommit(true));
            assertInstanceOf(IllegalStateException.class, e.getCause());
            veto.veto = false;

            assertFalse(c.getAutoCommit(), "the switch did not happen");
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO t VALUES (2)");   // inside the new transaction...
            }
            c.rollback();                                       // ...so it can be rolled back
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM t")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
            c.setAutoCommit(true);
            assertTrue(c.getAutoCommit());
        }
    }

    // ------------------------------------------------------------------ trailing comments

    @Test
    void trailingCommentsAreNotAStatement() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            for (String sql : List.of("SELECT 7; -- load users", "SELECT 7 -- no semicolon", "SELECT 7;\n/* end */\n",
                    "SELECT 7; /* unterminated", "SELECT 7; -- a\n-- b\n  ;  ")) {
                try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), sql);
                    assertEquals(7, rs.getInt(1), sql);
                }
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    assertTrue(rs.next(), sql);
                    assertEquals(7, rs.getInt(1), sql);
                }
            }
            assertThrows(SQLiteException.class, () -> c.prepareStatement("SELECT 1; SELECT 2"));
            assertThrows(SQLiteException.class, () -> c.prepareStatement("SELECT 1; -- c\nSELECT 2"));
        }
    }

    // ------------------------------------------------------------------ executeBatch keeps the failure's type and cause

    @Test
    void batchFailureKeepsTimeoutType() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE t (n INTEGER)");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t WITH RECURSIVE r(x) AS (SELECT 1 UNION ALL "
                    + "SELECT x + 1 FROM r) SELECT count(*) FROM (SELECT x FROM r LIMIT ?)")) {
                ps.setQueryTimeout(Duration.ofMillis(100));
                ps.setLong(1, 1);
                ps.addBatch();
                ps.setLong(1, 2_000_000_000L);
                ps.addBatch();
                SQLiteTimeoutException e = assertThrows(SQLiteTimeoutException.class, ps::executeBatch);
                assertTrue(e.getMessage().startsWith("batch entry 1 failed"), e.getMessage());
            }
        }
    }

    @Test
    void batchFailureKeepsTheFunctionsException() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            c.createFunction("check_positive", 1, args -> {
                if (args.getLong(0) <= 0) {
                    throw new IllegalArgumentException("not positive: " + args.getLong(0));
                }
                return args.getLong(0);
            });
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE t (n INTEGER)");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (check_positive(?))")) {
                ps.setLong(1, 5);
                ps.addBatch();
                ps.setLong(1, -1);
                ps.addBatch();
                SQLiteException e = assertThrows(SQLiteException.class, ps::executeBatch);
                assertInstanceOf(IllegalArgumentException.class, e.getCause());
                assertTrue(e.getMessage().startsWith("batch entry 1 failed"), e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ locale-independent table types

    @Test
    void tableTypesUnderATurkishLocale() throws SQLiteException {
        Locale saved = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try (Connection c = SQLite.getConnection(":memory:")) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE t (v); CREATE VIEW v_t AS SELECT v FROM t");
            }
            List<DatabaseMetaData.TableInfo> tables = c.getMetaData().getTables();
            assertEquals(DatabaseMetaData.TableType.VIEW,
                    tables.stream().filter(t -> t.name().equals("v_t")).findFirst().orElseThrow().type());
        } finally {
            Locale.setDefault(saved);
        }
    }

    // ------------------------------------------------------------------ file-format PRAGMAs first

    @Test
    void pageSizeIsAppliedBeforeJournalMode(@TempDir Path dir) throws SQLiteException {
        SQLite.Config config = SQLite.Config.DEFAULT
                .withJournalMode(SQLite.JournalMode.WAL)   // listed first...
                .withPragma("page_size", 8192);             // ...but must run first
        try (Connection c = SQLite.getConnection(dir.resolve("new.db").toString(), config);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (v)");
            try (ResultSet rs = st.executeQuery("PRAGMA page_size")) {
                rs.next();
                assertEquals(8192, rs.getInt(1));
            }
            try (ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
                rs.next();
                assertEquals("wal", rs.getString(1));
            }
        }
    }

    // ------------------------------------------------------------------ re-registering functions and collations

    @SuppressWarnings("unchecked")
    private static int stubArenas(Connection c, String field) throws ReflectiveOperationException {
        Field f = Connection.class.getDeclaredField(field);
        f.setAccessible(true);
        return ((Map<String, ?>) f.get(c)).size();
    }

    @Test
    void reRegisteringKeepsOneSetOfStubsAndTheLatestDefinitionWins() throws Exception {
        try (Connection c = SQLite.getConnection(":memory:"); Statement st = c.createStatement()) {
            for (int i = 0; i < 500; i++) {
                int version = i;
                c.createFunction("version", 0, args -> version);
                c.createCollation("rev", (a, b) -> b.compareTo(a));
            }
            assertEquals(1, stubArenas(c, "functionStubs"));
            assertEquals(1, stubArenas(c, "collationStubs"));
            try (ResultSet rs = st.executeQuery("SELECT version()")) {
                rs.next();
                assertEquals(499, rs.getInt(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT x FROM (SELECT 'a' AS x UNION ALL SELECT 'b') ORDER BY x COLLATE rev")) {
                rs.next();
                assertEquals("b", rs.getString(1));
            }
            c.removeFunction("version", 0);
            c.removeCollation("rev");
            assertEquals(0, stubArenas(c, "functionStubs"));
            assertEquals(0, stubArenas(c, "collationStubs"));
            assertThrows(SQLiteException.class, () -> st.executeQuery("SELECT version()"));
        }
    }

    @Test
    void failedReplacementKeepsThePreviousDefinition() throws Exception {
        try (Connection c = SQLite.getConnection(":memory:"); Statement st = c.createStatement()) {
            c.createFunction("one", 0, args -> 1);
            try (ResultSet running = st.executeQuery("SELECT one() FROM (SELECT 1 UNION ALL SELECT 2)")) {
                running.next();   // a statement is running: SQLite refuses to replace the function
                assertThrows(SQLiteException.class, () -> c.createFunction("one", 0, args -> 2));
                assertTrue(running.next());
                assertEquals(1, running.getInt(1), "the old stubs must still be alive");
            }
            assertEquals(1, stubArenas(c, "functionStubs"));
        }
    }
}
