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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Populating a fresh WAL + synchronous=FULL file database with 1,000,000 rows of the 20-column {@link WideRows}
 * table (secondary index included), committing every {@link #COMMIT_EVERY} rows. One prepared INSERT, one
 * executeUpdate per row, in every library. Score is seconds for the whole load.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(value = 2, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "-Xms1g", "-Xmx1g"})
@State(Scope.Thread)
public class BulkLoadBenchmark {
    static final int COMMIT_EVERY = 10_000;

    @Param({"jdbc", "ffm-sqlite", "ffm-sqlite-jdbc"})
    String lib;

    Path dir;
    String path;

    @Setup(Level.Iteration)
    public void freshDatabase() throws Exception {
        dir = Files.createTempDirectory(WideRows.dataDir(), "bulk-run");
        path = dir.resolve("wide.db").toString();
    }

    @TearDown(Level.Iteration)
    public void deleteDatabase() throws Exception {
        WideRows.deleteDir(dir);
    }

    @Benchmark
    public long load() throws Exception {
        return lib.equals("ffm-sqlite") ? loadFfm() : loadJdbc(lib.equals("jdbc") ? "jdbc:sqlite:" : "jdbc:ffmsqlite:");
    }

    long loadJdbc(String prefix) throws Exception {
        Properties p = new Properties();
        p.setProperty("jdbc.get_generated_keys", "false");
        try (java.sql.Connection c = DriverManager.getConnection(prefix + path, p)) {
            try (var st = c.createStatement()) {
                st.executeUpdate("PRAGMA journal_mode = WAL");
                st.executeUpdate("PRAGMA synchronous = FULL");
                for (String ddl : WideRows.DDL.split(";")) {
                    if (!ddl.isBlank()) {
                        st.executeUpdate(ddl);
                    }
                }
            }
            c.setAutoCommit(false);
            try (var ps = c.prepareStatement(WideRows.INSERT)) {
                for (long id = 1; id <= WideRows.ROWS; id++) {
                    WideRows.bind(ps, id);
                    ps.executeUpdate();
                    if (id % COMMIT_EVERY == 0) {
                        c.commit();
                    }
                }
            }
            c.commit();
        }
        return Files.size(Path.of(path));
    }

    long loadFfm() throws Exception {
        var config = io.github.psydic.ffmsqlite.db.SQLite.Config.DEFAULT
                .withJournalMode(io.github.psydic.ffmsqlite.db.SQLite.JournalMode.WAL)
                .withSynchronous(io.github.psydic.ffmsqlite.db.SQLite.Synchronous.FULL);
        try (var c = io.github.psydic.ffmsqlite.db.SQLite.getConnection(path, config)) {
            try (var st = c.createStatement()) {
                st.executeUpdate(WideRows.DDL);
            }
            c.setAutoCommit(false);
            try (var ps = c.prepareStatement(WideRows.INSERT)) {
                for (long id = 1; id <= WideRows.ROWS; id++) {
                    WideRows.bind(ps, id);
                    ps.executeUpdate();
                    if (id % COMMIT_EVERY == 0) {
                        c.commit();
                    }
                }
            }
            c.commit();
        }
        return Files.size(Path.of(path));
    }
}
