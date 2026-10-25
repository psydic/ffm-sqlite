package cmp;

import com.zaxxer.hikari.HikariDataSource;
import io.micronaut.context.ApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.SplittableRandom;
import java.util.UUID;

/** Single-thread cost of each layer of a findById, in µs: pool, driver, Micronaut Data. Prints BREAKDOWN lines. */
public final class Breakdown {
    private Breakdown() {
    }

    interface Op {
        void run(long id) throws Exception;
    }

    static double time(Op op, int seconds) throws Exception {
        SplittableRandom r = new SplittableRandom(3);
        long end = System.nanoTime() + 3_000_000_000L;   // warmup
        while (System.nanoTime() < end) {
            op.run(1 + r.nextLong(PerfWorkload.SEED_ROWS));
        }
        long n = 0;
        long t0 = System.nanoTime();
        end = t0 + seconds * 1_000_000_000L;
        while (System.nanoTime() < end) {
            for (int i = 0; i < 100; i++) {
                op.run(1 + r.nextLong(PerfWorkload.SEED_ROWS));
            }
            n += 100;
        }
        return (System.nanoTime() - t0) / 1e3 / n;
    }

    static final String SQL = "SELECT id, title, price, published, ref, created FROM book WHERE id = ?";

    static void readRow(PreparedStatement ps, long id) throws Exception {
        ps.setLong(1, id);
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            rs.getLong(1);
            rs.getString(2);
            rs.getBigDecimal(3);
            LocalDate.ofEpochDay(rs.getDate(4).toLocalDate().toEpochDay());
            UUID.fromString(rs.getString(5));
            Instant.ofEpochMilli(rs.getTimestamp(6).getTime());
        }
    }

    public static void run(String stack, ApplicationContext ctx, int seconds) throws Exception {
        DataSource ds = Scenarios.raw(ctx);
        PerfWorkload.schema(ds);
        BookRepository books = ctx.getBean(BookRepository.class);
        if (Boolean.getBoolean("count.calls")) {
            for (long i = 1; i <= 1000; i++) {
                books.findById(i);   // warm up, then count
            }
            CallCounter.CALLS.clear();
            for (long i = 1; i <= 1000; i++) {
                books.findById(i);
            }
            CallCounter.print(stack, 1000);
            return;
        }
        HikariDataSource pool = (HikariDataSource) ds;

        try (Connection held = ds.getConnection(); PreparedStatement ps = held.prepareStatement(SQL)) {
            report(stack, "1 driver: execute + read 6 columns (held connection, prepared once)", time(id -> readRow(ps, id), seconds));
            report(stack, "1b driver: SELECT id only (held connection, prepared once)", time(id -> {
                try (PreparedStatement p2 = held.prepareStatement("SELECT id FROM book WHERE id = ?")) {
                    p2.setLong(1, id);
                    try (ResultSet rs = p2.executeQuery()) {
                        rs.next();
                        rs.getLong(1);
                    }
                }
            }, seconds));
        }
        try (Connection held = ds.getConnection()) {
            report(stack, "1c driver: setReadOnly(true) + setReadOnly(false)", time(id -> {
                held.setReadOnly(true);
                held.setReadOnly(false);
            }, seconds));
            report(stack, "1d driver: prepare (cached) + execute + read 6 columns", time(id -> {
                try (PreparedStatement p = held.prepareStatement(SQL)) {
                    readRow(p, id);
                }
            }, seconds));
            report(stack, "1e driver: setReadOnly(true) + 1d + setReadOnly(false)", time(id -> {
                held.setReadOnly(true);
                try (PreparedStatement p = held.prepareStatement(SQL)) {
                    readRow(p, id);
                }
                held.setReadOnly(false);
            }, seconds));
        }
        report(stack, "2 pool: getConnection + close", time(id -> {
            try (Connection c = pool.getConnection()) {
                c.getAutoCommit();
            }
        }, seconds));
        report(stack, "3 pool + prepare + driver (plain JDBC findById)", time(id -> {
            try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(SQL)) {
                readRow(ps, id);
            }
        }, seconds));
        report(stack, "4 Micronaut Data books.findById", time(books::findById, seconds));
    }

    static void report(String stack, String layer, double us) {
        System.out.printf("BREAKDOWN\t%s\t%s\t%.2f us%n", stack, layer, us);
    }
}
