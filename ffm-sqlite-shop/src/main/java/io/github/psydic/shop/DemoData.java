package io.github.psydic.shop;

import io.github.psydic.shop.cart.Cart;
import io.github.psydic.shop.db.Database;

import java.sql.SQLException;
import java.util.SplittableRandom;

/**
 * Places random orders through the normal {@link Shop} API (so the reports have something to show), then spreads
 * their timestamps over the last 30 days.
 */
final class DemoData {
    private DemoData() {
    }

    static int placeOrders(Database db, Shop shop, int count) throws SQLException {
        SplittableRandom rnd = new SplittableRandom(7);
        int placed = 0;
        for (int i = 0; i < count; i++) {
            long customer = 1 + rnd.nextInt(3);
            Cart cart = shop.createCart(customer);
            for (int lines = 1 + rnd.nextInt(3); lines > 0; lines--) {
                shop.setCartItem(cart.id(), 1 + rnd.nextInt(24), 1 + rnd.nextInt(2));
            }
            try {
                shop.checkout(cart.id(), customer);
                placed++;
            } catch (ShopException outOfStock) {
                // sold out: skip this one
            }
        }
        db.write(c -> {
            try (var st = c.createStatement()) {
                return st.executeUpdate("""
                        UPDATE orders SET placed_at = strftime('%Y-%m-%d %H:%M:%f', placed_at,
                            '-' || (id * 7919 % 30) || ' days', '-' || (id * 104729 % 86400) || ' seconds')
                        """);
            }
        });
        return placed;
    }
}
