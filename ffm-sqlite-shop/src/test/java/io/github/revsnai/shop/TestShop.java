package io.github.revsnai.shop;

import io.github.revsnai.shop.db.Database;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;

/**
 * The application on a database in a temporary directory, with a fixed clock: a fresh Micronaut context per test
 * (startup migrates the schema), optionally with the HTTP server on a random port.
 */
final class TestShop implements AutoCloseable {
    static final Instant NOW = Instant.parse("2026-01-15T10:30:00.123Z");
    final Path file;
    final ApplicationContext ctx;
    final EmbeddedServer server;
    final Database db;
    final Shop shop;

    TestShop(Path dir) {
        this(dir, false);
    }

    TestShop(Path dir, boolean http) {
        file = dir.resolve("shop.db");
        Map<String, Object> props = Map.of(
                "shop.db", file.toString(),
                "shop.fixed-time", NOW.toString(),
                "micronaut.server.port", -1);
        if (http) {
            server = ApplicationContext.run(EmbeddedServer.class, props);
            ctx = server.getApplicationContext();
        } else {
            server = null;
            ctx = ApplicationContext.run(props);
        }
        db = ctx.getBean(Database.class);
        shop = ctx.getBean(Shop.class);
    }

    /** Runs one SQL statement on the writer, for test setup. */
    int sql(String statement) throws SQLException {
        return db.write(c -> {
            try (var st = c.createStatement()) {
                return st.executeUpdate(statement);
            }
        });
    }

    long count(String query) throws SQLException {
        return db.read(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery(query)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    @Override
    public void close() {
        ctx.close();
    }
}
