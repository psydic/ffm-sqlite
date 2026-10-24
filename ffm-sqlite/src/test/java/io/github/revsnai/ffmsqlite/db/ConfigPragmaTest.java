package io.github.revsnai.ffmsqlite.db;

import io.github.revsnai.ffmsqlite.db.SQLite.Config;
import io.github.revsnai.ffmsqlite.db.SQLite.JournalMode;
import io.github.revsnai.ffmsqlite.db.SQLite.Synchronous;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigPragmaTest {
    @TempDir
    Path dir;

    private static String one(Connection c, String sql) throws SQLiteException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    @Test
    void pragmasAreAppliedOnEveryOpen() throws SQLiteException {
        Config config = Config.DEFAULT
                .withJournalMode(JournalMode.WAL)
                .withSynchronous(Synchronous.NORMAL)
                .withForeignKeys(true)
                .withPragma("cache_size", -4000)
                .withPragma("application_id", 42);
        String path = dir.resolve("app.db").toString();
        for (int i = 0; i < 2; i++) {
            try (Connection c = SQLite.getConnection(path, config)) {
                assertEquals("wal", one(c, "PRAGMA journal_mode"));
                assertEquals("1", one(c, "PRAGMA synchronous"));
                assertEquals("1", one(c, "PRAGMA foreign_keys"));
                assertEquals("-4000", one(c, "PRAGMA cache_size"));
                assertEquals("42", one(c, "PRAGMA application_id"));
            }
        }
    }

    @Test
    void foreignKeysAreEnforcedWhenEnabled() throws SQLiteException {
        try (Connection c = SQLite.getConnection(":memory:", Config.DEFAULT.withForeignKeys(true));
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE p (id INTEGER PRIMARY KEY); CREATE TABLE ch (p INTEGER REFERENCES p)");
            SQLiteException e = assertThrows(SQLiteException.class, () -> st.executeUpdate("INSERT INTO ch VALUES (99)"));
            assertEquals(787, e.getExtendedErrorCode()); // SQLITE_CONSTRAINT_FOREIGNKEY
        }
    }

    @Test
    void stringValuesAreQuotedSoTheyCannotInjectSql() throws SQLiteException {
        String path = dir.resolve("t.db").toString();
        try (Connection c = SQLite.getConnection(path); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE keep (x)");
        }
        Config hostile = Config.DEFAULT.withPragma("user_version", "1; DROP TABLE keep");
        assertEquals("'1; DROP TABLE keep'", hostile.pragmas().get("user_version"));
        try (Connection c = SQLite.getConnection(path, hostile)) {
            assertEquals("keep", one(c, "SELECT name FROM sqlite_schema"));
        } catch (SQLiteException ignored) {
            // SQLite may reject the value; either way nothing was dropped
        }
        try (Connection c = SQLite.getConnection(path)) {
            assertEquals("keep", one(c, "SELECT name FROM sqlite_schema"));
        }
        // A quoted text value that SQLite accepts.
        try (Connection c = SQLite.getConnection(dir.resolve("u16.db").toString(), Config.DEFAULT.withPragma("encoding", "UTF-16le"))) {
            assertEquals("UTF-16le", one(c, "PRAGMA encoding"));
        }
    }

    @Test
    void invalidNamesAreRejectedAndOrderIsKept() {
        assertThrows(IllegalArgumentException.class, () -> Config.DEFAULT.withPragma("cache_size; DROP TABLE x", 1));
        Config c = Config.DEFAULT.withPragma("b", 1).withPragma("a", 2).withPragma("b", 3);
        assertEquals("{b=3, a=2}", c.pragmas().toString());
        assertThrows(UnsupportedOperationException.class, () -> c.pragmas().put("x", "1"));
    }
}
