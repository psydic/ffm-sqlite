package io.github.psydic.ffmsqlite.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExtensionTest {
    private Connection conn;

    @BeforeEach
    void open() throws SQLiteException {
        conn = SQLite.getConnection(":memory:");
    }

    @AfterEach
    void close() {
        conn.close();
    }

    /** target/test-ext/libffmtest.so from scripts/build-test-extension.sh, if it was built. */
    private static String testExtension() {
        String path = System.getProperty("ffmsqlite.test.extension", "");
        assumeTrue(!path.isEmpty() && Files.exists(Path.of(path)), "run scripts/build-test-extension.sh to test loading");
        return path;
    }

    private String one(Connection c, String sql) throws SQLiteException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    @Test
    void loadsAnExtensionWithItsDerivedEntryPoint() throws SQLiteException {
        conn.loadExtension(testExtension());
        assertEquals("Uryyb, Jbeyq!", one(conn, "SELECT rot13('Hello, World!')"));
        try (Connection other = SQLite.getConnection(":memory:")) {
            assertThrows(SQLiteException.class, () -> one(other, "SELECT rot13('x')"), "extensions are per connection");
        }
    }

    @Test
    void explicitAndFailingEntryPoints() throws SQLiteException {
        String ext = testExtension();
        conn.loadExtension(ext, "ffmtest_alt_init");
        assertEquals("nop", one(conn, "SELECT rot13_alt('abc')"));
        SQLiteException e = assertThrows(SQLiteException.class, () -> conn.loadExtension(ext, "ffmtest_fail_init"));
        assertTrue(e.getMessage().contains("ffmtest refuses to load"), e.getMessage());
        SQLiteException missing = assertThrows(SQLiteException.class, () -> conn.loadExtension(ext, "no_such_init"));
        assertTrue(missing.getMessage().contains("no_such_init"), missing.getMessage());
    }

    @Test
    void sqlLoadExtensionStaysDisabledEvenAfterLoading() throws SQLiteException {
        SQLiteException before = assertThrows(SQLiteException.class, () -> one(conn, "SELECT load_extension('libm.so.6')"));
        assertTrue(before.getMessage().contains("not authorized"), before.getMessage());
        String ext = System.getProperty("ffmsqlite.test.extension", "");
        if (Files.exists(Path.of(ext))) {
            conn.loadExtension(ext);
            SQLiteException after = assertThrows(SQLiteException.class,
                    () -> one(conn, "SELECT load_extension('" + ext + "', 'ffmtest_alt_init')"));
            assertTrue(after.getMessage().contains("not authorized"), after.getMessage());
        }
    }

    @Test
    void errorsForMissingFilesAndNonExtensions() {
        SQLiteException missing = assertThrows(SQLiteException.class, () -> conn.loadExtension("/no/such/extension.so"));
        assertEquals(1, missing.getErrorCode());
        assertTrue(missing.getMessage().contains("/no/such/extension.so"), missing.getMessage());
        // A real shared library without an SQLite entry point.
        SQLiteException notExt = assertThrows(SQLiteException.class, () -> conn.loadExtension("libm.so.6"));
        assertTrue(notExt.getMessage().contains("sqlite3_extension_init") || notExt.getMessage().contains("undefined symbol"),
                notExt.getMessage());
    }
}
