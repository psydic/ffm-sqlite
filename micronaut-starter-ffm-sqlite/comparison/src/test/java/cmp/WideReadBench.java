package cmp;

import io.micronaut.context.ApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Read-only comparison on 1M rows x 20 columns, through Micronaut Data: point read by random id, and 100
 * consecutive rows, at 1/2/4/8/16 threads. Prints WIDE lines (throughput and latency per thread count).
 */
public final class WideReadBench {
    static final int ROWS = 1_000_000;
    static final int MAX_US = 1_000_000;
    static final java.util.concurrent.atomic.AtomicReference<Throwable> FIRST_ERROR = new java.util.concurrent.atomic.AtomicReference<>();

    private WideReadBench() {
    }

    /** A fresh copy of the template on disk, converted to WAL. */
    public static Path copyTemplate(String name) throws Exception {
        Path template = Path.of(System.getProperty("wide.template"));
        Path dir = Files.createDirectories(Path.of(System.getProperty("perf.dir")));
        Path db = dir.resolve(name);
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(db + suffix));
        }
        Files.copy(template, db, StandardCopyOption.REPLACE_EXISTING);
        return db;
    }

    public static void run(String stack, ApplicationContext ctx, int[] threadCounts, int warmup, int seconds) throws Exception {
        WideRepository repo = ctx.getBean(WideRepository.class);
        System.out.printf("WIDE\t%s\tSETUP\tsqlite=%s\tjournal=%s\tsynchronous=%s\trows=%s\twarmup_s=%d\tseconds=%d%n", stack,
                Scenarios.one(Scenarios.raw(ctx), "SELECT sqlite_version()"), Scenarios.one(Scenarios.raw(ctx), "PRAGMA journal_mode"),
                Scenarios.one(Scenarios.raw(ctx), "PRAGMA synchronous"), Scenarios.one(Scenarios.raw(ctx), "SELECT count(*) FROM wide"),
                warmup, seconds);
        Wide check = repo.findById(12345L).orElseThrow();
        if (check.payload().length < 100 || check.amount() == null) {
            throw new IllegalStateException("bad row " + check);
        }
        for (String op : new String[]{"point", "range100"}) {
            for (int threads : threadCounts) {
                measure(stack, op, threads, repo, warmup, seconds);
            }
        }
    }

    static void measure(String stack, String op, int threads, WideRepository repo, int warmup, int seconds) throws Exception {
        long[][] hist = new long[threads][MAX_US + 1];
        AtomicInteger phase = new AtomicInteger();   // 0 warmup, 1 measure, 2 stop
        long[] errors = new long[threads];
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread.ofPlatform().start(() -> {
                SplittableRandom r = new SplittableRandom(1000L * threads + id);
                try {
                    int p;
                    while ((p = phase.get()) != 2) {
                        long t0 = System.nanoTime();
                        try {
                            if (op.equals("point")) {
                                repo.findById(1 + r.nextLong(ROWS)).orElseThrow();
                            } else {
                                long from = 1 + r.nextLong(ROWS - 100);
                                if (repo.findByIdBetweenOrderById(from, from + 99).size() != 100) {
                                    throw new IllegalStateException("short range");
                                }
                            }
                            if (p == 1) {
                                hist[id][(int) Math.min((System.nanoTime() - t0) / 1000, MAX_US)]++;
                            }
                        } catch (RuntimeException e) {
                            if (FIRST_ERROR.compareAndSet(null, e)) {
                                e.printStackTrace(System.out);
                            }
                            if (p == 1) {
                                errors[id]++;
                            }
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        Thread.sleep(warmup * 1000L);
        phase.set(1);
        long start = System.nanoTime();
        Thread.sleep(seconds * 1000L);
        phase.set(2);
        double elapsed = (System.nanoTime() - start) / 1e9;
        done.await();
        long[] merged = new long[MAX_US + 1];
        long n = 0;
        long err = 0;
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i <= MAX_US; i++) {
                merged[i] += hist[t][i];
                n += hist[t][i];
            }
            err += errors[t];
        }
        System.out.printf("WIDE\t%s\t%s\tthreads=%d\tops=%d\tops/s=%.0f\tmean_us=%.1f\tp50_us=%d\tp99_us=%d\tp999_us=%d\terrors=%d%n",
                stack, op, threads, n, n / elapsed, PerfWorkload.mean(merged, n), PerfWorkload.pct(merged, n, 0.5),
                PerfWorkload.pct(merged, n, 0.99), PerfWorkload.pct(merged, n, 0.999), err);
    }

    static int[] threadCounts() {
        String[] parts = System.getProperty("wide.threads", "1,2,4,8,16").split(",");
        int[] t = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            t[i] = Integer.parseInt(parts[i].trim());
        }
        return t;
    }
}
