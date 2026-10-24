package io.github.psydic.shop.catalog;

import io.github.psydic.shop.Money;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lesson 4: queries with plain JDBC. Every value is bound with {@code ?} (never concatenated into SQL), statements
 * are closed with try-with-resources, and rows map to records. ffm-sqlite-jdbc caches prepared statements per
 * connection, so preparing the same SQL again is cheap.
 */
public final class ProductRepository {
    private static final String COLUMNS = """
            SELECT p.id, p.sku, c.name, p.name, p.description, p.price_cents, p.stock, p.created_at
            FROM product p JOIN category c ON c.id = p.category_id
            """;

    public Optional<Product> find(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COLUMNS + "WHERE p.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Keyset pagination: "the next {@code limit} products after id {@code afterId}". Unlike OFFSET, it costs the
     * same on page 1 and page 1,000 (an index seek on (category_id, id) or the primary key).
     */
    public List<Product> page(Connection c, Integer categoryId, long afterId, int limit) throws SQLException {
        String sql = COLUMNS + (categoryId == null ? "WHERE p.id > ?" : "WHERE p.category_id = ? AND p.id > ?")
                + " ORDER BY p.id LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (categoryId != null) {
                ps.setInt(i++, categoryId);
            }
            ps.setLong(i++, afterId);
            ps.setInt(i, limit);
            List<Product> products = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    products.add(map(rs));
                }
            }
            return products;
        }
    }

    /**
     * Full-text search through the FTS5 index (migration V2). User input is bound as a parameter, and each word is
     * quoted so that characters such as {@code "}, {@code -} or {@code :} are searched for, not parsed as FTS5
     * query syntax. The last word is a prefix ({@code lamp*}), for search-as-you-type.
     */
    public List<SearchHit> search(Connection c, String text, int limit) throws SQLException {
        String query = ftsQuery(text);
        if (query.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT p.id, p.name, p.price_cents,
                       snippet(product_fts, 1, '[', ']', '…', 8),
                       bm25(product_fts, 10.0, 1.0)            -- a match in the name weighs 10x one in the description
                FROM product_fts JOIN product p ON p.id = product_fts.rowid
                WHERE product_fts MATCH ?
                ORDER BY 5
                LIMIT ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, query);
            ps.setInt(2, limit);
            List<SearchHit> hits = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hits.add(new SearchHit(rs.getLong(1), rs.getString(2), Money.of(rs.getLong(3)), rs.getString(4),
                            rs.getDouble(5)));
                }
            }
            return hits;
        }
    }

    static String ftsQuery(String text) {
        String[] words = text.trim().split("\\s+");
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (words[i].isEmpty()) {
                continue;
            }
            if (!q.isEmpty()) {
                q.append(' ');
            }
            q.append('"').append(words[i].replace("\"", "\"\"")).append('"');
            if (i == words.length - 1) {
                q.append('*');
            }
        }
        return q.toString();
    }

    private static Product map(ResultSet rs) throws SQLException {
        return new Product(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                Money.of(rs.getLong(6)), rs.getInt(7),
                rs.getObject(8, Instant.class));   // ffm-sqlite-jdbc reads SQLite date text into java.time types
    }
}
