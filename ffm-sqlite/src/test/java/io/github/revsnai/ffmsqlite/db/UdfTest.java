package io.github.revsnai.ffmsqlite.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdfTest {
    private Connection conn;

    @BeforeEach
    void open() throws SQLiteException {
        conn = SQLite.getConnection(":memory:");
        exec("CREATE TABLE t (grp TEXT, x REAL); "
                + "INSERT INTO t VALUES ('a', 1), ('a', 3), ('a', 2), ('b', 10), ('b', 20), ('c', NULL)");
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

    /** Rows 1..n in a column named value (generate_series is a CLI extension, not in the core library). */
    private static String series(int n) {
        return "(WITH RECURSIVE s(value) AS (SELECT 1 UNION ALL SELECT value + 1 FROM s WHERE value < " + n + ") SELECT value FROM s)";
    }

    private Object one(String sql) throws SQLiteException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getObject(1);
        }
    }

    /** The median of non-NULL inputs; NULL for none. */
    static final class Median implements AggregateFunction {
        private final List<Double> values = new ArrayList<>();

        @Override
        public void step(FunctionArgs args) throws SQLiteException {
            if (!args.isNull(0)) {
                values.add(args.getDouble(0));
            }
        }

        @Override
        public Object result() {
            if (values.isEmpty()) {
                return null;
            }
            Collections.sort(values);
            int n = values.size();
            return n % 2 == 1 ? values.get(n / 2) : (values.get(n / 2 - 1) + values.get(n / 2)) / 2;
        }
    }

    // ------------------------------------------------------------------ scalar

    @Test
    void scalarFunction() throws SQLiteException {
        conn.createFunction("reverse", 1, args -> new StringBuilder(args.getString(0)).reverse().toString(),
                FunctionFlag.DETERMINISTIC);
        assertEquals("olleh", one("SELECT reverse('hello')"));
        assertEquals("ba", one("SELECT REVERSE(grp || 'b') FROM t LIMIT 1")); // names are case-insensitive
    }

    @Test
    void variadicAndOverloadedByArgumentCount() throws SQLiteException {
        conn.createFunction("join_all", -1, args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.size(); i++) {
                sb.append(i == 0 ? "" : "|").append(args.getString(i));
            }
            return sb.toString();
        });
        assertEquals("", one("SELECT join_all()"));
        assertEquals("1|x|2.5", one("SELECT join_all(1, 'x', 2.5)"));

        conn.createFunction("f", 1, args -> "one");
        conn.createFunction("f", 2, args -> "two");
        assertEquals("one", one("SELECT f(1)"));
        assertEquals("two", one("SELECT f(1, 2)"));
        assertThrows(SQLiteException.class, () -> one("SELECT f(1, 2, 3)"));
    }

    @Test
    void argumentTypes() throws SQLiteException {
        AtomicReference<List<Object>> seen = new AtomicReference<>();
        conn.createFunction("probe", -1, args -> {
            List<Object> l = new ArrayList<>();
            l.add(args.getLong(0));
            l.add(args.getDouble(1));
            l.add(args.getString(2));
            l.add(args.getBytes(3));
            l.add(args.isNull(4));
            l.add(args.getString(4));
            l.add(args.getInt(4));
            l.add(args.getObject(5, LocalDate.class));
            l.add(args.getBigDecimal(6));
            l.add(args.getObject(7, UUID.class));
            l.add(args.getObject(0));
            l.add(args.getBoolean(0));
            seen.set(l);
            return null;
        });
        one("SELECT probe(9007199254740993, 2.5, 'héllo', x'0102', NULL, '2024-02-29', '1.50', "
                + "'123e4567-e89b-12d3-a456-426614174000')");
        List<Object> l = seen.get();
        assertEquals(9007199254740993L, l.get(0));
        assertEquals(2.5, l.get(1));
        assertEquals("héllo", l.get(2));
        assertArrayEquals(new byte[]{1, 2}, (byte[]) l.get(3));
        assertEquals(true, l.get(4));
        assertNull(l.get(5));
        assertEquals(0, l.get(6));
        assertEquals(LocalDate.of(2024, 2, 29), l.get(7));
        assertEquals(new BigDecimal("1.50"), l.get(8));
        assertEquals(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), l.get(9));
        assertEquals(9007199254740993L, l.get(10));
        assertEquals(true, l.get(11));
    }

    @Test
    void returnTypes() throws SQLiteException {
        conn.createFunction("ret", 1, args -> switch (args.getString(0)) {
            case "int" -> 42;
            case "long" -> Long.MIN_VALUE;
            case "double" -> 0.25;
            case "bool" -> true;
            case "null" -> null;
            case "blob" -> new byte[]{7, 8};
            case "decimal" -> new BigDecimal("12345678901234567890.5");
            case "date" -> LocalDate.of(2024, 2, 29);
            case "uuid" -> UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
            default -> new Object();
        });
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT ret('int'), typeof(ret('int')), ret('long'), ret('double'), ret('bool'), ret('null'), "
                        + "ret('blob'), ret('decimal'), ret('date'), ret('uuid'), date(ret('date'), '+1 day')")) {
            assertTrue(rs.next());
            assertEquals(42, rs.getInt(1));
            assertEquals("integer", rs.getString(2));
            assertEquals(Long.MIN_VALUE, rs.getLong(3));
            assertEquals(0.25, rs.getDouble(4));
            assertEquals(1, rs.getInt(5));
            assertNull(rs.getObject(6));
            assertArrayEquals(new byte[]{7, 8}, rs.getBytes(7));
            assertEquals(new BigDecimal("12345678901234567890.5"), rs.getBigDecimal(8));
            assertEquals(LocalDate.of(2024, 2, 29), rs.getObject(9, LocalDate.class));
            assertEquals("123e4567-e89b-12d3-a456-426614174000", rs.getString(10));
            assertEquals("2024-03-01", rs.getString(11));
        }
        SQLiteException e = assertThrows(SQLiteException.class, () -> one("SELECT ret('other')"));
        assertTrue(e.getMessage().contains("unsupported return type"), e.getMessage());
    }

    @Test
    void exceptionsFailTheStatementAndBecomeTheCause() throws SQLiteException {
        conn.createFunction("boom", 1, args -> {
            if (args.getInt(0) == 3) {
                throw new IllegalArgumentException("three is not allowed");
            }
            return args.getInt(0);
        });
        SQLiteException e = assertThrows(SQLiteException.class,
                () -> one("SELECT sum(boom(value)) FROM " + series(5)));
        assertTrue(e.getMessage().contains("boom(): three is not allowed"), e.getMessage());
        assertInstanceOf(IllegalArgumentException.class, e.getCause());

        // The connection and the function stay usable; a later unrelated error carries no stale cause.
        assertEquals(3L, one("SELECT boom(1) + boom(2)"));
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t (grp, x) VALUES (?, boom(?))")) {
            ps.setString(1, "z");
            ps.setInt(2, 3);
            SQLiteException e2 = assertThrows(SQLiteException.class, ps::executeUpdate);
            assertInstanceOf(IllegalArgumentException.class, e2.getCause());
        }
        SQLiteException unrelated = assertThrows(SQLiteException.class, () -> exec("INSERT INTO missing VALUES (1)"));
        assertNull(unrelated.getCause());
    }

    @Test
    void argumentsCannotBeUsedAfterTheCall() throws SQLiteException {
        AtomicReference<FunctionArgs> leaked = new AtomicReference<>();
        conn.createFunction("leak", 1, args -> {
            leaked.set(args);
            return 1;
        });
        one("SELECT leak('x')");
        SQLiteException e = assertThrows(SQLiteException.class, () -> leaked.get().getString(0));
        assertTrue(e.getMessage().contains("after the function returned"));
        conn.createFunction("bad_index", 1, args -> args.getString(1));
        assertThrows(SQLiteException.class, () -> one("SELECT bad_index('x')"));
    }

    @Test
    void manyCallsThroughUpcalls() throws SQLiteException {
        conn.createFunction("plus_one", 1, args -> args.getLong(0) + 1, FunctionFlag.DETERMINISTIC);
        assertEquals(200_000L * 200_001 / 2 + 200_000,
                one("SELECT sum(plus_one(value)) FROM " + series(200000)));
    }

    // ------------------------------------------------------------------ aggregate

    @Test
    void aggregateFunctionPerGroup() throws SQLiteException {
        conn.createAggregateFunction("median", 1, Median::new);
        List<String> rows = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT grp, median(x) FROM t GROUP BY grp ORDER BY grp")) {
            while (rs.next()) {
                rows.add(rs.getString(1) + "=" + rs.getObject(2));
            }
        }
        assertEquals(List.of("a=2.0", "b=15.0", "c=null"), rows);
        assertEquals(0, conn.liveAggregateGroups());
    }

    @Test
    void aggregateOverEmptyInputUsesAFreshAccumulator() throws SQLiteException {
        conn.createAggregateFunction("median", 1, Median::new);
        conn.createAggregateFunction("count_rows", 0, () -> new AggregateFunction() {
            long n;

            @Override
            public void step(FunctionArgs args) {
                n++;
            }

            @Override
            public Object result() {
                return n;
            }
        });
        assertNull(one("SELECT median(x) FROM t WHERE 0"));
        assertEquals(0L, one("SELECT count_rows() FROM t WHERE 0"));
        assertEquals(6L, one("SELECT count_rows() FROM t"));
    }

    @Test
    void failingStepCleansUpAccumulators() throws SQLiteException {
        conn.createAggregateFunction("fragile", 1, () -> new AggregateFunction() {
            int n;

            @Override
            public void step(FunctionArgs args) {
                if (++n == 3) {
                    throw new IllegalStateException("third row");
                }
            }

            @Override
            public Object result() {
                return n;
            }
        });
        SQLiteException e = assertThrows(SQLiteException.class, () -> one("SELECT fragile(value) FROM " + series(10)));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertEquals(0, conn.liveAggregateGroups());
        assertEquals(2, ((Long) one("SELECT fragile(value) FROM " + series(2))).intValue());
    }

    @Test
    void closingAGroupedResultEarlyLeavesNoAccumulators() throws SQLiteException {
        conn.createAggregateFunction("median", 1, Median::new);
        exec("CREATE TABLE big AS SELECT value % 100 AS g, value AS x FROM " + series(10000));
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT g, median(x) FROM big GROUP BY g")) {
            assertTrue(rs.next());
            // SQLite finalizes each group before returning its row and steps the next group only on next().
            assertEquals(0, conn.liveAggregateGroups());
            assertTrue(rs.next());
        }
        assertEquals(0, conn.liveAggregateGroups());
    }

    // ------------------------------------------------------------------ registration

    @Test
    void deterministicFlagAllowsIndexExpressions() throws SQLiteException {
        conn.createFunction("norm", 1, args -> args.getString(0).trim().toLowerCase());
        SQLiteException e = assertThrows(SQLiteException.class, () -> exec("CREATE INDEX ix ON t (norm(grp))"));
        assertTrue(e.getMessage().contains("non-deterministic"), e.getMessage());
        conn.createFunction("norm", 1, args -> args.getString(0).trim().toLowerCase(), FunctionFlag.DETERMINISTIC);
        exec("CREATE INDEX ix ON t (norm(grp))");
        assertEquals("a", one("SELECT grp FROM t WHERE norm(grp) = 'a' LIMIT 1"));
    }

    @Test
    void directOnlyRejectsUseFromViews() throws SQLiteException {
        conn.createFunction("secret", 0, args -> "s3cr3t", FunctionFlag.DIRECT_ONLY);
        assertEquals("s3cr3t", one("SELECT secret()"));
        exec("CREATE VIEW v AS SELECT secret() AS s");
        SQLiteException e = assertThrows(SQLiteException.class, () -> one("SELECT s FROM v"));
        assertTrue(e.getMessage().contains("unsafe use of secret()"), e.getMessage());
    }

    @Test
    void replaceAndRemove() throws SQLiteException {
        conn.createFunction("ver", 0, args -> 1);
        assertEquals(1L, one("SELECT ver()"));
        conn.createFunction("ver", 0, args -> 2);
        assertEquals(2L, one("SELECT ver()"));
        conn.removeFunction("ver", 0);
        SQLiteException e = assertThrows(SQLiteException.class, () -> one("SELECT ver()"));
        assertTrue(e.getMessage().contains("no such function"), e.getMessage());
    }

    @Test
    void cannotChangeFunctionsWhileAStatementIsRunning() throws SQLiteException {
        conn.createFunction("id1", 1, args -> args.getObject(0));
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT id1(x) FROM t")) {
            assertTrue(rs.next());
            SQLiteException e = assertThrows(SQLiteException.class, () -> conn.removeFunction("id1", 1));
            assertEquals(5, e.getErrorCode()); // SQLITE_BUSY
        }
        conn.removeFunction("id1", 1);
    }

    @Test
    void functionsArePerConnectionAndFreedOnClose() throws SQLiteException {
        try (Connection other = SQLite.getConnection(":memory:")) {
            other.createFunction("only_here", 0, args -> 1);
            try (Statement st = other.createStatement(); ResultSet rs = st.executeQuery("SELECT only_here()")) {
                assertTrue(rs.next());
            }
        }
        assertThrows(SQLiteException.class, () -> one("SELECT only_here()"));
        Connection closed = SQLite.getConnection(":memory:");
        closed.close();
        assertThrows(SQLiteException.class, () -> closed.createFunction("x", 0, args -> 1));
    }
}
