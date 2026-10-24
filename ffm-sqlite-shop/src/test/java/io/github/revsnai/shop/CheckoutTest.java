package io.github.revsnai.shop;

import io.github.revsnai.shop.cart.Cart;
import io.github.revsnai.shop.order.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CheckoutTest {

    @Test
    void checkoutCreatesOrderReservesStockAndDeletesCart(@TempDir Path dir) throws Exception {
        try (TestShop t = new TestShop(dir)) {
            Cart cart = t.shop.createCart(1L);
            t.shop.setCartItem(cart.id(), 16, 2);                 // 2 × Desk Lamp 44.99
            t.shop.setCartItem(cart.id(), 1, 1);                  // 1 × The Pragmatic Programmer 42.99
            t.shop.setCartItem(cart.id(), 1, 3);                  // upsert: now 3
            assertEquals(new BigDecimal("218.95"), t.shop.cart(cart.id()).total());

            Order order = t.shop.checkout(cart.id(), 1);
            assertEquals(Order.Status.PLACED, order.status());
            assertEquals(new BigDecimal("218.95"), order.total());
            assertEquals(TestShop.NOW, order.placedAt());         // Instant round trip through SQLite text
            assertEquals(2, order.lines().size());
            assertEquals(85 - 2, t.shop.product(16).stock());
            assertEquals(120 - 3, t.shop.product(1).stock());
            assertEquals(404, assertThrows(ShopException.class, () -> t.shop.cart(cart.id())).status());
        }
    }

    @Test
    void shortStockRollsBackTheWholeCheckout(@TempDir Path dir) throws Exception {
        try (TestShop t = new TestShop(dir)) {
            Cart cart = t.shop.createCart(1L);
            t.shop.setCartItem(cart.id(), 1, 1);                  // in stock
            t.shop.setCartItem(cart.id(), 22, 26);                // only 25 tents
            ShopException e = assertThrows(ShopException.class, () -> t.shop.checkout(cart.id(), 1));
            assertEquals(409, e.status());
            assertEquals(120, t.shop.product(1).stock());         // the first decrement was rolled back
            assertEquals(0, t.count("SELECT count(*) FROM orders"));
            assertEquals(2, t.shop.cart(cart.id()).lines().size());   // the cart is still there
        }
    }

    @Test
    void concurrentCheckoutsNeverOversell(@TempDir Path dir) throws Exception {
        try (TestShop t = new TestShop(dir)) {
            t.sql("UPDATE product SET stock = 10 WHERE id = 22");
            List<Cart> carts = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                Cart cart = t.shop.createCart(1L + i % 3);
                t.shop.setCartItem(cart.id(), 22, 1);
                carts.add(cart);
            }
            List<Callable<Boolean>> buyers = new ArrayList<>();
            for (int i = 0; i < carts.size(); i++) {
                Cart cart = carts.get(i);
                long customer = 1L + i % 3;
                buyers.add(() -> {
                    try {
                        t.shop.checkout(cart.id(), customer);
                        return true;
                    } catch (ShopException soldOut) {
                        return false;
                    }
                });
            }
            int sold = 0;
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Future<Boolean> f : pool.invokeAll(buyers)) {
                    sold += f.get() ? 1 : 0;
                }
            }
            assertEquals(10, sold);
            assertEquals(0, t.shop.product(22).stock());
            assertEquals(10, t.count("SELECT count(*) FROM orders"));
        }
    }

    @Test
    void statusChangesAreConditionalAndCancelRestoresStock(@TempDir Path dir) throws Exception {
        try (TestShop t = new TestShop(dir)) {
            Cart cart = t.shop.createCart(2L);
            t.shop.setCartItem(cart.id(), 3, 5);
            Order order = t.shop.checkout(cart.id(), 2);
            assertEquals(55, t.shop.product(3).stock());
            assertEquals(Order.Status.PAID, t.shop.changeStatus(order.id(), Order.Status.PLACED, Order.Status.PAID).status());
            assertEquals(409, assertThrows(ShopException.class,
                    () -> t.shop.changeStatus(order.id(), Order.Status.PLACED, Order.Status.PAID)).status());
            t.shop.changeStatus(order.id(), Order.Status.PAID, Order.Status.CANCELLED);
            assertEquals(60, t.shop.product(3).stock());
            assertEquals(0, t.shop.topProducts(3).size());        // reports ignore cancelled orders
        }
    }

    @Test
    void cancellingRestocksExactlyOnceAndOnlyFromPlacedOrPaid(@TempDir Path dir) throws Exception {
        try (TestShop t = new TestShop(dir)) {
            Cart cart = t.shop.createCart(1L);
            t.shop.setCartItem(cart.id(), 3, 5);
            Order order = t.shop.checkout(cart.id(), 1);
            assertEquals(55, t.shop.product(3).stock());
            assertEquals(Order.Status.CANCELLED, t.shop.cancel(order.id()).status());
            assertEquals(60, t.shop.product(3).stock());
            assertEquals(409, assertThrows(ShopException.class, () -> t.shop.cancel(order.id())).status());
            assertEquals(409, assertThrows(ShopException.class,
                    () -> t.shop.changeStatus(order.id(), Order.Status.CANCELLED, Order.Status.CANCELLED)).status());
            assertEquals(60, t.shop.product(3).stock(), "no second restock");

            Cart second = t.shop.createCart(1L);
            t.shop.setCartItem(second.id(), 3, 2);
            Order shipped = t.shop.checkout(second.id(), 1);
            t.shop.changeStatus(shipped.id(), Order.Status.PLACED, Order.Status.PAID);
            t.shop.changeStatus(shipped.id(), Order.Status.PAID, Order.Status.SHIPPED);
            assertEquals(409, assertThrows(ShopException.class, () -> t.shop.cancel(shipped.id())).status());
            assertEquals(58, t.shop.product(3).stock(), "shipped goods are not restocked");
        }
    }

    @Test
    void anotherCustomersCartCannotBeCheckedOut(@TempDir Path dir) throws Exception {
        try (TestShop t = new TestShop(dir)) {
            Cart owned = t.shop.createCart(1L);
            t.shop.setCartItem(owned.id(), 5, 1);
            assertEquals(409, assertThrows(ShopException.class, () -> t.shop.checkout(owned.id(), 2)).status());
            assertEquals(1, t.shop.cart(owned.id()).lines().size(), "the owner's cart is untouched");
            assertEquals(150, t.shop.product(5).stock());

            Cart guest = t.shop.createCart(null);
            t.shop.setCartItem(guest.id(), 5, 1);
            assertEquals(2, t.shop.checkout(guest.id(), 2).customerId(), "a guest cart can be checked out by whoever logs in");
        }
    }

    @Test
    void duplicateEmailIsAConflict(@TempDir Path dir) throws Exception {
        try (TestShop t = new TestShop(dir)) {
            t.shop.register("new@example.com", "New");
            assertEquals(409, assertThrows(ShopException.class, () -> t.shop.register("NEW@example.com", "Other")).status());
        }
    }

    @Test
    void backupIsAConsistentCopy(@TempDir Path dir) throws Exception {
        try (TestShop t = new TestShop(dir)) {
            Cart cart = t.shop.createCart(1L);
            t.shop.setCartItem(cart.id(), 5, 1);
            t.shop.checkout(cart.id(), 1);
            Path copy = t.shop.backup(t.file);
            try (var c = java.sql.DriverManager.getConnection("jdbc:ffmsqlite:" + copy);
                 var st = c.createStatement(); var rs = st.executeQuery("SELECT count(*) FROM orders")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }
}
