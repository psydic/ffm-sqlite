package cmp;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static cmp.Scenarios.*;

class StarterTest {
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
    void memory() {
        all("starter, :memory:", props(
                "ffm-sqlite.datasources.default.path", ":memory:",
                "ffm-sqlite.datasources.default.busy-timeout", "1s",
                "datasources.default.dialect", "SQLITE",
                "datasources.default.schema-generate", "CREATE_DROP"), false);
    }

    @Test
    void file() {
        all("starter, file (defaults)", props(
                "ffm-sqlite.datasources.default.path", dir.resolve("s.db").toString(),
                "datasources.default.dialect", "SQLITE",
                "datasources.default.schema-generate", "CREATE_DROP"), true);
    }
}
