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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Read scalability: N threads, each with its own connection to one WAL-mode file database (100,000 users),
 * doing primary-key point selects. Score is total throughput across threads (operations per ms).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "-Xms1g", "-Xmx1g"})
public class ConcurrentReadBenchmark {
    static final String SELECT_USER = "SELECT id, email, name, age FROM users WHERE id = ?";

    @State(Scope.Benchmark)
    public static class SharedDb {
        @Param({"jdbc", "ffm-sqlite", "ffm-sqlite-jdbc"})
        String lib;
        Path dir;
        String path;

        @Setup(Level.Trial)
        public void create() throws Exception {
            dir = Files.createTempDirectory("sqlite-concurrent");
            path = dir.resolve("app.db").toString();
            try (io.github.revsnai.ffmsqlite.db.Connection c = io.github.revsnai.ffmsqlite.db.SQLite.getConnection(path);
                 io.github.revsnai.ffmsqlite.db.Statement st = c.createStatement()) {
                st.executeUpdate("""
                        PRAGMA journal_mode = WAL;
                        CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT, name TEXT, age INTEGER);
                        WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM s WHERE i < 100000)
                        INSERT INTO users SELECT i, 'user' || i || '@example.com', 'User ' || i, 20 + i % 50 FROM s;
                        """);
            }
        }

        @TearDown(Level.Trial)
        public void delete() throws Exception {
            try (var files = Files.list(dir)) {
                for (Path p : files.toList()) {
                    Files.deleteIfExists(p);
                }
            }
            Files.delete(dir);
        }
    }

    @State(Scope.Thread)
    public static class Reader {
        java.sql.Connection jdbc;
        java.sql.PreparedStatement jSel;
        io.github.revsnai.ffmsqlite.db.Connection ffm;
        io.github.revsnai.ffmsqlite.db.PreparedStatement fSel;
        final SplittableRandom rnd = new SplittableRandom(Thread.currentThread().threadId());

        @Setup(Level.Trial)
        public void open(SharedDb db) throws Exception {
            if (!db.lib.equals("ffm-sqlite")) {
                jdbc = DriverManager.getConnection((db.lib.equals("jdbc") ? "jdbc:sqlite:" : "jdbc:ffmsqlite:") + db.path);
                jSel = jdbc.prepareStatement(SELECT_USER);
            } else {
                ffm = io.github.revsnai.ffmsqlite.db.SQLite.getConnection(db.path);
                fSel = ffm.prepareStatement(SELECT_USER);
            }
        }

        @TearDown(Level.Trial)
        public void close() throws Exception {
            if (jdbc != null) {
                jdbc.close();
            }
            if (ffm != null) {
                ffm.close();
            }
        }

        void select(Blackhole bh) throws Exception {
            int id = 1 + rnd.nextInt(100_000);
            if (jdbc != null) {
                jSel.setInt(1, id);
                try (var rs = jSel.executeQuery()) {
                    while (rs.next()) {
                        bh.consume(rs.getInt(1));
                        bh.consume(rs.getString(2));
                        bh.consume(rs.getString(3));
                        bh.consume(rs.getInt(4));
                    }
                }
                return;
            }
            fSel.setInt(1, id);
            try (var rs = fSel.executeQuery()) {
                while (rs.next()) {
                    bh.consume(rs.getInt(1));
                    bh.consume(rs.getString(2));
                    bh.consume(rs.getString(3));
                    bh.consume(rs.getInt(4));
                }
            }
        }
    }

    @Benchmark
    @Threads(1)
    public void pointSelect_1thread(Reader r, Blackhole bh) throws Exception {
        r.select(bh);
    }

    @Benchmark
    @Threads(4)
    public void pointSelect_4threads(Reader r, Blackhole bh) throws Exception {
        r.select(bh);
    }

    @Benchmark
    @Threads(8)
    public void pointSelect_8threads(Reader r, Blackhole bh) throws Exception {
        r.select(bh);
    }
}
