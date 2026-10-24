package io.github.revsnai.shop.cart;

import io.github.revsnai.shop.Money;
import io.github.revsnai.shop.ShopException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lesson 7: upserts and java.time / UUID values. {@code INSERT ... ON CONFLICT DO UPDATE} sets a cart line in one
 * statement whether or not it exists. Cart ids are random UUIDs, bound with {@code setObject(uuid)} and read back
 * with {@code getObject(i, UUID.class)}; timestamps are bound as {@link Instant}. Both are ffm-sqlite-jdbc
 * extensions (sqlite-jdbc needs {@code setString(uuid.toString())} and string parsing instead).
 */
public final class CartRepository {

    public UUID create(Connection c, Long customerId, Instant now) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO cart (id, customer_id, created_at) VALUES (?, ?, ?)")) {
            ps.setObject(1, id);
            if (customerId == null) {
                ps.setNull(2, Types.INTEGER);
            } else {
                ps.setLong(2, customerId);
            }
            ps.setObject(3, now);
            ps.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException e) {
            throw ShopException.notFound("customer", customerId);   // 23503: the customer does not exist
        }
        return id;
    }

    /** Sets the quantity of a product in the cart; 0 removes it. */
    public void setItem(Connection c, UUID cartId, long productId, int quantity) throws SQLException {
        if (quantity < 0) {
            throw ShopException.badRequest("quantity must be >= 0");
        }
        if (quantity == 0) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM cart_item WHERE cart_id = ? AND product_id = ?")) {
                ps.setObject(1, cartId);
                ps.setLong(2, productId);
                ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO cart_item (cart_id, product_id, quantity) VALUES (?, ?, ?)
                ON CONFLICT (cart_id, product_id) DO UPDATE SET quantity = excluded.quantity
                """)) {
            ps.setObject(1, cartId);
            ps.setLong(2, productId);
            ps.setInt(3, quantity);
            ps.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException e) {
            throw ShopException.notFound("cart or product", cartId + " / " + productId);   // foreign key (23503)
        }
    }

    public Optional<Cart> find(Connection c, UUID cartId) throws SQLException {
        Long customerId;
        try (PreparedStatement ps = c.prepareStatement("SELECT customer_id FROM cart WHERE id = ?")) {
            ps.setObject(1, cartId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                long id = rs.getLong(1);
                customerId = rs.wasNull() ? null : id;   // getLong returns 0 for NULL: check wasNull()
            }
        }
        List<Cart.Line> lines = new ArrayList<>();
        long total = 0;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT p.id, p.sku, p.name, ci.quantity, p.price_cents, p.stock
                FROM cart_item ci JOIN product p ON p.id = ci.product_id
                WHERE ci.cart_id = ?
                ORDER BY p.name
                """)) {
            ps.setObject(1, cartId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int quantity = rs.getInt(4);
                    long price = rs.getLong(5);
                    total += quantity * price;
                    lines.add(new Cart.Line(rs.getLong(1), rs.getString(2), rs.getString(3), quantity, Money.of(price),
                            Money.of(quantity * price), rs.getInt(6)));
                }
            }
        }
        return Optional.of(new Cart(cartId, customerId, lines, Money.of(total)));
    }
}
