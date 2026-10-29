package io.github.revsnai.ffmsqlite.micronaut;

import io.github.revsnai.ffmsqlite.db.SQLite;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One database, from {@code ffm-sqlite.datasources.<name>}; {@code default} is the primary one:
 *
 * <pre>{@code
 * ffm-sqlite:
 *   datasources:
 *     default:
 *       path: data/app.db          # or :memory: (one in-memory database shared by the pool)
 *       journal-mode: WAL          # the defaults suit a web application
 *       synchronous: NORMAL
 *       foreign-keys: true
 *       busy-timeout: 5s
 *       maximum-pool-size: 10
 *       pragmas:
 *         cache_size: -20000       # any PRAGMA, run on every new connection
 *         hard_heap_limit: 268435456   # caps SQLite's native heap for the whole process (bytes)
 *       limits:
 *         length: 10000000         # sqlite3_limit: no string or BLOB over 10 MB
 *       properties:
 *         date_class: TEXT         # other ffm-sqlite-jdbc driver properties
 * }</pre>
 */
@EachProperty(value = FfmSqliteDataSourceConfiguration.PREFIX, primary = "default")
public class FfmSqliteDataSourceConfiguration {

    public static final String PREFIX = "ffm-sqlite.datasources";

    private final String name;
    private String path;
    private boolean readOnly;
    private boolean createDirectories = true;
    private SQLite.JournalMode journalMode = SQLite.JournalMode.WAL;
    private SQLite.Synchronous synchronous = SQLite.Synchronous.NORMAL;
    private boolean foreignKeys = true;
    private Duration busyTimeout = Duration.ofSeconds(5);
    private int statementCacheSize = SQLite.Config.DEFAULT.statementCacheSize();
    private Map<String, String> pragmas = new LinkedHashMap<>();
    private Map<String, String> properties = new LinkedHashMap<>();
    private Map<String, Integer> limits = new LinkedHashMap<>();
    private int maximumPoolSize = 10;
    private int minimumIdle = -1;
    private Duration connectionTimeout = Duration.ofSeconds(30);
    private Duration idleTimeout = Duration.ofMinutes(10);
    private Duration maxLifetime = Duration.ZERO;

    public FfmSqliteDataSourceConfiguration(@Parameter String name) {
        this.name = name;
    }

    /** The bean name: {@code default}, or the key under {@code ffm-sqlite.datasources}. */
    public String getName() {
        return name;
    }

    /** The database file, {@code :memory:}, or a {@code file:} URI. Required. */
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    /** Opens the file read-only (connections fail on writes); the file must exist. */
    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    /** Creates missing parent directories of {@link #getPath()} on startup. Default true. */
    public boolean isCreateDirectories() {
        return createDirectories;
    }

    public void setCreateDirectories(boolean createDirectories) {
        this.createDirectories = createDirectories;
    }

    /** {@code PRAGMA journal_mode}. Default WAL: readers do not block the writer. In-memory databases ignore it. */
    public SQLite.JournalMode getJournalMode() {
        return journalMode;
    }

    public void setJournalMode(SQLite.JournalMode journalMode) {
        this.journalMode = journalMode;
    }

    /** {@code PRAGMA synchronous}. Default NORMAL, safe with WAL; FULL also survives power loss. */
    public SQLite.Synchronous getSynchronous() {
        return synchronous;
    }

    public void setSynchronous(SQLite.Synchronous synchronous) {
        this.synchronous = synchronous;
    }

    /** {@code PRAGMA foreign_keys}. Default true (SQLite's own default is off). */
    public boolean isForeignKeys() {
        return foreignKeys;
    }

    public void setForeignKeys(boolean foreignKeys) {
        this.foreignKeys = foreignKeys;
    }

    /** How long a connection waits for another connection's write lock before failing. Default 5s. */
    public Duration getBusyTimeout() {
        return busyTimeout;
    }

    public void setBusyTimeout(Duration busyTimeout) {
        this.busyTimeout = busyTimeout;
    }

    /** Prepared statements cached per connection. Default 64; 0 disables the cache. */
    public int getStatementCacheSize() {
        return statementCacheSize;
    }

    public void setStatementCacheSize(int statementCacheSize) {
        this.statementCacheSize = statementCacheSize;
    }

    /** Further {@code PRAGMA name = value} run on each new connection. Names may use - for _. */
    public Map<String, String> getPragmas() {
        return pragmas;
    }

    public void setPragmas(Map<String, String> pragmas) {
        this.pragmas = new LinkedHashMap<>(pragmas);
    }

    /**
     * Run-time limits ({@code sqlite3_limit}) set on each new connection, by {@link SQLite.Limit} name in any case,
     * with - for _: {@code length}, {@code sql-length}, {@code column}, {@code expr-depth}, {@code attached}, ...
     */
    public Map<String, Integer> getLimits() {
        return limits;
    }

    public void setLimits(Map<String, Integer> limits) {
        this.limits = new LinkedHashMap<>(limits);
    }

    /**
     * Other ffm-sqlite-jdbc driver properties ({@code date_class}, {@code integer_objects}, ...). Keys the typed
     * settings or {@link #getPragmas()} own are rejected at startup.
     */
    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = new LinkedHashMap<>(properties);
    }

    /** Pool size. Default 10. SQLite runs one write at a time; extra connections serve concurrent reads. */
    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    /** Idle connections kept open. Default (-1): the maximum pool size, a fixed pool. */
    public int getMinimumIdle() {
        return minimumIdle;
    }

    public void setMinimumIdle(int minimumIdle) {
        this.minimumIdle = minimumIdle;
    }

    /** How long a caller waits for a free pooled connection. Default 30s. */
    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    /** How long a connection above {@link #getMinimumIdle()} may sit idle. Default 10m. */
    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    /**
     * Maximum age of a pooled connection; 0 (the default) keeps connections for the pool's life. A local file has
     * no server to time out on, and keeping connections keeps their page and statement caches warm.
     */
    public Duration getMaxLifetime() {
        return maxLifetime;
    }

    public void setMaxLifetime(Duration maxLifetime) {
        this.maxLifetime = maxLifetime;
    }
}
