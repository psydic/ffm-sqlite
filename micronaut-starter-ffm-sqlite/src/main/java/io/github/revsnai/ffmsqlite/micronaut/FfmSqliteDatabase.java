package io.github.revsnai.ffmsqlite.micronaut;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.revsnai.ffmsqlite.db.Connection;
import io.github.revsnai.ffmsqlite.db.SQLite;
import io.github.revsnai.ffmsqlite.db.SQLiteException;
import io.github.revsnai.ffmsqlite.db.Statement;
import io.github.revsnai.ffmsqlite.jdbc.FfmSqliteDataSource;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.EachBean;
import io.micronaut.context.exceptions.ConfigurationException;
import jakarta.annotation.PreDestroy;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * One configured database: its connection pool (also exposed as the {@link DataSource} bean of the same name) and
 * connections for the ffm-sqlite API. Created at startup, so a wrong path or PRAGMA fails the application early.
 *
 * <pre>{@code
 * @Inject FfmSqliteDatabase db;
 *
 * long n = db.withConnection(conn -> {          // a pooled connection, as io.github.revsnai.ffmsqlite.db.Connection
 *     try (var ps = conn.prepareStatement("SELECT count(*) FROM orders"); var rs = ps.executeQuery()) {
 *         rs.next();
 *         return rs.getLong(1);
 *     }
 * });
 * }</pre>
 */
@Context
@EachBean(FfmSqliteDataSourceConfiguration.class)
public class FfmSqliteDatabase implements AutoCloseable {

    /** Work done on a pooled connection through the ffm-sqlite API. */
    @FunctionalInterface
    public interface ConnectionCallback<T> {
        T apply(Connection connection) throws SQLException, SQLiteException;
    }

    private final String name;
    private final String path;
    private final boolean memory;
    private final SQLite.Config config;
    private final HikariDataSource pool;
    /** Keeps an in-memory database alive while the pool replaces connections. */
    private final Connection anchor;

    /** Driver properties the typed settings own; under {@code properties} they would split pool and core API. */
    private static final Set<String> OWNED_PROPERTIES = Set.of("readonly", "read_only", "open_mode", "busy_timeout",
            "statement_cache_size", "journal_mode", "synchronous", "foreign_keys");

    public FfmSqliteDatabase(FfmSqliteDataSourceConfiguration c) {
        this.name = c.getName();
        String prefix = FfmSqliteDataSourceConfiguration.PREFIX + "." + name + ".";
        String configured = c.getPath();
        if (configured == null || configured.isBlank()) {
            throw new ConfigurationException(prefix + "path is required (a file, :memory:, or a file: URI)");
        }
        if (configured.startsWith("file:") && (configured.contains(":memory:") || configured.contains("mode=memory"))) {
            throw new ConfigurationException(prefix + "path: " + configured + " would give every pooled connection its "
                    + "own empty database; use :memory: for one in-memory database shared by the pool");
        }
        int busyMillis = millis(prefix + "busy-timeout", c.getBusyTimeout(), 0, Integer.MAX_VALUE);
        atLeast(prefix + "statement-cache-size", c.getStatementCacheSize(), 0);
        atLeast(prefix + "maximum-pool-size", c.getMaximumPoolSize(), 1);
        atLeast(prefix + "minimum-idle", c.getMinimumIdle(), -1);
        long connectionTimeout = millis(prefix + "connection-timeout", c.getConnectionTimeout(), 250, Long.MAX_VALUE);
        long idleTimeout = millis(prefix + "idle-timeout", c.getIdleTimeout(), 0, Long.MAX_VALUE);
        long maxLifetime = millis(prefix + "max-lifetime", c.getMaxLifetime(), 0, Long.MAX_VALUE);
        if (maxLifetime != 0 && maxLifetime < 30_000) {
            throw new ConfigurationException(prefix + "max-lifetime must be 0 (no limit) or at least 30s");
        }

        this.memory = configured.equals(":memory:");
        // A plain :memory: would give every pooled connection its own empty database; the memdb VFS shares one by
        // name. The name is process-wide, so it is made unique: two application contexts must not share it.
        this.path = memory
                ? "file:/ffm-sqlite-" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "-" + UUID.randomUUID() + "?vfs=memdb"
                : configured;
        if (c.isCreateDirectories() && !memory && !path.startsWith("file:")) {
            createParentDirectories(Path.of(path));
        }

        Properties props = new Properties();
        props.setProperty("readonly", Boolean.toString(c.isReadOnly()));
        props.setProperty("busy_timeout", Integer.toString(busyMillis));
        props.setProperty("statement_cache_size", Integer.toString(c.getStatementCacheSize()));
        // Changing the journal mode writes to the file, which a read-only connection cannot do.
        if (!memory && !c.isReadOnly()) {
            props.setProperty("pragma.journal_mode", c.getJournalMode().name());
        }
        props.setProperty("pragma.synchronous", c.getSynchronous().name());
        props.setProperty("pragma.foreign_keys", c.isForeignKeys() ? "ON" : "OFF");
        c.getPragmas().forEach((k, v) -> props.setProperty("pragma." + k.replace('-', '_').toLowerCase(Locale.ROOT), v));
        c.getProperties().forEach((k, v) -> {
            String key = k.replace('-', '_').toLowerCase(Locale.ROOT);
            if (OWNED_PROPERTIES.contains(key) || key.startsWith("pragma.")) {
                throw new ConfigurationException(prefix + "properties." + k + ": set it with the typed setting or under "
                        + prefix + "pragmas instead");
            }
            props.setProperty(key, v);
        });

        FfmSqliteDataSource driver = new FfmSqliteDataSource(path);
        driver.setProperties(props);
        try {
            // The settings the driver derives from the properties, so that openConnection() matches the pool.
            this.config = driver.getSqliteConfig();
        } catch (SQLException e) {
            throw new ConfigurationException(prefix + "pragmas or properties: " + e.getMessage(), e);
        }

        HikariConfig hc = new HikariConfig();
        try {
            hc.setPoolName("ffm-sqlite-" + name);
            hc.setDataSource(driver);
            hc.setMaximumPoolSize(c.getMaximumPoolSize());
            hc.setMinimumIdle(c.getMinimumIdle() < 0 ? c.getMaximumPoolSize() : Math.min(c.getMinimumIdle(), c.getMaximumPoolSize()));
            hc.setConnectionTimeout(connectionTimeout);
            hc.setIdleTimeout(idleTimeout);
            hc.setMaxLifetime(maxLifetime);
            hc.validate();
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(prefix + "pool settings: " + e.getMessage(), e);
        }

        // Opened only once nothing else can fail before the pool owns cleanup: a leaked anchor keeps the database.
        Connection anchor = null;
        if (memory) {
            try {
                anchor = SQLite.getConnection(path, config);
            } catch (SQLiteException e) {
                throw new ConfigurationException("cannot open the in-memory database " + name + ": " + e.getMessage(), e);
            }
        }
        this.anchor = anchor;
        try {
            this.pool = new HikariDataSource(hc);
        } catch (RuntimeException e) {
            closeAnchor();
            throw new ConfigurationException("cannot open database " + name + " (" + path + "): " + e.getMessage(), e);
        }
    }

    private static int atLeast(String property, int value, int min) {
        if (value < min) {
            throw new ConfigurationException(property + " must be at least " + min + ", not " + value);
        }
        return value;
    }

    private static int millis(String property, Duration d, int min, int max) {
        return (int) millis(property, d, (long) min, (long) max);
    }

    private static long millis(String property, Duration d, long min, long max) {
        long ms;
        try {
            ms = d.toMillis();
        } catch (ArithmeticException e) {
            ms = Long.MAX_VALUE;
        }
        if (d.isNegative() || ms < min || ms > max) {
            throw new ConfigurationException(property + " must be between " + Duration.ofMillis(min) + " and "
                    + Duration.ofMillis(max) + ", not " + d);
        }
        return ms;
    }

    private static void createParentDirectories(Path file) {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create " + parent, e);
            }
        }
    }

    /** {@code default}, or the key under {@code ffm-sqlite.datasources}. */
    public String getName() {
        return name;
    }

    /** What SQLite opens: the configured path, or a {@code memdb} URI for {@code :memory:}. */
    public String getPath() {
        return path;
    }

    public boolean isInMemory() {
        return memory;
    }

    /** The settings every connection gets, for opening connections yourself. */
    public SQLite.Config getConfig() {
        return config;
    }

    /** The connection pool; the same object as the {@link DataSource} bean of this name. */
    public HikariDataSource getDataSource() {
        return pool;
    }

    /**
     * Runs {@code callback} on a pooled connection through the ffm-sqlite API (functions, collations, backup,
     * serialize, hooks, ...). The connection goes back to the pool afterwards, so the callback must restore what it
     * changes. A transaction it leaves open (auto-commit off, or a {@code BEGIN} or {@code SAVEPOINT}) is rolled
     * back and the call fails; a connection it closes is dropped from the pool; functions, listeners and handlers it
     * registers stay on that pooled connection. This runs outside Micronaut transactions; within
     * {@code @Transactional}, unwrap the transaction's {@code java.sql.Connection} instead.
     */
    public <T> T withConnection(ConnectionCallback<T> callback) throws SQLException, SQLiteException {
        java.sql.Connection jdbc = pool.getConnection();
        boolean evict = true;   // until the connection is known to be clean
        try {
            Connection conn = jdbc.unwrap(Connection.class);
            T result;
            try {
                result = callback.apply(conn);
            } catch (SQLException | SQLiteException | RuntimeException | Error e) {
                try {
                    endTransaction(conn);
                    evict = conn.isClosed();
                } catch (SQLiteException | RuntimeException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
            boolean leftOpen = endTransaction(conn);
            evict = conn.isClosed();
            if (leftOpen) {
                throw new IllegalStateException("withConnection: the callback left a transaction open (auto-commit off, "
                        + "or BEGIN/SAVEPOINT); its uncommitted work was rolled back");
            }
            return result;
        } finally {
            if (evict) {
                pool.evictConnection(jdbc);   // closed by the callback, or in an unknown state: never reuse it
            }
            jdbc.close();
        }
    }

    /**
     * Rolls back what the callback left open and turns auto-commit on again; the pool cannot see either change.
     * Returns whether there was something to roll back.
     */
    private static boolean endTransaction(Connection conn) throws SQLiteException {
        if (conn.isClosed()) {
            return false;
        }
        if (!conn.getAutoCommit()) {
            conn.rollback();
            conn.setAutoCommit(true);
            return true;
        }
        if (conn.inTransaction()) {   // a raw BEGIN or SAVEPOINT, which getAutoCommit() does not see
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("ROLLBACK");
            }
            return true;
        }
        return false;
    }

    /**
     * A new, unpooled connection with this database's settings; the caller closes it. Prefer
     * {@link #withConnection} for short work: opening a connection costs tens of microseconds and starts with
     * empty caches.
     */
    public Connection openConnection() throws SQLiteException {
        return SQLite.getConnection(path, config);
    }

    @Override
    @PreDestroy
    public void close() {
        try {
            pool.close();
        } finally {
            closeAnchor();
        }
    }

    private void closeAnchor() {
        if (anchor != null) {
            anchor.close();
        }
    }
}
