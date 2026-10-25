package io.github.revsnai.ffmsqlite.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A function or collation may use its connection for other statements, but closing the connection or closing,
 * resetting or re-running the statement that called it would free memory SQLite is still executing: each of these
 * used to crash the JVM (or silently corrupt the running statement) and must now fail that statement instead.
 */
class ReentryTest {
    Connection conn;
    final PreparedStatement[] running = new PreparedStatement[1];

    @BeforeEach
    void open() throws SQLiteException {
        conn = SQLite.getConnection(":memory:");
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE t (v TEXT); INSERT INTO t VALUES ('b'), ('a'), ('d'), ('c')");
        }
    }

    @AfterEach
    void close() {
        conn.close();
    }

    /** Runs {@code sql} through {@link #running}; returns the failure, after checking the connection still works. */
    private SQLiteException failsInside(String sql) throws SQLiteException {
        running[0] = conn.prepareStatement(sql);
        SQLiteException e = assertThrows(SQLiteException.class, () -> {
            try (ResultSet rs = running[0].executeQuery()) {
                while (rs.next()) {
                    rs.getString(1);
                }
            }
        });
        assertFalse(conn.isClosed());
        assertEquals("4", one("SELECT count(*) FROM t"));
        running[0].close();
        return e;
    }

    private String one(String sql) throws SQLiteException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    @Test
    void closingTheConnectionInAFunction() throws SQLiteException {
        conn.createFunction("f", 1, args -> {
            conn.close();
            return 1;
        });
        assertInstanceOf(IllegalStateException.class, failsInside("SELECT f(v) FROM t").getCause());
    }

    @Test
    void closingTheConnectionInACollation() throws SQLiteException {
        conn.createCollation("k", (a, b) -> {
            conn.close();
            return a.compareTo(b);
        });
        assertInstanceOf(IllegalStateException.class, failsInside("SELECT v FROM t ORDER BY v COLLATE k").getCause());
    }

    @Test
    void closingTheRunningStatementOrResultSet() throws SQLiteException {
        conn.createFunction("f", 1, args -> {
            running[0].close();
            return 1;
        });
        assertInstanceOf(IllegalStateException.class, failsInside("SELECT f(v) FROM t").getCause());
        conn.createFunction("f", 1, args -> {
            running[0].getResultSet().close();
            return 1;
        });
        running[0] = conn.prepareStatement("SELECT f(v) FROM t");
        assertTrue(running[0].execute());
        assertThrows(SQLiteException.class, () -> running[0].getResultSet().next());
        running[0].close();
    }

    @Test
    void steppingOrRerunningTheRunningStatement() throws SQLiteException {
        conn.createFunction("f", 1, args -> running[0].getResultSet().next());
        running[0] = conn.prepareStatement("SELECT f(v) FROM t");
        assertTrue(running[0].execute());
        assertThrows(SQLiteException.class, () -> running[0].getResultSet().next());
        running[0].close();

        conn.createFunction("f", 1, args -> running[0].executeQuery());
        failsInside("SELECT f(v) FROM t");
        conn.createFunction("f", 1, args -> running[0].executeUpdate());
        failsInside("SELECT f(v) FROM t");
    }

    @Test
    void updatesRerunningThemselvesFail() throws SQLiteException {
        conn.createFunction("g", 1, args -> running[0].executeUpdate());
        running[0] = conn.prepareStatement("UPDATE t SET v = g(v)");
        assertThrows(SQLiteException.class, running[0]::executeUpdate);
        running[0].close();
        assertEquals("a", one("SELECT min(v) FROM t"));
    }

    @Test
    void otherStatementsStillWorkInsideFunctionsAndCollations() throws SQLiteException {
        conn.createFunction("n", 0, args -> one("SELECT count(*) FROM t"));
        conn.createCollation("k", (a, b) -> {
            try {
                return one("SELECT '" + a + "' < '" + b + "'").equals("1") ? -1 : a.equals(b) ? 0 : 1;
            } catch (SQLiteException e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals("4", one("SELECT n()"));
        assertEquals("a,b,c,d", one("SELECT group_concat(v) FROM (SELECT v FROM t ORDER BY v COLLATE k)"));
    }
}
