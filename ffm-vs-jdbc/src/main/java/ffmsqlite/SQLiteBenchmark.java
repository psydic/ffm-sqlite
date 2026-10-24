package ffmsqlite;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * FFM vs sqlite-jdbc (JNI). Write/read scores are per row ({@link OperationsPerInvocation}).
 *
 * <p>Run: {@code java -jar target/benchmarks.jar [-Dsqlite.lib=...]} (see README.md).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "-Xms1g", "-Xmx1g"})
public class SQLiteBenchmark {

    static final int ROWS = 1_000;
    static final String DDL = "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT NOT NULL, score INTEGER NOT NULL)";
    static final String INSERT = "INSERT INTO t (id, name, score) VALUES (?, ?, ?)";
    static final String SELECT = "SELECT id, name, score FROM t";

    static String[] names() {
        String[] names = new String[ROWS];
        for (int i = 0; i < ROWS; i++) {
            names[i] = "name-" + i;
        }
        return names;
    }

    static String text(MemorySegment ptr, int len) {
        byte[] bytes = new byte[len];
        MemorySegment.copy(ptr.reinterpret(len), JAVA_BYTE, 0, bytes, 0, len);
        return new String(bytes, UTF_8);
    }

    // =====================================================================================
    // 1. Connection / setup overhead
    // =====================================================================================

    @State(Scope.Thread)
    public static class ConnState {
        @Param({"file", "memory"})
        String target;

        String path;
        String url;
        Path dir;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            if (target.equals("memory")) {
                path = ":memory:";
            } else {
                dir = Files.createTempDirectory("sqlite-bench");
                path = dir.resolve("conn.db").toString();
                try (SQLiteFFM db = SQLiteFFM.open(path)) {
                    db.exec(DDL);
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            }
            url = "jdbc:sqlite:" + path;
        }

        @TearDown(Level.Trial)
        public void tearDown() throws Exception {
            if (dir != null) {
                try (var files = Files.list(dir)) {
                    for (Path p : files.toList()) {
                        Files.delete(p);
                    }
                }
                Files.delete(dir);
            }
        }
    }

    /** Open + close; includes marshalling the path string into a confined arena. */
    @Benchmark
    public void connect_ffm(ConnState s) throws Throwable {
        SQLiteFFM.open(s.path).close();
    }

    /** The idiomatic path: DriverManager scans registered drivers and parses the URL. */
    @Benchmark
    public void connect_jdbcDriverManager(ConnState s) throws SQLException {
        DriverManager.getConnection(s.url).close();
    }

    /** Bypasses DriverManager to isolate sqlite-jdbc's own connection cost. */
    @Benchmark
    public void connect_jdbcDirect(ConnState s) throws SQLException {
        org.sqlite.JDBC.createConnection(s.url, new Properties()).close();
    }

    /** Open + first statement: forces the schema to be read, which open alone defers. */
    @Benchmark
    public int connect_ffmFirstQuery(ConnState s) throws Throwable {
        try (SQLiteFFM db = SQLiteFFM.open(s.path)) {
            MemorySegment stmt = db.prepare("SELECT 1");
            int rc = (int) Sqlite3.step.invokeExact(stmt);
            SQLiteFFM.finalize(stmt);
            return rc;
        }
    }

    @Benchmark
    public boolean connect_jdbcFirstQuery(ConnState s) throws SQLException {
        try (Connection c = org.sqlite.JDBC.createConnection(s.url, new Properties());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            return rs.next();
        }
    }

    // =====================================================================================
    // 2. Write throughput: BEGIN; DELETE FROM t; 1,000 x INSERT; COMMIT  (score = ns/row)
    //    DELETE without WHERE hits SQLite's truncate optimisation, keeping the table size stable.
    // =====================================================================================

    @State(Scope.Thread)
    public static class FfmWriteState {
        final String[] names = names();
        Arena arena;
        SQLiteFFM db;
        MemorySegment insert, delete, begin, commit, textBuf;

        @Setup(Level.Trial)
        public void setup() throws Throwable {
            arena = Arena.ofConfined();
            db = SQLiteFFM.open(":memory:");
            db.exec(DDL);
            insert = db.prepare(INSERT);
            delete = db.prepare("DELETE FROM t");
            begin = arena.allocateFrom("BEGIN");
            commit = arena.allocateFrom("COMMIT");
            textBuf = arena.allocate(256);
        }

        void begin() throws Throwable {
            int rc = (int) Sqlite3.exec.invokeExact(db.handle(), begin, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
            db.check(rc, "BEGIN");
            if ((int) Sqlite3.step.invokeExact(delete) != Sqlite3.SQLITE_DONE) {
                throw new IllegalStateException("DELETE failed");
            }
            int ignored = (int) Sqlite3.reset.invokeExact(delete);
        }

        void commit() throws Throwable {
            int rc = (int) Sqlite3.exec.invokeExact(db.handle(), commit, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
            db.check(rc, "COMMIT");
        }

        @TearDown(Level.Trial)
        public void tearDown() throws Throwable {
            SQLiteFFM.finalize(insert);
            SQLiteFFM.finalize(delete);
            db.close();
            arena.close();
        }
    }

    @State(Scope.Thread)
    public static class JdbcWriteState {
        /**
         * sqlite-jdbc defaults to true: after every INSERT it prepares, runs and finalizes an extra
         * "SELECT last_insert_rowid()" so getGeneratedKeys() works. That dominates executeUpdate().
         */
        @Param({"true", "false"})
        String generatedKeys;

        final String[] names = names();
        Connection conn;
        PreparedStatement insert, delete;

        @Setup(Level.Trial)
        public void setup() throws SQLException {
            Properties props = new Properties();
            props.setProperty("jdbc.get_generated_keys", generatedKeys);
            conn = DriverManager.getConnection("jdbc:sqlite::memory:", props);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(DDL);
            }
            conn.setAutoCommit(false);
            insert = conn.prepareStatement(INSERT);
            delete = conn.prepareStatement("DELETE FROM t");
        }

        @TearDown(Level.Trial)
        public void tearDown() throws SQLException {
            insert.close();
            delete.close();
            conn.close();
        }
    }

    /** What a direct port of "sqlite3_exec per statement" does: build SQL text, allocate, parse, run. */
    @Benchmark
    @OperationsPerInvocation(ROWS)
    public void write_ffmExecPerRow(FfmWriteState s) throws Throwable {
        s.begin();
        for (int i = 0; i < ROWS; i++) {
            String sql = "INSERT INTO t (id, name, score) VALUES (" + i + ", '" + s.names[i] + "', " + (i * 7) + ")";
            try (Arena arena = Arena.ofConfined()) {
                int rc = (int) Sqlite3.exec.invokeExact(s.db.handle(), arena.allocateFrom(sql),
                        MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
                s.db.check(rc, "INSERT");
            }
        }
        s.commit();
    }

    /** Prepared statement, regular downcalls, text copied into a reused native buffer (SQLITE_STATIC). */
    @Benchmark
    @OperationsPerInvocation(ROWS)
    public void write_ffmPrepared(FfmWriteState s) throws Throwable {
        MemorySegment stmt = s.insert;
        MemorySegment buf = s.textBuf;
        s.begin();
        for (int i = 0; i < ROWS; i++) {
            byte[] name = s.names[i].getBytes(UTF_8);
            MemorySegment.copy(name, 0, buf, JAVA_BYTE, 0, name.length);
            int rc = (int) Sqlite3.bind_int.invokeExact(stmt, 1, i);
            rc |= (int) Sqlite3.bind_text.invokeExact(stmt, 2, buf, name.length, Sqlite3.SQLITE_STATIC);
            rc |= (int) Sqlite3.bind_int.invokeExact(stmt, 3, i * 7);
            if (rc != Sqlite3.SQLITE_OK || (int) Sqlite3.step.invokeExact(stmt) != Sqlite3.SQLITE_DONE) {
                throw new IllegalStateException("INSERT failed");
            }
            rc = (int) Sqlite3.reset.invokeExact(stmt);
        }
        s.commit();
    }

    /**
     * Prepared statement with critical downcalls for bind/reset and the Java byte[] passed straight
     * to SQLite as a heap segment (SQLITE_TRANSIENT: SQLite copies it before returning).
     * sqlite3_step stays a regular downcall: it can block on locks/IO and must not stall safepoints.
     */
    @Benchmark
    @OperationsPerInvocation(ROWS)
    public void write_ffmPreparedCritical(FfmWriteState s) throws Throwable {
        MemorySegment stmt = s.insert;
        s.begin();
        for (int i = 0; i < ROWS; i++) {
            byte[] name = s.names[i].getBytes(UTF_8);
            int rc = (int) Sqlite3.bind_int_c.invokeExact(stmt, 1, i);
            rc |= (int) Sqlite3.bind_text_c.invokeExact(stmt, 2, MemorySegment.ofArray(name), name.length, Sqlite3.SQLITE_TRANSIENT);
            rc |= (int) Sqlite3.bind_int_c.invokeExact(stmt, 3, i * 7);
            if (rc != Sqlite3.SQLITE_OK || (int) Sqlite3.step.invokeExact(stmt) != Sqlite3.SQLITE_DONE) {
                throw new IllegalStateException("INSERT failed");
            }
            rc = (int) Sqlite3.reset_c.invokeExact(stmt);
        }
        s.commit();
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    public void write_jdbcExecuteUpdate(JdbcWriteState s) throws SQLException {
        PreparedStatement ps = s.insert;
        s.delete.executeUpdate();
        for (int i = 0; i < ROWS; i++) {
            ps.setInt(1, i);
            ps.setString(2, s.names[i]);
            ps.setInt(3, i * 7);
            ps.executeUpdate();
        }
        s.conn.commit();
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    public void write_jdbcBatch(JdbcWriteState s) throws SQLException {
        PreparedStatement ps = s.insert;
        s.delete.executeUpdate();
        for (int i = 0; i < ROWS; i++) {
            ps.setInt(1, i);
            ps.setString(2, s.names[i]);
            ps.setInt(3, i * 7);
            ps.addBatch();
        }
        ps.executeBatch();
        s.conn.commit();
    }

    // ---- The JDBC-style FFM library (../ffm-sqlite): same call shape as the JDBC variants above ----

    @State(Scope.Thread)
    public static class LibWriteState {
        final String[] names = names();
        io.github.revsnai.ffmsqlite.db.Connection conn;
        io.github.revsnai.ffmsqlite.db.PreparedStatement insert, delete;

        @Setup(Level.Trial)
        public void setup() throws io.github.revsnai.ffmsqlite.db.SQLiteException {
            conn = io.github.revsnai.ffmsqlite.db.SQLite.getConnection(":memory:");
            try (io.github.revsnai.ffmsqlite.db.Statement st = conn.createStatement()) {
                st.executeUpdate(DDL);
            }
            conn.setAutoCommit(false);
            insert = conn.prepareStatement(INSERT);
            delete = conn.prepareStatement("DELETE FROM t");
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            conn.close();
        }
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    public void write_libExecuteUpdate(LibWriteState s) throws io.github.revsnai.ffmsqlite.db.SQLiteException {
        io.github.revsnai.ffmsqlite.db.PreparedStatement ps = s.insert;
        s.delete.executeUpdate();
        for (int i = 0; i < ROWS; i++) {
            ps.setInt(1, i);
            ps.setString(2, s.names[i]);
            ps.setInt(3, i * 7);
            ps.executeUpdate();
        }
        s.conn.commit();
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    public void write_libBatch(LibWriteState s) throws io.github.revsnai.ffmsqlite.db.SQLiteException {
        io.github.revsnai.ffmsqlite.db.PreparedStatement ps = s.insert;
        s.delete.executeUpdate();
        for (int i = 0; i < ROWS; i++) {
            ps.setInt(1, i);
            ps.setString(2, s.names[i]);
            ps.setInt(3, i * 7);
            ps.addBatch();
        }
        ps.executeBatch();
        s.conn.commit();
    }

    // =====================================================================================
    // 3. Read throughput: SELECT and materialise 1,000 rows as (int, String, int)  (score = ns/row)
    // =====================================================================================

    /** Target of the sqlite3_exec upcall. exec hands every column over as C text. */
    public static final class RowSink {
        Blackhole bh;

        int onRow(MemorySegment arg, int argc, MemorySegment argv, MemorySegment colNames) {
            MemorySegment cols = argv.reinterpret(argc * ADDRESS.byteSize());
            bh.consume(Integer.parseInt(cstr(cols, 0)));
            bh.consume(cstr(cols, 1));
            bh.consume(Integer.parseInt(cstr(cols, 2)));
            return 0;
        }

        private static String cstr(MemorySegment cols, int i) {
            return cols.getAtIndex(ADDRESS, i).reinterpret(Long.MAX_VALUE).getString(0);
        }
    }

    @State(Scope.Thread)
    public static class FfmReadState {
        Arena arena;
        SQLiteFFM db;
        MemorySegment select, selectSql, callback;
        final RowSink sink = new RowSink();

        @Setup(Level.Trial)
        public void setup() throws Throwable {
            arena = Arena.ofConfined();
            db = SQLiteFFM.open(":memory:");
            db.exec(DDL);
            MemorySegment insert = db.prepare(INSERT);
            String[] names = names();
            db.exec("BEGIN");
            for (int i = 0; i < ROWS; i++) {
                int rc = (int) Sqlite3.bind_int.invokeExact(insert, 1, i);
                rc = (int) Sqlite3.bind_text.invokeExact(insert, 2, arena.allocateFrom(names[i]), -1, Sqlite3.SQLITE_TRANSIENT);
                rc = (int) Sqlite3.bind_int.invokeExact(insert, 3, i * 7);
                rc = (int) Sqlite3.step.invokeExact(insert);
                rc = (int) Sqlite3.reset.invokeExact(insert);
            }
            db.exec("COMMIT");
            SQLiteFFM.finalize(insert);

            select = db.prepare(SELECT);
            selectSql = arena.allocateFrom(SELECT);
            MethodHandle onRow = MethodHandles.lookup()
                    .findVirtual(RowSink.class, "onRow", MethodType.methodType(int.class,
                            MemorySegment.class, int.class, MemorySegment.class, MemorySegment.class))
                    .bindTo(sink);
            callback = Sqlite3.linker().upcallStub(onRow, Sqlite3.EXEC_CALLBACK, arena);
        }

        @TearDown(Level.Trial)
        public void tearDown() throws Throwable {
            SQLiteFFM.finalize(select);
            db.close();
            arena.close();
        }
    }

    @State(Scope.Thread)
    public static class JdbcReadState {
        Connection conn;
        PreparedStatement select;

        @Setup(Level.Trial)
        public void setup() throws SQLException {
            conn = DriverManager.getConnection("jdbc:sqlite::memory:");
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(DDL);
            }
            SQLiteJDBC.insertRows(conn, ROWS);
            select = conn.prepareStatement(SELECT);
        }

        @TearDown(Level.Trial)
        public void tearDown() throws SQLException {
            select.close();
            conn.close();
        }
    }

    /** sqlite3_exec + one Java upcall per row; values arrive as C strings and are parsed. */
    @Benchmark
    @OperationsPerInvocation(ROWS)
    public void read_ffmExecUpcall(FfmReadState s, Blackhole bh) throws Throwable {
        s.sink.bh = bh;
        int rc = (int) Sqlite3.exec.invokeExact(s.db.handle(), s.selectSql, s.callback, MemorySegment.NULL, MemorySegment.NULL);
        s.db.check(rc, "SELECT");
    }

    /** Prepared statement, pull model: step + typed column accessors, no upcalls. */
    @Benchmark
    @OperationsPerInvocation(ROWS)
    public void read_ffmPrepared(FfmReadState s, Blackhole bh) throws Throwable {
        MemorySegment stmt = s.select;
        while ((int) Sqlite3.step.invokeExact(stmt) == Sqlite3.SQLITE_ROW) {
            bh.consume((int) Sqlite3.column_int.invokeExact(stmt, 0));
            MemorySegment p = (MemorySegment) Sqlite3.column_text.invokeExact(stmt, 1);
            bh.consume(text(p, (int) Sqlite3.column_bytes.invokeExact(stmt, 1)));
            bh.consume((int) Sqlite3.column_int.invokeExact(stmt, 2));
        }
        int rc = (int) Sqlite3.reset.invokeExact(stmt);
    }

    /** As above, but the per-column accessors are critical downcalls. */
    @Benchmark
    @OperationsPerInvocation(ROWS)
    public void read_ffmPreparedCritical(FfmReadState s, Blackhole bh) throws Throwable {
        MemorySegment stmt = s.select;
        while ((int) Sqlite3.step.invokeExact(stmt) == Sqlite3.SQLITE_ROW) {
            bh.consume((int) Sqlite3.column_int_c.invokeExact(stmt, 0));
            MemorySegment p = (MemorySegment) Sqlite3.column_text_c.invokeExact(stmt, 1);
            bh.consume(text(p, (int) Sqlite3.column_bytes_c.invokeExact(stmt, 1)));
            bh.consume((int) Sqlite3.column_int_c.invokeExact(stmt, 2));
        }
        int rc = (int) Sqlite3.reset_c.invokeExact(stmt);
    }

    @State(Scope.Thread)
    public static class LibReadState {
        io.github.revsnai.ffmsqlite.db.Connection conn;
        io.github.revsnai.ffmsqlite.db.PreparedStatement select;

        @Setup(Level.Trial)
        public void setup() throws io.github.revsnai.ffmsqlite.db.SQLiteException {
            conn = io.github.revsnai.ffmsqlite.db.SQLite.getConnection(":memory:");
            try (io.github.revsnai.ffmsqlite.db.Statement st = conn.createStatement()) {
                st.executeUpdate(DDL);
            }
            conn.setAutoCommit(false);
            String[] names = names();
            try (io.github.revsnai.ffmsqlite.db.PreparedStatement ps = conn.prepareStatement(INSERT)) {
                for (int i = 0; i < ROWS; i++) {
                    ps.setInt(1, i);
                    ps.setString(2, names[i]);
                    ps.setInt(3, i * 7);
                    ps.executeUpdate();
                }
            }
            conn.setAutoCommit(true);
            select = conn.prepareStatement(SELECT);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            conn.close();
        }
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    public void read_lib(LibReadState s, Blackhole bh) throws io.github.revsnai.ffmsqlite.db.SQLiteException {
        try (io.github.revsnai.ffmsqlite.db.ResultSet rs = s.select.executeQuery()) {
            while (rs.next()) {
                bh.consume(rs.getInt(1));
                bh.consume(rs.getString(2));
                bh.consume(rs.getInt(3));
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    public void read_jdbc(JdbcReadState s, Blackhole bh) throws SQLException {
        try (ResultSet rs = s.select.executeQuery()) {
            while (rs.next()) {
                bh.consume(rs.getInt(1));
                bh.consume(rs.getString(2));
                bh.consume(rs.getInt(3));
            }
        }
    }
}
