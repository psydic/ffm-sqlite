package io.github.revsnai.ffmsqlite.db;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Entry point, in the role of {@code java.sql.DriverManager}.
 *
 * <pre>{@code
 * try (Connection conn = SQLite.getConnection("app.db");
 *      PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM users WHERE id = ?")) {
 *     ps.setInt(1, 42);
 *     try (ResultSet rs = ps.executeQuery()) {
 *         while (rs.next()) {
 *             System.out.println(rs.getInt("id") + " " + rs.getString("name"));
 *         }
 *     }
 * }
 * }</pre>
 */
public final class SQLite {

    public enum JournalMode { DELETE, TRUNCATE, PERSIST, MEMORY, WAL, OFF }

    public enum Synchronous { OFF, NORMAL, FULL, EXTRA }

    /**
     * Per-connection run-time limits ({@code sqlite3_limit}). Each bounds what one statement can make SQLite
     * allocate or compute; a limit can be lowered below the library's compile-time maximum, never raised above it.
     */
    public enum Limit {
        /** Largest string or BLOB (and row), in bytes. Bounds e.g. {@code randomblob()}, {@code zeroblob()}, {@code group_concat()}. */
        LENGTH(0),
        /** Longest SQL text, in bytes. */
        SQL_LENGTH(1),
        /** Most columns in a table, index, view, result set, or ORDER BY / GROUP BY. */
        COLUMN(2),
        /** Deepest expression parse tree. */
        EXPR_DEPTH(3),
        /** Most terms in a compound SELECT. */
        COMPOUND_SELECT(4),
        /** Most virtual-machine instructions in one prepared statement. */
        VDBE_OP(5),
        /** Most arguments to a function. */
        FUNCTION_ARG(6),
        /** Most attached databases. */
        ATTACHED(7),
        /** Longest LIKE or GLOB pattern, in bytes. */
        LIKE_PATTERN_LENGTH(8),
        /** Highest parameter number ({@code ?NNN}). */
        VARIABLE_NUMBER(9),
        /** Deepest trigger recursion. */
        TRIGGER_DEPTH(10),
        /** Most auxiliary worker threads one statement may start. */
        WORKER_THREADS(11);

        final int id;

        Limit(int id) {
            this.id = id;
        }
    }

    /**
     * Connection settings.
     *
     * @param readOnly           open with SQLITE_OPEN_READONLY instead of READWRITE|CREATE
     * @param busyTimeoutMillis  how long to wait on a locked database before failing with SQLITE_BUSY
     * @param statementCacheSize prepared statements kept per connection for reuse; 0 disables the cache
     * @param pragmas            {@code PRAGMA name = value} statements run, in order, right after opening;
     *                           values are already-rendered SQL literals (see {@link #withPragma})
     * @param limits             run-time limits set right after opening, before the PRAGMAs (see {@link #withLimit})
     */
    public record Config(boolean readOnly, int busyTimeoutMillis, int statementCacheSize, Map<String, String> pragmas,
                         Map<Limit, Integer> limits) {
        /** Read-write, 3 s busy timeout (as sqlite-jdbc), 64 cached statements, no PRAGMAs. */
        public static final Config DEFAULT = new Config(false, 3_000, 64);

        /** A PRAGMA name, optionally schema-qualified ({@code main.cache_size}). */
        private static final Pattern NAME = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*\\.)?[A-Za-z_][A-Za-z0-9_]*");
        private static final Pattern PLAIN_VALUE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|[+-]?[0-9]+(\\.[0-9]+)?");

        public Config(boolean readOnly, int busyTimeoutMillis, int statementCacheSize) {
            this(readOnly, busyTimeoutMillis, statementCacheSize, Map.of());
        }

        public Config(boolean readOnly, int busyTimeoutMillis, int statementCacheSize, Map<String, String> pragmas) {
            this(readOnly, busyTimeoutMillis, statementCacheSize, pragmas, Map.of());
        }

        public Config {
            if (busyTimeoutMillis < 0 || statementCacheSize < 0) {
                throw new IllegalArgumentException("busyTimeoutMillis and statementCacheSize must be >= 0");
            }
            for (String name : pragmas.keySet()) {
                if (!NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("not a PRAGMA name: " + name);
                }
            }
            pragmas = Collections.unmodifiableMap(new LinkedHashMap<>(pragmas));
            EnumMap<Limit, Integer> l = new EnumMap<>(Limit.class);
            for (Map.Entry<Limit, Integer> e : limits.entrySet()) {
                if (e.getValue() < 0) {
                    throw new IllegalArgumentException("limit " + e.getKey() + " must be >= 0");
                }
                l.put(Objects.requireNonNull(e.getKey()), e.getValue());
            }
            limits = Collections.unmodifiableMap(l);
        }

        public Config withReadOnly(boolean readOnly) {
            return new Config(readOnly, busyTimeoutMillis, statementCacheSize, pragmas, limits);
        }

        public Config withBusyTimeoutMillis(int busyTimeoutMillis) {
            return new Config(readOnly, busyTimeoutMillis, statementCacheSize, pragmas, limits);
        }

        public Config withStatementCacheSize(int statementCacheSize) {
            return new Config(readOnly, busyTimeoutMillis, statementCacheSize, pragmas, limits);
        }

        /**
         * Adds (or replaces) {@code PRAGMA name = value}, run on every connection opened with this config.
         * Numbers, booleans (ON/OFF), enums and identifiers are used as-is; other strings become quoted SQL
         * string literals, so a value cannot inject SQL.
         */
        public Config withPragma(String name, Object value) {
            Objects.requireNonNull(value, "value");
            String literal = switch (value) {
                case Boolean b -> b ? "ON" : "OFF";
                case Number n -> n.toString();
                case Enum<?> e -> e.name();
                default -> {
                    String s = value.toString();
                    yield PLAIN_VALUE.matcher(s).matches() ? s : "'" + s.replace("'", "''") + "'";
                }
            };
            Map<String, String> next = new LinkedHashMap<>(pragmas);
            next.put(name, literal);
            return new Config(readOnly, busyTimeoutMillis, statementCacheSize, next, limits);
        }

        /** {@code PRAGMA journal_mode}; WAL is ignored by in-memory databases, which always use MEMORY. */
        public Config withJournalMode(JournalMode mode) {
            return withPragma("journal_mode", mode);
        }

        public Config withSynchronous(Synchronous synchronous) {
            return withPragma("synchronous", synchronous);
        }

        /** {@code PRAGMA foreign_keys}: SQLite does not enforce foreign keys unless this is on. */
        public Config withForeignKeys(boolean enforce) {
            return withPragma("foreign_keys", enforce);
        }

        /**
         * Sets a run-time limit ({@code sqlite3_limit}) on every connection opened with this config. Values above the
         * library's compile-time maximum are lowered to it. For example {@code withLimit(Limit.LENGTH, 10_000_000)}
         * makes any statement producing a string or BLOB over 10 MB fail with SQLITE_TOOBIG instead of allocating it.
         */
        public Config withLimit(Limit limit, int value) {
            Map<Limit, Integer> next = new EnumMap<>(Limit.class);
            next.putAll(limits);
            next.put(Objects.requireNonNull(limit, "limit"), value);
            return new Config(readOnly, busyTimeoutMillis, statementCacheSize, pragmas, next);
        }

        /**
         * {@code PRAGMA cache_size = -kib}: the most page cache each connection keeps, per attached database (SQLite's
         * default is 2000 KiB). Pooled connections each have their own cache, so this bounds the pool's share by
         * {@code kib * pool size}.
         */
        public Config withCacheSizeKib(long kib) {
            if (kib <= 0) {
                throw new IllegalArgumentException("kib must be positive");
            }
            return withPragma("cache_size", -kib);
        }
    }

    /** Opens (creating if needed) {@code path}; also accepts {@code ":memory:"}, {@code ""} and {@code file:} URIs. */
    public static Connection getConnection(String path) throws SQLiteException {
        return getConnection(path, Config.DEFAULT);
    }

    public static Connection getConnection(String path, Config config) throws SQLiteException {
        return Connection.open(path, config);
    }

    /**
     * A new in-memory connection holding a copy of {@code image} (bytes from {@link Connection#serialize()} or
     * of a database file). Writes go to memory only; use {@link Connection#serialize()} or
     * {@link Connection#backup(String)} to save them.
     */
    public static Connection getConnection(byte[] image) throws SQLiteException {
        Connection conn = getConnection(":memory:");
        try {
            conn.deserialize(image);
            return conn;
        } catch (SQLiteException | RuntimeException e) {
            conn.close();
            throw e;
        }
    }

    // ------------------------------------------------------------------ process-wide memory limits

    /**
     * Caps the heap memory SQLite allocates in this process ({@code sqlite3_hard_heap_limit64}): an allocation that
     * would go over fails, and the statement needing it fails with SQLITE_NOMEM; the connection stays usable.
     * {@code 0} removes the cap. The cap covers every connection (and every other user of the same native library in
     * the process), not the JVM's own memory.
     *
     * <p>SQLite enforces heap limits only while it tracks memory use. Builds with {@code SQLITE_DEFAULT_MEMSTATUS=0}
     * (sqlite-jdbc's settings) do not; this switches tracking on if no connection has been opened yet, and otherwise
     * fails rather than set a limit that would be ignored. Start the JVM with {@code -Dffmsqlite.memstatus=true} to
     * switch it on up front. A {@code hard_heap_limit} PRAGMA in a {@link Config} gets the same check.
     *
     * @return the previous cap ({@code 0}: none)
     * @throws IllegalStateException         if SQLite does not track memory use and it is too late to switch it on
     * @throws UnsupportedOperationException if the library is older than 3.31.1
     */
    public static long setHardHeapLimit(long bytes) {
        if (!Native.hardHeapLimitAvailable()) {
            throw new UnsupportedOperationException("sqlite3_hard_heap_limit64 needs SQLite 3.31.1 or later, not " + version());
        }
        requireMemoryStatistics(bytes, "hard");
        return Native.hard_heap_limit64(bytes);
    }

    /** The current hard heap limit ({@code 0}: none); {@code 0} too on libraries without one. */
    public static long hardHeapLimit() {
        return Native.hardHeapLimitAvailable() ? Native.hard_heap_limit64(-1) : 0;
    }

    /**
     * Sets SQLite's advisory heap limit ({@code sqlite3_soft_heap_limit64}): above it, page caches reuse their pages
     * instead of growing, and allocations still succeed. Use it below
     * {@link #setHardHeapLimit the hard limit} so that caches shrink before statements start failing. {@code 0}
     * removes it. Needs memory tracking, as the hard limit does.
     *
     * @return the previous limit ({@code 0}: none)
     */
    public static long setSoftHeapLimit(long bytes) {
        requireMemoryStatistics(bytes, "soft");
        return Native.soft_heap_limit64(bytes);
    }

    /** The current soft heap limit ({@code 0}: none). */
    public static long softHeapLimit() {
        return Native.soft_heap_limit64(-1);
    }

    /**
     * Whether SQLite tracks its heap use, which heap limits and {@link #memoryUsed()} need. Asking does not switch
     * it on.
     */
    public static boolean memoryStatisticsEnabled() {
        return Native.memoryStatistics(false);
    }

    /** Bytes SQLite has allocated and not freed, across the process; 0 if {@link #memoryStatisticsEnabled() untracked}. */
    public static long memoryUsed() {
        return Native.memory_used();
    }

    /** The most {@link #memoryUsed()} has been since start or the last reset; 0 if untracked. */
    public static long memoryHighwater(boolean reset) {
        return Native.memory_highwater(reset);
    }

    static void requireMemoryStatistics(long bytes, String which) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be >= 0");
        }
        if (bytes > 0 && !Native.memoryStatistics(true)) {
            throw new IllegalStateException("SQLite would ignore a " + which + " heap limit: the loaded library does not "
                    + "track memory use (built with SQLITE_DEFAULT_MEMSTATUS=0) and a connection is already open, so it is "
                    + "too late to switch tracking on; set the limit before opening any connection, or start the JVM with "
                    + "-Dffmsqlite.memstatus=true");
        }
    }

    /** The version of the loaded native SQLite library, e.g. {@code "3.50.3"}. */
    public static String version() {
        return Native.libversion();
    }

    private SQLite() {
    }
}
