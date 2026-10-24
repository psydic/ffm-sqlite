package io.github.psydic.ffmsqlite.jdbc;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same plain-JDBC code, run against sqlite-jdbc ({@code jdbc:sqlite:}) and ffm-sqlite-jdbc
 * ({@code jdbc:ffmsqlite:}), must observe the same results: the drop-in contract.
 */
class CompatibilityTest {
    @TempDir
    Path dir;

    private Connection open(String prefix) throws SQLException {
        return DriverManager.getConnection(prefix + dir.resolve("app.db"));
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:sqlite:", "jdbc:ffmsqlite:"})
    void crudAndTypes(String prefix) throws SQLException {
        try (Connection c = open(prefix)) {
            exec(c, "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT NOT NULL, score REAL, big INTEGER, data BLOB, amount TEXT)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t (name, score, big, data, amount) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, "ada");
                ps.setDouble(2, 9.5);
                ps.setLong(3, 5_000_000_000L);
                ps.setBytes(4, new byte[]{1, 2, 3});
                ps.setBigDecimal(5, new BigDecimal("12.50"));
                assertEquals(1, ps.executeUpdate());
                ps.setString(1, "bob");
                ps.setNull(2, Types.REAL);
                ps.setNull(3, Types.BIGINT);
                ps.setNull(4, Types.BLOB);
                ps.setNull(5, Types.VARCHAR);
                assertEquals(1, ps.executeUpdate());
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, name, score, big, data, amount FROM t ORDER BY id")) {
                assertTrue(rs.next());
                assertInstanceOf(Integer.class, rs.getObject(1), "small integers come back as Integer");
                assertInstanceOf(Long.class, rs.getObject("big"), "large ones as Long");
                assertEquals("ada", rs.getString("NAME"));
                assertEquals(9.5, rs.getDouble(3));
                assertInstanceOf(Double.class, rs.getObject(3));
                assertArrayEquals(new byte[]{1, 2, 3}, rs.getBytes("data"));
                assertEquals(new BigDecimal("12.50"), rs.getBigDecimal("amount"));
                assertEquals(5_000_000_000L, rs.getLong(4));
                assertTrue(rs.next());
                assertEquals(0.0, rs.getDouble(3));
                assertTrue(rs.wasNull());
                assertNull(rs.getBytes(5));
                assertNull(rs.getObject(4));
                assertFalse(rs.next());
            }
            assertEquals(2, count(c, "t"));
            try (Statement st = c.createStatement()) {
                assertEquals(2, st.executeUpdate("UPDATE t SET score = 1"));
                assertEquals(0, st.executeUpdate("CREATE TABLE other (x)"));
                assertFalse(st.execute("DELETE FROM t WHERE name = 'bob'"));
                assertEquals(1, st.getUpdateCount());
            }
        }
    }

    private static int count(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:sqlite:", "jdbc:ffmsqlite:"})
    void datesAreStoredAsEpochMillisLikeSqliteJdbc(String prefix) throws SQLException {
        Timestamp ts = Timestamp.valueOf("2024-02-29 13:45:30.123");
        java.sql.Date date = java.sql.Date.valueOf("2024-02-29");
        try (Connection c = open(prefix)) {
            exec(c, "CREATE TABLE ev (at TIMESTAMP, day DATE)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO ev VALUES (?, ?)")) {
                ps.setTimestamp(1, ts);
                ps.setDate(2, date);
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT at, day, typeof(at), at FROM ev")) {
                assertTrue(rs.next());
                assertEquals(ts, rs.getTimestamp(1));
                assertEquals(date.toString(), rs.getDate(2).toString());
                assertEquals("integer", rs.getString(3));
                assertEquals(ts.getTime(), rs.getLong(4));
                assertEquals(LocalDate.of(2024, 2, 29), rs.getObject(2, LocalDate.class));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:sqlite:", "jdbc:ffmsqlite:"})
    void generatedKeysBatchesAndTransactions(String prefix) throws SQLException {
        try (Connection c = open(prefix)) {
            exec(c, "CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT UNIQUE)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t (v) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, "a");
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertTrue(keys.next());
                    assertEquals(1, keys.getLong(1));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t (v) VALUES (?)")) {
                for (String v : new String[]{"b", "c", "d"}) {
                    ps.setString(1, v);
                    ps.addBatch();
                }
                assertArrayEquals(new int[]{1, 1, 1}, ps.executeBatch());
            }
            try (Statement st = c.createStatement()) {
                st.addBatch("INSERT INTO t (v) VALUES ('e')");
                st.addBatch("UPDATE t SET v = v || '!' WHERE v IN ('a', 'b')");
                assertArrayEquals(new int[]{1, 2}, st.executeBatch());
                st.addBatch("INSERT INTO t (v) VALUES ('f')");
                st.addBatch("INSERT INTO t (v) VALUES ('f')");
                BatchUpdateException e = assertThrows(BatchUpdateException.class, st::executeBatch);
                // JDBC allows either: counts for the entries before the failure (ffm-sqlite-jdbc), or for all
                // entries with a failure marker (sqlite-jdbc). Both report the first entry's success.
                assertEquals(1, e.getUpdateCounts()[0]);
                // sqlite-jdbc reports error code 0 here; ffm-sqlite-jdbc reports SQLite's code (19)
            }
            c.setAutoCommit(false);
            exec(c, "INSERT INTO t (v) VALUES ('kept')");
            Savepoint sp = c.setSavepoint("sp1");
            exec(c, "INSERT INTO t (v) VALUES ('undone')");
            c.rollback(sp);
            c.commit();
            exec(c, "INSERT INTO t (v) VALUES ('rolled back')");
            c.rollback();
            c.setAutoCommit(true);
            List<String> values = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT v FROM t ORDER BY id")) {
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
            }
            assertEquals(List.of("a!", "b!", "c", "d", "e", "f", "kept"), values);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:sqlite:", "jdbc:ffmsqlite:"})
    void errorsAndMetadata(String prefix) throws SQLException {
        try (Connection c = open(prefix)) {
            exec(c, "CREATE TABLE parent (id INTEGER PRIMARY KEY, code TEXT NOT NULL UNIQUE); "
                    + "CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parent (id), note VARCHAR(20)); "
                    + "CREATE INDEX ix_child_parent ON child (parent_id); INSERT INTO parent VALUES (1, 'a')");
            SQLException dup = assertThrows(SQLException.class, () -> exec(c, "INSERT INTO parent VALUES (2, 'a')"));
            assertEquals(19, dup.getErrorCode());
            assertThrows(SQLException.class, () -> exec(c, "SELEC 1"));

            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, parent_id, note FROM child")) {
                ResultSetMetaData md = rs.getMetaData();
                assertEquals(3, md.getColumnCount());
                assertEquals("parent_id", md.getColumnLabel(2));
                assertEquals("VARCHAR", md.getColumnTypeName(3));
                assertEquals(20, md.getPrecision(3));
                assertEquals(Types.INTEGER, md.getColumnType(1));
                assertEquals(Types.VARCHAR, md.getColumnType(3));
                assertEquals("child", md.getTableName(1));
            }

            DatabaseMetaData md = c.getMetaData();
            assertEquals("SQLite", md.getDatabaseProductName());
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = md.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            assertEquals(List.of("child", "parent"), tables.stream().sorted().toList());
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = md.getColumns(null, null, "child", "%")) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME") + ":" + rs.getInt("DATA_TYPE") + ":" + rs.getInt("ORDINAL_POSITION"));
                }
            }
            assertEquals(List.of("id:" + Types.INTEGER + ":1", "parent_id:" + Types.INTEGER + ":2", "note:" + Types.VARCHAR + ":3"), columns);
            try (ResultSet rs = md.getPrimaryKeys(null, null, "parent")) {
                assertTrue(rs.next());
                assertEquals("id", rs.getString("COLUMN_NAME"));
            }
            try (ResultSet rs = md.getImportedKeys(null, null, "child")) {
                assertTrue(rs.next());
                assertEquals("parent", rs.getString("PKTABLE_NAME"));
                assertEquals("id", rs.getString("PKCOLUMN_NAME"));
                assertEquals("parent_id", rs.getString("FKCOLUMN_NAME"));
            }
            boolean found = false;
            try (ResultSet rs = md.getIndexInfo(null, null, "child", false, false)) {
                while (rs.next()) {
                    found |= "ix_child_parent".equals(rs.getString("INDEX_NAME"));
                }
            }
            assertTrue(found);
        }
    }

    /** A database written by one driver reads back identically through the other, both ways. */
    @ParameterizedTest
    @ValueSource(strings = {"jdbc:sqlite:>jdbc:ffmsqlite:", "jdbc:ffmsqlite:>jdbc:sqlite:"})
    void databasesMoveBetweenDrivers(String pair) throws SQLException {
        String writer = pair.split(">")[0], reader = pair.split(">")[1];
        Timestamp ts = Timestamp.valueOf("2023-12-31 23:59:59.999");
        try (Connection c = open(writer)) {
            exec(c, "CREATE TABLE m (id INTEGER PRIMARY KEY, at TIMESTAMP, name TEXT, amount TEXT, raw BLOB)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO m (at, name, amount, raw) VALUES (?, ?, ?, ?)")) {
                ps.setTimestamp(1, ts);
                ps.setString(2, "héllo 🌍");
                ps.setBigDecimal(3, new BigDecimal("1234567890.123456789"));
                ps.setBytes(4, new byte[]{9, 8, 7});
                ps.executeUpdate();
            }
        }
        try (Connection c = open(reader); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT at, name, amount, raw FROM m")) {
            assertTrue(rs.next());
            assertEquals(ts, rs.getTimestamp(1));
            assertEquals("héllo 🌍", rs.getString(2));
            assertEquals(new BigDecimal("1234567890.123456789"), rs.getBigDecimal(3));
            assertArrayEquals(new byte[]{9, 8, 7}, rs.getBytes(4));
        }
    }
}
