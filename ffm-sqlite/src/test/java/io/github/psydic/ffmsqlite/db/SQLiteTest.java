package io.github.psydic.ffmsqlite.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLiteTest {
    private Connection conn;

    @BeforeEach
    void open() throws SQLiteException {
        conn = SQLite.getConnection(":memory:");
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, score REAL, avatar BLOB)");
        }
    }

    @AfterEach
    void close() {
        conn.close();
    }

    private int count() throws SQLiteException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM users")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    @Test
    void insertAndSelectAllTypes() throws SQLiteException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO users (id, name, score, avatar) VALUES (?, ?, ?, ?)")) {
            ps.setInt(1, 1);
            ps.setString(2, "ada");
            ps.setDouble(3, 9.5);
            ps.setBytes(4, new byte[]{1, 2, 3});
            assertEquals(1, ps.executeUpdate());
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, name, score, avatar FROM users WHERE id = ?")) {
            ps.setInt(1, 1);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("id"));
                assertEquals("ada", rs.getString("NAME"));
                assertEquals(9.5, rs.getDouble(3));
                assertArrayEquals(new byte[]{1, 2, 3}, rs.getBytes("avatar"));
                assertFalse(rs.next());
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void nullsAndWasNull() throws SQLiteException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO users (name, score, avatar) VALUES (?, ?, ?)")) {
            ps.setString(1, "bob");
            ps.setNull(2);
            ps.setBytes(3, null);
            ps.executeUpdate();
        }
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT score, avatar, name FROM users")) {
            assertTrue(rs.next());
            assertEquals(0.0, rs.getDouble(1));
            assertTrue(rs.wasNull());
            assertNull(rs.getBytes(2));
            assertNull(rs.getObject(2));
            assertEquals("bob", rs.getString(3));
            assertFalse(rs.wasNull());
        }
    }

    @Test
    void getObjectFollowsStorageClass() throws SQLiteException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1, 2.5, 'x', x'ff', NULL, 9223372036854775807")) {
            assertTrue(rs.next());
            assertEquals(1L, rs.getObject(1));
            assertEquals(2.5, rs.getObject(2));
            assertEquals("x", rs.getObject(3));
            assertArrayEquals(new byte[]{(byte) 0xff}, (byte[]) rs.getObject(4));
            assertNull(rs.getObject(5));
            assertEquals(Long.MAX_VALUE, rs.getLong(6));
        }
    }

    @Test
    void unicodeAndEmptyValuesRoundTrip() throws SQLiteException {
        String text = "héllo, 世界 🌍";
        try (PreparedStatement ps = conn.prepareStatement("SELECT ?, ?, ?")) {
            ps.setString(1, text);
            ps.setString(2, "");
            ps.setBytes(3, new byte[0]);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(text, rs.getString(1));
                assertEquals("", rs.getString(2));
                assertArrayEquals(new byte[0], rs.getBytes(3));
            }
        }
    }

    @Test
    void metadata() throws SQLiteException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT id, name AS who, 1 + 1 FROM users")) {
            ResultSetMetaData md = rs.getMetaData();
            assertEquals(3, md.getColumnCount());
            assertEquals("who", md.getColumnLabel(2));
            assertEquals("INTEGER", md.getColumnTypeName(1));
            assertNull(md.getColumnTypeName(3));
        }
    }

    @Test
    void transactionsCommitAndRollback() throws SQLiteException {
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO users (name) VALUES (?)")) {
            ps.setString(1, "kept");
            ps.executeUpdate();
            conn.commit();
            ps.setString(1, "discarded");
            ps.executeUpdate();
            conn.rollback();
        }
        conn.setAutoCommit(true);
        assertEquals(1, count());
        assertThrows(SQLiteException.class, conn::commit);
    }

    @Test
    void setAutoCommitTrueCommitsOpenTransaction() throws SQLiteException {
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO users (name) VALUES ('a')");
        }
        conn.setAutoCommit(true);
        try (Connection other = conn) {
            assertEquals(1, count());
        }
    }

    @Test
    void batch() throws SQLiteException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO users (name, score) VALUES (?, ?)")) {
            for (int i = 0; i < 100; i++) {
                ps.setString(1, "u" + i);
                ps.setDouble(2, i);
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            assertEquals(100, counts.length);
            assertEquals(1, counts[99]);
        }
        assertEquals(100, count());
    }

    @Test
    void batchFailureNamesEntryAndClearsBatch() throws SQLiteException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO users (name) VALUES (?)")) {
            for (String name : new String[]{"a", "b", "a", "c"}) {
                ps.setString(1, name);
                ps.addBatch();
            }
            SQLiteException e = assertThrows(SQLiteException.class, ps::executeBatch);
            assertTrue(e.getMessage().startsWith("batch entry 2 failed"), e.getMessage());
            assertEquals(19, e.getErrorCode()); // SQLITE_CONSTRAINT
            assertEquals(2067, e.getExtendedErrorCode()); // SQLITE_CONSTRAINT_UNIQUE
            assertEquals(0, ps.executeBatch().length);
        }
        assertEquals(2, count());
    }

    @Test
    void updateCounts() throws SQLiteException {
        try (Statement st = conn.createStatement()) {
            assertEquals(3, st.executeUpdate("INSERT INTO users (name) VALUES ('a'), ('b'), ('c')"));
            assertEquals(0, st.executeUpdate("CREATE TABLE other (x)"));
            assertEquals(2, st.executeUpdate("UPDATE users SET score = 1 WHERE name <> 'a'"));
            assertFalse(st.execute("DELETE FROM users"));
            assertEquals(3, st.getUpdateCount());
        }
    }

    @Test
    void scriptsAndExecute() throws SQLiteException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE t (x); INSERT INTO t VALUES (1); INSERT INTO t VALUES (2);");
            assertTrue(st.execute("INSERT INTO t VALUES (3); SELECT sum(x) FROM t;"));
            try (ResultSet rs = st.getResultSet()) {
                assertTrue(rs.next());
                assertEquals(6, rs.getInt(1));
            }
            assertEquals(-1, st.getUpdateCount());
            assertThrows(SQLiteException.class, () -> st.executeQuery("DELETE FROM t"));
        }
    }

    @Test
    void parametersPersistAcrossExecutionsAndMustBeSet() throws SQLiteException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT ? + ?")) {
            assertEquals(2, ps.getParameterCount());
            ps.setInt(1, 40);
            assertThrows(SQLiteException.class, ps::executeQuery);
            ps.setInt(2, 2);
            for (int i = 0; i < 3; i++) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(42, rs.getInt(1));
                }
            }
            assertThrows(SQLiteException.class, () -> ps.setInt(3, 1));
            ps.clearParameters();
            assertThrows(SQLiteException.class, ps::executeQuery);
        }
    }

    @Test
    void namedParameters() throws SQLiteException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT :a || :b")) {
            ps.setString(1, "foo");
            ps.setString(2, "bar");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("foobar", rs.getString(1));
            }
        }
    }

    @Test
    void errorsCarrySqliteCodes() {
        SQLiteException syntax = assertThrows(SQLiteException.class, () -> conn.prepareStatement("SELEC 1"));
        assertEquals(1, syntax.getErrorCode()); // SQLITE_ERROR
        assertTrue(syntax.getMessage().contains("syntax error"), syntax.getMessage());
        assertThrows(SQLiteException.class, () -> conn.prepareStatement("SELECT 1; SELECT 2"));
        assertThrows(SQLiteException.class, () -> conn.prepareStatement("INSERT INTO users (name) VALUES (?)").executeQuery());
    }

    @Test
    void resultSetStateChecks() throws SQLiteException {
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT 1");
            assertThrows(SQLiteException.class, () -> rs.getInt(1)); // before next()
            assertTrue(rs.next());
            assertThrows(SQLiteException.class, () -> rs.getInt(2));
            assertThrows(SQLiteException.class, () -> rs.getInt("nope"));
            st.executeQuery("SELECT 2"); // re-executing closes the previous result set
            assertTrue(rs.isClosed());
            assertThrows(SQLiteException.class, rs::next);
        }
    }

    @Test
    void statementCacheReusesCompiledStatements() throws SQLiteException {
        String sql = "INSERT INTO users (name) VALUES (?)";
        for (int i = 0; i < 1_000; i++) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, "n" + i);
                ps.executeUpdate();
            }
        }
        assertEquals(1_000, count());
        // Two statements open at once with the same SQL must not share a compiled statement.
        try (PreparedStatement a = conn.prepareStatement("SELECT ?"); PreparedStatement b = conn.prepareStatement("SELECT ?")) {
            a.setInt(1, 1);
            b.setInt(1, 2);
            try (ResultSet ra = a.executeQuery(); ResultSet rb = b.executeQuery()) {
                assertTrue(ra.next());
                assertTrue(rb.next());
                assertEquals(1, ra.getInt(1));
                assertEquals(2, rb.getInt(1));
            }
        }
    }

    @Test
    void closingConnectionClosesChildren() throws SQLiteException {
        Connection c = SQLite.getConnection(":memory:");
        PreparedStatement ps = c.prepareStatement("SELECT 1");
        ResultSet rs = ps.executeQuery();
        Statement st = c.createStatement();
        c.close();
        c.close();
        assertTrue(c.isClosed());
        assertTrue(ps.isClosed());
        assertTrue(rs.isClosed());
        assertTrue(st.isClosed());
        assertThrows(SQLiteException.class, c::createStatement);
        assertThrows(SQLiteException.class, ps::executeQuery);
    }

    @Test
    void lastInsertRowId() throws SQLiteException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO users (id, name) VALUES (77, 'x')");
        }
        assertEquals(77, conn.getLastInsertRowId());
    }

    @Test
    void fileDatabasePersistsAndReadOnlyRejectsWrites(@TempDir Path dir) throws SQLiteException {
        String path = dir.resolve("app.db").toString();
        try (Connection c = SQLite.getConnection(path); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT); INSERT INTO kv VALUES ('a', '1')");
        }
        try (Connection c = SQLite.getConnection(path, SQLite.Config.DEFAULT.withReadOnly(true));
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT v FROM kv WHERE k = 'a'")) {
                assertTrue(rs.next());
                assertEquals("1", rs.getString(1));
            }
            SQLiteException e = assertThrows(SQLiteException.class, () -> st.executeUpdate("DELETE FROM kv"));
            assertEquals(8, e.getErrorCode()); // SQLITE_READONLY
        }
        assertThrows(SQLiteException.class,
                () -> SQLite.getConnection(dir.resolve("missing/x.db").toString()));
    }

    @Test
    void largeResultIteration() throws SQLiteException {
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO users (name, score) VALUES (?, ?)")) {
            for (int i = 0; i < 10_000; i++) {
                ps.setString(1, "user-" + i);
                ps.setDouble(2, i * 0.5);
                ps.executeUpdate();
            }
        }
        conn.commit();
        List<String> names = new ArrayList<>();
        double sum = 0;
        try (PreparedStatement ps = conn.prepareStatement("SELECT name, score FROM users ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                names.add(rs.getString(1));
                sum += rs.getDouble(2);
            }
        }
        assertEquals(10_000, names.size());
        assertEquals("user-9999", names.getLast());
        assertEquals(0.5 * 9999 * 10_000 / 2, sum);
    }
}
