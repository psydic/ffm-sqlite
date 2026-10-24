package io.github.revsnai.ffmsqlite.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQL passes through to SQLite unchanged: temp tables, junction tables, every join kind, and modern SQL. */
class SqlFeaturesTest {
    @TempDir
    Path dir;
    private Connection conn;

    @BeforeEach
    void open() throws SQLiteException {
        conn = SQLite.getConnection(dir.resolve("app.db").toString(), SQLite.Config.DEFAULT.withForeignKeys(true));
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

    /** Every row as "col1|col2|…". */
    private static List<String> rows(Connection c, String sql) throws SQLiteException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    sb.append(i > 1 ? "|" : "").append(rs.getString(i));
                }
                out.add(sb.toString());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ temp tables

    @Test
    void tempTablesArePrivateToTheConnectionAndVanishOnClose() throws SQLiteException {
        exec(conn, "CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT); INSERT INTO items (name) VALUES ('main row')");
        exec(conn, "CREATE TEMP TABLE scratch (id INTEGER PRIMARY KEY, note TEXT); INSERT INTO scratch (note) VALUES ('only here')");
        assertEquals(List.of("only here"), rows(conn, "SELECT note FROM scratch"));
        assertEquals(List.of("main row|only here"), rows(conn, "SELECT i.name, s.note FROM items i JOIN temp.scratch s ON s.id = i.id"));
        assertEquals(List.of("scratch"), conn.getMetaData().getTables("temp").stream().map(DatabaseMetaData.TableInfo::name).toList());

        try (Connection other = SQLite.getConnection(dir.resolve("app.db").toString())) {
            assertThrows(SQLiteException.class, () -> rows(other, "SELECT * FROM scratch"), "invisible to other connections");
            assertEquals(List.of("main row"), rows(other, "SELECT name FROM items"));
        }
        // A temp table shadows a main table of the same name; main.items still reaches the real one.
        exec(conn, "CREATE TEMP TABLE items (id INTEGER PRIMARY KEY, name TEXT); INSERT INTO temp.items (name) VALUES ('temp row')");
        assertEquals(List.of("temp row"), rows(conn, "SELECT name FROM items"));
        assertEquals(List.of("main row"), rows(conn, "SELECT name FROM main.items"));

        conn.close();
        conn = SQLite.getConnection(dir.resolve("app.db").toString());
        assertThrows(SQLiteException.class, () -> rows(conn, "SELECT * FROM scratch"), "dropped when the connection closed");
        assertEquals(List.of("main row"), rows(conn, "SELECT name FROM items"));
    }

    // ------------------------------------------------------------------ junction (many-to-many) tables

    @Test
    void junctionTableForManyToMany() throws SQLiteException {
        exec(conn, """
                CREATE TABLE student (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
                CREATE TABLE course  (id INTEGER PRIMARY KEY, title TEXT NOT NULL);
                CREATE TABLE enrollment (
                    student_id INTEGER NOT NULL REFERENCES student (id) ON DELETE CASCADE,
                    course_id  INTEGER NOT NULL REFERENCES course (id) ON DELETE CASCADE,
                    grade TEXT,
                    PRIMARY KEY (student_id, course_id)
                ) WITHOUT ROWID;
                CREATE INDEX ix_enrollment_course ON enrollment (course_id);
                INSERT INTO student VALUES (1, 'Ada'), (2, 'Alan'), (3, 'Grace');
                INSERT INTO course VALUES (10, 'Math'), (20, 'Physics'), (30, 'Art');
                INSERT INTO enrollment VALUES (1, 10, 'A'), (1, 20, 'B'), (2, 10, 'A'), (3, 20, NULL);
                """);
        assertEquals(List.of("Ada|Math,Physics", "Alan|Math", "Grace|Physics"), rows(conn, """
                SELECT s.name, group_concat(c.title, ',' ORDER BY c.title)
                FROM student s JOIN enrollment e ON e.student_id = s.id JOIN course c ON c.id = e.course_id
                GROUP BY s.id ORDER BY s.name"""));
        assertEquals(List.of("Art|0", "Math|2", "Physics|2"), rows(conn, """
                SELECT c.title, count(e.student_id) FROM course c LEFT JOIN enrollment e ON e.course_id = c.id
                GROUP BY c.id ORDER BY c.title"""));

        SQLiteException dup = assertThrows(SQLiteException.class, () -> exec(conn, "INSERT INTO enrollment VALUES (1, 10, 'C')"));
        assertEquals(1555, dup.getExtendedErrorCode()); // SQLITE_CONSTRAINT_PRIMARYKEY
        SQLiteException orphan = assertThrows(SQLiteException.class, () -> exec(conn, "INSERT INTO enrollment VALUES (9, 10, 'A')"));
        assertEquals(787, orphan.getExtendedErrorCode()); // SQLITE_CONSTRAINT_FOREIGNKEY

        exec(conn, "DELETE FROM student WHERE id = 1");
        assertEquals(List.of("2"), rows(conn, "SELECT count(*) FROM enrollment"), "ON DELETE CASCADE removed Ada's enrollments");
        assertEquals(List.of("student_id", "course_id"), conn.getMetaData().getPrimaryKey("enrollment"));
        assertEquals(2, conn.getMetaData().getForeignKeys("enrollment").size());
    }

    // ------------------------------------------------------------------ joins

    @Test
    void everyJoinKind() throws SQLiteException {
        exec(conn, """
                CREATE TABLE l (k INTEGER, lv TEXT); INSERT INTO l VALUES (1, 'l1'), (2, 'l2');
                CREATE TABLE r (k INTEGER, rv TEXT); INSERT INTO r VALUES (2, 'r2'), (3, 'r3');
                CREATE TABLE emp (id INTEGER PRIMARY KEY, name TEXT, manager_id INTEGER);
                INSERT INTO emp VALUES (1, 'boss', NULL), (2, 'dev', 1), (3, 'intern', 2);
                """);
        assertEquals(List.of("l2|r2"), rows(conn, "SELECT lv, rv FROM l INNER JOIN r ON r.k = l.k"));
        assertEquals(List.of("l1|null", "l2|r2"), rows(conn, "SELECT lv, rv FROM l LEFT JOIN r ON r.k = l.k ORDER BY lv"));
        assertEquals(List.of("l2|r2", "null|r3"), rows(conn, "SELECT lv, rv FROM l RIGHT JOIN r ON r.k = l.k ORDER BY rv"));
        assertEquals(List.of("l1|null", "l2|r2", "null|r3"),
                rows(conn, "SELECT lv, rv FROM l FULL OUTER JOIN r ON r.k = l.k ORDER BY coalesce(lv, 'z'), rv"));
        assertEquals("4", rows(conn, "SELECT count(*) FROM l CROSS JOIN r").getFirst());
        assertEquals(List.of("2|l2|r2"), rows(conn, "SELECT k, lv, rv FROM l JOIN r USING (k)"));
        assertEquals(List.of("2|l2|r2"), rows(conn, "SELECT * FROM l NATURAL JOIN r"));
        assertEquals(List.of("dev|boss", "intern|dev"),
                rows(conn, "SELECT e.name, m.name FROM emp e JOIN emp m ON m.id = e.manager_id ORDER BY e.id"));
        assertEquals(List.of("l1"), rows(conn, "SELECT lv FROM l WHERE NOT EXISTS (SELECT 1 FROM r WHERE r.k = l.k)"));
    }

    // ------------------------------------------------------------------ the rest of modern SQLite SQL

    @Test
    void modernSql() throws SQLiteException {
        exec(conn, """
                CREATE TABLE sales (id INTEGER PRIMARY KEY, region TEXT, amount INTEGER,
                                    doubled INTEGER GENERATED ALWAYS AS (amount * 2) VIRTUAL);
                INSERT INTO sales (region, amount) VALUES ('n', 10), ('n', 20), ('s', 5), ('s', 50), ('s', 15);
                CREATE VIEW totals AS SELECT region, sum(amount) AS total FROM sales GROUP BY region;
                CREATE TABLE audit (msg TEXT);
                CREATE TRIGGER sales_ai AFTER INSERT ON sales BEGIN INSERT INTO audit VALUES ('added ' || NEW.amount); END;
                CREATE TABLE kv (k TEXT PRIMARY KEY, v INTEGER) STRICT;
                """);
        // Window functions and a recursive CTE
        assertEquals(List.of("n|10|1|10", "n|20|2|30", "s|5|1|5", "s|15|2|20", "s|50|3|70"), rows(conn, """
                SELECT region, amount, row_number() OVER w, sum(amount) OVER w FROM sales
                WINDOW w AS (PARTITION BY region ORDER BY amount) ORDER BY region, amount"""));
        assertEquals(List.of("1,2,3,4,5"), rows(conn,
                "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 5) SELECT group_concat(i) FROM n"));
        // View, compound selects, CASE, generated column
        assertEquals(List.of("n|30", "s|70"), rows(conn, "SELECT * FROM totals ORDER BY region"));
        assertEquals(List.of("n"), rows(conn, "SELECT region FROM sales INTERSECT SELECT 'n' EXCEPT SELECT 'x'"));
        assertEquals(List.of("big"), rows(conn, "SELECT CASE WHEN max(amount) > 40 THEN 'big' ELSE 'small' END FROM sales"));
        assertEquals(List.of("100"), rows(conn, "SELECT doubled FROM sales WHERE amount = 50"));
        // UPSERT, and INSERT ... RETURNING read like a query
        exec(conn, "INSERT INTO kv VALUES ('hits', 1) ON CONFLICT (k) DO UPDATE SET v = v + excluded.v");
        exec(conn, "INSERT INTO kv VALUES ('hits', 1) ON CONFLICT (k) DO UPDATE SET v = v + excluded.v");
        assertEquals(List.of("2"), rows(conn, "SELECT v FROM kv WHERE k = 'hits'"));
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO sales (region, amount) VALUES (?, ?) RETURNING id, doubled")) {
            ps.setString(1, "e");
            ps.setInt(2, 7);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(6, rs.getInt("id"));
                assertEquals(14, rs.getInt("doubled"));
            }
        }
        // Trigger, STRICT typing, JSON
        assertEquals(List.of("added 7"), rows(conn, "SELECT msg FROM audit"));
        SQLiteException strict = assertThrows(SQLiteException.class, () -> exec(conn, "INSERT INTO kv VALUES ('x', 'not a number')"));
        assertEquals(3091, strict.getExtendedErrorCode()); // SQLITE_CONSTRAINT_DATATYPE
        assertEquals(List.of("Ada|2"), rows(conn,
                "SELECT '{\"name\":\"Ada\",\"tags\":[1,2]}' ->> '$.name', json_array_length('{\"tags\":[1,2]}', '$.tags')"));
        // Savepoints in SQL
        exec(conn, "SAVEPOINT sp; DELETE FROM sales; ROLLBACK TO sp; RELEASE sp");
        assertEquals(List.of("6"), rows(conn, "SELECT count(*) FROM sales"));
    }

    @Test
    void fullTextSearchWhenTheLibraryHasFts5() throws SQLiteException {
        try {
            exec(conn, "CREATE VIRTUAL TABLE docs USING fts5(title, body)");
        } catch (SQLiteException e) {
            org.junit.jupiter.api.Assumptions.abort("this SQLite build has no FTS5: " + e.getMessage());
        }
        exec(conn, "INSERT INTO docs VALUES ('FFM', 'Java calls SQLite through the foreign function API'), "
                + "('JNI', 'the older native interface')");
        assertEquals(List.of("FFM"), rows(conn, "SELECT title FROM docs WHERE docs MATCH 'foreign AND function'"));
    }
}
