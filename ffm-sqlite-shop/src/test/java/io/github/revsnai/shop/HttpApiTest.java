package io.github.revsnai.shop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The API end to end, over real HTTP: the Micronaut server on a random port. */
class HttpApiTest {
    @TempDir
    Path dir;
    TestShop t;
    String base;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        t = new TestShop(dir, true);
        base = "http://localhost:" + t.server.getPort() + "/api";
    }

    @AfterEach
    void stop() {
        t.close();
    }

    HttpResponse<String> call(String method, String path, String json) throws Exception {
        HttpRequest.BodyPublisher body = json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json);
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).method(method, body).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([^\",}]+)").matcher(json);
        assertTrue(m.find(), name + " in " + json);
        return m.group(1).trim();
    }

    @Test
    void browseRegisterAndBuy() throws Exception {
        assertEquals(200, call("GET", "/products?category=2&limit=3", null).statusCode());
        assertTrue(call("GET", "/search?q=coffee", null).body().contains("Pour-Over Coffee Kit"));

        HttpResponse<String> customer = call("POST", "/customers", "{\"email\":\"t@example.com\",\"name\":\"Trainee\"}");
        assertEquals(201, customer.statusCode());
        String customerId = field(customer.body(), "id");
        assertEquals(409, call("POST", "/customers", "{\"email\":\"T@example.com\",\"name\":\"Again\"}").statusCode());

        String cartId = field(call("POST", "/carts", "{\"customerId\":" + customerId + "}").body(), "id");
        assertEquals(200, call("PUT", "/carts/" + cartId + "/items/13", "{\"quantity\":2}").statusCode());
        HttpResponse<String> order = call("POST", "/carts/" + cartId + "/checkout", "{\"customerId\":" + customerId + "}");
        assertEquals(201, order.statusCode());
        assertEquals("69.98", field(order.body(), "total"));

        String orderId = field(order.body(), "id");
        assertEquals("PAID", field(call("POST", "/orders/" + orderId + "/pay", null).body(), "status"));
        assertEquals(409, call("POST", "/orders/" + orderId + "/pay", null).statusCode());
        assertTrue(call("GET", "/customers/" + customerId + "/orders", null).body().contains("\"PAID\""));
        assertTrue(call("GET", "/reports/top-products?per=1", null).body().contains("Pour-Over Coffee Kit"));
    }

    @Test
    void cancelTakesTheStateFromTheDatabaseNotTheRequest() throws Exception {
        String cartId = field(call("POST", "/carts", "{\"customerId\":1}").body(), "id");
        call("PUT", "/carts/" + cartId + "/items/13", "{\"quantity\":2}");
        String orderId = field(call("POST", "/carts/" + cartId + "/checkout", "{\"customerId\":1}").body(), "id");
        assertEquals(200, call("POST", "/orders/" + orderId + "/cancel", null).statusCode());
        // the old API let the client claim the order was CANCELLED, restocking it again on every call
        assertEquals(409, call("POST", "/orders/" + orderId + "/cancel", "{\"from\":\"CANCELLED\"}").statusCode());
        assertEquals("70", field(call("GET", "/products/13", null).body(), "stock"));
    }

    @Test
    void errorsMapToHttpStatuses() throws Exception {
        assertEquals(404, call("GET", "/products/9999", null).statusCode());
        assertEquals(404, call("POST", "/orders/9999/pay", null).statusCode());
        assertEquals(400, call("POST", "/customers", "{\"email\":\"x@example.com\"}").statusCode());
        assertEquals(400, call("POST", "/customers", "not json").statusCode());
        assertEquals(404, call("POST", "/carts", "{\"customerId\":9999}").statusCode());
        assertEquals(405, call("DELETE", "/products", null).statusCode());   // the path exists, the method does not
    }

    @Test
    void healthShowsBothPools() throws Exception {
        HttpResponse<String> health = http.send(HttpRequest.newBuilder(URI.create(base.replace("/api", "/health"))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("\"ffm-sqlite\""), health.body());
        assertTrue(health.body().contains("\"writer\"") && health.body().contains("\"reader\""), health.body());
        assertEquals("wal", field(health.body(), "journalMode"));
    }
}
