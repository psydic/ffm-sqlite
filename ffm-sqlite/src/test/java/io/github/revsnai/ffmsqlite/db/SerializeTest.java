package io.github.revsnai.ffmsqlite.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializeTest {
    @TempDir
    Path dir;
    private Connection conn;

    @BeforeEach
    void open() throws SQLiteException {
        conn = SQLite.getConnection(":memory:");
        exec(conn, "CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT NOT NULL); CREATE INDEX ix ON items (name); "
                + "INSERT INTO items (name) WITH RECURSIVE s(v) AS (SELECT 1 UNION ALL SELECT v + 1 FROM s WHERE v < 2000) "
                + "SELECT 'item-' || v FROM s");
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

    private static long one(Connection c, String sql) throws SQLiteException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static String text(Connection c, String sql) throws SQLiteException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    @Test
    void roundTripThroughBytes() throws SQLiteException {
        byte[] image = conn.serialize();
        assertEquals("SQLite format 3\0", new String(image, 0, 16, StandardCharsets.US_ASCII));
        assertEquals(one(conn, "PRAGMA page_count") * one(conn, "PRAGMA page_size"), image.length);
        try (Connection copy = SQLite.getConnection(image)) {
            assertEquals(2000, one(copy, "SELECT count(*) FROM items"));
            assertEquals("item-1234", text(copy, "SELECT name FROM items WHERE id = 1234"));
            assertEquals("ok", text(copy, "PRAGMA integrity_check"));
            assertArrayEquals(image, copy.serialize(), "serialize(deserialize(x)) == x");
        }
    }

    @Test
    void theBytesAreAValidDatabaseFile() throws SQLiteException, IOException {
        Path file = dir.resolve("from-bytes.db");
        Files.write(file, conn.serialize());
        try (Connection c = SQLite.getConnection(file.toString())) {
            assertEquals(2000, one(c, "SELECT count(*) FROM items"));
            assertEquals("ok", text(c, "PRAGMA integrity_check"));
        }
        // ...and a database file's bytes deserialize.
        try (Connection c = SQLite.getConnection(Files.readAllBytes(file))) {
            assertEquals(2000, one(c, "SELECT count(*) FROM items"));
        }
    }

    @Test
    void deserializeReplacesContentAndKeepsPreparedStatementsWorking() throws SQLiteException {
        byte[] other;
        try (Connection c = SQLite.getConnection(":memory:")) {
            exec(c, "CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT, extra TEXT); INSERT INTO items VALUES (1, 'other', 'x')");
            other = c.serialize();
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM items WHERE id = ?")) {
            ps.setInt(1, 1);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("item-1", rs.getString(1));
            }
            conn.deserialize(other);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("other", rs.getString(1));
            }
        }
        assertEquals(0, one(conn, "SELECT count(*) FROM sqlite_schema WHERE name = 'ix'"));
    }

    @Test
    void sqliteOwnsACopyThatCanGrow() throws SQLiteException {
        byte[] image = conn.serialize();
        try (Connection c = SQLite.getConnection(image)) {
            Arrays.fill(image, (byte) 0); // the caller's array is not used after deserialize
            exec(c, "INSERT INTO items (name) WITH RECURSIVE s(v) AS (SELECT 1 UNION ALL SELECT v + 1 FROM s WHERE v < 50000) "
                    + "SELECT 'more-' || v FROM s");
            assertEquals(52000, one(c, "SELECT count(*) FROM items"));
            assertTrue(c.serialize().length > conn.serialize().length * 5, "grew well beyond the original image");
            assertEquals("ok", text(c, "PRAGMA integrity_check"));
        }
    }

    @Test
    void readOnlyImagesRejectWrites() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:")) {
            c.deserialize(conn.serialize(), "main", true);
            assertEquals(2000, one(c, "SELECT count(*) FROM items"));
            SQLiteException e = assertThrows(SQLiteException.class, () -> exec(c, "DELETE FROM items"));
            assertEquals(8, e.getErrorCode()); // SQLITE_READONLY
        }
    }

    @Test
    void emptyDatabases() throws SQLiteException {
        try (Connection empty = SQLite.getConnection(":memory:")) {
            byte[] image = empty.serialize();
            // Older SQLite (e.g. 3.45) has no pages yet and serializes to nothing; newer (e.g. 3.50) to one empty page.
            assertTrue(image.length == 0 || image.length == one(empty, "PRAGMA page_size"), "length " + image.length);
            try (Connection c = SQLite.getConnection(image)) {
                exec(c, "CREATE TABLE t (x); INSERT INTO t VALUES (1)");
                assertEquals(1, one(c, "SELECT count(*) FROM t"));
            }
        }
    }

    @Test
    void schemas() throws SQLiteException {
        SQLiteException unknown = assertThrows(SQLiteException.class, () -> conn.serialize("nope"));
        assertTrue(unknown.getMessage().contains("unknown database nope"), unknown.getMessage());
        assertThrows(SQLiteException.class, () -> conn.serialize("temp")); // no temp database yet

        exec(conn, "ATTACH ':memory:' AS aux; CREATE TABLE aux.notes (txt); INSERT INTO aux.notes VALUES ('in aux')");
        byte[] aux = conn.serialize("aux");
        try (Connection c = SQLite.getConnection(aux)) {
            assertEquals("in aux", text(c, "SELECT txt FROM notes"));
            assertEquals(0, one(c, "SELECT count(*) FROM sqlite_schema WHERE name = 'items'"));
        }
        exec(conn, "DELETE FROM aux.notes");
        conn.deserialize(aux, "aux", false);
        assertEquals("in aux", text(conn, "SELECT txt FROM aux.notes"));
        assertEquals(2000, one(conn, "SELECT count(*) FROM main.items"), "main is untouched");

        exec(conn, "CREATE TEMP TABLE scratch (v); INSERT INTO scratch VALUES (42)");
        try (Connection c = SQLite.getConnection(conn.serialize("temp"))) {
            assertEquals(42, one(c, "SELECT v FROM scratch"));
        }
    }

    @Test
    void walModeImagesAreConvertedToRollbackJournal() throws SQLiteException {
        byte[] image;
        try (Connection f = SQLite.getConnection(dir.resolve("wal.db").toString())) {
            exec(f, "PRAGMA journal_mode = WAL; CREATE TABLE t (x); INSERT INTO t VALUES (1), (2)");
            image = f.serialize();
        }
        assertEquals(2, image[18]); // WAL
        try (Connection c = SQLite.getConnection(image)) {
            assertEquals(2, one(c, "SELECT count(*) FROM t")); // SQLite alone would fail: "unable to open database file"
            exec(c, "INSERT INTO t VALUES (3)");
            assertEquals(3, one(c, "SELECT count(*) FROM t"));
        }
        assertEquals(2, image[18], "the caller's array is not modified");
    }

    @Test
    void invalidImagesAreRejectedWithoutTouchingTheDatabase() throws SQLiteException {
        byte[] text = "definitely not a database ".repeat(100).getBytes(StandardCharsets.UTF_8);
        SQLiteException bad = assertThrows(SQLiteException.class, () -> conn.deserialize(text));
        assertEquals(26, bad.getErrorCode()); // SQLITE_NOTADB
        assertThrows(SQLiteException.class, () -> conn.deserialize(Arrays.copyOf(conn.serialize(), 50)));
        assertEquals(2000, one(conn, "SELECT count(*) FROM items"));
        assertThrows(SQLiteException.class, () -> SQLite.getConnection(text));
    }

    @Test
    void corruptPagesBehindAValidHeaderFailOnUseNotCrash() throws SQLiteException {
        byte[] image = conn.serialize();
        for (int i = 100; i < image.length; i += 7) {
            image[i] ^= (byte) 0x5A;
        }
        try (Connection c = SQLite.getConnection(image)) {
            assertThrows(SQLiteException.class, () -> {
                one(c, "SELECT count(*) FROM items");
                text(c, "PRAGMA integrity_check"); // if the count happened to work, the check must not say ok
                throw new SQLiteException("not detected");
            });
        }
    }

    @Test
    void deserializeFailsWhileAStatementIsRunning() throws SQLiteException {
        byte[] image = conn.serialize();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT name FROM items")) {
            assertTrue(rs.next());
            // SQLite itself would accept this and the next rs.next() would crash the JVM (SIGSEGV).
            SQLiteException e = assertThrows(SQLiteException.class, () -> conn.deserialize(image));
            assertEquals(5, e.getErrorCode()); // SQLITE_BUSY, raised by the library
            assertTrue(e.getMessage().contains("statements are running"), e.getMessage());
            assertTrue(rs.next(), "the running query is unaffected");
            assertEquals("item-2", rs.getString(1));
        }
        conn.deserialize(image);
        assertEquals(2000, one(conn, "SELECT count(*) FROM items"));
    }

    @Test
    void deserializeInsideAnOpenTransaction() throws SQLiteException {
        byte[] image = conn.serialize();
        conn.setAutoCommit(false);
        exec(conn, "INSERT INTO items (name) VALUES ('uncommitted')");
        // SQLite itself would accept this and silently drop the uncommitted insert.
        SQLiteException e = assertThrows(SQLiteException.class, () -> conn.deserialize(image));
        assertEquals(5, e.getErrorCode());
        assertTrue(e.getMessage().contains("a transaction is active"), e.getMessage());
        assertEquals(2001, one(conn, "SELECT count(*) FROM items"), "the open transaction is intact");
        conn.rollback(); // starts a new, still untouched, transaction: deserialize is allowed again
        conn.deserialize(image);
        exec(conn, "INSERT INTO items (name) VALUES ('after')");
        conn.commit();
        conn.setAutoCommit(true);
        assertEquals(2001, one(conn, "SELECT count(*) FROM items"));
    }

    @Test
    void deserializingIntoAFileConnectionLeavesTheFileUnchanged() throws SQLiteException {
        String path = dir.resolve("file.db").toString();
        try (Connection f = SQLite.getConnection(path)) {
            exec(f, "CREATE TABLE original (x); INSERT INTO original VALUES ('on disk')");
            f.deserialize(conn.serialize());
            exec(f, "INSERT INTO items (name) VALUES ('in memory only')");
            assertEquals(2001, one(f, "SELECT count(*) FROM items"));
        }
        try (Connection f = SQLite.getConnection(path)) {
            assertEquals("on disk", text(f, "SELECT x FROM original"));
            assertEquals(0, one(f, "SELECT count(*) FROM sqlite_schema WHERE name = 'items'"));
        }
    }
}
