package io.github.psydic.ffmsqlite.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression tests for the issues found in the 0.1.0 code review, through the java.sql API. */
class ReviewFixesJdbcTest {

    @Test
    void resultSetMetaDataAfterCloseDoesNotCrash() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite::memory:"); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            ResultSetMetaData md;
            try (ResultSet rs = st.executeQuery("SELECT id, name FROM t")) {
                md = rs.getMetaData();
            }
            st.executeQuery("SELECT 1, 2, 3, 4").close();
            assertEquals("TEXT", md.getColumnTypeName(2));
            assertEquals(Types.VARCHAR, md.getColumnType(2));
            assertEquals("t", md.getTableName(2));
            assertEquals(ResultSetMetaData.columnNoNulls, md.isNullable(2));
        }
    }

    @Test
    void getTimeReadsTimeOnlyText() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite::memory:"); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (at TEXT)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (?)")) {
                ps.setObject(1, LocalTime.of(13, 45, 30));
                ps.executeUpdate();
            }
            try (ResultSet rs = st.executeQuery("SELECT at, time('2026-01-15 08:30:05'), '07:15' FROM t")) {
                rs.next();
                assertEquals(Time.valueOf("13:45:30"), rs.getTime(1));
                assertEquals(Time.valueOf("08:30:05"), rs.getTime(2));
                assertEquals(Time.valueOf("07:15:00"), rs.getTime(3));
                assertEquals(Timestamp.valueOf("1970-01-01 13:45:30"), rs.getTimestamp(1));
                assertEquals(LocalTime.of(13, 45, 30), rs.getObject(1, LocalTime.class));
            }
        }
    }

    @Test
    void urlPageSizeWinsOverJournalModeOrder(@TempDir Path dir) throws SQLException {
        String url = "jdbc:ffmsqlite:" + dir.resolve("new.db") + "?journal_mode=WAL&page_size=8192";
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (v)");
            try (ResultSet rs = st.executeQuery("PRAGMA page_size")) {
                rs.next();
                assertEquals(8192, rs.getInt(1));
            }
        }
    }

    @Test
    void batchTimeoutIsASqlTimeoutException() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite::memory:"); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (n INTEGER)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t WITH RECURSIVE r(x) AS (SELECT 1 UNION ALL "
                    + "SELECT x + 1 FROM r) SELECT count(*) FROM (SELECT x FROM r LIMIT ?)")) {
                ps.setQueryTimeout(1);
                ps.setLong(1, 1);
                ps.addBatch();
                ps.setLong(1, 4_000_000_000L);
                ps.addBatch();
                java.sql.BatchUpdateException e = assertThrows(java.sql.BatchUpdateException.class, ps::executeBatch);
                assertEquals("HYT00", e.getSQLState());                  // was HY008 (cancelled) before the fix
                assertInstanceOf(SQLTimeoutException.class, e.getCause());
                assertEquals(1, e.getUpdateCounts().length);              // entry 0 succeeded
            }
        }
    }
}
