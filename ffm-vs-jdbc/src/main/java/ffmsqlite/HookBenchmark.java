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

import java.sql.DriverManager;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Cost of an update hook: inserting 1,000 rows in one transaction with no listener vs a listener that
 * counts rows (sqlite-jdbc {@code SQLiteConnection.addUpdateListener}, JNI callback; ffm-sqlite
 * {@code addUpdateListener}, FFM upcall). Score is ns per inserted row.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "-Xms1g", "-Xmx1g"})
@State(Scope.Thread)
public class HookBenchmark {
    static final int ROWS = 1_000;

    @Param({"jdbc", "ffm-sqlite"})
    String lib;

    @Param({"none", "updateListener"})
    String hook;

    long events;
    java.sql.Connection jdbc;
    java.sql.PreparedStatement jInsert, jDelete;
    io.github.revsnai.ffmsqlite.db.Connection ffm;
    io.github.revsnai.ffmsqlite.db.PreparedStatement fInsert, fDelete;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        String ddl = "CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)";
        if (lib.equals("jdbc")) {
            Properties props = new Properties();
            props.setProperty("jdbc.get_generated_keys", "false");
            jdbc = DriverManager.getConnection("jdbc:sqlite::memory:", props);
            jdbc.createStatement().executeUpdate(ddl);
            if (hook.equals("updateListener")) {
                ((org.sqlite.SQLiteConnection) jdbc).addUpdateListener((type, db, table, rowId) -> events++);
            }
            jdbc.setAutoCommit(false);
            jInsert = jdbc.prepareStatement("INSERT INTO t (v) VALUES (?)");
            jDelete = jdbc.prepareStatement("DELETE FROM t WHERE 1"); // WHERE 1: reported rows, no truncate shortcut
        } else {
            ffm = io.github.revsnai.ffmsqlite.db.SQLite.getConnection(":memory:");
            try (var st = ffm.createStatement()) {
                st.executeUpdate(ddl);
            }
            if (hook.equals("updateListener")) {
                ffm.addUpdateListener((type, db, table, rowId) -> events++);
            }
            ffm.setAutoCommit(false);
            fInsert = ffm.prepareStatement("INSERT INTO t (v) VALUES (?)");
            fDelete = ffm.prepareStatement("DELETE FROM t WHERE 1");
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

    /** Per invocation: 1,000 inserts plus deleting them again, so 2,000 hook events with a listener. */
    @Benchmark
    @OperationsPerInvocation(ROWS)
    public long insertRows() throws Exception {
        if (jdbc != null) {
            for (int i = 0; i < ROWS; i++) {
                jInsert.setString(1, "row");
                jInsert.executeUpdate();
            }
            jDelete.executeUpdate();
            jdbc.commit();
        } else {
            for (int i = 0; i < ROWS; i++) {
                fInsert.setString(1, "row");
                fInsert.executeUpdate();
            }
            fDelete.executeUpdate();
            ffm.commit();
        }
        return events;
    }
}
