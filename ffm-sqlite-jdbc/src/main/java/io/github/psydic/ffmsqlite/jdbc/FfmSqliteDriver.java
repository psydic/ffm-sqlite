package io.github.psydic.ffmsqlite.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * The JDBC driver. URLs: {@code jdbc:ffmsqlite:app.db}, {@code jdbc:ffmsqlite::memory:},
 * {@code jdbc:ffmsqlite:file:app.db?mode=ro}, with options as query parameters or Properties
 * ({@code journal_mode}, {@code foreign_keys}, {@code busy_timeout}, {@code date_class}, …; see README).
 *
 * <p>To take over existing {@code jdbc:sqlite:} URLs without editing them, start the JVM with
 * {@code -Dffmsqlite.jdbc.acceptSqliteUrls=true} (and remove sqlite-jdbc from the classpath, or this driver may
 * not be the one DriverManager picks).
 */
public final class FfmSqliteDriver implements Driver {
    public static final String PREFIX = "jdbc:ffmsqlite:";
    static final String SQLITE_PREFIX = "jdbc:sqlite:";
    static final int MAJOR = 0;
    static final int MINOR = 1;

    static {
        try {
            DriverManager.registerDriver(new FfmSqliteDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && (url.startsWith(PREFIX)
                || (Boolean.getBoolean("ffmsqlite.jdbc.acceptSqliteUrls") && url.startsWith(SQLITE_PREFIX)));
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null; // JDBC: let DriverManager try other drivers
        }
        return new JdbcConnection(url, JdbcConfig.parse(url, info));
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        String[][] known = {
                {"journal_mode", "DELETE, TRUNCATE, PERSIST, MEMORY, WAL or OFF"},
                {"synchronous", "OFF, NORMAL, FULL or EXTRA"},
                {"foreign_keys", "true to enforce foreign keys"},
                {"busy_timeout", "milliseconds to wait for locks (default 3000)"},
                {"readonly", "true to open read-only"},
                {"statement_cache_size", "prepared statements cached per connection (default 64)"},
                {"date_class", "INTEGER (epoch ms, default), TEXT or REAL: how java.sql dates are stored"},
                {"date_precision", "MILLISECONDS (default) or SECONDS for INTEGER dates"},
                {"date_string_format", "pattern for TEXT dates (default yyyy-MM-dd HH:mm:ss.SSS)"},
                {"integer_objects", "true (default): getObject returns Integer for integers that fit"},
                {"jdbc.get_generated_keys", "true (default): getGeneratedKeys after INSERT"},
        };
        DriverPropertyInfo[] out = new DriverPropertyInfo[known.length];
        for (int i = 0; i < known.length; i++) {
            out[i] = new DriverPropertyInfo(known[i][0], info == null ? null : info.getProperty(known[i][0]));
            out[i].description = known[i][1];
        }
        return out;
    }

    @Override
    public int getMajorVersion() {
        return MAJOR;
    }

    @Override
    public int getMinorVersion() {
        return MINOR;
    }

    /** Not fully JDBC compliant (SQLite has no stored procedures, scrollable cursors, …), like sqlite-jdbc. */
    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw Errors.unsupported("getParentLogger");
    }
}
