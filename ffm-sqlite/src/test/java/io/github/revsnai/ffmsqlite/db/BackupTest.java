package io.github.revsnai.ffmsqlite.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupTest {
    @TempDir
    Path dir;
    private Connection conn;

    @BeforeEach
    void open() throws SQLiteException {
        conn = SQLite.getConnection(":memory:");
        exec(conn, "CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT NOT NULL, payload BLOB); "
                + "CREATE INDEX ix_items_name ON items (name); "
                + "INSERT INTO items (name, payload) "
                + "WITH RECURSIVE s(v) AS (SELECT 1 UNION ALL SELECT v + 1 FROM s WHERE v < 5000) "
                + "SELECT 'item-' || v, randomblob(200) FROM s");
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

    private static String one(Connection c, String sql) throws SQLiteException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private String file(String name) {
        return dir.resolve(name).toString();
    }

    @Test
    void backupInMemoryDatabaseToFile() throws SQLiteException {
        conn.backup(file("b.db"));
        try (Connection c = SQLite.getConnection(file("b.db"))) {
            assertEquals("5000", one(c, "SELECT count(*) FROM items"));
            assertEquals("item-4242", one(c, "SELECT name FROM items WHERE id = 4242"));
            assertEquals("ix_items_name", one(c, "SELECT name FROM sqlite_schema WHERE type = 'index'"));
            assertEquals("ok", one(c, "PRAGMA integrity_check"));
        }
    }

    @Test
    void backupOverwritesAnExistingFile() throws SQLiteException {
        try (Connection c = SQLite.getConnection(file("b.db"))) {
            exec(c, "CREATE TABLE old_stuff (x); INSERT INTO old_stuff VALUES (1)");
        }
        conn.backup(file("b.db"));
        try (Connection c = SQLite.getConnection(file("b.db"))) {
            assertEquals("0", one(c, "SELECT count(*) FROM sqlite_schema WHERE name = 'old_stuff'"));
            assertEquals("5000", one(c, "SELECT count(*) FROM items"));
        }
    }

    @Test
    void restoreReplacesTheWholeDatabase() throws SQLiteException {
        conn.backup(file("b.db"));
        exec(conn, "DELETE FROM items WHERE id > 10; CREATE TABLE extra (x)");
        conn.restore(file("b.db"));
        assertEquals("5000", one(conn, "SELECT count(*) FROM items"));
        assertEquals("0", one(conn, "SELECT count(*) FROM sqlite_schema WHERE name = 'extra'"));
    }

    @Test
    void preparedStatementsSurviveARestoreThatChangesTheSchema() throws SQLiteException {
        try (Connection other = SQLite.getConnection(file("other.db"))) {
            exec(other, "CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT, extra TEXT); "
                    + "INSERT INTO items VALUES (1, 'restored', 'x')");
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM items WHERE id = ?")) {
            ps.setInt(1, 1);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("item-1", rs.getString(1));
            }
            conn.restore(file("other.db"));
            try (ResultSet rs = ps.executeQuery()) { // re-prepared by SQLite against the new schema
                assertTrue(rs.next());
                assertEquals("restored", rs.getString(1));
            }
        }
    }

    @Test
    void backupBetweenOpenConnections() throws SQLiteException {
        try (Connection copy = SQLite.getConnection(":memory:")) {
            exec(copy, "CREATE TABLE junk (x)");
            conn.backup(copy);
            assertEquals("5000", one(copy, "SELECT count(*) FROM items"));
            assertEquals("0", one(copy, "SELECT count(*) FROM sqlite_schema WHERE name = 'junk'"));
            assertThrows(SQLiteException.class, () -> conn.backup(conn));
        }
    }

    @Test
    void incrementalBackupReportsProgressAndIncludesConcurrentWrites() throws SQLiteException {
        List<int[]> calls = new ArrayList<>();
        conn.backup(file("b.db"), BackupOptions.DEFAULT.withPagesPerStep(10).withPause(Duration.ofMillis(1))
                .withProgress((remaining, total) -> {
                    if (calls.isEmpty()) {
                        try { // a write through the source connection mid-backup is copied too
                            exec(conn, "INSERT INTO items (name) VALUES ('written during backup')");
                        } catch (SQLiteException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    calls.add(new int[]{remaining, total});
                    return true;
                }));
        assertTrue(calls.size() > 10, "many steps: " + calls.size());
        assertEquals(0, calls.getLast()[0]);
        for (int i = 1; i < calls.size(); i++) {
            assertTrue(calls.get(i)[0] <= calls.get(i - 1)[0] || calls.get(i)[1] != calls.get(i - 1)[1], "remaining decreases");
        }
        try (Connection c = SQLite.getConnection(file("b.db"))) {
            assertEquals("5001", one(c, "SELECT count(*) FROM items"));
            assertEquals("1", one(c, "SELECT count(*) FROM items WHERE name = 'written during backup'"));
            assertEquals("ok", one(c, "PRAGMA integrity_check"));
        }
    }

    @Test
    void cancelledBackupLeavesNoNewFileAndKeepsAnExistingOne() throws SQLiteException {
        BackupOptions cancelAfterFirstStep = BackupOptions.DEFAULT.withPagesPerStep(5).withProgress((r, t) -> false);
        SQLiteException e = assertThrows(SQLiteException.class, () -> conn.backup(file("new.db"), cancelAfterFirstStep));
        assertTrue(e.getMessage().contains("cancelled"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("new.db")));

        try (Connection c = SQLite.getConnection(file("existing.db"))) {
            exec(c, "CREATE TABLE keep (x); INSERT INTO keep VALUES ('still here')");
        }
        assertThrows(SQLiteException.class, () -> conn.backup(file("existing.db"), cancelAfterFirstStep));
        try (Connection c = SQLite.getConnection(file("existing.db"))) {
            assertEquals("still here", one(c, "SELECT x FROM keep"));
        }
    }

    @Test
    void cancelledRestoreLeavesTheDatabaseUnchanged() throws SQLiteException {
        try (Connection other = SQLite.getConnection(file("other.db"))) {
            exec(other, "CREATE TABLE replacement (x); INSERT INTO replacement "
                    + "WITH RECURSIVE s(v) AS (SELECT 1 UNION ALL SELECT v + 1 FROM s WHERE v < 3000) SELECT randomblob(100) FROM s");
        }
        assertThrows(SQLiteException.class, () -> conn.restore(file("other.db"),
                BackupOptions.DEFAULT.withPagesPerStep(5).withProgress((r, t) -> false)));
        assertEquals("5000", one(conn, "SELECT count(*) FROM items"));
        assertEquals("0", one(conn, "SELECT count(*) FROM sqlite_schema WHERE name = 'replacement'"));
    }

    @Test
    void progressCallbackExceptionsPropagateAndRollBack() throws SQLiteException {
        try (Connection target = SQLite.getConnection(":memory:")) {
            exec(target, "CREATE TABLE mine (x)");
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> conn.backup(target,
                    BackupOptions.DEFAULT.withPagesPerStep(5).withProgress((r, t) -> {
                        throw new IllegalStateException("stop");
                    })));
            assertEquals("stop", e.getMessage());
            assertEquals("1", one(target, "SELECT count(*) FROM sqlite_schema WHERE name = 'mine'"));
        }
    }

    @Test
    void attachedSchemas() throws SQLiteException {
        exec(conn, "ATTACH DATABASE ':memory:' AS aux; CREATE TABLE aux.notes (txt); INSERT INTO aux.notes VALUES ('in aux')");
        conn.backup(file("aux.db"), BackupOptions.DEFAULT.withSchema("aux"));
        try (Connection c = SQLite.getConnection(file("aux.db"))) {
            assertEquals("in aux", one(c, "SELECT txt FROM notes"));
            assertEquals("0", one(c, "SELECT count(*) FROM sqlite_schema WHERE name = 'items'"));
        }
        exec(conn, "DELETE FROM aux.notes");
        conn.restore(file("aux.db"), BackupOptions.DEFAULT.withSchema("aux"));
        assertEquals("in aux", one(conn, "SELECT txt FROM aux.notes"));
        SQLiteException e = assertThrows(SQLiteException.class,
                () -> conn.backup(file("x.db"), BackupOptions.DEFAULT.withSchema("nope")));
        assertTrue(e.getMessage().contains("unknown database nope"), e.getMessage());
    }

    @Test
    void walModeFileDatabases() throws SQLiteException {
        try (Connection src = SQLite.getConnection(file("wal.db"))) {
            exec(src, "PRAGMA journal_mode = WAL; CREATE TABLE t (x); INSERT INTO t VALUES (1), (2)");
            src.backup(file("wal-copy.db"));
        }
        try (Connection c = SQLite.getConnection(file("wal-copy.db"))) {
            assertEquals("2", one(c, "SELECT count(*) FROM t"));
        }
    }

    @Test
    void errors() throws SQLiteException {
        SQLiteException missingDir = assertThrows(SQLiteException.class, () -> conn.backup(file("no/such/dir/b.db")));
        assertEquals(14, missingDir.getErrorCode()); // SQLITE_CANTOPEN
        SQLiteException missingFile = assertThrows(SQLiteException.class, () -> conn.restore(file("missing.db")));
        assertEquals(14, missingFile.getErrorCode());
        assertFalse(Files.exists(dir.resolve("missing.db")), "restore must not create its source");
        try {
            Files.writeString(dir.resolve("garbage.db"), "this is not a database file, just text ".repeat(200));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        SQLiteException notADb = assertThrows(SQLiteException.class, () -> conn.restore(file("garbage.db")));
        assertEquals(26, notADb.getErrorCode()); // SQLITE_NOTADB
        assertEquals("5000", one(conn, "SELECT count(*) FROM items"));
    }

    @Test
    void restoreFailsWhileAStatementIsRunning() throws SQLiteException {
        conn.backup(file("b.db"));
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT name FROM items")) {
            assertTrue(rs.next());
            SQLiteException e = assertThrows(SQLiteException.class, () -> conn.restore(file("b.db")));
            assertEquals(1, e.getErrorCode()); // SQLITE_ERROR from sqlite3_backup_init
            assertTrue(e.getMessage().contains("destination database is in use"), e.getMessage());
            assertTrue(rs.next(), "the running query is unaffected");
        }
        conn.restore(file("b.db"));
        assertEquals("5000", one(conn, "SELECT count(*) FROM items"));
    }

    @Test
    void restoreIsRefusedInsideAnActiveTransaction() throws SQLiteException {
        conn.backup(file("b.db"));
        conn.setAutoCommit(false);
        exec(conn, "INSERT INTO items (name) VALUES ('pending')");
        SQLiteException e = assertThrows(SQLiteException.class, () -> conn.restore(file("b.db")));
        assertTrue(e.getMessage().contains("destination database is in use"), e.getMessage());
        assertEquals("5001", one(conn, "SELECT count(*) FROM items"), "the pending write survives");
        conn.rollback();
        conn.setAutoCommit(true);
    }
}
