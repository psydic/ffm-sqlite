package io.github.revsnai.shop.report;

import io.github.revsnai.shop.Money;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Lesson 10: analytics in SQL. Window functions (SQLite 3.25+) rank products within their category and keep a
 * running total, in the database rather than in Java loops.
 */
public final class ReportRepository {

    public record TopProduct(String category, int rank, long productId, String name, long units, BigDecimal revenue) {
    }

    public record DailySales(LocalDate day, int orders, BigDecimal revenue, BigDecimal runningTotal) {
    }

    /** The best {@code perCategory} products of every category by revenue, ignoring cancelled orders. */
    public List<TopProduct> topProducts(Connection c, int perCategory) throws SQLException {
        String sql = """
                WITH revenue AS (
                    SELECT p.category_id, p.id, p.name,
                           sum(l.quantity) AS units,
                           sum(l.quantity * l.unit_price_cents) AS cents
                    FROM order_line l
                    JOIN orders o ON o.id = l.order_id AND o.status <> 'CANCELLED'
                    JOIN product p ON p.id = l.product_id
                    GROUP BY p.id
                ), ranked AS (
                    SELECT cat.name AS category, r.*,
                           rank() OVER (PARTITION BY r.category_id ORDER BY r.cents DESC) AS rnk
                    FROM revenue r JOIN category cat ON cat.id = r.category_id
                )
                SELECT category, rnk, id, name, units, cents FROM ranked
                WHERE rnk <= ?
                ORDER BY category, rnk
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, perCategory);
            List<TopProduct> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new TopProduct(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getString(4), rs.getLong(5),
                            Money.of(rs.getLong(6))));
                }
            }
            return rows;
        }
    }

    /** Orders and revenue per day (UTC) with a running total. */
    public List<DailySales> dailySales(Connection c) throws SQLException {
        String sql = """
                SELECT date(placed_at) AS day, count(*), sum(total_cents),
                       sum(sum(total_cents)) OVER (ORDER BY date(placed_at)) AS running
                FROM orders
                WHERE status <> 'CANCELLED'
                GROUP BY day
                ORDER BY day
                """;
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<DailySales> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new DailySales(rs.getObject(1, LocalDate.class), rs.getInt(2), Money.of(rs.getLong(3)),
                        Money.of(rs.getLong(4))));
            }
            return rows;
        }
    }
}
