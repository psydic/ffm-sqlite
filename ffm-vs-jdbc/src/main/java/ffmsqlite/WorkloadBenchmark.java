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
import java.util.Properties;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Application-style workloads on a file database in WAL mode with synchronous=NORMAL (the usual app setup):
 * 100,000 users, 300,000 orders. Each library uses its idiomatic API with statements prepared once, except
 * where a benchmark says otherwise. Scores are µs per operation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "-Xms1g", "-Xmx1g"})
@State(Scope.Thread)
public class WorkloadBenchmark {
    static final int USERS = 100_000;
    static final String SELECT_USER = "SELECT id, email, name, age FROM users WHERE id = ?";
    static final String SELECT_BY_EMAIL = "SELECT id, name FROM users WHERE email = ?";
    static final String INSERT_EVENT = "INSERT INTO events (user_id, kind, payload) VALUES (?, ?, ?)";
    static final String UPDATE_AGE = "UPDATE users SET age = age + 1 WHERE id = ?";
    static final String INSERT_ORDER = "INSERT INTO orders (user_id, amount, status) VALUES (?, ?, 'new')";
    static final String RANGE = "SELECT id, user_id, amount, status FROM orders WHERE user_id BETWEEN ? AND ?";
    static final String JOIN = "SELECT u.name, count(o.id), sum(o.amount) FROM users u JOIN orders o ON o.user_id = u.id "
            + "WHERE u.id BETWEEN ? AND ? GROUP BY u.id";

    /** ffm-sqlite-jdbc runs the very same JDBC code as jdbc, through the jdbc:ffmsqlite: driver. */
    @Param({"jdbc", "ffm-sqlite", "ffm-sqlite-jdbc"})
    String lib;

    /** PRAGMA synchronous for every connection: NORMAL (the usual WAL setting) or FULL (fsync on every commit). */
    @Param({"NORMAL"})
    String synchronous;

    Path dir;
    String path;
    Workload w;
    final SplittableRandom rnd = new SplittableRandom(42);

    /** One library's implementation; each fork uses one, so calls stay monomorphic. */
    interface Workload extends AutoCloseable {
        void pointSelect(int id, Blackhole bh) throws Exception;

        void selectByEmail(String email, Blackhole bh) throws Exception;

        void insertEvent(int userId) throws Exception;

        void updateAge(int id) throws Exception;

        /** Manual-commit transaction: read a user, insert an order, update the user, commit. */
        void businessTransaction(int id, Blackhole bh) throws Exception;

        /** ~100 rows: orders of 33 consecutive users. */
        void rangeScan(int from, Blackhole bh) throws Exception;

        /** Join + GROUP BY over 50 users (~150 orders), 50 rows out. */
        void joinAggregate(int from, Blackhole bh) throws Exception;

        /** prepareStatement inside the operation, as much code does: re-parses unless the library caches. */
        void prepareEachTime(int id, Blackhole bh) throws Exception;

        /** No pool: open the file, point select, close. */
        void connectQueryClose(int id, Blackhole bh) throws Exception;
    }

    @Setup(Level.Trial)
    public void setup() throws Exception {
        dir = Files.createTempDirectory("sqlite-workload");
        path = dir.resolve("app.db").toString();
        try (io.github.revsnai.ffmsqlite.db.Connection c = io.github.revsnai.ffmsqlite.db.SQLite.getConnection(path);
             io.github.revsnai.ffmsqlite.db.Statement st = c.createStatement()) {
            st.executeUpdate("""
                    PRAGMA journal_mode = WAL;
                    CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
                                        age INTEGER NOT NULL, created TEXT NOT NULL);
                    CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users (id),
                                         amount INTEGER NOT NULL, status TEXT NOT NULL);
                    CREATE INDEX ix_orders_user ON orders (user_id);
                    CREATE TABLE events (id INTEGER PRIMARY KEY, user_id INTEGER, kind TEXT, payload TEXT);
                    BEGIN;
                    WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM s WHERE i < 100000)
                    INSERT INTO users SELECT i, 'user' || i || '@example.com', 'User ' || i, 20 + i % 50,
                                             datetime('2024-01-01', '+' || (i % 365) || ' days') FROM s;
                    WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM s WHERE i < 300000)
                    INSERT INTO orders (user_id, amount, status) SELECT 1 + i % 100000, i % 997, 'done' FROM s;
                    COMMIT;
                    """);
        }
        w = switch (lib) {
            case "jdbc" -> new Jdbc(path, "jdbc:sqlite:", synchronous);
            case "ffm-sqlite-jdbc" -> new Jdbc(path, "jdbc:ffmsqlite:", synchronous);
            default -> new Ffm(path, synchronous);
        };
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        w.close();
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) {
                Files.delete(p);
            }
        }
        Files.delete(dir);
    }

    int userId() {
        return 1 + rnd.nextInt(USERS);
    }

    @Benchmark
    public void pointSelectById(Blackhole bh) throws Exception {
        w.pointSelect(userId(), bh);
    }

    @Benchmark
    public void pointSelectByIndexedEmail(Blackhole bh) throws Exception {
        w.selectByEmail("user" + userId() + "@example.com", bh);
    }

    @Benchmark
    public void insertAutoCommit() throws Exception {
        w.insertEvent(userId());
    }

    @Benchmark
    public void updateAutoCommit() throws Exception {
        w.updateAge(userId());
    }

    @Benchmark
    public void businessTransaction(Blackhole bh) throws Exception {
        w.businessTransaction(userId(), bh);
    }

    @Benchmark
    public void rangeScan100Rows(Blackhole bh) throws Exception {
        w.rangeScan(1 + rnd.nextInt(USERS - 40), bh);
    }

    @Benchmark
    public void joinGroupBy50Users(Blackhole bh) throws Exception {
        w.joinAggregate(1 + rnd.nextInt(USERS - 60), bh);
    }

    @Benchmark
    public void prepareInsideLoop(Blackhole bh) throws Exception {
        w.prepareEachTime(userId(), bh);
    }

    @Benchmark
    public void connectQueryClose(Blackhole bh) throws Exception {
        w.connectQueryClose(userId(), bh);
    }

    // ------------------------------------------------------------------ sqlite-jdbc

    static final class Jdbc implements Workload {
        final String path, prefix, synchronous;
        final java.sql.Connection conn, tx;
        final java.sql.PreparedStatement selUser, selEmail, insEvent, updAge, range, join;
        final java.sql.PreparedStatement txSel, txIns, txUpd;

        static java.sql.Connection open(String prefix, String path, String synchronous) throws Exception {
            Properties p = new Properties();
            p.setProperty("jdbc.get_generated_keys", "false"); // sqlite-jdbc's fastest executeUpdate path (honoured by both)
            java.sql.Connection c = DriverManager.getConnection(prefix + path, p);
            try (var st = c.createStatement()) {
                st.executeUpdate("PRAGMA synchronous = " + synchronous);
            }
            return c;
        }

        Jdbc(String path, String prefix, String synchronous) throws Exception {
            this.path = path;
            this.prefix = prefix;
            this.synchronous = synchronous;
            conn = open(prefix, path, synchronous);
            selUser = conn.prepareStatement(SELECT_USER);
            selEmail = conn.prepareStatement(SELECT_BY_EMAIL);
            insEvent = conn.prepareStatement(INSERT_EVENT);
            updAge = conn.prepareStatement(UPDATE_AGE);
            range = conn.prepareStatement(RANGE);
            join = conn.prepareStatement(JOIN);
            tx = open(prefix, path, synchronous);
            tx.setAutoCommit(false);
            txSel = tx.prepareStatement(SELECT_USER);
            txIns = tx.prepareStatement(INSERT_ORDER);
            txUpd = tx.prepareStatement(UPDATE_AGE);
        }

        static void readUser(java.sql.PreparedStatement ps, int id, Blackhole bh) throws Exception {
            ps.setInt(1, id);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    bh.consume(rs.getInt(1));
                    bh.consume(rs.getString(2));
                    bh.consume(rs.getString(3));
                    bh.consume(rs.getInt(4));
                }
            }
        }

        @Override
        public void pointSelect(int id, Blackhole bh) throws Exception {
            readUser(selUser, id, bh);
        }

        @Override
        public void selectByEmail(String email, Blackhole bh) throws Exception {
            selEmail.setString(1, email);
            try (var rs = selEmail.executeQuery()) {
                while (rs.next()) {
                    bh.consume(rs.getInt(1));
                    bh.consume(rs.getString(2));
                }
            }
        }

        @Override
        public void insertEvent(int userId) throws Exception {
            insEvent.setInt(1, userId);
            insEvent.setString(2, "click");
            insEvent.setString(3, "{\"page\":\"/home\"}");
            insEvent.executeUpdate();
        }

        @Override
        public void updateAge(int id) throws Exception {
            updAge.setInt(1, id);
            updAge.executeUpdate();
        }

        @Override
        public void businessTransaction(int id, Blackhole bh) throws Exception {
            readUser(txSel, id, bh);
            txIns.setInt(1, id);
            txIns.setInt(2, 42);
            txIns.executeUpdate();
            txUpd.setInt(1, id);
            txUpd.executeUpdate();
            tx.commit();
        }

        @Override
        public void rangeScan(int from, Blackhole bh) throws Exception {
            range.setInt(1, from);
            range.setInt(2, from + 32);
            try (var rs = range.executeQuery()) {
                while (rs.next()) {
                    bh.consume(rs.getLong(1));
                    bh.consume(rs.getInt(2));
                    bh.consume(rs.getInt(3));
                    bh.consume(rs.getString(4));
                }
            }
        }

        @Override
        public void joinAggregate(int from, Blackhole bh) throws Exception {
            join.setInt(1, from);
            join.setInt(2, from + 49);
            try (var rs = join.executeQuery()) {
                while (rs.next()) {
                    bh.consume(rs.getString(1));
                    bh.consume(rs.getInt(2));
                    bh.consume(rs.getLong(3));
                }
            }
        }

        @Override
        public void prepareEachTime(int id, Blackhole bh) throws Exception {
            try (var ps = conn.prepareStatement(SELECT_USER)) {
                readUser(ps, id, bh);
            }
        }

        @Override
        public void connectQueryClose(int id, Blackhole bh) throws Exception {
            try (var c = open(prefix, path, synchronous); var ps = c.prepareStatement(SELECT_USER)) {
                readUser(ps, id, bh);
            }
        }

        @Override
        public void close() throws Exception {
            tx.rollback();
            tx.close();
            conn.close();
        }
    }

    // ------------------------------------------------------------------ ffm-sqlite

    static final class Ffm implements Workload {
        final String path;
        final io.github.revsnai.ffmsqlite.db.Connection conn, tx;
        final io.github.revsnai.ffmsqlite.db.PreparedStatement selUser, selEmail, insEvent, updAge, range, join;
        final io.github.revsnai.ffmsqlite.db.PreparedStatement txSel, txIns, txUpd;
        final io.github.revsnai.ffmsqlite.db.SQLite.Config CONFIG;

        Ffm(String path, String synchronous) throws Exception {
            this.path = path;
            CONFIG = io.github.revsnai.ffmsqlite.db.SQLite.Config.DEFAULT.withSynchronous(io.github.revsnai.ffmsqlite.db.SQLite.Synchronous.valueOf(synchronous));
            conn = io.github.revsnai.ffmsqlite.db.SQLite.getConnection(path, CONFIG);
            selUser = conn.prepareStatement(SELECT_USER);
            selEmail = conn.prepareStatement(SELECT_BY_EMAIL);
            insEvent = conn.prepareStatement(INSERT_EVENT);
            updAge = conn.prepareStatement(UPDATE_AGE);
            range = conn.prepareStatement(RANGE);
            join = conn.prepareStatement(JOIN);
            tx = io.github.revsnai.ffmsqlite.db.SQLite.getConnection(path, CONFIG);
            tx.setAutoCommit(false);
            txSel = tx.prepareStatement(SELECT_USER);
            txIns = tx.prepareStatement(INSERT_ORDER);
            txUpd = tx.prepareStatement(UPDATE_AGE);
        }

        static void readUser(io.github.revsnai.ffmsqlite.db.PreparedStatement ps, int id, Blackhole bh) throws Exception {
            ps.setInt(1, id);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    bh.consume(rs.getInt(1));
                    bh.consume(rs.getString(2));
                    bh.consume(rs.getString(3));
                    bh.consume(rs.getInt(4));
                }
            }
        }

        @Override
        public void pointSelect(int id, Blackhole bh) throws Exception {
            readUser(selUser, id, bh);
        }

        @Override
        public void selectByEmail(String email, Blackhole bh) throws Exception {
            selEmail.setString(1, email);
            try (var rs = selEmail.executeQuery()) {
                while (rs.next()) {
                    bh.consume(rs.getInt(1));
                    bh.consume(rs.getString(2));
                }
            }
        }

        @Override
        public void insertEvent(int userId) throws Exception {
            insEvent.setInt(1, userId);
            insEvent.setString(2, "click");
            insEvent.setString(3, "{\"page\":\"/home\"}");
            insEvent.executeUpdate();
        }

        @Override
        public void updateAge(int id) throws Exception {
            updAge.setInt(1, id);
            updAge.executeUpdate();
        }

        @Override
        public void businessTransaction(int id, Blackhole bh) throws Exception {
            readUser(txSel, id, bh);
            txIns.setInt(1, id);
            txIns.setInt(2, 42);
            txIns.executeUpdate();
            txUpd.setInt(1, id);
            txUpd.executeUpdate();
            tx.commit();
        }

        @Override
        public void rangeScan(int from, Blackhole bh) throws Exception {
            range.setInt(1, from);
            range.setInt(2, from + 32);
            try (var rs = range.executeQuery()) {
                while (rs.next()) {
                    bh.consume(rs.getLong(1));
                    bh.consume(rs.getInt(2));
                    bh.consume(rs.getInt(3));
                    bh.consume(rs.getString(4));
                }
            }
        }

        @Override
        public void joinAggregate(int from, Blackhole bh) throws Exception {
            join.setInt(1, from);
            join.setInt(2, from + 49);
            try (var rs = join.executeQuery()) {
                while (rs.next()) {
                    bh.consume(rs.getString(1));
                    bh.consume(rs.getInt(2));
                    bh.consume(rs.getLong(3));
                }
            }
        }

        @Override
        public void prepareEachTime(int id, Blackhole bh) throws Exception {
            try (var ps = conn.prepareStatement(SELECT_USER)) {
                readUser(ps, id, bh);
            }
        }

        @Override
        public void connectQueryClose(int id, Blackhole bh) throws Exception {
            try (var c = io.github.revsnai.ffmsqlite.db.SQLite.getConnection(path, CONFIG); var ps = c.prepareStatement(SELECT_USER)) {
                readUser(ps, id, bh);
            }
        }

        @Override
        public void close() throws Exception {
            tx.rollback();
            tx.close();
            conn.close();
        }
    }
}
