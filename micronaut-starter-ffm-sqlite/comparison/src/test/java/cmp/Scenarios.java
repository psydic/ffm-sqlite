package cmp;

import io.micronaut.context.ApplicationContext;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** The same scenarios on any stack; each prints "RESULT<TAB>stack<TAB>scenario<TAB>outcome". */
public final class Scenarios {
    private Scenarios() {
    }

    public static void report(String stack, String scenario, Object outcome) {
        System.out.println("RESULT\t" + stack + "\t" + scenario + "\t" + outcome);
    }

    static DataSource raw(ApplicationContext ctx) {
        return DelegatingDataSource.unwrapDataSource(ctx.getBean(DataSource.class));
    }

    static String one(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static String attempt(Callable c) {
        try {
            return "ok: " + c.call();
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return "FAIL: " + root.getClass().getSimpleName() + ": " + String.valueOf(root.getMessage()).lines().findFirst().orElse("");
        }
    }

    interface Callable {
        Object call() throws Exception;
    }

    /** Micronaut Data with Dialect.SQLITE and schema-generate: CRUD, finder, types, rollback, read-only. */
    public static void data(String stack, ApplicationContext ctx) {
        BookRepository books = ctx.getBean(BookRepository.class);
        BookService service = ctx.getBean(BookService.class);
        report(stack, "schema-generate CREATE_DROP", attempt(() -> one(raw(ctx), "SELECT sql FROM sqlite_schema WHERE name = 'book'")));
        report(stack, "save + findById (BigDecimal, LocalDate, UUID, Instant)", attempt(() -> {
            UUID ref = UUID.randomUUID();
            Book b = books.save(new Book(null, "SQLite", new BigDecimal("12.50"), LocalDate.of(2024, 5, 1), ref, null));
            Book f = books.findById(b.id()).orElseThrow();
            boolean same = f.price().compareTo(new BigDecimal("12.50")) == 0 && f.published().equals(LocalDate.of(2024, 5, 1))
                    && ref.equals(f.ref()) && f.created() != null;
            return same ? "round trip exact; stored as " + one(raw(ctx),
                    "SELECT typeof(price)||'/'||typeof(published)||'/'||typeof(ref)||'/'||typeof(created) FROM book LIMIT 1")
                    : "MISMATCH " + f;
        }));
        report(stack, "derived finder", attempt(() -> books.findByPriceLessThanOrderByTitle(new BigDecimal("20")).size() + " row(s)"));
        report(stack, "@Transactional rollback", attempt(() -> {
            long before = books.count();
            try {
                service.saveThenFail();
            } catch (IllegalStateException expected) {
                // rolled back
            }
            return books.count() == before ? "rolled back" : "NOT rolled back";
        }));
        report(stack, "write inside @Transactional(readOnly = true)", attempt(() -> {
            service.writeInReadOnly();
            return "write ALLOWED (not enforced)";
        }));
    }

    /** One connection holds an uncommitted write; another pooled connection reads. */
    public static void readDuringWrite(String stack, DataSource ds) {
        report(stack, "read while another connection has a write open", attempt(() -> {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS rw (x)");
            }
            try (Connection w = ds.getConnection(); Connection r = ds.getConnection()) {
                w.setAutoCommit(false);
                try (Statement st = w.createStatement()) {
                    st.executeUpdate("INSERT INTO rw VALUES (1)");
                }
                long t0 = System.nanoTime();
                try (Statement st = r.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM rw")) {
                    rs.next();
                    return "read " + rs.getLong(1) + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms";
                } finally {
                    w.rollback();
                }
            }
        }));
    }

    public static void concurrentWriters(String stack, DataSource ds) {
        report(stack, "8 threads x 200 autocommit inserts (pool of 10)", attempt(() -> {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS w (t, n)");
            }
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Future<Integer>> fs = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int thread = t;
                fs.add(pool.submit(() -> {
                    int failed = 0;
                    for (int i = 0; i < 200; i++) {
                        try (Connection c = ds.getConnection(); var ps = c.prepareStatement("INSERT INTO w VALUES (?, ?)")) {
                            ps.setInt(1, thread);
                            ps.setInt(2, i);
                            ps.executeUpdate();
                        } catch (Exception e) {
                            failed++;
                        }
                    }
                    return failed;
                }));
            }
            int failed = 0;
            for (Future<Integer> f : fs) {
                failed += f.get();
            }
            pool.shutdown();
            return one(ds, "SELECT count(*) FROM w") + " rows, " + failed + " failed";
        }));
    }

    public static void pragmas(String stack, DataSource ds) {
        report(stack, "effective PRAGMAs (journal_mode, synchronous, foreign_keys, busy_timeout)", attempt(() ->
                one(ds, "PRAGMA journal_mode") + ", " + one(ds, "PRAGMA synchronous") + ", " + one(ds, "PRAGMA foreign_keys")
                        + ", " + one(ds, "PRAGMA busy_timeout")));
    }

    public static void health(String stack, ApplicationContext ctx) {
        List<String> out = new ArrayList<>();
        for (HealthIndicator h : ctx.getBeansOfType(HealthIndicator.class)) {
            String cls = h.getClass().getSimpleName();
            if (!cls.contains("Jdbc") && !cls.contains("FfmSqlite")) {
                continue;
            }
            CompletableFuture<HealthResult> r = new CompletableFuture<>();
            h.getResult().subscribe(new Subscriber<>() {
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                public void onNext(HealthResult x) {
                    r.complete(x);
                }

                public void onError(Throwable t) {
                    r.completeExceptionally(t);
                }

                public void onComplete() {
                }
            });
            try {
                HealthResult x = r.get(10, TimeUnit.SECONDS);
                out.add(cls + " -> " + x.getName() + " " + x.getStatus() + " " + x.getDetails());
            } catch (Exception e) {
                out.add(cls + " -> " + e);
            }
        }
        report(stack, "health indicators", out);
    }

    public static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
