package io.github.revsnai.ffmsqlite.db;

import java.util.Collections;
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
     * Connection settings.
     *
     * @param readOnly           open with SQLITE_OPEN_READONLY instead of READWRITE|CREATE
     * @param busyTimeoutMillis  how long to wait on a locked database before failing with SQLITE_BUSY
     * @param statementCacheSize prepared statements kept per connection for reuse; 0 disables the cache
     * @param pragmas            {@code PRAGMA name = value} statements run, in order, right after opening;
     *                           values are already-rendered SQL literals (see {@link #withPragma})
     */
    public record Config(boolean readOnly, int busyTimeoutMillis, int statementCacheSize, Map<String, String> pragmas) {
        /** Read-write, 3 s busy timeout (as sqlite-jdbc), 64 cached statements, no PRAGMAs. */
        public static final Config DEFAULT = new Config(false, 3_000, 64);

        private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
        private static final Pattern PLAIN_VALUE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|[+-]?[0-9]+(\\.[0-9]+)?");

        public Config(boolean readOnly, int busyTimeoutMillis, int statementCacheSize) {
            this(readOnly, busyTimeoutMillis, statementCacheSize, Map.of());
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
        }

        public Config withReadOnly(boolean readOnly) {
            return new Config(readOnly, busyTimeoutMillis, statementCacheSize, pragmas);
        }

        public Config withBusyTimeoutMillis(int busyTimeoutMillis) {
            return new Config(readOnly, busyTimeoutMillis, statementCacheSize, pragmas);
        }

        public Config withStatementCacheSize(int statementCacheSize) {
            return new Config(readOnly, busyTimeoutMillis, statementCacheSize, pragmas);
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
            return new Config(readOnly, busyTimeoutMillis, statementCacheSize, next);
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

    /** The version of the loaded native SQLite library, e.g. {@code "3.50.3"}. */
    public static String version() {
        return Native.libversion();
    }

    private SQLite() {
    }
}
