package io.github.psydic.shop;

import io.github.psydic.shop.cart.Cart;
import io.github.psydic.shop.cart.CartRepository;
import io.github.psydic.shop.catalog.Product;
import io.github.psydic.shop.catalog.ProductRepository;
import io.github.psydic.shop.catalog.SearchHit;
import io.github.psydic.shop.customer.Customer;
import io.github.psydic.shop.customer.CustomerRepository;
import io.github.psydic.shop.db.Database;
import io.github.psydic.shop.order.Order;
import io.github.psydic.shop.order.OrderRepository;
import io.github.psydic.shop.report.ReportRepository;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * The service layer: decides where each transaction begins and ends. Repositories take a {@code Connection}
 * and never manage transactions themselves, so several of them can take part in one unit of work.
 * Reads go to {@link Database#read}, writes to {@link Database#write}.
 */
public final class Shop {
    private final Database db;
    private final Clock clock;
    private final ProductRepository products = new ProductRepository();
    private final CustomerRepository customers = new CustomerRepository();
    private final CartRepository carts = new CartRepository();
    private final OrderRepository orders = new OrderRepository();
    private final ReportRepository reports = new ReportRepository();

    public Shop(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public Product product(long id) throws SQLException {
        return db.read(c -> products.find(c, id)).orElseThrow(() -> ShopException.notFound("product", id));
    }

    public List<Product> products(Integer categoryId, long afterId, int limit) throws SQLException {
        return db.read(c -> products.page(c, categoryId, afterId, Math.clamp(limit, 1, 100)));
    }

    public List<SearchHit> search(String text, int limit) throws SQLException {
        return db.read(c -> products.search(c, text, Math.clamp(limit, 1, 50)));
    }

    public Customer register(String email, String name) throws SQLException {
        if (email == null || !email.contains("@") || name == null || name.isBlank()) {
            throw ShopException.badRequest("email and name are required");
        }
        return db.write(c -> customers.register(c, email, name));
    }

    public Customer customer(long id) throws SQLException {
        return db.read(c -> customers.find(c, id)).orElseThrow(() -> ShopException.notFound("customer", id));
    }

    public Cart createCart(Long customerId) throws SQLException {
        return db.write(c -> carts.find(c, carts.create(c, customerId, clock.instant())).orElseThrow());
    }

    public Cart cart(UUID id) throws SQLException {
        return db.read(c -> carts.find(c, id)).orElseThrow(() -> ShopException.notFound("cart", id));
    }

    public Cart setCartItem(UUID cartId, long productId, int quantity) throws SQLException {
        return db.write(c -> {
            carts.setItem(c, cartId, productId, quantity);
            return carts.find(c, cartId).orElseThrow(() -> ShopException.notFound("cart", cartId));
        });
    }

    /** One write transaction: stock, order, lines and cart change together or not at all. */
    public Order checkout(UUID cartId, long customerId) throws SQLException {
        return db.write(c -> orders.find(c, orders.checkout(c, cartId, customerId, clock.instant())).orElseThrow());
    }

    public Order order(long id) throws SQLException {
        return db.read(c -> orders.find(c, id)).orElseThrow(() -> ShopException.notFound("order", id));
    }

    public List<Order> orderHistory(long customerId, long beforeId, int limit) throws SQLException {
        return db.read(c -> orders.history(c, customerId, beforeId, Math.clamp(limit, 1, 100)));
    }

    public Order changeStatus(long orderId, Order.Status from, Order.Status to) throws SQLException {
        return db.write(c -> {
            orders.changeStatus(c, orderId, from, to);
            return orders.find(c, orderId).orElseThrow();
        });
    }

    public List<ReportRepository.TopProduct> topProducts(int perCategory) throws SQLException {
        return db.read(c -> reports.topProducts(c, Math.clamp(perCategory, 1, 10)));
    }

    public List<ReportRepository.DailySales> dailySales() throws SQLException {
        return db.read(reports::dailySales);
    }

    /** An online backup next to the database file: {@code <name>-backup-<epoch-second>.db}. */
    public java.nio.file.Path backup(java.nio.file.Path databaseFile) throws SQLException {
        String name = databaseFile.getFileName().toString().replaceFirst("\\.db$", "");
        java.nio.file.Path target = databaseFile.toAbsolutePath().resolveSibling(
                name + "-backup-" + clock.instant().getEpochSecond() + ".db");
        db.backupTo(target);
        return target;
    }
}
