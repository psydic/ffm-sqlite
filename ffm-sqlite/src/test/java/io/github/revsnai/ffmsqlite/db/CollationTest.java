package io.github.revsnai.ffmsqlite.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollationTest {
    private Connection conn;

    /** "file2" before "file10": digit runs compare numerically, the rest case-insensitively. */
    static final Comparator<String> NATURAL = (a, b) -> {
        Matcher ma = Pattern.compile("\\d+|\\D+").matcher(a);
        Matcher mb = Pattern.compile("\\d+|\\D+").matcher(b);
        while (ma.find() && mb.find()) {
            String x = ma.group(), y = mb.group();
            int c = Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0))
                    ? Long.compare(Long.parseLong(x), Long.parseLong(y))
                    : x.compareToIgnoreCase(y);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.length(), b.length());
    };

    @BeforeEach
    void open() throws SQLiteException {
        conn = SQLite.getConnection(":memory:");
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

    private List<String> col(String sql) throws SQLiteException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    @Test
    void naturalOrderInOrderByAndMinMax() throws SQLiteException {
        conn.createCollation("natsort", NATURAL);
        exec("CREATE TABLE f (name TEXT); INSERT INTO f VALUES ('file10'), ('file2'), ('File1'), ('file20'), ('file3')");
        assertEquals(List.of("File1", "file10", "file2", "file20", "file3"), col("SELECT name FROM f ORDER BY name"));
        assertEquals(List.of("File1", "file2", "file3", "file10", "file20"), col("SELECT name FROM f ORDER BY name COLLATE natsort"));
        assertEquals(List.of("file20"), col("SELECT max(name COLLATE natsort) FROM f"));
        assertEquals(List.of("file20", "file10", "file3", "file2", "File1"), col("SELECT name FROM f ORDER BY name COLLATE NATSORT DESC"));
    }

    @Test
    void javaTextCollatorWorksDirectly() throws SQLiteException {
        Collator primary = Collator.getInstance(Locale.FRENCH);
        primary.setStrength(Collator.PRIMARY); // ignore case and accents
        conn.createCollation("fr_ci_ai", primary);
        exec("CREATE TABLE w (word TEXT); INSERT INTO w VALUES ('cote'), ('Côte'), ('côté'), ('coté'), ('zèbre'), ('apple')");
        assertEquals(List.of("4"), col("SELECT count(*) FROM w WHERE word = 'COTE' COLLATE fr_ci_ai"));
        assertEquals(List.of("3"), col("SELECT count(DISTINCT word COLLATE fr_ci_ai) FROM w"));
        assertEquals("apple", col("SELECT word FROM w ORDER BY word COLLATE fr_ci_ai").getFirst());
        assertEquals("zèbre", col("SELECT word FROM w ORDER BY word COLLATE fr_ci_ai").getLast());
    }

    @Test
    void columnCollationDrivesIndexesUniquenessAndGrouping() throws SQLiteException {
        conn.createCollation("ci", String.CASE_INSENSITIVE_ORDER);
        exec("CREATE TABLE u (email TEXT COLLATE ci UNIQUE)");
        exec("INSERT INTO u VALUES ('Ada@example.com'), ('bob@example.com')");
        SQLiteException dup = assertThrows(SQLiteException.class, () -> exec("INSERT INTO u VALUES ('ADA@EXAMPLE.COM')"));
        assertEquals(2067, dup.getExtendedErrorCode()); // SQLITE_CONSTRAINT_UNIQUE
        assertEquals(List.of("Ada@example.com"), col("SELECT email FROM u WHERE email = 'ada@EXAMPLE.com'"));
        // The lookup uses the UNIQUE index, which is ordered by the custom collation.
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "EXPLAIN QUERY PLAN SELECT email FROM u WHERE email = 'x'")) {
            assertTrue(rs.next());
            assertTrue(rs.getString(4).contains("USING COVERING INDEX"), rs.getString(4));
        }
        exec("CREATE TABLE g (tag TEXT COLLATE ci); INSERT INTO g VALUES ('Java'), ('JAVA'), ('java'), ('Go')");
        assertEquals(List.of("Go 1", "java 3"), groups("SELECT lower(min(tag)), count(*) FROM g GROUP BY tag ORDER BY tag"));
    }

    private List<String> groups(String sql) throws SQLiteException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add((rs.getString(1).equals("go") ? "Go" : rs.getString(1)) + " " + rs.getInt(2));
            }
        }
        return out;
    }

    @Test
    void comparatorSeesExactUnicodeStrings() throws SQLiteException {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        conn.createCollation("spy", (a, b) -> {
            seen.add(a);
            seen.add(b);
            return a.compareTo(b);
        });
        exec("CREATE TABLE s (v TEXT); INSERT INTO s VALUES ('héllo'), ('世界'), ('🌍 earth'), (''), ('plain')");
        assertEquals(5, col("SELECT v FROM s ORDER BY v COLLATE spy").size());
        assertEquals(Set.of("héllo", "世界", "🌍 earth", "", "plain"), seen);
    }

    @Test
    void throwingComparatorFailsTheStatementWithItsCause() throws SQLiteException {
        conn.createCollation("fragile", (a, b) -> {
            if (a.equals("bad") || b.equals("bad")) {
                throw new IllegalStateException("cannot compare 'bad'");
            }
            return a.compareTo(b);
        });
        exec("CREATE TABLE t (v TEXT); INSERT INTO t VALUES ('b'), ('a'), ('bad'), ('c')");
        SQLiteException e = assertThrows(SQLiteException.class, () -> col("SELECT v FROM t ORDER BY v COLLATE fragile"));
        assertEquals(Native.SQLITE_INTERRUPT, e.getErrorCode());
        assertTrue(e.getMessage().contains("collation fragile: cannot compare 'bad'"), e.getMessage());
        assertInstanceOf(IllegalStateException.class, e.getCause());

        // The interrupt does not leak into later statements; the collation still works on good data.
        assertEquals(List.of("4"), col("SELECT count(*) FROM t"));
        assertEquals(List.of("a", "b", "c"), col("SELECT v FROM t WHERE v <> 'bad' ORDER BY v COLLATE fragile"));
        SQLiteException unrelated = assertThrows(SQLiteException.class, () -> exec("INSERT INTO nope VALUES (1)"));
        assertNull(unrelated.getCause());

        // Also through executeUpdate (CREATE INDEX sorts with the collation) and a prepared statement.
        SQLiteException idx = assertThrows(SQLiteException.class, () -> exec("CREATE INDEX ix ON t (v COLLATE fragile)"));
        assertInstanceOf(IllegalStateException.class, idx.getCause());
        try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM t WHERE v = ? COLLATE fragile")) {
            ps.setString(1, "a");
            SQLiteException pe = assertThrows(SQLiteException.class, () -> {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                }
            });
            assertInstanceOf(IllegalStateException.class, pe.getCause());
        }
    }

    @Test
    void aFailedComparisonNeverYieldsRows() throws SQLiteException {
        // With few rows the sort completes before SQLite checks the interrupt, so step() returns a row
        // computed with the fallback comparison; the library must turn that into an error instead.
        conn.createCollation("once", new Comparator<String>() {
            boolean thrown;

            @Override
            public int compare(String a, String b) {
                if (!thrown) {
                    thrown = true;
                    throw new IllegalArgumentException("first comparison fails");
                }
                return a.compareTo(b);
            }
        });
        exec("CREATE TABLE t (v TEXT); INSERT INTO t VALUES ('b'), ('a')");
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT v FROM t ORDER BY v COLLATE once")) {
            SQLiteException e = assertThrows(SQLiteException.class, rs::next);
            assertEquals(Native.SQLITE_INTERRUPT, e.getErrorCode());
            assertInstanceOf(IllegalArgumentException.class, e.getCause());
            assertTrue(e.getMessage().contains("collation once: first comparison fails"), e.getMessage());
            assertTrue(!rs.next(), "no rows after the failure");
        }
        assertEquals(List.of("a", "b"), col("SELECT v FROM t ORDER BY v COLLATE once"));
    }

    @Test
    void unknownReplacedAndRemovedCollations() throws SQLiteException {
        SQLiteException e = assertThrows(SQLiteException.class, () -> col("SELECT 'a' = 'A' COLLATE nope"));
        assertTrue(e.getMessage().contains("no such collation sequence: nope"), e.getMessage());

        conn.createCollation("flip", Comparator.naturalOrder());
        assertEquals(List.of("a", "b"), col("SELECT column1 FROM (VALUES ('b'), ('a')) ORDER BY column1 COLLATE flip"));
        conn.createCollation("flip", Comparator.reverseOrder());
        assertEquals(List.of("b", "a"), col("SELECT column1 FROM (VALUES ('b'), ('a')) ORDER BY column1 COLLATE flip"));
        conn.removeCollation("FLIP");
        assertThrows(SQLiteException.class, () -> col("SELECT column1 FROM (VALUES ('b')) ORDER BY column1 COLLATE flip"));
    }

    @Test
    void cannotReplaceACollationInUse() throws SQLiteException {
        conn.createCollation("ci", String.CASE_INSENSITIVE_ORDER);
        exec("CREATE TABLE t (v TEXT); INSERT INTO t VALUES ('b'), ('a')");
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT v FROM t ORDER BY v COLLATE ci")) {
            assertTrue(rs.next());
            SQLiteException e = assertThrows(SQLiteException.class, () -> conn.createCollation("ci", Comparator.reverseOrder()));
            assertEquals(5, e.getErrorCode()); // SQLITE_BUSY
        }
        conn.createCollation("ci", Comparator.reverseOrder());
    }

    @Test
    void schemaCollationsMustBeRegisteredOnEachConnection(@TempDir Path dir) throws SQLiteException {
        String path = dir.resolve("c.db").toString();
        try (Connection c = SQLite.getConnection(path)) {
            c.createCollation("natsort", NATURAL);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE f (name TEXT COLLATE natsort); CREATE INDEX ix ON f (name); "
                        + "INSERT INTO f VALUES ('f10'), ('f9')");
            }
        }
        try (Connection c = SQLite.getConnection(path); Statement st = c.createStatement()) {
            SQLiteException e = assertThrows(SQLiteException.class, () -> st.executeQuery("SELECT name FROM f ORDER BY name"));
            assertTrue(e.getMessage().contains("no such collation sequence: natsort"), e.getMessage());
            c.createCollation("natsort", NATURAL);
            List<String> names = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT name FROM f ORDER BY name")) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
            assertEquals(List.of("f9", "f10"), names);
        }
    }
}
