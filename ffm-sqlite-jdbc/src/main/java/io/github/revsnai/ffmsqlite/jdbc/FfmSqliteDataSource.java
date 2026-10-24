package io.github.revsnai.ffmsqlite.jdbc;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * A {@link DataSource} for frameworks and pools: {@code new FfmSqliteDataSource("jdbc:ffmsqlite:app.db")}, or set
 * {@link #setUrl} and {@link #setProperties}. Each {@link #getConnection()} opens a new connection; wrap it in a
 * pool (e.g. HikariCP) to reuse connections. User and password are ignored: SQLite has no authentication.
 */
public class FfmSqliteDataSource implements DataSource {
    private String url;
    private Properties properties = new Properties();
    private PrintWriter logWriter;
    private int loginTimeout;

    public FfmSqliteDataSource() {
    }

    public FfmSqliteDataSource(String url) {
        setUrl(url);
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url.startsWith("jdbc:") ? url : FfmSqliteDriver.PREFIX + url;
    }

    public Properties getProperties() {
        return properties;
    }

    public void setProperties(Properties properties) {
        this.properties = properties == null ? new Properties() : properties;
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (url == null) {
            throw new SQLException("FfmSqliteDataSource: no url set", "08001");
        }
        return new JdbcConnection(url, JdbcConfig.parse(url, properties));
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        this.loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw Errors.unsupported("getParentLogger");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
