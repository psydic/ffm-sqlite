package io.github.revsnai.ffmsqlite.db;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A compiled statement with parameters, in the role of {@code java.sql.PreparedStatement}.
 * Parameter indexes are 1-based. Values are held in Java until execution, so parameters keep
 * their values across executions (as in JDBC) and {@link #addBatch()} can snapshot them.
 */
public final class PreparedStatement implements AutoCloseable, Connection.Child, ResultSet.Owner {
    private static final byte UNSET = 0, LONG = 1, DOUBLE = 2, TEXT = 3, BLOB = 4, NULL = 5;

    /** Parameter values, stored without boxing. TEXT holds UTF-8 bytes, encoded once at set time. */
    private static final class Params {
        final byte[] kinds;
        final long[] longs;
        final double[] doubles;
        final byte[][] bytes;

        Params(int n) {
            kinds = new byte[n];
            longs = new long[n];
            doubles = new double[n];
            bytes = new byte[n][];
        }

        Params(Params p) {
            kinds = p.kinds.clone();
            longs = p.longs.clone();
            doubles = p.doubles.clone();
            bytes = p.bytes.clone();
        }
    }

    private final Connection conn;
    private final Stmt stmt;
    private final Params params;
    private final List<Params> batch = new ArrayList<>();
    private ResultSet current;
    private long updateCount = -1;
    private int queryTimeoutMillis;
    private boolean closed;

    PreparedStatement(Connection conn, Stmt stmt) {
        this.conn = conn;
        this.stmt = stmt;
        this.params = new Params(stmt.paramCount);
    }

    // ------------------------------------------------------------------ parameters

    public void setInt(int parameterIndex, int x) throws SQLiteException {
        setLong(parameterIndex, x);
    }

    public void setLong(int parameterIndex, long x) throws SQLiteException {
        int i = param(parameterIndex);
        params.kinds[i] = LONG;
        params.longs[i] = x;
    }

    public void setBoolean(int parameterIndex, boolean x) throws SQLiteException {
        setLong(parameterIndex, x ? 1 : 0);
    }

    public void setDouble(int parameterIndex, double x) throws SQLiteException {
        int i = param(parameterIndex);
        params.kinds[i] = DOUBLE;
        params.doubles[i] = x;
    }

    public void setFloat(int parameterIndex, float x) throws SQLiteException {
        setDouble(parameterIndex, x);
    }

    public void setString(int parameterIndex, String x) throws SQLiteException {
        int i = param(parameterIndex);
        params.kinds[i] = x == null ? NULL : TEXT;
        params.bytes[i] = x == null ? null : x.getBytes(UTF_8);
    }

    /**
     * Binds {@code x} by reference, as sqlite-jdbc does: SQLite copies it when the statement executes, so do not
     * modify the array between this call and the execution (or {@code executeBatch}) that uses it.
     */
    public void setBytes(int parameterIndex, byte[] x) throws SQLiteException {
        int i = param(parameterIndex);
        params.kinds[i] = x == null ? NULL : BLOB;
        params.bytes[i] = x;
    }

    /** {@code sqlType} is accepted for source compatibility with JDBC and ignored: SQLite NULLs are untyped. */
    public void setNull(int parameterIndex, int sqlType) throws SQLiteException {
        setNull(parameterIndex);
    }

    public void setNull(int parameterIndex) throws SQLiteException {
        int i = param(parameterIndex);
        params.kinds[i] = NULL;
        params.bytes[i] = null;
    }

    /**
     * Bound as TEXT so the value stays exact; declare the column TEXT (not NUMERIC/DECIMAL, whose
     * affinity would convert it to a 15-digit REAL).
     */
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLiteException {
        setString(parameterIndex, x == null ? null : Conversions.decimalText(x));
    }

    /**
     * Integer and floating types, Boolean, String, byte[], BigDecimal, BigInteger, null, and the
     * java.time types LocalDate, LocalTime, LocalDateTime, Instant, OffsetDateTime and ZonedDateTime
     * (stored as ISO-8601 TEXT in SQLite's date format), and UUID (canonical TEXT); see {@link Conversions}.
     */
    public void setObject(int parameterIndex, Object x) throws SQLiteException {
        switch (x) {
            case null -> setNull(parameterIndex);
            case Integer v -> setLong(parameterIndex, v);
            case Long v -> setLong(parameterIndex, v);
            case Short v -> setLong(parameterIndex, v);
            case Byte v -> setLong(parameterIndex, v);
            case Boolean v -> setBoolean(parameterIndex, v);
            case Double v -> setDouble(parameterIndex, v);
            case Float v -> setDouble(parameterIndex, v);
            case String v -> setString(parameterIndex, v);
            case byte[] v -> setBytes(parameterIndex, v);
            case BigDecimal v -> setBigDecimal(parameterIndex, v);
            case BigInteger v when v.bitLength() < Long.SIZE -> setLong(parameterIndex, v.longValue());
            case BigInteger v -> setString(parameterIndex, v.toString());
            default -> {
                byte[] utf8 = Conversions.text(x);
                if (utf8 == null) {
                    throw new SQLiteException("unsupported parameter type: " + x.getClass().getName());
                }
                int i = param(parameterIndex);
                params.kinds[i] = TEXT;
                params.bytes[i] = utf8;
            }
        }
    }

    public void clearParameters() throws SQLiteException {
        checkOpen();
        Arrays.fill(params.kinds, UNSET);
        Arrays.fill(params.bytes, null);
    }

    /** The result columns, available before execution; null if the statement returns no rows. */
    public ResultSetMetaData getMetaData() throws SQLiteException {
        checkOpen();
        return stmt.columnCount == 0 ? null : new ResultSetMetaData(stmt);
    }

    /** Number of parameters the SQL declares ({@code getParameterMetaData().getParameterCount()} in JDBC). */
    public int getParameterCount() {
        return stmt.paramCount;
    }

    // ------------------------------------------------------------------ execution

    public ResultSet executeQuery() throws SQLiteException {
        if (stmt.columnCount == 0) {
            throw new SQLiteException("statement does not return a result set: " + stmt.sql);
        }
        startExecution(params);
        current = new ResultSet(this, conn, stmt, queryTimeoutMillis);
        updateCount = -1;
        return current;
    }

    public int executeUpdate() throws SQLiteException {
        return Math.toIntExact(executeLargeUpdate());
    }

    public long executeLargeUpdate() throws SQLiteException {
        startExecution(params);
        try {
            updateCount = conn.stepToEnd(stmt, stmt.sql, queryTimeoutMillis);
        } finally {
            Native.reset(stmt.ptr);
        }
        return updateCount;
    }

    /** {@code true} if the statement is a query (rows via {@link #getResultSet()}). */
    public boolean execute() throws SQLiteException {
        if (stmt.columnCount > 0) {
            executeQuery();
            return true;
        }
        executeLargeUpdate();
        return false;
    }

    public ResultSet getResultSet() throws SQLiteException {
        checkOpen();
        return current;
    }

    public int getUpdateCount() throws SQLiteException {
        checkOpen();
        return (int) updateCount;
    }

    /** Snapshots the current parameter values as one batch entry. */
    public void addBatch() throws SQLiteException {
        checkOpen();
        checkAllSet(params);
        batch.add(new Params(params));
    }

    public void clearBatch() throws SQLiteException {
        checkOpen();
        batch.clear();
    }

    /**
     * Executes every batch entry in order and returns their update counts. On failure the batch is
     * cleared and the exception names the failing entry; earlier entries stay applied (wrap the batch
     * in a transaction to make it all-or-nothing).
     */
    public int[] executeBatch() throws SQLiteException {
        checkOpen();
        closeCurrent();
        int[] counts = new int[batch.size()];
        try {
            for (int n = 0; n < counts.length; n++) {
                bind(batch.get(n));
                try {
                    counts[n] = (int) conn.stepToEnd(stmt, stmt.sql, queryTimeoutMillis);
                } catch (SQLiteException e) {
                    // Same type (a timeout stays a SQLiteTimeoutException) and same cause (a UDF or listener
                    // failure), as when the statement runs outside a batch; only the message names the entry.
                    String message = "batch entry " + n + " failed: " + e.getMessage();
                    SQLiteException failure = e instanceof SQLiteTimeoutException
                            ? new SQLiteTimeoutException(message, e.getErrorCode(), e.getExtendedErrorCode())
                            : new SQLiteException(message, e.getErrorCode(), e.getExtendedErrorCode());
                    if (e.getCause() != null) {
                        failure.initCause(e.getCause());
                    }
                    failure.setStackTrace(e.getStackTrace());
                    throw failure;
                } finally {
                    Native.reset(stmt.ptr);
                }
            }
        } finally {
            batch.clear();
        }
        return counts;
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
        queryTimeoutMillis = Math.toIntExact(timeout.toMillis());
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

    /** Returns the compiled statement to the connection's cache. */
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
        conn.release(stmt);
    }

    @Override
    public void resultSetClosed(ResultSet rs, Stmt s) {
        Native.reset(s.ptr);
        if (rs == current) {
            current = null;
        }
    }

    // ------------------------------------------------------------------ internals

    private void startExecution(Params p) throws SQLiteException {
        checkOpen();
        closeCurrent();
        bind(p);
    }

    private void bind(Params p) throws SQLiteException {
        checkAllSet(p);
        var ptr = stmt.ptr;
        for (int i = 0; i < p.kinds.length; i++) {
            int rc = switch (p.kinds[i]) {
                case LONG -> Native.bind_int64(ptr, i + 1, p.longs[i]);
                case DOUBLE -> Native.bind_double(ptr, i + 1, p.doubles[i]);
                case TEXT -> Native.bind_text(ptr, i + 1, p.bytes[i]);
                case BLOB -> Native.bind_blob(ptr, i + 1, p.bytes[i]);
                default -> Native.bind_null(ptr, i + 1);
            };
            if (rc != Native.SQLITE_OK) {
                throw SQLiteException.of(conn.db, rc, "bind parameter " + (i + 1));
            }
        }
    }

    private static void checkAllSet(Params p) throws SQLiteException {
        for (int i = 0; i < p.kinds.length; i++) {
            if (p.kinds[i] == UNSET) {
                throw new SQLiteException("parameter " + (i + 1) + " is not set");
            }
        }
    }

    private int param(int parameterIndex) throws SQLiteException {
        checkOpen();
        if (parameterIndex < 1 || parameterIndex > stmt.paramCount) {
            throw new SQLiteException("parameter index " + parameterIndex + " out of range 1.." + stmt.paramCount);
        }
        return parameterIndex - 1;
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
