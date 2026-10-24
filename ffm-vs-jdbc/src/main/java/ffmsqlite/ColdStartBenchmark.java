package ffmsqlite;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.sql.DriverManager;
import java.util.concurrent.TimeUnit;

/**
 * Time to first result in a fresh JVM: load the library, open an in-memory database, create a table, insert
 * and select one row, close. Includes class loading and native setup (sqlite-jdbc extracts its bundled
 * native library to a temp file on every start; ffm-sqlite looks up libsqlite3 and builds its downcall
 * handles). One measurement per fork, many forks. Milliseconds.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(value = 20, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@State(Scope.Thread)
public class ColdStartBenchmark {
    @Param({"jdbc", "ffm-sqlite"})
    String lib;

    @Benchmark
    public String firstQuery() throws Exception {
        if (lib.equals("jdbc")) {
            try (var c = DriverManager.getConnection("jdbc:sqlite::memory:"); var st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE t (x TEXT)");
                st.executeUpdate("INSERT INTO t VALUES ('hello')");
                try (var rs = st.executeQuery("SELECT x FROM t")) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        }
        try (var c = io.github.psydic.ffmsqlite.db.SQLite.getConnection(":memory:"); var st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (x TEXT)");
            st.executeUpdate("INSERT INTO t VALUES ('hello')");
            try (var rs = st.executeQuery("SELECT x FROM t")) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
