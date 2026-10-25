package cmp;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static cmp.Scenarios.*;

class OfficialTest {
    @TempDir
    Path dir;

    private static void all(String stack, Map<String, Object> props, boolean file) {
        try (ApplicationContext ctx = ApplicationContext.builder().deduceEnvironment(false).properties(props).start()) {
            report(stack, "DataSource class", raw(ctx).getClass().getName());
            pragmas(stack, raw(ctx));
            data(stack, ctx);
            readDuringWrite(stack, raw(ctx));
            if (file) {
                concurrentWriters(stack, raw(ctx));
            }
            health(stack, ctx);
        }
    }

    @Test
    void launchDefaults() {
        // Exactly what Micronaut Launch generates for features data-jdbc + sqlite (+ jdbc-hikari): no URL.
        all("official/xerial, Launch defaults (no url)", props(
                "datasources.default.dialect", "SQLITE",
                "datasources.default.driver-class-name", "org.sqlite.JDBC",
                "datasources.default.schema-generate", "CREATE_DROP"), false);
    }

    @Test
    void documentedFileUrl() {
        all("official/xerial, file + documented URL", props(
                "datasources.default.url", "jdbc:sqlite:" + dir.resolve("x.db") + "?foreign_keys=on&busy_timeout=5000&journal_mode=WAL&synchronous=NORMAL",
                "datasources.default.driver-class-name", "org.sqlite.JDBC",
                "datasources.default.dialect", "SQLITE",
                "datasources.default.schema-generate", "CREATE_DROP"), true);
    }

    @Test
    void officialStackWithFfmDriver() {
        all("official/ffm-sqlite-jdbc driver, file", props(
                "datasources.default.url", "jdbc:ffmsqlite:" + dir.resolve("f.db") + "?foreign_keys=true&busy_timeout=5000&journal_mode=WAL&synchronous=NORMAL",
                "datasources.default.driver-class-name", "io.github.revsnai.ffmsqlite.jdbc.FfmSqliteDriver",
                "datasources.default.dialect", "SQLITE",
                "datasources.default.schema-generate", "CREATE_DROP"), true);
    }
}
