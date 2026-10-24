package io.github.psydic.shop;

import io.github.psydic.shop.db.Migrations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigrationsTest {

    @Test
    void migratesOnceAndIsIdempotent(@TempDir Path dir) throws Exception {
        try (TestShop t = new TestShop(dir)) {
            assertEquals(3, Migrations.migrate(t.db));   // second run: nothing pending
            assertEquals(3, t.count("PRAGMA user_version"));
            assertEquals(24, t.count("SELECT count(*) FROM product"));
            assertEquals(1, t.count("SELECT count(*) FROM pragma_journal_mode WHERE journal_mode = 'wal'"));
        }
    }

    @Test
    void searchIndexFollowsProductChanges(@TempDir Path dir) throws Exception {
        try (TestShop t = new TestShop(dir)) {
            t.sql("INSERT INTO product (sku, category_id, name, price_cents, stock) VALUES ('X-1', 4, 'Folding Kayak', 59900, 3)");
            assertEquals("Folding Kayak", t.shop.search("kayaks", 5).getFirst().name());   // porter stemming
            t.sql("UPDATE product SET name = 'Inflatable Canoe' WHERE sku = 'X-1'");
            assertEquals(0, t.shop.search("kayak", 5).size());
            assertEquals(1, t.shop.search("canoe", 5).size());
        }
    }

    @Test
    void searchTreatsUserInputAsText(@TempDir Path dir) throws Exception {
        try (TestShop t = new TestShop(dir)) {
            // FTS5 syntax characters in user input: no SQLException, just (no) results
            for (String q : new String[]{"\"", "lamp\"", "C++ -x:", "NEAR(", "*", "   "}) {
                t.shop.search(q, 5);
            }
            assertEquals(1, t.shop.search("desk lam", 5).size());   // prefix match on the last word
        }
    }

    @Test
    void readerConnectionsAreQueryOnly(@TempDir Path dir) throws Exception {
        try (TestShop t = new TestShop(dir)) {
            assertThrows(SQLException.class, () -> t.db.read(c -> {
                try (var st = c.createStatement()) {
                    return st.executeUpdate("DELETE FROM product");
                }
            }));
            assertEquals(24, t.count("SELECT count(*) FROM product"));
        }
    }
}
