package io.github.revsnai.shop.db;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Lesson 3: schema migrations without a framework. The schema version lives in the database header
 * ({@code PRAGMA user_version}); each script in {@code db/migration} runs once, in order. All pending scripts and
 * the version bump run in one transaction, so a failed migration leaves the database exactly as it was.
 *
 * <p>ffm-sqlite-jdbc's {@code Statement.executeUpdate} runs every statement of a multi-statement script
 * (including triggers with {@code BEGIN ... END}), so a script needs no splitting. Flyway and Liquibase work too:
 * point them at the {@code jdbc:ffmsqlite:} URL.
 */
public final class Migrations {
    static final List<String> SCRIPTS = List.of(
            "V1__schema.sql",
            "V2__product_search.sql",
            "V3__catalog_seed.sql");

    private Migrations() {
    }

    /** Applies the pending scripts; returns the schema version afterwards. */
    public static int migrate(Database db) throws SQLException {
        return db.write(c -> {
            try (Statement st = c.createStatement()) {
                int version;
                try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                    rs.next();
                    version = rs.getInt(1);
                }
                for (int v = version + 1; v <= SCRIPTS.size(); v++) {
                    st.executeUpdate(load(SCRIPTS.get(v - 1)));
                    st.executeUpdate("PRAGMA user_version = " + v);   // transactional: rolled back with the script
                }
                return SCRIPTS.size();
            }
        });
    }

    private static String load(String name) {
        try (InputStream in = Migrations.class.getResourceAsStream("/db/migration/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing migration " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
