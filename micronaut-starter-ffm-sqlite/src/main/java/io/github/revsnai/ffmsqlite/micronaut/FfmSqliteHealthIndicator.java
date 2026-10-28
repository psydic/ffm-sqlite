package io.github.revsnai.ffmsqlite.micronaut;

import com.zaxxer.hikari.HikariPoolMXBean;
import io.github.revsnai.ffmsqlite.db.SQLite;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.endpoint.health.HealthEndpoint;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Health of every configured database ({@code /health} shows {@code ffm-sqlite}): runs a query on a pooled
 * connection (on the blocking executor) and reports the journal mode, SQLite version and pool usage. Registered when the application has
 * micronaut-management; {@code endpoints.health.ffm-sqlite.enabled: false} turns it off.
 */
@Singleton
@Requires(classes = HealthIndicator.class)
@Requires(beans = HealthEndpoint.class)
@Requires(property = "endpoints.health.ffm-sqlite.enabled", notEquals = "false")
public class FfmSqliteHealthIndicator implements HealthIndicator {

    static final String NAME = "ffm-sqlite";

    private final List<FfmSqliteDatabase> databases;
    private final ExecutorService blocking;

    public FfmSqliteHealthIndicator(List<FfmSqliteDatabase> databases, @Named(TaskExecutors.BLOCKING) ExecutorService blocking) {
        this.databases = databases;
        this.blocking = blocking;
    }

    /** JDBC blocks, so the check runs on the blocking executor, never on the HTTP event loop that asks for it. */
    @Override
    public Publisher<HealthResult> getResult() {
        return Publishers.fromCompletableFuture(() -> CompletableFuture.supplyAsync(this::check, blocking));
    }

    /** No connection to borrow without waiting: callers are queued, or all are in use and the pool is full. */
    static boolean saturated(HikariPoolMXBean pool, int maximumPoolSize) {
        return pool.getThreadsAwaitingConnection() > 0
                || (pool.getIdleConnections() == 0 && pool.getTotalConnections() >= maximumPoolSize);
    }

    HealthResult check() {
        HealthStatus status = HealthStatus.UP;
        Map<String, Object> details = new LinkedHashMap<>();
        for (FfmSqliteDatabase db : databases) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("path", db.getPath());
            HikariPoolMXBean pool = db.getDataSource().getHikariPoolMXBean();
            if (pool != null && saturated(pool, db.getDataSource().getMaximumPoolSize())) {
                // Every connection is busy: answering beats joining the queue for up to connection-timeout and
                // then reporting DOWN. A saturated pool is working, so this is not DOWN.
                d.put("status", HealthStatus.NAME_UP);
                d.put("check", "skipped: all connections in use");
            } else if (!probe(db, d)) {
                status = HealthStatus.DOWN;
            }
            d.put("sqliteVersion", SQLite.version());
            if (pool != null) {
                d.put("pool", Map.of("active", pool.getActiveConnections(), "idle", pool.getIdleConnections(),
                        "total", pool.getTotalConnections(), "waiting", pool.getThreadsAwaitingConnection()));
            }
            details.put(db.getName(), d);
        }
        return HealthResult.builder(NAME, status).details(details).build();
    }

    private static boolean probe(FfmSqliteDatabase db, Map<String, Object> d) {
        try (Connection conn = db.getDataSource().getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
            rs.next();
            d.put("status", HealthStatus.NAME_UP);
            d.put("journalMode", rs.getString(1));
            return true;
        } catch (Exception e) {
            d.put("status", HealthStatus.NAME_DOWN);
            d.put("error", e.getClass().getName() + ": " + e.getMessage());
            return false;
        }
    }
}
