package io.github.revsnai.shop.customer;

import io.github.revsnai.shop.ShopException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;

/**
 * Lesson 6: constraint violations as business errors. The UNIQUE index on email (case-insensitive, see V1) is the
 * source of truth; no "SELECT first, then INSERT" race. ffm-sqlite-jdbc reports constraint failures as
 * {@link SQLIntegrityConstraintViolationException} with a standard SQLState: 23505 unique, 23503 foreign key,
 * 23502 not null, 23514 check.
 */
public final class CustomerRepository {

    public Customer register(Connection c, String email, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO customer (email, name) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email.trim());
            ps.setString(2, name.trim());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return find(c, keys.getLong(1)).orElseThrow();
            }
        } catch (SQLIntegrityConstraintViolationException e) {
            if ("23505".equals(e.getSQLState())) {
                throw ShopException.conflict("email " + email + " is already registered");
            }
            throw e;
        }
    }

    public Optional<Customer> find(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, email, name, created_at FROM customer WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new Customer(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, Instant.class)))
                        : Optional.empty();
            }
        }
    }
}
