package io.github.revsnai.shop.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.revsnai.shop.Shop;
import io.github.revsnai.shop.ShopException;
import io.github.revsnai.shop.order.Order;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small JSON API on the JDK's built-in HTTP server: no web framework, so the database code stays in view.
 * Requests run on virtual threads; the connection pools bound how many touch the database at once.
 */
public final class HttpApi {
    private final Shop shop;
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final List<Route> routes;
    private HttpServer server;

    public HttpApi(Shop shop, java.nio.file.Path databaseFile) {
        this.shop = shop;
        routes = List.of(
                new Route("GET", "/api/products", (m, q, b) -> shop.products(intParam(q, "category"), longParam(q, "after", 0), (int) longParam(q, "limit", 20))),
                new Route("GET", "/api/products/(\\d+)", (m, q, b) -> shop.product(Long.parseLong(m.group(1)))),
                new Route("GET", "/api/search", (m, q, b) -> shop.search(q.getOrDefault("q", ""), (int) longParam(q, "limit", 10))),
                new Route("POST", "/api/customers", (m, q, b) -> created(shop.register(text(b, "email"), text(b, "name")))),
                new Route("GET", "/api/customers/(\\d+)", (m, q, b) -> shop.customer(Long.parseLong(m.group(1)))),
                new Route("GET", "/api/customers/(\\d+)/orders", (m, q, b) -> shop.orderHistory(Long.parseLong(m.group(1)), longParam(q, "before", Long.MAX_VALUE), (int) longParam(q, "limit", 20))),
                new Route("POST", "/api/carts", (m, q, b) -> created(shop.createCart(b != null && b.hasNonNull("customerId") ? b.get("customerId").asLong() : null))),
                new Route("GET", "/api/carts/([0-9a-f-]{36})", (m, q, b) -> shop.cart(UUID.fromString(m.group(1)))),
                new Route("PUT", "/api/carts/([0-9a-f-]{36})/items/(\\d+)", (m, q, b) -> shop.setCartItem(UUID.fromString(m.group(1)), Long.parseLong(m.group(2)), number(b, "quantity"))),
                new Route("POST", "/api/carts/([0-9a-f-]{36})/checkout", (m, q, b) -> created(shop.checkout(UUID.fromString(m.group(1)), number(b, "customerId")))),
                new Route("GET", "/api/orders/(\\d+)", (m, q, b) -> shop.order(Long.parseLong(m.group(1)))),
                new Route("POST", "/api/orders/(\\d+)/(pay|ship|cancel)", (m, q, b) -> transition(Long.parseLong(m.group(1)), m.group(2), b)),
                new Route("GET", "/api/reports/top-products", (m, q, b) -> shop.topProducts((int) longParam(q, "per", 3))),
                new Route("GET", "/api/reports/daily-sales", (m, q, b) -> shop.dailySales()),
                new Route("POST", "/api/admin/backup", (m, q, b) -> created(Map.of("file", shop.backup(databaseFile).toString()))));
    }

    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    interface Handler {
        Object handle(Matcher path, Map<String, String> query, JsonNode body) throws Exception;
    }

    record Route(String method, Pattern path, Handler handler) {
        Route(String method, String path, Handler handler) {
            this(method, Pattern.compile(path), handler);
        }
    }

    /** A 201 response. */
    record Created(Object body) {
    }

    static Created created(Object body) {
        return new Created(body);
    }

    private Order transition(long id, String action, JsonNode body) throws Exception {
        return switch (action) {
            case "pay" -> shop.changeStatus(id, Order.Status.PLACED, Order.Status.PAID);
            case "ship" -> shop.changeStatus(id, Order.Status.PAID, Order.Status.SHIPPED);
            default -> shop.cancel(id);   // the current state comes from the database, never from the client
        };
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            for (Route r : routes) {
                Matcher m = r.path().matcher(path);
                if (m.matches() && r.method().equals(ex.getRequestMethod())) {
                    respond(ex, invoke(r, m, ex));
                    return;
                }
            }
            respond(ex, new Response(404, Map.of("error", "no route for " + ex.getRequestMethod() + " " + path)));
        }
    }

    private record Response(int status, Object body) {
    }

    private Response invoke(Route r, Matcher m, HttpExchange ex) {
        try {
            byte[] raw = ex.getRequestBody().readAllBytes();
            JsonNode body = raw.length == 0 ? null : json.readTree(raw);
            Object result = r.handler().handle(m, query(ex.getRequestURI().getRawQuery()), body);
            return result instanceof Created c ? new Response(201, c.body()) : new Response(200, result);
        } catch (ShopException e) {
            return new Response(e.status(), Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException | IOException e) {
            return new Response(400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            e.printStackTrace();
            return new Response(500, Map.of("error", "internal error"));
        }
    }

    private void respond(HttpExchange ex, Response r) throws IOException {
        byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(r.body());
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(r.status(), bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    q.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return q;
    }

    private static Integer intParam(Map<String, String> q, String name) {
        return q.containsKey(name) ? Integer.valueOf(q.get(name)) : null;
    }

    private static long longParam(Map<String, String> q, String name, long otherwise) {
        return q.containsKey(name) ? Long.parseLong(q.get(name)) : otherwise;
    }

    private static String text(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field)) {
            throw ShopException.badRequest("missing field " + field);
        }
        return body.get(field).asText();
    }

    private static int number(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field) || !body.get(field).canConvertToInt()) {
            throw ShopException.badRequest("missing or invalid number " + field);
        }
        return body.get(field).asInt();
    }
}
