package io.github.psydic.shop.order;

import io.github.psydic.shop.Money;
import io.github.psydic.shop.ShopException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lesson 5: a business transaction. {@link #checkout} turns a cart into an order; it must run inside one write
 * transaction (see {@code Shop.checkout}), so either everything below happens or nothing does:
 * <ol>
 *   <li>read the cart lines with the current prices;</li>
 *   <li>reserve stock with a conditional update ({@code ... WHERE stock >= ?}): if any product is short, throw,
 *       and the rollback restores the products already decremented;</li>
 *   <li>insert the order, get its id from {@code getGeneratedKeys()};</li>
 *   <li>insert the order lines with one JDBC batch;</li>
 *   <li>delete the cart (its items go with it: {@code ON DELETE CASCADE}).</li>
 * </ol>
 */
public final class OrderRepository {

    public long checkout(Connection c, UUID cartId, long customerId, Instant now) throws SQLException {
        record Item(long productId, String name, int quantity, long priceCents) {
        }
        List<Item> items = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT ci.product_id, p.name, ci.quantity, p.price_cents
                FROM cart_item ci JOIN product p ON p.id = ci.product_id
                WHERE ci.cart_id = ?
                ORDER BY ci.product_id
                """)) {
            ps.setObject(1, cartId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new Item(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4)));
                }
            }
        }
        if (items.isEmpty()) {
            throw ShopException.conflict("cart " + cartId + " is empty or does not exist");
        }

        try (PreparedStatement reserve = c.prepareStatement(
                "UPDATE product SET stock = stock - ? WHERE id = ? AND stock >= ?")) {
            for (Item item : items) {
                reserve.setInt(1, item.quantity());
                reserve.setLong(2, item.productId());
                reserve.setInt(3, item.quantity());
                if (reserve.executeUpdate() == 0) {   // 0 rows: not enough stock
                    throw ShopException.conflict("not enough stock for " + item.name());
                }
            }
        }

        long total = items.stream().mapToLong(i -> i.quantity() * i.priceCents()).sum();
        long orderId;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO orders (customer_id, status, total_cents, placed_at) VALUES (?, 'PLACED', ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, customerId);
            ps.setLong(2, total);
            ps.setObject(3, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                orderId = keys.getLong(1);
            }
        } catch (java.sql.SQLIntegrityConstraintViolationException e) {
            throw ShopException.notFound("customer", customerId);
        }

        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO order_line (order_id, line_no, product_id, quantity, unit_price_cents) VALUES (?, ?, ?, ?, ?)")) {
            int lineNo = 1;
            for (Item item : items) {
                ps.setLong(1, orderId);
                ps.setInt(2, lineNo++);
                ps.setLong(3, item.productId());
                ps.setInt(4, item.quantity());
                ps.setLong(5, item.priceCents());
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (PreparedStatement ps = c.prepareStatement("DELETE FROM cart WHERE id = ?")) {
            ps.setObject(1, cartId);
            ps.executeUpdate();
        }
        return orderId;
    }

    /**
     * Lesson 8: state transitions without lost updates. The {@code WHERE status = ?} makes the change conditional on
     * the state the caller saw (optimistic concurrency); cancelling puts the stock back with {@code UPDATE ... FROM}.
     */
    public void changeStatus(Connection c, long orderId, Order.Status from, Order.Status to) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE orders SET status = ? WHERE id = ? AND status = ?")) {
            ps.setString(1, to.name());
            ps.setLong(2, orderId);
            ps.setString(3, from.name());
            if (ps.executeUpdate() == 0) {
                throw ShopException.conflict("order " + orderId + " is not " + from);
            }
        }
        if (to == Order.Status.CANCELLED) {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE product SET stock = stock + l.quantity
                    FROM order_line l
                    WHERE l.order_id = ? AND l.product_id = product.id
                    """)) {
                ps.setLong(1, orderId);
                ps.executeUpdate();
            }
        }
    }

    public Optional<Order> find(Connection c, long orderId) throws SQLException {
        long customerId;
        Order.Status status;
        long total;
        Instant placedAt;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT customer_id, status, total_cents, placed_at FROM orders WHERE id = ?")) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                customerId = rs.getLong(1);
                status = Order.Status.valueOf(rs.getString(2));
                total = rs.getLong(3);
                placedAt = rs.getObject(4, Instant.class);
            }
        }
        List<Order.Line> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT l.line_no, l.product_id, p.name, l.quantity, l.unit_price_cents
                FROM order_line l JOIN product p ON p.id = l.product_id
                WHERE l.order_id = ? ORDER BY l.line_no
                """)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(new Order.Line(rs.getInt(1), rs.getLong(2), rs.getString(3), rs.getInt(4), Money.of(rs.getLong(5))));
                }
            }
        }
        return Optional.of(new Order(orderId, customerId, status, Money.of(total), placedAt, lines));
    }

    /** A customer's orders, newest first, keyset-paginated by id. */
    public List<Order> history(Connection c, long customerId, long beforeId, int limit) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM orders WHERE customer_id = ? AND id < ? ORDER BY id DESC LIMIT ?")) {
            ps.setLong(1, customerId);
            ps.setLong(2, beforeId);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        List<Order> orders = new ArrayList<>();
        for (long id : ids) {
            orders.add(find(c, id).orElseThrow());
        }
        return orders;
    }
}
