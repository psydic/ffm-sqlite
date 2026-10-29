package io.github.revsnai.ffmsqlite.micronaut;

import io.github.revsnai.ffmsqlite.db.SQLite;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryLimitsTest {
    private static final String P = "ffm-sqlite.datasources.default.";

    @Test
    void limitsApplyToPooledAndDirectConnections() throws Exception {
        try (ApplicationContext ctx = ApplicationContext.builder().deduceEnvironment(false)
                .properties(Map.of(P + "path", ":memory:", P + "limits.length", "100000", P + "limits.sql-length", "5000"))
                .start()) {
            FfmSqliteDatabase db = ctx.getBean(FfmSqliteDatabase.class);
            assertEquals(Map.of(SQLite.Limit.LENGTH, 100_000, SQLite.Limit.SQL_LENGTH, 5000), db.getConfig().limits());
            int[] seen = db.withConnection(c -> new int[] {c.getLimit(SQLite.Limit.LENGTH), c.getLimit(SQLite.Limit.SQL_LENGTH)});
            assertEquals(100_000, seen[0]);
            assertEquals(5000, seen[1]);
        }
    }

    @Test
    void anUnknownLimitFailsStartup() {
        Exception e = assertThrows(Exception.class, () -> ApplicationContext.builder().deduceEnvironment(false)
                .properties(Map.of(P + "path", ":memory:", P + "limits.nonsense", "1")).start().close());
        assertTrue(rootMessage(e).contains("limit.nonsense"), rootMessage(e));
    }

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (; t != null; t = t.getCause()) {
            sb.append(t.getMessage()).append(" / ");
        }
        return sb.toString();
    }
}
