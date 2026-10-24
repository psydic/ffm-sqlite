package io.github.revsnai.ffmsqlite.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowFunctionTest {
    private Connection conn;
    static final AtomicLong INVERSE_CALLS = new AtomicLong();

    /** Integer sum of non-NULL values, NULL for an empty frame: the same contract as the built-in sum(). */
    static final class MovingSum implements WindowFunction {
        private long sum, count;

        @Override
        public void step(FunctionArgs args) throws SQLiteException {
            if (!args.isNull(0)) {
                sum += args.getLong(0);
                count++;
            }
        }

        @Override
        public void inverse(FunctionArgs args) throws SQLiteException {
            INVERSE_CALLS.incrementAndGet();
            if (!args.isNull(0)) {
                sum -= args.getLong(0);
                count--;
            }
        }

        @Override
        public Object result() {
            return count == 0 ? null : sum;
        }
    }

    @BeforeEach
    void open() throws SQLiteException {
        conn = SQLite.getConnection(":memory:");
        conn.createWindowFunction("jsum", 1, MovingSum::new);
    }

    @AfterEach
    void close() {
        conn.close();
    }

    private void exec(String sql) throws SQLiteException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private List<String> rows(String sql) throws SQLiteException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    sb.append(i > 1 ? " " : "").append(rs.getObject(i));
                }
                out.add(sb.toString());
            }
        }
        return out;
    }

    @Test
    void sqliteDocumentationExample() throws SQLiteException {
        // https://sqlite.org/windowfunctions.html#user_defined_aggregate_window_functions
        exec("CREATE TABLE t3(x, y); INSERT INTO t3 VALUES ('a', 4), ('b', 5), ('c', 3), ('d', 8), ('e', 1)");
        assertEquals(List.of("a 9", "b 12", "c 16", "d 12", "e 9"),
                rows("SELECT x, jsum(y) OVER (ORDER BY x ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS sum_y FROM t3 ORDER BY x"));
    }

    @Test
    void matchesBuiltinSumForEveryFrameKind() throws SQLiteException {
        exec("CREATE TABLE d (id INTEGER PRIMARY KEY, grp TEXT, k INTEGER NOT NULL, v INTEGER)");
        Random rnd = new Random(7);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO d (grp, k, v) VALUES (?, ?, ?)")) {
            for (int i = 0; i < 2_000; i++) {
                ps.setString(1, "g" + rnd.nextInt(7));
                ps.setInt(2, rnd.nextInt(300));
                if (rnd.nextInt(10) == 0) {
                    ps.setNull(3);
                } else {
                    ps.setInt(3, rnd.nextInt(-1000, 1000));
                }
                ps.executeUpdate();
            }
        }
        String[] windows = {
                "ORDER BY id ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING",
                "ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW",
                "ORDER BY id ROWS BETWEEN 3 PRECEDING AND 1 PRECEDING",          // empty frame on the first row
                "ORDER BY id ROWS BETWEEN 2 FOLLOWING AND 5 FOLLOWING",          // empty frames at the end
                "PARTITION BY grp ORDER BY id ROWS BETWEEN 2 PRECEDING AND 2 FOLLOWING",
                "PARTITION BY grp ORDER BY k RANGE BETWEEN 5 PRECEDING AND 5 FOLLOWING",
                "ORDER BY k GROUPS BETWEEN 1 PRECEDING AND CURRENT ROW",
                "ORDER BY id ROWS BETWEEN 2 PRECEDING AND 2 FOLLOWING EXCLUDE CURRENT ROW",
                "PARTITION BY grp ORDER BY k ROWS BETWEEN 4 PRECEDING AND CURRENT ROW EXCLUDE TIES",
                "PARTITION BY grp",
                "ORDER BY k RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING",
        };
        INVERSE_CALLS.set(0);
        for (String w : windows) {
            int rows = 0;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT jsum(v) OVER (" + w + "), sum(v) OVER (" + w + ") FROM d")) {
                while (rs.next()) {
                    assertEquals(rs.getObject(2), rs.getObject(1), w);
                    rows++;
                }
            }
            assertEquals(2_000, rows, w);
            assertEquals(0, conn.liveAggregateGroups(), w);
        }
        assertTrue(INVERSE_CALLS.get() > 0, "sliding frames must use inverse()");
    }

    @Test
    void alsoWorksAsAPlainAggregate() throws SQLiteException {
        exec("CREATE TABLE t (g, v); INSERT INTO t VALUES ('a', 1), ('a', 2), ('b', 5), ('c', NULL)");
        assertEquals(List.of("a 3 3", "b 5 5", "c null null"), rows("SELECT g, jsum(v), sum(v) FROM t GROUP BY g ORDER BY g"));
        assertEquals(List.of("null"), rows("SELECT jsum(v) FROM t WHERE 0"));
    }

    @Test
    void valueIsUsedPerRowAndResultForPlainAggregates() throws SQLiteException {
        conn.createWindowFunction("tagged", 1, () -> new WindowFunction() {
            long n;

            @Override
            public void step(FunctionArgs args) {
                n++;
            }

            @Override
            public void inverse(FunctionArgs args) {
                n--;
            }

            @Override
            public Object value() {
                return "value:" + n;
            }

            @Override
            public Object result() {
                return "result:" + n;
            }
        });
        exec("CREATE TABLE t (x); INSERT INTO t VALUES (1), (2), (3)");
        assertEquals(List.of("value:1", "value:2", "value:2"),
                rows("SELECT tagged(x) OVER (ORDER BY x ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) FROM t"));
        assertEquals(List.of("result:3"), rows("SELECT tagged(x) FROM t"));
    }

    @Test
    void plainAggregatesAreRejectedAsWindowFunctions() throws SQLiteException {
        conn.createAggregateFunction("agg_only", 1, () -> new AggregateFunction() {
            @Override
            public void step(FunctionArgs args) {
            }

            @Override
            public Object result() {
                return 0;
            }
        });
        SQLiteException e = assertThrows(SQLiteException.class, () -> rows("SELECT agg_only(1) OVER () FROM (SELECT 1)"));
        assertTrue(e.getMessage().contains("may not be used as a window function"), e.getMessage());
    }

    @Test
    void exceptionInInverseFailsTheStatementAndCleansUp() throws SQLiteException {
        conn.createWindowFunction("bad_inverse", 1, () -> new WindowFunction() {
            @Override
            public void step(FunctionArgs args) {
            }

            @Override
            public void inverse(FunctionArgs args) {
                throw new UnsupportedOperationException("cannot remove rows");
            }

            @Override
            public Object result() {
                return 0;
            }
        });
        exec("CREATE TABLE t (x); INSERT INTO t VALUES (1), (2), (3), (4)");
        SQLiteException e = assertThrows(SQLiteException.class,
                () -> rows("SELECT bad_inverse(x) OVER (ORDER BY x ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) FROM t"));
        assertTrue(e.getMessage().contains("bad_inverse(): cannot remove rows"), e.getMessage());
        assertInstanceOf(UnsupportedOperationException.class, e.getCause());
        assertEquals(0, conn.liveAggregateGroups());
        // A frame that only grows never calls inverse().
        assertEquals(4, rows("SELECT bad_inverse(x) OVER (ORDER BY x) FROM t").size());
    }

    @Test
    void closingAResultSetEarlyFinalizesTheOpenPartition() throws SQLiteException {
        exec("CREATE TABLE big AS WITH RECURSIVE s(v) AS (SELECT 1 UNION ALL SELECT v + 1 FROM s WHERE v < 10000) SELECT v FROM s");
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT jsum(v) OVER (ORDER BY v ROWS BETWEEN 10 PRECEDING AND CURRENT ROW) FROM big")) {
            assertTrue(rs.next());
            assertTrue(rs.next());
            assertTrue(conn.liveAggregateGroups() > 0, "the partition's accumulator is alive while rows stream");
        }
        // sqlite3_reset halted the statement and ran xFinal (a Java upcall) for the open partition.
        assertEquals(0, conn.liveAggregateGroups());
    }

    @Test
    void replaceAndRemoveWindowFunctions() throws SQLiteException {
        conn.removeFunction("jsum", 1);
        assertThrows(SQLiteException.class, () -> rows("SELECT jsum(1) OVER ()"));
        conn.createWindowFunction("jsum", 1, MovingSum::new, FunctionFlag.DETERMINISTIC, FunctionFlag.INNOCUOUS);
        assertEquals(List.of("1"), rows("SELECT jsum(1) OVER ()"));
        assertTrue(Objects.equals(List.of("6"), rows("SELECT jsum(x) FROM (SELECT 1 x UNION ALL SELECT 2 UNION ALL SELECT 3)")));
    }
}
