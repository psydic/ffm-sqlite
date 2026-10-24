package io.github.psydic.ffmsqlite.jdbc;

import io.github.psydic.ffmsqlite.db.SQLiteException;

import java.math.BigDecimal;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

/** {@code java.sql.ResultSet} over a live ffm-sqlite cursor. Every call synchronizes on the connection. */
final class JdbcResultSet extends AbstractResultSet {
    private final JdbcStatement owner;
    private final JdbcConnection conn;
    final io.github.psydic.ffmsqlite.db.ResultSet rs;
    private final long maxRows;
    private long row;
    private boolean after;
    private boolean closed;

    JdbcResultSet(JdbcStatement owner, JdbcConnection conn, io.github.psydic.ffmsqlite.db.ResultSet rs, long maxRows) {
        this.owner = owner;
        this.conn = conn;
        this.rs = rs;
        this.maxRows = maxRows;
    }

    @Override
    JdbcConfig config() {
        return conn.config;
    }

    @Override
    void checkOpen() throws SQLException {
        if (closed || rs.isClosed()) {
            throw Errors.closed("ResultSet");
        }
    }

    /** On a row (for metadata that looks at the current values). */
    boolean onRow() {
        return !closed && !after && row > 0;
    }

    @Override
    public boolean next() throws SQLException {
        synchronized (conn) {
            checkOpen();
            if (after) {
                return false;
            }
            if (maxRows > 0 && row >= maxRows) {
                after = true;
                return false;
            }
            try {
                if (rs.next()) {
                    row++;
                    return true;
                }
                after = true;
                return false;
            } catch (SQLiteException e) {
                after = true;
                throw Errors.of(e);
            }
        }
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        synchronized (conn) {
            try {
                return rs.getString(columnIndex);
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        synchronized (conn) {
            try {
                return rs.getBoolean(columnIndex);
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        synchronized (conn) {
            try {
                return rs.getInt(columnIndex);
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        synchronized (conn) {
            try {
                return rs.getLong(columnIndex);
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        synchronized (conn) {
            try {
                return rs.getDouble(columnIndex);
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        synchronized (conn) {
            try {
                return rs.getBigDecimal(columnIndex);
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        synchronized (conn) {
            try {
                return rs.getBytes(columnIndex);
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    /** By storage class; with {@code integer_objects} (default) integers that fit are Integer, as in sqlite-jdbc. */
    @Override
    public Object getObject(int columnIndex) throws SQLException {
        Object v = storage(columnIndex);
        if (conn.config.integerObjects && v instanceof Long l && l == (int) (long) l) {
            return (int) (long) l;
        }
        return v;
    }

    @Override
    Object storage(int columnIndex) throws SQLException {
        synchronized (conn) {
            try {
                return rs.getObject(columnIndex);
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    @Override
    io.github.psydic.ffmsqlite.db.ResultSet.StorageClass storageClass(int columnIndex) throws SQLException {
        synchronized (conn) {
            try {
                return rs.getStorageClass(columnIndex);
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    @Override
    <T> T typed(int columnIndex, Class<T> type) throws SQLException {
        synchronized (conn) {
            try {
                return rs.getObject(columnIndex, type);
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    @Override
    public boolean wasNull() throws SQLException {
        synchronized (conn) {
            try {
                return rs.wasNull();
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        synchronized (conn) {
            try {
                return rs.findColumn(columnLabel);
            } catch (SQLiteException e) {
                throw new SQLException(e.getMessage(), "S0022", e);
            }
        }
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        synchronized (conn) {
            checkOpen();
            try {
                return new JdbcResultSetMetaData(conn, rs.getMetaData(), this);
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    @Override
    public Statement getStatement() throws SQLException {
        checkOpen();
        return owner;
    }

    @Override
    public int getRow() throws SQLException {
        checkOpen();
        return after ? 0 : (int) row;
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkOpen();
        return !after && row == 0;
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        checkOpen();
        return after && row > 0;
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkOpen();
        return !after && row == 1;
    }

    @Override
    public void close() throws SQLException {
        synchronized (conn) {
            if (closed) {
                return;
            }
            closed = true;
            rs.close();
            owner.resultSetClosed(this);
        }
    }

    /** Called by the statement when it re-executes or closes. */
    void closeQuietly() {
        synchronized (conn) {
            closed = true;
            rs.close();
        }
    }

    @Override
    public boolean isClosed() {
        return closed || rs.isClosed();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(rs)) {
            return iface.cast(rs);
        }
        return super.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(rs) || super.isWrapperFor(iface);
    }
}
