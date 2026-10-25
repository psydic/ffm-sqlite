package cmp;

import io.micronaut.context.ApplicationContext;

import javax.sql.DataSource;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The same mixed workload on any stack, through Micronaut Data: 70% findById, 10% indexed range (20 rows), 15%
 * insert, 5% write-first transaction (update, then read back). N client threads, warmup then a timed run; prints PERF lines.
 */
public final class PerfWorkload {
    static final String[] OPS = {"findById", "range20", "insert", "txUpdate"};
    static final int SEED_ROWS = 100_000;
    static final int MAX_MICROS = 200_000;

    private PerfWorkload() {
    }

    static void schema(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE book ("id" INTEGER PRIMARY KEY,"title" VARCHAR(255) NOT NULL,"price" DECIMAL NOT NULL,
                      "published" DATE NOT NULL,"ref" TEXT NOT NULL,"created" TIMESTAMP)""");
            st.executeUpdate("CREATE INDEX book_price ON book (price)");
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO book (title, price, published, ref, created) VALUES (?,?,?,?,?)")) {
            c.setAutoCommit(false);
            SplittableRandom r = new SplittableRandom(1);
            for (int i = 0; i < SEED_ROWS; i++) {
                ps.setString(1, "Book " + i);
                ps.setBigDecimal(2, BigDecimal.valueOf(r.nextInt(100_000), 2));
                ps.setLong(3, LocalDate.of(2020, 1, 1).plusDays(r.nextInt(2000)).toEpochDay() * 86_400_000L);
                ps.setString(4, new UUID(r.nextLong(), r.nextLong()).toString());
                ps.setLong(5, Instant.now().toEpochMilli());
                ps.addBatch();
                if (i % 1000 == 999) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
            c.commit();
            c.setAutoCommit(true);
        }
    }

    public static void run(String stack, ApplicationContext ctx, int threads, int warmupSeconds, int seconds) throws Exception {
        DataSource ds = Scenarios.raw(ctx);
        System.out.printf("PERF\t%s\tSQLITE\tversion=%s\tsource_id=%s\tthreads=%d\twarmup_s=%d\tseconds=%d%n", stack,
                Scenarios.one(ds, "SELECT sqlite_version()"), Scenarios.one(ds, "SELECT substr(sqlite_source_id(), 1, 30)"),
                threads, warmupSeconds, seconds);
        schema(ds);
        BookRepository books = ctx.getBean(BookRepository.class);
        BookService service = ctx.getBean(BookService.class);
        AtomicLong maxId = new AtomicLong(SEED_ROWS);

        long[][][] hist = new long[threads][OPS.length][MAX_MICROS + 1];
        long[][] errors = new long[threads][OPS.length];
        long[] phase = {0};   // 0 warmup, 1 measure, 2 stop
        CountDownLatch done = new CountDownLatch(threads);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            workers.add(Thread.ofPlatform().name("load-" + t).start(() -> {
                SplittableRandom r = new SplittableRandom(100 + id);
                try {
                    while (true) {
                        long p;
                        synchronized (phase) {
                            p = phase[0];
                        }
                        if (p == 2) {
                            break;
                        }
                        int dice = r.nextInt(100);
                        int op = dice < 70 ? 0 : dice < 80 ? 1 : dice < 95 ? 2 : 3;
                        long t0 = System.nanoTime();
                        try {
                            switch (op) {
                                case 0 -> books.findById(1 + r.nextLong(maxId.get()));
                                case 1 -> books.findTop20ByPriceGreaterThanEqualOrderByPrice(BigDecimal.valueOf(r.nextInt(100_000), 2));
                                case 2 -> {
                                    books.save(new Book(null, "New " + r.nextInt(), BigDecimal.valueOf(r.nextInt(100_000), 2),
                                            LocalDate.of(2026, 1, 1), UUID.randomUUID(), null));
                                    maxId.incrementAndGet();
                                }
                                default -> service.repriceWriteFirst(1 + r.nextLong(SEED_ROWS), BigDecimal.valueOf(r.nextInt(100_000), 2));
                            }
                            if (p == 1) {
                                long us = (System.nanoTime() - t0) / 1000;
                                hist[id][op][(int) Math.min(us, MAX_MICROS)]++;
                            }
                        } catch (RuntimeException e) {
                            if (p == 1) {
                                errors[id][op]++;
                            }
                        }
                    }
                } finally {
                    done.countDown();
                }
            }));
        }
        Thread.sleep(warmupSeconds * 1000L);
        long cpu0 = cpuNanos();
        long[] gc0 = gc();
        synchronized (phase) {
            phase[0] = 1;
        }
        long start = System.nanoTime();
        Thread.sleep(seconds * 1000L);
        synchronized (phase) {
            phase[0] = 2;
        }
        double elapsed = (System.nanoTime() - start) / 1e9;
        done.await();
        long cpu = cpuNanos() - cpu0;
        long[] gc1 = gc();

        long totalOps = 0;
        long totalErrors = 0;
        long[] all = new long[MAX_MICROS + 1];
        for (int op = 0; op < OPS.length; op++) {
            long[] merged = new long[MAX_MICROS + 1];
            long n = 0;
            long err = 0;
            for (int t = 0; t < threads; t++) {
                for (int i = 0; i <= MAX_MICROS; i++) {
                    merged[i] += hist[t][op][i];
                    all[i] += hist[t][op][i];
                    n += hist[t][op][i];
                }
                err += errors[t][op];
            }
            totalOps += n;
            totalErrors += err;
            System.out.printf("PERF\t%s\t%s\tops=%d\tops/s=%.0f\tmean_us=%.1f\tp50_us=%d\tp99_us=%d\tp999_us=%d\tmax_us=%s\terrors=%d%n",
                    stack, OPS[op], n, n / elapsed, mean(merged, n), pct(merged, n, 0.50), pct(merged, n, 0.99),
                    pct(merged, n, 0.999), max(merged), err);
        }
        System.out.printf("PERF\t%s\tALL\tops=%d\tops/s=%.0f\tmean_us=%.1f\tp50_us=%d\tp99_us=%d\tp999_us=%d\tmax_us=%s\terrors=%d"
                        + "\tcpu_s=%.1f\tcpu_us_per_op=%.1f\tgc_count=%d\tgc_ms=%d\tseconds=%.0f%n",
                stack, totalOps, totalOps / elapsed, mean(all, totalOps), pct(all, totalOps, 0.50), pct(all, totalOps, 0.99),
                pct(all, totalOps, 0.999), max(all), totalErrors, cpu / 1e9, cpu / 1e3 / totalOps, gc1[0] - gc0[0],
                gc1[1] - gc0[1], elapsed);
    }

    static long cpuNanos() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
    }

    static long[] gc() {
        long count = 0;
        long ms = 0;
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, g.getCollectionCount());
            ms += Math.max(0, g.getCollectionTime());
        }
        return new long[]{count, ms};
    }

    static double mean(long[] h, long n) {
        double sum = 0;
        for (int i = 0; i < h.length; i++) {
            sum += (double) i * h[i];
        }
        return n == 0 ? 0 : sum / n;
    }

    static long pct(long[] h, long n, double q) {
        long target = (long) Math.ceil(q * n);
        long seen = 0;
        for (int i = 0; i < h.length; i++) {
            seen += h[i];
            if (seen >= target && seen > 0) {
                return i;
            }
        }
        return -1;
    }

    static String max(long[] h) {
        for (int i = h.length - 1; i >= 0; i--) {
            if (h[i] > 0) {
                return i == MAX_MICROS ? ">=" + i : String.valueOf(i);
            }
        }
        return "-";
    }
}
