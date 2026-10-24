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

import java.sql.DriverManager;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Large values: write (INSERT OR REPLACE) and read one BLOB of 1 KB, 64 KB or 1 MB, in memory. µs per operation. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "-Xms1g", "-Xmx1g"})
@State(Scope.Thread)
public class BlobBenchmark {
    @Param({"jdbc", "ffm-sqlite"})
    String lib;

    @Param({"1024", "65536", "1048576"})
    int size;

    byte[] data;
    java.sql.Connection jdbc;
    java.sql.PreparedStatement jWrite, jRead;
    io.github.psydic.ffmsqlite.db.Connection ffm;
    io.github.psydic.ffmsqlite.db.PreparedStatement fWrite, fRead;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        data = new byte[size];
        new java.util.Random(1).nextBytes(data);
        String ddl = "CREATE TABLE b (id INTEGER PRIMARY KEY, data BLOB)";
        String write = "INSERT OR REPLACE INTO b (id, data) VALUES (1, ?)";
        String read = "SELECT data FROM b WHERE id = 1";
        if (lib.equals("jdbc")) {
            Properties p = new Properties();
            p.setProperty("jdbc.get_generated_keys", "false");
            jdbc = DriverManager.getConnection("jdbc:sqlite::memory:", p);
            jdbc.createStatement().executeUpdate(ddl);
            jWrite = jdbc.prepareStatement(write);
            jRead = jdbc.prepareStatement(read);
        } else {
            ffm = io.github.psydic.ffmsqlite.db.SQLite.getConnection(":memory:");
            try (var st = ffm.createStatement()) {
                st.executeUpdate(ddl);
            }
            fWrite = ffm.prepareStatement(write);
            fRead = ffm.prepareStatement(read);
        }
        write();
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

    @Benchmark
    public int write() throws Exception {
        if (jdbc != null) {
            jWrite.setBytes(1, data);
            return jWrite.executeUpdate();
        }
        fWrite.setBytes(1, data);
        return fWrite.executeUpdate();
    }

    @Benchmark
    public byte[] read() throws Exception {
        if (jdbc != null) {
            try (var rs = jRead.executeQuery()) {
                rs.next();
                return rs.getBytes(1);
            }
        }
        try (var rs = fRead.executeQuery()) {
            rs.next();
            return rs.getBytes(1);
        }
    }
}
