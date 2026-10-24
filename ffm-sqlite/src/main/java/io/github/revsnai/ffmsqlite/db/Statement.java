package io.github.revsnai.ffmsqlite.db;

/**
 * Executes literal SQL, in the role of {@code java.sql.Statement}. Accepts scripts: several
 * {@code ;}-separated statements run in order. Statements compiled here are not cached; use
 * {@link PreparedStatement} for anything executed repeatedly.
 */
public final class Statement implements AutoCloseable, Connection.Child, ResultSet.Owner {
    private final Connection conn;
    private ResultSet current;
    private long updateCount = -1;
    private int queryTimeoutMillis;
    private boolean closed;

    Statement(Connection conn) {
        this.conn = conn;
    }

    /** Runs {@code sql}; its final statement must be a query, whose rows the returned ResultSet iterates. */
    public ResultSet executeQuery(String sql) throws SQLiteException {
        if (!execute(sql)) {
            throw new SQLiteException("statement does not return a result set: " + sql);
        }
        return current;
    }

    /** Runs {@code sql} and returns the rows changed by its last statement (0 for DDL). */
    public int executeUpdate(String sql) throws SQLiteException {
        return Math.toIntExact(executeLargeUpdate(sql));
    }

    public long executeLargeUpdate(String sql) throws SQLiteException {
        checkOpen();
        closeCurrent();
        updateCount = Math.max(conn.runScript(sql, false, queryTimeoutMillis).updateCount(), 0);
        return updateCount;
    }

    /**
     * Runs {@code sql}. Returns {@code true} if the final statement is a query, whose rows are
     * available from {@link #getResultSet()}; otherwise {@code false}, with {@link #getUpdateCount()}.
     */
    public boolean execute(String sql) throws SQLiteException {
        checkOpen();
        closeCurrent();
        Connection.ScriptResult r = conn.runScript(sql, true, queryTimeoutMillis);
        if (r.query() != null) {
            current = new ResultSet(this, conn, r.query(), queryTimeoutMillis);
            updateCount = -1;
            return true;
        }
        updateCount = Math.max(r.updateCount(), 0);
        return false;
    }

    public ResultSet getResultSet() throws SQLiteException {
        checkOpen();
        return current;
    }

    /** The update count of the last execution; -1 if it produced a ResultSet. */
    public int getUpdateCount() throws SQLiteException {
        checkOpen();
        return (int) updateCount;
    }

    /**
     * Limits each call into SQLite for this statement (an execute, or one {@code ResultSet.next()}) to
     * {@code seconds}; 0 (default) means no limit. Unlike JDBC drivers that only bound lock waiting, this
     * interrupts running work too, failing with {@link SQLiteTimeoutException} (the statement's changes are
     * rolled back). Precision is a few milliseconds of SQLite work, or the progress handler's interval.
     */
    public void setQueryTimeout(int seconds) throws SQLiteException {
        if (seconds < 0) {
            throw new IllegalArgumentException("seconds must be >= 0");
        }
        setQueryTimeout(java.time.Duration.ofSeconds(seconds));
    }

    /** As {@link #setQueryTimeout(int)}, with millisecond resolution. */
    public void setQueryTimeout(java.time.Duration timeout) throws SQLiteException {
        checkOpen();
        queryTimeoutMillis = Connection.timeoutMillis(timeout);
    }

    /** The timeout in whole seconds, as JDBC (rounded up; 0 = none). */
    public int getQueryTimeout() throws SQLiteException {
        checkOpen();
        return (queryTimeoutMillis + 999) / 1000;
    }

    /**
     * Aborts the statement currently running on this statement's connection; safe to call from another thread.
     * The aborted call fails with SQLITE_INTERRUPT (9). Note that SQLite interrupts every statement running on
     * the connection at that moment.
     */
    public void cancel() {
        conn.interrupt();
    }

    public Connection getConnection() {
        return conn;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (!closed) {
            conn.checkNotInHook();
            closeFromConnection();
            conn.unregister(this);
        }
    }

    @Override
    public void closeFromConnection() {
        closeCurrent();
        closed = true;
    }

    @Override
    public void resultSetClosed(ResultSet rs, Stmt stmt) {
        stmt.finalizeQuietly();
        if (rs == current) {
            current = null;
        }
    }

    private void closeCurrent() {
        if (current != null) {
            current.close();
        }
    }

    private void checkOpen() throws SQLiteException {
        if (closed) {
            throw new SQLiteException("statement is closed");
        }
        conn.checkOpen();
    }
}
