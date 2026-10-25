package cmp;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static cmp.Scenarios.*;

class TwoContextsTest {
    @Test
    void launchDefaultMemoryAcrossContexts() throws Exception {
        var p = props("datasources.default.dialect", "SQLITE", "datasources.default.driver-class-name", "org.sqlite.JDBC");
        try (ApplicationContext a = ApplicationContext.builder().deduceEnvironment(false).properties(p).start();
             ApplicationContext b = ApplicationContext.builder().deduceEnvironment(false).properties(p).start()) {
            try (Connection c = raw(a).getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE from_a (x)");
            }
            report("official/xerial, Launch defaults (no url)", "two application contexts in one JVM",
                    attempt(() -> one(raw(b), "SELECT count(*) FROM sqlite_schema WHERE name = 'from_a'").equals("1")
                            ? "SHARE one database" : "separate databases"));
        }
    }
}
