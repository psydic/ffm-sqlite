package io.github.revsnai.shop;

import io.github.revsnai.shop.db.Database;
import io.github.revsnai.shop.db.Migrations;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** A migrated database in a temporary directory, and a Shop on it with a fixed clock. */
final class TestShop implements AutoCloseable {
    static final Instant NOW = Instant.parse("2026-01-15T10:30:00.123Z");
    final Path file;
    final Database db;
    final Shop shop;

    TestShop(Path dir) throws SQLException {
        file = dir.resolve("shop.db");
        db = new Database(file, 4);
        Migrations.migrate(db);
        shop = new Shop(db, Clock.fixed(NOW, ZoneOffset.UTC));
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
        db.close();
    }
}
