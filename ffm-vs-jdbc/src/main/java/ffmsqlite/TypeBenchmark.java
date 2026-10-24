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

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Per-datatype cost of binding (write) and reading 1,000 single-column rows, sqlite-jdbc vs ffm-sqlite,
 * each through its idiomatic API for that type. Scores are ns per row.
 *
 * <p>sqlite-jdbc runs with {@code jdbc.get_generated_keys=false} (its fastest executeUpdate path).
 * For INSTANT sqlite-jdbc uses setTimestamp/getTimestamp, since getObject(i, Instant.class) is not
 * implemented; ffm-sqlite uses setObject/getObject(i, Instant.class). Likewise for UUID sqlite-jdbc uses
 * setString(uuid.toString()) / UUID.fromString(getString), ffm-sqlite setObject/getObject(i, UUID.class).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "-Xms1g", "-Xmx1g"})
@State(Scope.Thread)
public class TypeBenchmark {
    static final int ROWS = 1_000;

    @Param({"INTEGER", "REAL", "TEXT", "BLOB", "BOOLEAN", "NULL", "BIGDECIMAL", "LOCALDATE", "LOCALDATETIME", "INSTANT", "UUID"})
    String type;

    @Param({"jdbc", "ffm-sqlite", "ffm-sqlite-jdbc"})
    String lib;

    /** One library x one type. Each fork sees a single implementation, so calls stay monomorphic. */
    interface Db {
        void writeBatch() throws Exception;

        void readAll(Blackhole bh) throws Exception;

        void close() throws Exception;
    }

    Db db;

    // Distinct values per row, prepared up front so value construction is not measured.
    static final int[] INTS = new int[ROWS];
    static final double[] DOUBLES = new double[ROWS];
    static final String[] STRINGS = new String[ROWS];
    static final byte[][] BLOBS = new byte[ROWS][];
    static final BigDecimal[] DECIMALS = new BigDecimal[ROWS];
    static final LocalDate[] DATES = new LocalDate[ROWS];
    static final LocalDateTime[] DATETIMES = new LocalDateTime[ROWS];
    static final Instant[] INSTANTS = new Instant[ROWS];
    static final Timestamp[] TIMESTAMPS = new Timestamp[ROWS];
    static final java.util.UUID[] UUIDS = new java.util.UUID[ROWS];

    static {
        LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
        for (int i = 0; i < ROWS; i++) {
            INTS[i] = i * 7919;
            DOUBLES[i] = i * 1.25 + 0.001;
            STRINGS[i] = "value-" + i + "-" + Integer.toHexString(i * 31);
            BLOBS[i] = new byte[16];
            BLOBS[i][0] = (byte) i;
            DECIMALS[i] = new BigDecimal(i + ".1234567890123");
            DATES[i] = base.toLocalDate().plusDays(i);
            DATETIMES[i] = base.plusSeconds(i * 3_607L).plusNanos(i * 1_000_000L);
            INSTANTS[i] = DATETIMES[i].toInstant(java.time.ZoneOffset.UTC);
            TIMESTAMPS[i] = Timestamp.from(INSTANTS[i]);
            UUIDS[i] = new java.util.UUID(0x123e4567e89b12d3L + i, 0xa456426614174000L ^ (i * 0x9E3779B97F4A7C15L));
        }
    }

    static String declType(String type) {
        return switch (type) {
            case "INTEGER" -> "INTEGER";
            case "REAL" -> "REAL";
            case "TEXT", "NULL", "BIGDECIMAL", "UUID" -> "TEXT";
            case "BLOB" -> "BLOB";
            case "BOOLEAN" -> "BOOLEAN";
            case "LOCALDATE" -> "DATE";
            case "LOCALDATETIME" -> "DATETIME";
            default -> "TIMESTAMP";
        };
    }

    @Setup(Level.Trial)
    public void setup() throws Exception {
        db = switch (lib) {
            case "jdbc" -> new JdbcDb(type, "jdbc:sqlite:");
            case "ffm-sqlite-jdbc" -> new JdbcDb(type, "jdbc:ffmsqlite:");
            default -> new LibDb(type);
        };
        db.writeBatch(); // the read benchmark needs rows
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        db.close();
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    public void write() throws Exception {
        db.writeBatch();
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    public void read(Blackhole bh) throws Exception {
        db.readAll(bh);
    }

    // ------------------------------------------------------------------ sqlite-jdbc

    interface JdbcBinder {
        void bind(java.sql.PreparedStatement ps, int i) throws Exception;
    }

    interface JdbcReader {
        void read(java.sql.ResultSet rs, Blackhole bh) throws Exception;
    }

    static final class JdbcDb implements Db {
        final java.sql.Connection conn;
        final java.sql.PreparedStatement insert, delete, select;
        final JdbcBinder binder;
        final JdbcReader reader;

        JdbcDb(String type, String prefix) throws Exception {
            Properties props = new Properties();
            props.setProperty("jdbc.get_generated_keys", "false");
            conn = DriverManager.getConnection(prefix + ":memory:", props);
            try (var st = conn.createStatement()) {
                st.executeUpdate("CREATE TABLE t (v " + declType(type) + ")");
            }
            conn.setAutoCommit(false);
            insert = conn.prepareStatement("INSERT INTO t (v) VALUES (?)");
            delete = conn.prepareStatement("DELETE FROM t");
            select = conn.prepareStatement("SELECT v FROM t");
            switch (type) {
                case "INTEGER" -> { binder = (ps, i) -> ps.setInt(1, INTS[i]); reader = (rs, bh) -> bh.consume(rs.getInt(1)); }
                case "REAL" -> { binder = (ps, i) -> ps.setDouble(1, DOUBLES[i]); reader = (rs, bh) -> bh.consume(rs.getDouble(1)); }
                case "TEXT" -> { binder = (ps, i) -> ps.setString(1, STRINGS[i]); reader = (rs, bh) -> bh.consume(rs.getString(1)); }
                case "BLOB" -> { binder = (ps, i) -> ps.setBytes(1, BLOBS[i]); reader = (rs, bh) -> bh.consume(rs.getBytes(1)); }
                case "BOOLEAN" -> { binder = (ps, i) -> ps.setBoolean(1, (i & 1) == 0); reader = (rs, bh) -> bh.consume(rs.getBoolean(1)); }
                case "NULL" -> { binder = (ps, i) -> ps.setNull(1, java.sql.Types.VARCHAR); reader = (rs, bh) -> { bh.consume(rs.getString(1)); bh.consume(rs.wasNull()); }; }
                case "BIGDECIMAL" -> { binder = (ps, i) -> ps.setBigDecimal(1, DECIMALS[i]); reader = (rs, bh) -> bh.consume(rs.getBigDecimal(1)); }
                case "LOCALDATE" -> { binder = (ps, i) -> ps.setObject(1, DATES[i]); reader = (rs, bh) -> bh.consume(rs.getObject(1, LocalDate.class)); }
                case "LOCALDATETIME" -> { binder = (ps, i) -> ps.setObject(1, DATETIMES[i]); reader = (rs, bh) -> bh.consume(rs.getObject(1, LocalDateTime.class)); }
                case "INSTANT" -> { binder = (ps, i) -> ps.setTimestamp(1, TIMESTAMPS[i]); reader = (rs, bh) -> bh.consume(rs.getTimestamp(1).toInstant()); }
                case "UUID" -> { binder = (ps, i) -> ps.setString(1, UUIDS[i].toString()); reader = (rs, bh) -> bh.consume(java.util.UUID.fromString(rs.getString(1))); }
                default -> throw new IllegalArgumentException(type);
            }
        }

        @Override
        public void writeBatch() throws Exception {
            delete.executeUpdate();
            for (int i = 0; i < ROWS; i++) {
                binder.bind(insert, i);
                insert.executeUpdate();
            }
            conn.commit();
        }

        @Override
        public void readAll(Blackhole bh) throws Exception {
            try (var rs = select.executeQuery()) {
                while (rs.next()) {
                    reader.read(rs, bh);
                }
            }
        }

        @Override
        public void close() throws Exception {
            conn.close();
        }
    }

    // ------------------------------------------------------------------ ffm-sqlite

    interface LibBinder {
        void bind(io.github.revsnai.ffmsqlite.db.PreparedStatement ps, int i) throws Exception;
    }

    interface LibReader {
        void read(io.github.revsnai.ffmsqlite.db.ResultSet rs, Blackhole bh) throws Exception;
    }

    static final class LibDb implements Db {
        final io.github.revsnai.ffmsqlite.db.Connection conn;
        final io.github.revsnai.ffmsqlite.db.PreparedStatement insert, delete, select;
        final LibBinder binder;
        final LibReader reader;

        LibDb(String type) throws Exception {
            conn = io.github.revsnai.ffmsqlite.db.SQLite.getConnection(":memory:");
            try (var st = conn.createStatement()) {
                st.executeUpdate("CREATE TABLE t (v " + declType(type) + ")");
            }
            conn.setAutoCommit(false);
            insert = conn.prepareStatement("INSERT INTO t (v) VALUES (?)");
            delete = conn.prepareStatement("DELETE FROM t");
            select = conn.prepareStatement("SELECT v FROM t");
            switch (type) {
                case "INTEGER" -> { binder = (ps, i) -> ps.setInt(1, INTS[i]); reader = (rs, bh) -> bh.consume(rs.getInt(1)); }
                case "REAL" -> { binder = (ps, i) -> ps.setDouble(1, DOUBLES[i]); reader = (rs, bh) -> bh.consume(rs.getDouble(1)); }
                case "TEXT" -> { binder = (ps, i) -> ps.setString(1, STRINGS[i]); reader = (rs, bh) -> bh.consume(rs.getString(1)); }
                case "BLOB" -> { binder = (ps, i) -> ps.setBytes(1, BLOBS[i]); reader = (rs, bh) -> bh.consume(rs.getBytes(1)); }
                case "BOOLEAN" -> { binder = (ps, i) -> ps.setBoolean(1, (i & 1) == 0); reader = (rs, bh) -> bh.consume(rs.getBoolean(1)); }
                case "NULL" -> { binder = (ps, i) -> ps.setNull(1); reader = (rs, bh) -> { bh.consume(rs.getString(1)); bh.consume(rs.wasNull()); }; }
                case "BIGDECIMAL" -> { binder = (ps, i) -> ps.setBigDecimal(1, DECIMALS[i]); reader = (rs, bh) -> bh.consume(rs.getBigDecimal(1)); }
                case "LOCALDATE" -> { binder = (ps, i) -> ps.setObject(1, DATES[i]); reader = (rs, bh) -> bh.consume(rs.getObject(1, LocalDate.class)); }
                case "LOCALDATETIME" -> { binder = (ps, i) -> ps.setObject(1, DATETIMES[i]); reader = (rs, bh) -> bh.consume(rs.getObject(1, LocalDateTime.class)); }
                case "INSTANT" -> { binder = (ps, i) -> ps.setObject(1, INSTANTS[i]); reader = (rs, bh) -> bh.consume(rs.getObject(1, Instant.class)); }
                case "UUID" -> { binder = (ps, i) -> ps.setObject(1, UUIDS[i]); reader = (rs, bh) -> bh.consume(rs.getObject(1, java.util.UUID.class)); }
                default -> throw new IllegalArgumentException(type);
            }
        }

        @Override
        public void writeBatch() throws Exception {
            delete.executeUpdate();
            for (int i = 0; i < ROWS; i++) {
                binder.bind(insert, i);
                insert.executeUpdate();
            }
            conn.commit();
        }

        @Override
        public void readAll(Blackhole bh) throws Exception {
            try (var rs = select.executeQuery()) {
                while (rs.next()) {
                    reader.read(rs, bh);
                }
            }
        }

        @Override
        public void close() {
            conn.close();
        }
    }
}
