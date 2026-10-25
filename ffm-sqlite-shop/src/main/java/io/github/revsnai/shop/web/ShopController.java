package io.github.revsnai.shop.web;

import io.github.revsnai.shop.Shop;
import io.github.revsnai.shop.ShopException;
import io.github.revsnai.shop.cart.Cart;
import io.github.revsnai.shop.catalog.Product;
import io.github.revsnai.shop.catalog.SearchHit;
import io.github.revsnai.shop.customer.Customer;
import io.github.revsnai.shop.order.Order;
import io.github.revsnai.shop.report.ReportRepository;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The JSON API. JDBC calls block, so every request runs on Micronaut's blocking executor (virtual threads on
 * JDK 21+) instead of the Netty event loop; the connection pools bound how many touch the database at once.
 * {@link ShopExceptionHandler} turns {@link ShopException} into HTTP statuses.
 */
@Controller("/api")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ShopController {
    private final Shop shop;

    public ShopController(Shop shop) {
        this.shop = shop;
    }

    @Serdeable
    public record NewCustomer(@Nullable String email, @Nullable String name) {
    }

    @Serdeable
    public record NewCart(@Nullable Long customerId) {
    }

    @Serdeable
    public record Quantity(@Nullable Integer quantity) {
    }

    @Serdeable
    public record Checkout(@Nullable Long customerId) {
    }

    @Get("/products")
    public List<Product> products(@QueryValue @Nullable Integer category, @QueryValue(defaultValue = "0") long after,
                                  @QueryValue(defaultValue = "20") int limit) throws SQLException {
        return shop.products(category, after, limit);
    }

    @Get("/products/{id}")
    public Product product(long id) throws SQLException {
        return shop.product(id);
    }

    @Get("/search")
    public List<SearchHit> search(@QueryValue(defaultValue = "") String q, @QueryValue(defaultValue = "10") int limit)
            throws SQLException {
        return shop.search(q, limit);
    }

    @Post("/customers")
    public HttpResponse<Customer> register(@Body NewCustomer body) throws SQLException {
        return HttpResponse.created(shop.register(body.email(), body.name()));
    }

    @Get("/customers/{id}")
    public Customer customer(long id) throws SQLException {
        return shop.customer(id);
    }

    @Get("/customers/{id}/orders")
    public List<Order> orders(long id, @QueryValue(defaultValue = "9223372036854775807") long before,
                              @QueryValue(defaultValue = "20") int limit) throws SQLException {
        return shop.orderHistory(id, before, limit);
    }

    @Post("/carts")
    public HttpResponse<Cart> createCart(@Body @Nullable NewCart body) throws SQLException {
        return HttpResponse.created(shop.createCart(body == null ? null : body.customerId()));
    }

    @Get("/carts/{id}")
    public Cart cart(UUID id) throws SQLException {
        return shop.cart(id);
    }

    @Put("/carts/{id}/items/{productId}")
    public Cart setItem(UUID id, long productId, @Body Quantity body) throws SQLException {
        return shop.setCartItem(id, productId, required(body.quantity(), "quantity"));
    }

    @Post("/carts/{id}/checkout")
    public HttpResponse<Order> checkout(UUID id, @Body Checkout body) throws SQLException {
        return HttpResponse.created(shop.checkout(id, required(body.customerId(), "customerId")));
    }

    @Get("/orders/{id}")
    public Order order(long id) throws SQLException {
        return shop.order(id);
    }

    @Post("/orders/{id}/pay")
    public Order pay(long id) throws SQLException {
        return shop.changeStatus(id, Order.Status.PLACED, Order.Status.PAID);
    }

    @Post("/orders/{id}/ship")
    public Order ship(long id) throws SQLException {
        return shop.changeStatus(id, Order.Status.PAID, Order.Status.SHIPPED);
    }

    /** The current state comes from the database, never from the client (a request body is ignored). */
    @Post("/orders/{id}/cancel")
    public Order cancel(long id) throws SQLException {
        return shop.cancel(id);
    }

    @Get("/reports/top-products")
    public List<ReportRepository.TopProduct> topProducts(@QueryValue(defaultValue = "3") int per) throws SQLException {
        return shop.topProducts(per);
    }

    @Get("/reports/daily-sales")
    public List<ReportRepository.DailySales> dailySales() throws SQLException {
        return shop.dailySales();
    }

    @Post("/admin/backup")
    public HttpResponse<Map<String, String>> backup() throws SQLException {
        return HttpResponse.<Map<String, String>>status(HttpStatus.CREATED).body(Map.of("file", shop.backup().toString()));
    }

    private static <T> T required(@Nullable T value, String field) {
        if (value == null) {
            throw ShopException.badRequest("missing or invalid field " + field);
        }
        return value;
    }
}
