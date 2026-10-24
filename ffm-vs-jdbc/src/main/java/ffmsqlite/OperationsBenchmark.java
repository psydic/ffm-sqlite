package ffmsqlite;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.TimeUnit;

/**
 * Schema and administration operations, sqlite-jdbc vs ffm-sqlite: creating databases, tables and
 * indexes, PRAGMAs (statement and table-valued function forms) and SQL functions. All SQL goes through
 * each library's plain {@code Statement}, as such SQL usually does. Scores are µs per operation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "-Xms1g", "-Xmx1g"})
@State(Scope.Thread)
public class OperationsBenchmark {
    static final int BIG_ROWS = 10_000;

    @Param({"jdbc", "ffm-sqlite"})
    String lib;

    /** The minimum both libraries need: execute SQL, iterate a query reading every column as text. */
    interface Sql extends AutoCloseable {
        void exec(String sql) throws Exception;

        void query(String sql, Blackhole bh) throws Exception;

        void backup(String path) throws Exception;

        void restore(String path) throws Exception;

        byte[] serialize() throws Exception;

        void deserialize(byte[] image) throws Exception;

        @Override
        void close() throws Exception;
    }

    static final class JdbcSql implements Sql {
        final java.sql.Connection conn;
        final java.sql.Statement st;

        JdbcSql(String path) throws Exception {
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            st = conn.createStatement();
        }

        @Override
        public void exec(String sql) throws Exception {
            st.executeUpdate(sql);
        }

        @Override
        public void query(String sql, Blackhole bh) throws Exception {
            try (var rs = st.executeQuery(sql)) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    for (int i = 1; i <= n; i++) {
                        bh.consume(rs.getString(i));
                    }
                }
            }
        }

        /** sqlite-jdbc's extended SQL commands, which call the same sqlite3_backup_* API. */
        @Override
        public void backup(String path) throws Exception {
            st.executeUpdate("backup to " + path);
        }

        @Override
        public void restore(String path) throws Exception {
            st.executeUpdate("restore from " + path);
        }

        @Override
        public byte[] serialize() throws Exception {
            return ((org.sqlite.SQLiteConnection) conn).serialize("main");
        }

        @Override
        public void deserialize(byte[] image) throws Exception {
            ((org.sqlite.SQLiteConnection) conn).deserialize("main", image);
        }

        @Override
        public void close() throws Exception {
            conn.close();
        }
    }

    static final class LibSql implements Sql {
        final io.github.psydic.ffmsqlite.db.Connection conn;
        final io.github.psydic.ffmsqlite.db.Statement st;

        LibSql(String path) throws Exception {
            conn = io.github.psydic.ffmsqlite.db.SQLite.getConnection(path);
            st = conn.createStatement();
        }

        @Override
        public void exec(String sql) throws Exception {
            st.executeUpdate(sql);
        }

        @Override
        public void query(String sql, Blackhole bh) throws Exception {
            try (var rs = st.executeQuery(sql)) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    for (int i = 1; i <= n; i++) {
                        bh.consume(rs.getString(i));
                    }
                }
            }
        }

        @Override
        public void backup(String path) throws Exception {
            conn.backup(path);
        }

        @Override
        public void restore(String path) throws Exception {
            conn.restore(path);
        }

        @Override
        public byte[] serialize() throws Exception {
            return conn.serialize();
        }

        @Override
        public void deserialize(byte[] image) throws Exception {
            conn.deserialize(image);
        }

        @Override
        public void close() {
            conn.close();
        }
    }

    Sql mem;
    Path dir;
    long fileCounter;
    String existingDb;
    String backupFile;
    byte[] image;

    Sql open(String path) throws Exception {
        return lib.equals("jdbc") ? new JdbcSql(path) : new LibSql(path);
    }

    @Setup(Level.Trial)
    public void setup() throws Exception {
        dir = Files.createTempDirectory("sqlite-ops");
        existingDb = dir.resolve("existing.db").toString();
        try (Sql s = open(existingDb)) {
            s.exec("CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY, name TEXT)");
        }
        mem = open(":memory:");
        mem.exec("CREATE TABLE big (id INTEGER PRIMARY KEY, name TEXT NOT NULL, score REAL, created TEXT, payload BLOB)");
        mem.exec("BEGIN");
        mem.exec("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < " + BIG_ROWS + ") "
                + "INSERT INTO big SELECT i, 'name-' || (i * 7919 % " + BIG_ROWS + "), i * 0.5, "
                + "datetime('2024-01-01', '+' || i || ' minutes'), randomblob(16) FROM n");
        mem.exec("COMMIT");
        backupFile = dir.resolve("restore-source.db").toString();
        mem.backup(backupFile);
        image = mem.serialize();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        mem.close();
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) {
                Files.delete(p);
            }
        }
        Files.delete(dir);
    }

    // ------------------------------------------------------------------ databases

    /** New database file: open, CREATE TABLE (first write creates the file, with fsync), close, delete. */
    @Benchmark
    public void db_create() throws Exception {
        Path p = dir.resolve("new-" + (fileCounter++) + ".db");
        try (Sql s = open(p.toString())) {
            s.exec("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
        }
        Files.delete(p);
    }

    /** Typical connection initialisation: open an existing file and apply the usual PRAGMAs. */
    @Benchmark
    public void db_openWithPragmas() throws Exception {
        try (Sql s = open(existingDb)) {
            s.exec("PRAGMA journal_mode = WAL");
            s.exec("PRAGMA synchronous = NORMAL");
            s.exec("PRAGMA foreign_keys = ON");
            s.exec("PRAGMA busy_timeout = 5000");
        }
    }

    /** Online backup of the 10,000-row in-memory database to a new file, then delete it. */
    @Benchmark
    public void db_backupToFile() throws Exception {
        Path p = dir.resolve("backup-" + (fileCounter++) + ".db");
        mem.backup(p.toString());
        Files.delete(p);
    }

    /** Restore the in-memory database from a backup file of the same content. */
    @Benchmark
    public void db_restoreFromFile() throws Exception {
        mem.restore(backupFile);
    }

    /** The 10,000-row in-memory database as a byte[]. */
    @Benchmark
    public byte[] db_serialize() throws Exception {
        return mem.serialize();
    }

    /** Replace the in-memory database with an image of the same content. */
    @Benchmark
    public void db_deserialize() throws Exception {
        mem.deserialize(image);
    }

    // ------------------------------------------------------------------ tables

    @Benchmark
    public void table_createDrop() throws Exception {
        mem.exec("CREATE TABLE x (id INTEGER PRIMARY KEY, a TEXT NOT NULL, b REAL, c BLOB, d INTEGER DEFAULT 0, "
                + "e TEXT CHECK (length(e) < 100), UNIQUE (a, d))");
        mem.exec("DROP TABLE x");
    }

    @Benchmark
    public void table_alterAddColumn() throws Exception {
        mem.exec("CREATE TABLE y (id INTEGER PRIMARY KEY)");
        mem.exec("ALTER TABLE y ADD COLUMN extra TEXT DEFAULT 'x'");
        mem.exec("DROP TABLE y");
    }

    // ------------------------------------------------------------------ indexes (on 10,000 rows)

    @Benchmark
    public void index_createDrop() throws Exception {
        mem.exec("CREATE INDEX ix_name ON big (name)");
        mem.exec("DROP INDEX ix_name");
    }

    @Benchmark
    public void index_createDropUniqueComposite() throws Exception {
        mem.exec("CREATE UNIQUE INDEX ux_name_id ON big (name, id)");
        mem.exec("DROP INDEX ux_name_id");
    }

    @Benchmark
    public void index_createDropPartialExpression() throws Exception {
        mem.exec("CREATE INDEX ix_expr ON big (lower(name)) WHERE score > 100");
        mem.exec("DROP INDEX ix_expr");
    }

    // ------------------------------------------------------------------ pragmas

    @Benchmark
    public void pragma_read(Blackhole bh) throws Exception {
        mem.query("PRAGMA user_version", bh);
    }

    @Benchmark
    public void pragma_write() throws Exception {
        mem.exec("PRAGMA cache_size = -8000");
    }

    @Benchmark
    public void pragma_tableInfo(Blackhole bh) throws Exception {
        mem.query("PRAGMA table_info(big)", bh);
    }

    /** Table-valued pragma function, filterable and joinable like a table. */
    @Benchmark
    public void pragma_tableInfoFunction(Blackhole bh) throws Exception {
        mem.query("SELECT name, type FROM pragma_table_info('big') WHERE pk = 0", bh);
    }

    @Benchmark
    public void pragma_schemaListing(Blackhole bh) throws Exception {
        mem.query("SELECT m.name, p.name, p.type FROM sqlite_schema m JOIN pragma_table_info(m.name) p WHERE m.type = 'table'", bh);
    }

    // ------------------------------------------------------------------ SQL functions

    /** Scalar built-ins evaluated per row: 1,000 rows x 4 columns returned. */
    @Benchmark
    public void function_scalar1000Rows(Blackhole bh) throws Exception {
        mem.query("SELECT upper(name), length(name), round(score, 1), date(created, '+1 day') FROM big LIMIT 1000", bh);
    }

    /** Aggregates over 10,000 rows, one row returned: engine-bound. */
    @Benchmark
    public void function_aggregate(Blackhole bh) throws Exception {
        mem.query("SELECT count(*), sum(score), avg(score), max(name), group_concat(id % 10) FROM big", bh);
    }

    @Benchmark
    public void function_json(Blackhole bh) throws Exception {
        mem.query("SELECT json_extract(json_object('id', id, 'name', name), '$.name') FROM big LIMIT 1000", bh);
    }
}
