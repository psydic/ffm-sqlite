package io.github.revsnai.ffmsqlite.db;

import java.math.BigDecimal;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A forward-only cursor over query rows, in the role of {@code java.sql.ResultSet}. Column indexes
 * are 1-based; labels are matched case-insensitively. Getters read straight from SQLite's current
 * row, so values are only valid until the next call to {@link #next()}.
 */
public final class ResultSet implements AutoCloseable {

    /** The statement that produced a result set, told when it closes so it can reset or free the stmt. */
    interface Owner {
        void resultSetClosed(ResultSet rs, Stmt stmt);
    }

    private static final int BEFORE_FIRST = 0, ON_ROW = 1, AFTER_LAST = 2, CLOSED = 3;

    private final Owner owner;
    private final Connection conn;
    private final Stmt stmt;
    private final int timeoutMillis;
    private int state = BEFORE_FIRST;
    private int lastColumn = -1;

    ResultSet(Owner owner, Connection conn, Stmt stmt, int timeoutMillis) {
        this.owner = owner;
        this.conn = conn;
        this.stmt = stmt;
        this.timeoutMillis = timeoutMillis;
    }

    /** Advances to the next row; {@code false} once the rows are exhausted. */
    public boolean next() throws SQLiteException {
        if (state == ON_ROW || state == BEFORE_FIRST) {
            conn.checkOpen();
            boolean first = state == BEFORE_FIRST;
            if (first) {
                conn.beforeStatement();
            }
            conn.clearCallbackFailure();
            int rc;
            boolean capped = false;
            if (timeoutMillis > 0) {
                int previousMillis = conn.deadlineMillis();
                long previous = conn.pushDeadline(timeoutMillis);
                try {
                    rc = conn.step(stmt);
                    capped = conn.busyCapped();
                } finally {
                    conn.popDeadline(previous, previousMillis);
                }
            } else {
                rc = conn.step(stmt);
            }
            if (first) {
                stmt.refreshIfReprepared();
            }
            boolean ok = rc == Native.SQLITE_ROW || rc == Native.SQLITE_DONE;
            if (ok && !conn.hasCallbackFailure()) {
                state = rc == Native.SQLITE_ROW ? ON_ROW : AFTER_LAST;
                return rc == Native.SQLITE_ROW;
            }
            state = AFTER_LAST;
            String context = "next: " + stmt.sql;
            SQLiteException e = ok ? conn.callbackError(context) : conn.stepFailure(rc, context, capped);
            // Stop the statement now: a pending sqlite3_interrupt (from a failed collation) stays in
            // effect, failing other statements too, until no statement is running.
            stmt.reset();
            conn.afterStatementFailure(e);
            throw e;
        }
        if (state == CLOSED) {
            throw new SQLiteException("result set is closed");
        }
        return false;
    }

    // ------------------------------------------------------------------ getters by index

    /** 0 for NULL, as in JDBC; check {@link #wasNull()}. */
    public int getInt(int columnIndex) throws SQLiteException {
        return (int) Conversions.checkRange(Native.column_int64(stmt.ptr, col(columnIndex)), Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
    }

    public long getLong(int columnIndex) throws SQLiteException {
        return Native.column_int64(stmt.ptr, col(columnIndex));
    }

    public double getDouble(int columnIndex) throws SQLiteException {
        return Native.column_double(stmt.ptr, col(columnIndex));
    }

    public float getFloat(int columnIndex) throws SQLiteException {
        return (float) getDouble(columnIndex);
    }

    public boolean getBoolean(int columnIndex) throws SQLiteException {
        return getLong(columnIndex) != 0;
    }

    /** {@code null} for SQL NULL. Non-text values are converted to text by SQLite. */
    public String getString(int columnIndex) throws SQLiteException {
        byte[] utf8 = Native.column_text(stmt.ptr, col(columnIndex));
        return utf8 == null ? null : new String(utf8, UTF_8);
    }

    /** {@code null} for SQL NULL. */
    public byte[] getBytes(int columnIndex) throws SQLiteException {
        int c = col(columnIndex);
        return Native.column_blob(stmt.ptr, c, Native.column_type(stmt.ptr, c) == Native.SQLITE_NULL);
    }

    /** By storage class: {@code Long}, {@code Double}, {@code String}, {@code byte[]} or {@code null}. */
    public Object getObject(int columnIndex) throws SQLiteException {
        int c = col(columnIndex);
        return Conversions.readAny(stmt.ptr, c, Native.column_type(stmt.ptr, c));
    }

    /**
     * The value converted to {@code type}, or {@code null} for SQL NULL (as JDBC 4.2). Supports the
     * wrapper types, {@code String}, {@code byte[]}, {@code BigDecimal}, {@code BigInteger},
     * {@code LocalDate}, {@code LocalTime}, {@code LocalDateTime}, {@code Instant},
     * {@code OffsetDateTime}, {@code ZonedDateTime}, {@code UUID} and {@code Object}; see {@link Conversions}
     * for how dates are read from TEXT, INTEGER and REAL values.
     */
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLiteException {
        return Conversions.read(stmt.ptr, col(columnIndex), type);
    }

    /** Exact for TEXT values; INTEGER and REAL values convert via their shortest decimal form. */
    public BigDecimal getBigDecimal(int columnIndex) throws SQLiteException {
        return getObject(columnIndex, BigDecimal.class);
    }

    // ------------------------------------------------------------------ getters by label

    public int getInt(String columnLabel) throws SQLiteException {
        return getInt(findColumn(columnLabel));
    }

    public long getLong(String columnLabel) throws SQLiteException {
        return getLong(findColumn(columnLabel));
    }

    public double getDouble(String columnLabel) throws SQLiteException {
        return getDouble(findColumn(columnLabel));
    }

    public float getFloat(String columnLabel) throws SQLiteException {
        return getFloat(findColumn(columnLabel));
    }

    public boolean getBoolean(String columnLabel) throws SQLiteException {
        return getBoolean(findColumn(columnLabel));
    }

    public String getString(String columnLabel) throws SQLiteException {
        return getString(findColumn(columnLabel));
    }

    public byte[] getBytes(String columnLabel) throws SQLiteException {
        return getBytes(findColumn(columnLabel));
    }

    public Object getObject(String columnLabel) throws SQLiteException {
        return getObject(findColumn(columnLabel));
    }

    public <T> T getObject(String columnLabel, Class<T> type) throws SQLiteException {
        return getObject(findColumn(columnLabel), type);
    }

    public BigDecimal getBigDecimal(String columnLabel) throws SQLiteException {
        return getBigDecimal(findColumn(columnLabel));
    }

    // ------------------------------------------------------------------ the rest

    /** SQLite's storage classes, as reported by {@code typeof()}. */
    public enum StorageClass { INTEGER, FLOAT, TEXT, BLOB, NULL }

    /** The storage class of the column's value in the current row, without reading the value. */
    public StorageClass getStorageClass(int columnIndex) throws SQLiteException {
        return switch (Native.column_type(stmt.ptr, col(columnIndex))) {
            case Native.SQLITE_INTEGER -> StorageClass.INTEGER;
            case Native.SQLITE_FLOAT -> StorageClass.FLOAT;
            case Native.SQLITE_TEXT -> StorageClass.TEXT;
            case Native.SQLITE_BLOB -> StorageClass.BLOB;
            default -> StorageClass.NULL;
        };
    }

    /** Whether the column read last was SQL NULL. */
    public boolean wasNull() throws SQLiteException {
        if (lastColumn < 0) {
            throw new SQLiteException("no column has been read");
        }
        checkRow();
        return Native.column_type(stmt.ptr, lastColumn) == Native.SQLITE_NULL;
    }

    /** The 1-based index of {@code columnLabel}. */
    public int findColumn(String columnLabel) throws SQLiteException {
        checkNotClosed();
        int idx = stmt.indexOf(columnLabel);
        if (idx < 0) {
            throw new SQLiteException("no such column: " + columnLabel);
        }
        return idx;
    }

    public ResultSetMetaData getMetaData() throws SQLiteException {
        checkNotClosed();
        return new ResultSetMetaData(stmt);
    }

    public boolean isClosed() {
        return state == CLOSED;
    }

    @Override
    public void close() {
        if (state != CLOSED) {
            conn.checkNotInHook();
            conn.checkNotRunningOnClose(stmt);
            state = CLOSED;
            owner.resultSetClosed(this, stmt);
        }
    }

    private int col(int columnIndex) throws SQLiteException {
        checkRow();
        if (columnIndex < 1 || columnIndex > stmt.columnCount) {
            throw new SQLiteException("column index " + columnIndex + " out of range 1.." + stmt.columnCount);
        }
        return lastColumn = columnIndex - 1;
    }

    private void checkRow() throws SQLiteException {
        if (state != ON_ROW) {
            throw new SQLiteException(state == CLOSED ? "result set is closed" : "no current row; call next()");
        }
    }

    private void checkNotClosed() throws SQLiteException {
        if (state == CLOSED) {
            throw new SQLiteException("result set is closed");
        }
    }
}
