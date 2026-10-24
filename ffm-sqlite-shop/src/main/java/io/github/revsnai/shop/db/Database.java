package io.github.revsnai.shop.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientException;

/**
 * Lesson 1: connecting. The application's only entry point to SQLite, built on two HikariCP pools over
 * {@code jdbc:ffmsqlite:} URLs.
 *
 * <p><b>Why two pools.</b> SQLite allows many readers but one writer at a time. In WAL mode readers never block
 * the writer and the writer never blocks readers, but two connections that both want to write make one of them
 * wait, or fail with {@code SQLITE_BUSY}. So:
 * <ul>
 *   <li>the <b>writer</b> pool has exactly one connection: writes queue in the pool (fair, no busy errors);</li>
 *   <li>the <b>reader</b> pool has several, opened with {@code query_only=true}, so a write sent there by mistake
 *       fails at once instead of competing with the writer.</li>
 * </ul>
 *
 * <p><b>Configuration</b> goes in the URL query (or as driver properties): ffm-sqlite-jdbc applies the PRAGMAs to
 * every new connection, so pooled connections are all configured the same way.
 */
public final class Database implements AutoCloseable {
    private final HikariDataSource writer;
    private final HikariDataSource reader;

    public Database(Path file, int readers) {
        String url = "jdbc:ffmsqlite:" + file.toAbsolutePath();
        // journal_mode=WAL is stored in the file, so setting it on the writer is enough.
        // synchronous=NORMAL is safe with WAL (a power cut can lose the last commits, never corrupt the file).
        // foreign_keys is off by default in SQLite and is per connection: turn it on everywhere.
        // busy_timeout: how long to wait for a lock (e.g. a checkpoint) before failing.
        writer = pool("shop-writer", url + "?journal_mode=WAL&synchronous=NORMAL&foreign_keys=true&busy_timeout=5000", 1);
        reader = pool("shop-reader", url + "?foreign_keys=true&busy_timeout=5000&query_only=true", readers);
    }

    private static HikariDataSource pool(String name, String url, int size) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(name);
        config.setJdbcUrl(url);            // DriverManager finds the driver via META-INF/services/java.sql.Driver
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(size);       // opening a SQLite connection is cheap, but no need to churn
        config.setAutoCommit(true);        // Hikari restores this when a connection comes back to the pool
        return new HikariDataSource(config);
    }

    /**
     * Lesson 2: transactions. Runs {@code work} in one write transaction on the writer connection: commit on
     * success, rollback on any exception. A transient failure ({@link SQLTransientException}: ffm-sqlite-jdbc
     * maps SQLITE_BUSY and SQLITE_LOCKED to it, SQLState 40001) is retried, since the whole unit is rerun.
     */
    public <T> T write(SqlWork<T> work) throws SQLException {
        for (int attempt = 1; ; attempt++) {
            try (Connection c = writer.getConnection()) {
                c.setAutoCommit(false);    // the driver issues BEGIN
                try {
                    T result = work.run(c);
                    c.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            } catch (SQLTransientException e) {
                if (attempt == 3) {
                    throw e;
                }
            }
        }
    }

    /**
     * Runs {@code work} on a reader connection inside one read transaction, so every query in it sees the same
     * snapshot of the database even while the writer commits (WAL gives each read transaction a stable view).
     */
    public <T> T read(SqlWork<T> work) throws SQLException {
        try (Connection c = reader.getConnection()) {
            c.setAutoCommit(false);
            try {
                return work.run(c);
            } finally {
                c.rollback();              // nothing to commit: ends the read transaction, releases the snapshot
            }
        }
    }

    /**
     * Lesson 9: ffm-sqlite features beyond JDBC. Unwraps the writer's JDBC connection to the underlying
     * {@code io.github.revsnai.ffmsqlite.db.Connection} and takes an online backup with SQLite's backup API:
     * a consistent copy, made page by page while readers keep working.
     */
    public void backupTo(Path target) throws SQLException {
        try (Connection c = writer.getConnection()) {
            c.unwrap(io.github.revsnai.ffmsqlite.db.Connection.class).backup(target.toAbsolutePath().toString());
        } catch (io.github.revsnai.ffmsqlite.db.SQLiteException e) {
            // The core library's exceptions are not java.sql exceptions: translate at the boundary.
            throw new SQLException(e.getMessage(), "HY000", e.getErrorCode(), e);
        }
    }

    @Override
    public void close() {
        reader.close();
        writer.close();
    }
}
