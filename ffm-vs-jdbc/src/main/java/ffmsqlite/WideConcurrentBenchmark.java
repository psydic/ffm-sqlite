package ffmsqlite;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
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

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Multi-threaded WAL + synchronous=FULL on the 1,000,000-row, 20-column {@link WideRows} table: one writer thread
 * with its own connection inserting full rows in autocommit (an fsync per commit), while reader threads, each with
 * its own connection, read concurrently. Readers: 4 by default, set with {@code -tg N,1} (JMH orders group members by method name: read, then write).
 * <ul>
 *   <li>{@code point}: readers fetch one random row by primary key, all 20 columns through typed getters.</li>
 *   <li>{@code range}: readers scan 100 consecutive rows (5 columns, including the payload BLOB).</li>
 * </ul>
 * Scores are operations per second, reported separately for the writer and for all readers together.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "-Xms1g", "-Xmx1g"})
public class WideConcurrentBenchmark {
    static final int RANGE = 100;

    @State(Scope.Benchmark)
    public static class Db {
        @Param({"jdbc", "ffm-sqlite", "ffm-sqlite-jdbc"})
        String lib;
        Path file;

        @Setup(Level.Trial)
        public void copy() throws Exception {
            file = WideRows.copyOfTemplate();
        }

        @TearDown(Level.Trial)
        public void delete() throws Exception {
            WideRows.deleteDir(file.getParent());
        }

        Conn open() throws Exception {
            return lib.equals("ffm-sqlite") ? new Ffm(file.toString())
                    : new Jdbc((lib.equals("jdbc") ? "jdbc:sqlite:" : "jdbc:ffmsqlite:") + file);
        }
    }

    /** One connection with the three statements; each thread opens its own. */
    interface Conn extends AutoCloseable {
        void insert(long id) throws Exception;

        void point(long id, Blackhole bh) throws Exception;

        void range(long from, Blackhole bh) throws Exception;
    }

    static final class Jdbc implements Conn {
        final java.sql.Connection c;
        final java.sql.PreparedStatement ins, row, range;

        Jdbc(String url) throws Exception {
            Properties p = new Properties();
            p.setProperty("jdbc.get_generated_keys", "false");
            c = DriverManager.getConnection(url, p);
            try (var st = c.createStatement()) {
                st.executeUpdate("PRAGMA synchronous = FULL");
            }
            ins = c.prepareStatement(WideRows.INSERT);
            row = c.prepareStatement(WideRows.SELECT_ROW);
            range = c.prepareStatement(WideRows.SELECT_RANGE);
        }

        @Override
        public void insert(long id) throws Exception {
            WideRows.bind(ins, id);
            ins.executeUpdate();
        }

        @Override
        public void point(long id, Blackhole bh) throws Exception {
            row.setLong(1, id);
            try (var rs = row.executeQuery()) {
                while (rs.next()) {
                    WideRows.read(rs, bh);
                }
            }
        }

        @Override
        public void range(long from, Blackhole bh) throws Exception {
            range.setLong(1, from);
            range.setLong(2, from + RANGE - 1);
            try (var rs = range.executeQuery()) {
                while (rs.next()) {
                    bh.consume(rs.getLong(1));
                    bh.consume(rs.getString(2));
                    bh.consume(rs.getDouble(3));
                    bh.consume(rs.getString(4));
                    bh.consume(rs.getBytes(5));
                }
            }
        }

        @Override
        public void close() throws Exception {
            c.close();
        }
    }

    static final class Ffm implements Conn {
        final io.github.psydic.ffmsqlite.db.Connection c;
        final io.github.psydic.ffmsqlite.db.PreparedStatement ins, row, range;

        Ffm(String path) throws Exception {
            c = io.github.psydic.ffmsqlite.db.SQLite.getConnection(path, io.github.psydic.ffmsqlite.db.SQLite.Config.DEFAULT
                    .withSynchronous(io.github.psydic.ffmsqlite.db.SQLite.Synchronous.FULL));
            ins = c.prepareStatement(WideRows.INSERT);
            row = c.prepareStatement(WideRows.SELECT_ROW);
            range = c.prepareStatement(WideRows.SELECT_RANGE);
        }

        @Override
        public void insert(long id) throws Exception {
            WideRows.bind(ins, id);
            ins.executeUpdate();
        }

        @Override
        public void point(long id, Blackhole bh) throws Exception {
            row.setLong(1, id);
            try (var rs = row.executeQuery()) {
                while (rs.next()) {
                    WideRows.read(rs, bh);
                }
            }
        }

        @Override
        public void range(long from, Blackhole bh) throws Exception {
            range.setLong(1, from);
            range.setLong(2, from + RANGE - 1);
            try (var rs = range.executeQuery()) {
                while (rs.next()) {
                    bh.consume(rs.getLong(1));
                    bh.consume(rs.getString(2));
                    bh.consume(rs.getDouble(3));
                    bh.consume(rs.getString(4));
                    bh.consume(rs.getBytes(5));
                }
            }
        }

        @Override
        public void close() {
            c.close();
        }
    }

    @State(Scope.Thread)
    public static class Writer {
        Conn conn;
        long next = WideRows.ROWS + 1;

        @Setup(Level.Trial)
        public void open(Db db) throws Exception {
            conn = db.open();
        }

        @TearDown(Level.Trial)
        public void close() throws Exception {
            conn.close();
        }
    }

    @State(Scope.Thread)
    public static class Reader {
        Conn conn;
        final SplittableRandom rnd = new SplittableRandom(Thread.currentThread().threadId());

        @Setup(Level.Trial)
        public void open(Db db) throws Exception {
            conn = db.open();
        }

        @TearDown(Level.Trial)
        public void close() throws Exception {
            conn.close();
        }

        long id() {
            return 1 + rnd.nextInt(WideRows.ROWS - RANGE);
        }
    }

    @Benchmark
    @Group("point")
    @GroupThreads(1)
    public void pointWrite(Writer w) throws Exception {
        w.conn.insert(w.next++);
    }

    @Benchmark
    @Group("point")
    @GroupThreads(4)
    public void pointRead(Reader r, Blackhole bh) throws Exception {
        r.conn.point(r.id(), bh);
    }

    @Benchmark
    @Group("range")
    @GroupThreads(1)
    public void rangeWrite(Writer w) throws Exception {
        w.conn.insert(w.next++);
    }

    @Benchmark
    @Group("range")
    @GroupThreads(4)
    public void rangeRead(Reader r, Blackhole bh) throws Exception {
        r.conn.range(r.id(), bh);
    }
}
