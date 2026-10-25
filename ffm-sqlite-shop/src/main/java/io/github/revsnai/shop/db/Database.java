package io.github.revsnai.shop.db;

import io.github.revsnai.ffmsqlite.micronaut.FfmSqliteDatabase;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientException;

/**
 * Lesson 1: connecting. The application's only entry point to SQLite, built on the two pools that
 * micronaut-starter-ffm-sqlite creates from {@code ffm-sqlite.datasources.writer} and {@code .reader} in
 * {@code application.yml}.
 *
 * <p><b>Why two pools.</b> SQLite allows many readers but one writer at a time. In WAL mode readers never block
 * the writer and the writer never blocks readers, but two connections that both want to write make one of them
 * wait, or fail with {@code SQLITE_BUSY}. So:
 * <ul>
 *   <li>the <b>writer</b> pool has exactly one connection: writes queue in the pool (fair, no busy errors);</li>
 *   <li>the <b>reader</b> pool has several, opened with {@code query_only}, so a write sent there by mistake
 *       fails at once instead of competing with the writer.</li>
 * </ul>
 *
 * <p><b>Configuration</b> lives in {@code application.yml}: the starter applies the PRAGMAs to every new
 * connection, so pooled connections are all configured the same way, and opens both pools at startup.
 */
@Singleton
public final class Database {
    private final FfmSqliteDatabase writer;
    private final FfmSqliteDatabase reader;

    public Database(@Named("writer") FfmSqliteDatabase writer, @Named("reader") FfmSqliteDatabase reader) {
        this.writer = writer;
        this.reader = reader;
    }

    /** The database file, as configured. */
    public Path file() {
        return Path.of(writer.getPath());
    }

    /**
     * Lesson 2: transactions. Runs {@code work} in one write transaction on the writer connection: commit on
     * success, rollback on any exception. A lock conflict (SQLITE_BUSY or SQLITE_LOCKED, which ffm-sqlite-jdbc
     * reports as {@link SQLTransientException} with SQLState 40001) is retried, since the whole unit is rerun.
     * Other transient errors are not: a query timeout (also a SQLTransientException) or a pool timeout would just
     * repeat, multiplying the wait.
     *
     * <p>Micronaut's {@code @Transactional} (with micronaut-data) would do the commit/rollback part; this
     * application keeps it explicit so the database code stays in view, and adds the retry.
     */
    public <T> T write(SqlWork<T> work) throws SQLException {
        for (int attempt = 1; ; attempt++) {
            try (Connection c = writer.getDataSource().getConnection()) {
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
                if (attempt == 3 || !"40001".equals(e.getSQLState())) {
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
        try (Connection c = reader.getDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                return work.run(c);
            } finally {
                c.rollback();              // nothing to commit: ends the read transaction, releases the snapshot
            }
        }
    }

    /**
     * Lesson 9: ffm-sqlite features beyond JDBC. {@link FfmSqliteDatabase#withConnection} lends a pooled writer
     * connection as an {@code io.github.revsnai.ffmsqlite.db.Connection} (what {@code unwrap} on a JDBC connection
     * returns), and SQLite's backup API takes an online backup: a consistent copy, made page by page while readers
     * keep working.
     */
    public void backupTo(Path target) throws SQLException {
        try {
            writer.withConnection(conn -> {
                conn.backup(target.toAbsolutePath().toString());
                return null;
            });
        } catch (io.github.revsnai.ffmsqlite.db.SQLiteException e) {
            // The core library's exceptions are not java.sql exceptions: translate at the boundary.
            throw new SQLException(e.getMessage(), "HY000", e.getErrorCode(), e);
        }
    }
}
