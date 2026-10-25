import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives the shop API: java LoadGen.java <baseUrl> <workers> <seconds>.
 * Mix per iteration: browse 35%, product 15%, search 15%, cart + checkout 20%, cancel 5%, history 5%, reports 5%;
 * plus one online backup every 30 s. Prints requests/s, status counts and latency every 10 s.
 */
public class LoadGen {
    static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"?([0-9a-f-]+)");
    static final String[] TERMS = {"coffee", "lamp", "book", "java", "tent", "keyboard", "kettle", "ssd", "data", "desk lam", "\"quote", "c++ -x"};
    static final Map<String, LongAdder> counts = new ConcurrentHashMap<>();
    static final LongAdder requests = new LongAdder(), nanos = new LongAdder();
    static final java.util.Queue<String> orders = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public static void main(String[] a) throws Exception {
        String base = a[0];
        int workers = Integer.parseInt(a[1]);
        long end = System.nanoTime() + Long.parseLong(a[2]) * 1_000_000_000L;
        HttpClient http = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(5)).build();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < workers; w++) {
                pool.submit(() -> {
                    while (System.nanoTime() < end) {
                        try {
                            iteration(http, base);
                        } catch (Exception e) {
                            counts.computeIfAbsent("exception:" + e.getClass().getSimpleName(), k -> new LongAdder()).increment();
                        }
                    }
                    return null;
                });
            }
            pool.submit(() -> {
                while (System.nanoTime() < end) {
                    Thread.sleep(30_000);
                    call(http, "POST", base + "/admin/backup", null);
                }
                return null;
            });
            long start = System.nanoTime(), last = start, lastReq = 0;
            while (System.nanoTime() < end) {
                Thread.sleep(10_000);
                long now = System.nanoTime(), req = requests.sum();
                System.out.printf("t=%4ds  %7.0f req/s  avg %.1f ms  %s%n", (now - start) / 1_000_000_000L,
                        (req - lastReq) / ((now - last) / 1e9), nanos.sum() / 1e6 / Math.max(1, req), new java.util.TreeMap<>(counts));
                last = now;
                lastReq = req;
            }
        }
    }

    static void iteration(HttpClient http, String base) throws Exception {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int p = r.nextInt(100);
        if (p < 35) {
            call(http, "GET", base + "/products?limit=20&after=" + r.nextInt(20) + (r.nextBoolean() ? "&category=" + (1 + r.nextInt(4)) : ""), null);
        } else if (p < 50) {
            call(http, "GET", base + "/products/" + (1 + r.nextInt(24)), null);
        } else if (p < 65) {
            call(http, "GET", base + "/search?q=" + java.net.URLEncoder.encode(TERMS[r.nextInt(TERMS.length)], "UTF-8"), null);
        } else if (p < 85) {
            long customer = 1 + r.nextInt(3);
            String cart = id(call(http, "POST", base + "/carts", "{\"customerId\":" + customer + "}"));
            for (int i = 1 + r.nextInt(3); i > 0; i--) {
                call(http, "PUT", base + "/carts/" + cart + "/items/" + (1 + r.nextInt(24)), "{\"quantity\":" + (1 + r.nextInt(2)) + "}");
            }
            String order = id(call(http, "POST", base + "/carts/" + cart + "/checkout", "{\"customerId\":" + customer + "}"));
            if (order != null && !order.contains("-")) {
                orders.add(order);
            }
        } else if (p < 90) {
            String order = orders.poll();   // cancel one in two placed orders: keeps the stock from running out
            if (order != null && r.nextBoolean()) {
                call(http, "POST", base + "/orders/" + order + "/cancel", null);
            }
        } else if (p < 95) {
            call(http, "GET", base + "/customers/" + (1 + r.nextInt(3)) + "/orders?limit=10", null);
        } else {
            call(http, "GET", base + (r.nextBoolean() ? "/reports/top-products?per=3" : "/reports/daily-sales"), null);
        }
    }

    static String call(HttpClient http, String method, String url, String body) throws Exception {
        long t0 = System.nanoTime();
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        nanos.add(System.nanoTime() - t0);
        requests.increment();
        counts.computeIfAbsent(String.valueOf(resp.statusCode()), k -> new LongAdder()).increment();
        return resp.statusCode() < 300 ? resp.body() : null;
    }

    static String id(String json) {
        if (json == null) {
            return null;
        }
        Matcher m = ID.matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
