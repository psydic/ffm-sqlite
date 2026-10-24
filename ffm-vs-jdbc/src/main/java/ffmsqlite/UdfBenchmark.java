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

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * Cost of a Java user-defined function call from SQL: sqlite-jdbc's {@code org.sqlite.Function} (JNI
 * callback) vs ffm-sqlite's {@code createFunction} (FFM upcall). Each query calls the function once per
 * row over 10,000 rows, so scores are ns per call. {@code builtin} runs the same query with SQLite's own
 * C function as the baseline.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "-Xms1g", "-Xmx1g"})
@State(Scope.Thread)
public class UdfBenchmark {
    static final int ROWS = 10_000;
    static final String TABLE = "CREATE TABLE t AS WITH RECURSIVE s(v) AS (SELECT 1 UNION ALL SELECT v + 1 FROM s WHERE v < "
            + ROWS + ") SELECT v AS n, 'name-' || v AS s FROM s";

    @Param({"jdbc", "ffm-sqlite"})
    String lib;

    java.sql.Connection jdbc;
    io.github.revsnai.ffmsqlite.db.Connection ffm;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        if (lib.equals("jdbc")) {
            jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
            try (var st = jdbc.createStatement()) {
                st.executeUpdate(TABLE);
            }
            org.sqlite.Function.create(jdbc, "plus_one", new org.sqlite.Function() {
                @Override
                protected void xFunc() throws SQLException {
                    result(value_long(0) + 1);
                }
            });
            org.sqlite.Function.create(jdbc, "jupper", new org.sqlite.Function() {
                @Override
                protected void xFunc() throws SQLException {
                    result(value_text(0).toUpperCase());
                }
            });
            org.sqlite.Function.create(jdbc, "jsum", new org.sqlite.Function.Aggregate() {
                long sum;

                @Override
                protected void xStep() throws SQLException {
                    sum += value_long(0);
                }

                @Override
                protected void xFinal() throws SQLException {
                    result(sum);
                    sum = 0;
                }
            });
            org.sqlite.Function.create(jdbc, "wsum", new org.sqlite.Function.Window() {
                long sum;

                @Override
                protected void xStep() throws SQLException {
                    sum += value_long(0);
                }

                @Override
                protected void xInverse() throws SQLException {
                    sum -= value_long(0);
                }

                @Override
                protected void xValue() throws SQLException {
                    result(sum);
                }

                @Override
                protected void xFinal() throws SQLException {
                    result(sum);
                    sum = 0;
                }
            });
            org.sqlite.Collation.create(jdbc, "jci", new org.sqlite.Collation() {
                @Override
                protected int xCompare(String a, String b) {
                    return String.CASE_INSENSITIVE_ORDER.compare(a, b);
                }
            });
        } else {
            ffm = io.github.revsnai.ffmsqlite.db.SQLite.getConnection(":memory:");
            ffm.createCollation("jci", String.CASE_INSENSITIVE_ORDER);
            try (var st = ffm.createStatement()) {
                st.executeUpdate(TABLE);
            }
            ffm.createFunction("plus_one", 1, args -> args.getLong(0) + 1);
            ffm.createFunction("jupper", 1, args -> args.getString(0).toUpperCase());
            ffm.createAggregateFunction("jsum", 1, () -> new io.github.revsnai.ffmsqlite.db.AggregateFunction() {
                long sum;

                @Override
                public void step(io.github.revsnai.ffmsqlite.db.FunctionArgs args) throws io.github.revsnai.ffmsqlite.db.SQLiteException {
                    sum += args.getLong(0);
                }

                @Override
                public Object result() {
                    return sum;
                }
            });
            ffm.createWindowFunction("wsum", 1, () -> new io.github.revsnai.ffmsqlite.db.WindowFunction() {
                long sum;

                @Override
                public void step(io.github.revsnai.ffmsqlite.db.FunctionArgs args) throws io.github.revsnai.ffmsqlite.db.SQLiteException {
                    sum += args.getLong(0);
                }

                @Override
                public void inverse(io.github.revsnai.ffmsqlite.db.FunctionArgs args) throws io.github.revsnai.ffmsqlite.db.SQLiteException {
                    sum -= args.getLong(0);
                }

                @Override
                public Object result() {
                    return sum;
                }
            });
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        if (jdbc != null) {
            jdbc.close();
        }
        if (ffm != null) {
            ffm.close();
        }
    }

    long one(String sql) throws Exception {
        if (jdbc != null) {
            try (var st = jdbc.createStatement(); var rs = st.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        }
        try (var st = ffm.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Baseline: SQLite's C arithmetic, no Java call. */
    @Benchmark
    @OperationsPerInvocation(ROWS)
    public long builtin() throws Exception {
        return one("SELECT sum(n + 1) FROM t");
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    public long scalarLong() throws Exception {
        return one("SELECT sum(plus_one(n)) FROM t");
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    public long scalarText() throws Exception {
        return one("SELECT sum(length(jupper(s))) FROM t");
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    public long aggregate() throws Exception {
        return one("SELECT jsum(n) FROM t");
    }

    /** Sliding 10-row frame: per row one xStep, one xInverse and one xValue. */
    @Benchmark
    @OperationsPerInvocation(ROWS)
    public long windowSliding() throws Exception {
        return one("SELECT sum(w) FROM (SELECT wsum(n) OVER (ORDER BY n ROWS BETWEEN 9 PRECEDING AND CURRENT ROW) AS w FROM t)");
    }

    /** Baseline for windowSliding: the built-in C sum() over the same frame. */
    @Benchmark
    @OperationsPerInvocation(ROWS)
    public long windowSlidingBuiltin() throws Exception {
        return one("SELECT sum(w) FROM (SELECT sum(n) OVER (ORDER BY n ROWS BETWEEN 9 PRECEDING AND CURRENT ROW) AS w FROM t)");
    }

    /** One comparison per row: max() over a custom collation (case-insensitive, in Java). */
    @Benchmark
    @OperationsPerInvocation(ROWS)
    public long collationMax(Blackhole bh) throws Exception {
        return one("SELECT length(max(s COLLATE jci)) FROM t");
    }

    /** Baseline for collationMax: SQLite's built-in C NOCASE collation. */
    @Benchmark
    @OperationsPerInvocation(ROWS)
    public long collationMaxBuiltin() throws Exception {
        return one("SELECT length(max(s COLLATE NOCASE)) FROM t");
    }

    /** ORDER BY with the custom collation (~n log n comparisons), reading every row. Score is per row. */
    @Benchmark
    @OperationsPerInvocation(ROWS)
    public void collationOrderBy(Blackhole bh) throws Exception {
        String sql = "SELECT s FROM t ORDER BY s COLLATE jci";
        if (jdbc != null) {
            try (var st = jdbc.createStatement(); var rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    bh.consume(rs.getString(1));
                }
            }
            return;
        }
        try (var st = ffm.createStatement(); var rs = st.executeQuery(sql)) {
            while (rs.next()) {
                bh.consume(rs.getString(1));
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    public void scalarPerRowResult(Blackhole bh) throws Exception {
        String sql = "SELECT plus_one(n) FROM t";
        if (jdbc != null) {
            try (var st = jdbc.createStatement(); var rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    bh.consume(rs.getLong(1));
                }
            }
            return;
        }
        try (var st = ffm.createStatement(); var rs = st.executeQuery(sql)) {
            while (rs.next()) {
                bh.consume(rs.getLong(1));
            }
        }
    }
}
