package io.github.psydic.ffmsqlite.jdbc;

import io.github.psydic.ffmsqlite.db.SQLiteException;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.BatchUpdateException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code java.sql.PreparedStatement} over an ffm-sqlite prepared statement (served from its statement cache).
 * java.sql date types are stored per {@code date_class}: epoch milliseconds by default, as sqlite-jdbc does.
 */
final class JdbcPreparedStatement extends JdbcStatement implements PreparedStatement {
    private static final Pattern BATCH_ENTRY = Pattern.compile("batch entry (\\d+) failed");

    final io.github.psydic.ffmsqlite.db.PreparedStatement ps;
    private final String sql;
    private final boolean keysRequested;

    JdbcPreparedStatement(JdbcConnection conn, io.github.psydic.ffmsqlite.db.PreparedStatement ps, String sql, boolean keysRequested) {
        super(conn, null);
        this.ps = ps;
        this.sql = sql;
        this.keysRequested = keysRequested;
    }

    private void applyTimeout() throws SQLiteException {
        ps.setQueryTimeout(Duration.ofSeconds(queryTimeoutSeconds));
    }

    // ------------------------------------------------------------------ execution

    @Override
    public ResultSet executeQuery() throws SQLException {
        synchronized (conn) {
            checkOpen();
            closeCurrent();
            try {
                applyTimeout();
                current = new JdbcResultSet(this, conn, ps.executeQuery(), maxRows);
                updateCount = -1;
                return current;
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    @Override
    public int executeUpdate() throws SQLException {
        return (int) executeLargeUpdate();
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        synchronized (conn) {
            checkOpen();
            closeCurrent();
            try {
                applyTimeout();
                long n = ps.executeLargeUpdate();
                captureKey(sql, n, keysRequested);
                updateCount = n;
                return n;
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    @Override
    public boolean execute() throws SQLException {
        synchronized (conn) {
            checkOpen();
            closeCurrent();
            try {
                applyTimeout();
                if (ps.execute()) {
                    current = new JdbcResultSet(this, conn, ps.getResultSet(), maxRows);
                    updateCount = -1;
                    return true;
                }
                updateCount = ps.getUpdateCount();
                captureKey(sql, updateCount, keysRequested);
                return false;
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    @Override
    public void addBatch() throws SQLException {
        synchronized (conn) {
            checkOpen();
            try {
                ps.addBatch();
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    @Override
    public void clearBatch() throws SQLException {
        synchronized (conn) {
            checkOpen();
            try {
                ps.clearBatch();
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    @Override
    public int[] executeBatch() throws SQLException {
        synchronized (conn) {
            checkOpen();
            closeCurrent();
            try {
                applyTimeout();
                return ps.executeBatch();
            } catch (SQLiteException e) {
                // Entries before the failing one were applied; their exact counts are not reported.
                Matcher m = BATCH_ENTRY.matcher(String.valueOf(e.getMessage()));
                int[] done = new int[m.find() ? Integer.parseInt(m.group(1)) : 0];
                Arrays.fill(done, SUCCESS_NO_INFO);
                SQLException cause = Errors.of(e);
                throw new BatchUpdateException(e.getMessage(), cause.getSQLState(), cause.getErrorCode(), done, cause);
            }
        }
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        return Arrays.stream(executeBatch()).asLongStream().toArray();
    }

    // JDBC: the SQL-taking Statement methods may not be called on a PreparedStatement.

    private static SQLException notOnPrepared() {
        return new SQLException("this method cannot be called on a PreparedStatement", "HY000");
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        throw notOnPrepared();
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        throw notOnPrepared();
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        throw notOnPrepared();
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        throw notOnPrepared();
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        throw notOnPrepared();
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        throw notOnPrepared();
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        throw notOnPrepared();
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        throw notOnPrepared();
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        throw notOnPrepared();
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        throw notOnPrepared();
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        throw notOnPrepared();
    }

    @Override
    public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        throw notOnPrepared();
    }

    @Override
    public long executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException {
        throw notOnPrepared();
    }

    @Override
    public long executeLargeUpdate(String sql, String[] columnNames) throws SQLException {
        throw notOnPrepared();
    }

    // ------------------------------------------------------------------ metadata

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        synchronized (conn) {
            checkOpen();
            try {
                io.github.psydic.ffmsqlite.db.ResultSetMetaData md = ps.getMetaData();
                return md == null ? null : new JdbcResultSetMetaData(conn, md, null);
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        synchronized (conn) {
            checkOpen();
            return new JdbcParameterMetaData(ps.getParameterCount());
        }
    }

    // ------------------------------------------------------------------ parameters

    private interface Binder {
        void bind() throws SQLiteException;
    }

    private void bind(Binder b) throws SQLException {
        synchronized (conn) {
            checkOpen();
            try {
                b.bind();
            } catch (SQLiteException e) {
                throw new SQLException(e.getMessage(), e.getErrorCode() == 0 ? "07009" : "HY000", e.getErrorCode(), e);
            }
        }
    }

    @Override
    public void clearParameters() throws SQLException {
        bind(ps::clearParameters);
    }

    @Override
    public void setNull(int i, int sqlType) throws SQLException {
        bind(() -> ps.setNull(i));
    }

    @Override
    public void setNull(int i, int sqlType, String typeName) throws SQLException {
        bind(() -> ps.setNull(i));
    }

    @Override
    public void setBoolean(int i, boolean x) throws SQLException {
        bind(() -> ps.setBoolean(i, x));
    }

    @Override
    public void setByte(int i, byte x) throws SQLException {
        bind(() -> ps.setInt(i, x));
    }

    @Override
    public void setShort(int i, short x) throws SQLException {
        bind(() -> ps.setInt(i, x));
    }

    @Override
    public void setInt(int i, int x) throws SQLException {
        synchronized (conn) {
            checkOpen();
            try {
                ps.setInt(i, x);
            } catch (SQLiteException e) {
                throw new SQLException(e.getMessage(), "07009", e);
            }
        }
    }

    @Override
    public void setLong(int i, long x) throws SQLException {
        synchronized (conn) {
            checkOpen();
            try {
                ps.setLong(i, x);
            } catch (SQLiteException e) {
                throw new SQLException(e.getMessage(), "07009", e);
            }
        }
    }

    @Override
    public void setFloat(int i, float x) throws SQLException {
        bind(() -> ps.setDouble(i, x));
    }

    @Override
    public void setDouble(int i, double x) throws SQLException {
        bind(() -> ps.setDouble(i, x));
    }

    @Override
    public void setBigDecimal(int i, BigDecimal x) throws SQLException {
        bind(() -> ps.setBigDecimal(i, x));
    }

    @Override
    public void setString(int i, String x) throws SQLException {
        synchronized (conn) {
            checkOpen();
            try {
                ps.setString(i, x);
            } catch (SQLiteException e) {
                throw new SQLException(e.getMessage(), "07009", e);
            }
        }
    }

    @Override
    public void setNString(int i, String value) throws SQLException {
        setString(i, value);
    }

    @Override
    public void setBytes(int i, byte[] x) throws SQLException {
        bind(() -> ps.setBytes(i, x));
    }

    // ------------------------------------------------------------------ java.sql dates (date_class)

    /** Stores an instant as sqlite-jdbc does for {@code date_class}: INTEGER epoch ms/s, REAL Julian day, or TEXT. */
    private void bindMillis(int i, long millis, Calendar cal) throws SQLException {
        JdbcConfig c = conn.config;
        bind(() -> {
            switch (c.dateClass) {
                case INTEGER -> ps.setLong(i, c.datePrecision == JdbcConfig.DatePrecision.SECONDS ? Math.floorDiv(millis, 1000) : millis);
                case REAL -> ps.setDouble(i, millis / 86_400_000d + 2440587.5);
                case TEXT -> ps.setString(i, c.dateFormatter.withZone(AbstractResultSet.zone(cal)).format(Instant.ofEpochMilli(millis)));
            }
        });
    }

    @Override
    public void setDate(int i, Date x) throws SQLException {
        setDate(i, x, null);
    }

    @Override
    public void setDate(int i, Date x, Calendar cal) throws SQLException {
        if (x == null) {
            setNull(i, Types.DATE);
        } else {
            bindMillis(i, x.getTime(), cal);
        }
    }

    @Override
    public void setTime(int i, Time x) throws SQLException {
        setTime(i, x, null);
    }

    @Override
    public void setTime(int i, Time x, Calendar cal) throws SQLException {
        if (x == null) {
            setNull(i, Types.TIME);
        } else {
            bindMillis(i, x.getTime(), cal);
        }
    }

    @Override
    public void setTimestamp(int i, Timestamp x) throws SQLException {
        setTimestamp(i, x, null);
    }

    @Override
    public void setTimestamp(int i, Timestamp x, Calendar cal) throws SQLException {
        if (x == null) {
            setNull(i, Types.TIMESTAMP);
        } else {
            bindMillis(i, x.getTime(), cal);
        }
    }

    // ------------------------------------------------------------------ objects

    @Override
    public void setObject(int i, Object x) throws SQLException {
        switch (x) {
            case null -> setNull(i, Types.NULL);
            case java.util.Date d -> bindMillis(i, d.getTime(), null); // includes java.sql.Date/Time/Timestamp
            case Calendar c -> bindMillis(i, c.getTimeInMillis(), c);
            case Character c -> setString(i, String.valueOf(c));
            case Blob b -> setBlob(i, b);
            case Clob c -> setClob(i, c);
            case URL u -> setString(i, u.toString());
            default -> bind(() -> ps.setObject(i, x)); // numbers, String, byte[], BigDecimal/BigInteger, java.time, UUID
        }
    }

    @Override
    public void setObject(int i, Object x, int targetSqlType) throws SQLException {
        setObject(i, x, targetSqlType, 0);
    }

    /** Converts {@code x} to {@code targetSqlType} first, as JDBC specifies. */
    @Override
    public void setObject(int i, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        if (x == null) {
            setNull(i, targetSqlType);
            return;
        }
        switch (targetSqlType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB,
                 Types.NCLOB -> {
                if (x instanceof Clob c) {
                    setClob(i, c);
                } else {
                    setString(i, x instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : x.toString());
                }
            }
            case Types.BIT, Types.BOOLEAN -> setBoolean(i, toBoolean(x));
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> setLong(i, toNumber(x).longValue());
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> setDouble(i, toNumber(x).doubleValue());
            case Types.DECIMAL, Types.NUMERIC -> {
                BigDecimal d = x instanceof BigDecimal b ? b : new BigDecimal(toNumber(x).toString());
                setBigDecimal(i, targetSqlType == Types.DECIMAL && scaleOrLength > 0 ? d.setScale(scaleOrLength, java.math.RoundingMode.HALF_UP) : d);
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                if (x instanceof Blob b) {
                    setBlob(i, b);
                } else if (x instanceof byte[] b) {
                    setBytes(i, b);
                } else {
                    setBytes(i, x.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            case Types.NULL -> setNull(i, Types.NULL);
            default -> setObject(i, x); // DATE/TIME/TIMESTAMP (java.util.Date or java.time), OTHER, JAVA_OBJECT, …
        }
    }

    @Override
    public void setObject(int i, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException {
        setObject(i, x, vendorType(targetSqlType), scaleOrLength);
    }

    @Override
    public void setObject(int i, Object x, SQLType targetSqlType) throws SQLException {
        setObject(i, x, vendorType(targetSqlType), 0);
    }

    private static int vendorType(SQLType type) throws SQLException {
        if (type == null || type.getVendorTypeNumber() == null) {
            throw Errors.unsupported("SQLType " + type);
        }
        return type.getVendorTypeNumber();
    }

    private static boolean toBoolean(Object x) throws SQLException {
        return switch (x) {
            case Boolean b -> b;
            case Number n -> n.doubleValue() != 0;
            case String s -> s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("y") || s.equalsIgnoreCase("yes");
            default -> throw new SQLException("cannot convert " + x.getClass().getName() + " to BOOLEAN", "22018");
        };
    }

    private static Number toNumber(Object x) throws SQLException {
        try {
            return switch (x) {
                case Number n -> n;
                case Boolean b -> b ? 1 : 0;
                case String s -> new BigDecimal(s.strip());
                case Character c -> new BigInteger(String.valueOf(c));
                default -> throw new SQLException("cannot convert " + x.getClass().getName() + " to a number", "22018");
            };
        } catch (NumberFormatException e) {
            throw new SQLException("not a number: " + x, "22018", e);
        }
    }

    // ------------------------------------------------------------------ streams and LOBs (read fully, then bound)

    private static byte[] read(InputStream in, long length) throws SQLException {
        try {
            return length < 0 ? in.readAllBytes() : in.readNBytes(Math.toIntExact(length));
        } catch (IOException e) {
            throw new SQLException("reading the stream failed: " + e.getMessage(), "HY000", e);
        }
    }

    private static String read(Reader reader, long length) throws SQLException {
        try {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            long remaining = length < 0 ? Long.MAX_VALUE : length;
            int n;
            while (remaining > 0 && (n = reader.read(buf, 0, (int) Math.min(buf.length, remaining))) > 0) {
                sb.append(buf, 0, n);
                remaining -= n;
            }
            return sb.toString();
        } catch (IOException e) {
            throw new SQLException("reading the reader failed: " + e.getMessage(), "HY000", e);
        }
    }

    @Override
    public void setBinaryStream(int i, InputStream x, int length) throws SQLException {
        setBinaryStream(i, x, (long) length);
    }

    @Override
    public void setBinaryStream(int i, InputStream x, long length) throws SQLException {
        if (x == null) {
            setNull(i, Types.BLOB);
        } else {
            setBytes(i, read(x, length));
        }
    }

    @Override
    public void setBinaryStream(int i, InputStream x) throws SQLException {
        setBinaryStream(i, x, -1L);
    }

    @Override
    public void setAsciiStream(int i, InputStream x, int length) throws SQLException {
        setAsciiStream(i, x, (long) length);
    }

    @Override
    public void setAsciiStream(int i, InputStream x, long length) throws SQLException {
        if (x == null) {
            setNull(i, Types.VARCHAR);
        } else {
            setString(i, new String(read(x, length), StandardCharsets.US_ASCII));
        }
    }

    @Override
    public void setAsciiStream(int i, InputStream x) throws SQLException {
        setAsciiStream(i, x, -1L);
    }

    @Override
    @Deprecated
    public void setUnicodeStream(int i, InputStream x, int length) throws SQLException {
        if (x == null) {
            setNull(i, Types.VARCHAR);
        } else {
            setString(i, new String(read(x, length), StandardCharsets.UTF_8));
        }
    }

    @Override
    public void setCharacterStream(int i, Reader reader, int length) throws SQLException {
        setCharacterStream(i, reader, (long) length);
    }

    @Override
    public void setCharacterStream(int i, Reader reader, long length) throws SQLException {
        if (reader == null) {
            setNull(i, Types.VARCHAR);
        } else {
            setString(i, read(reader, length));
        }
    }

    @Override
    public void setCharacterStream(int i, Reader reader) throws SQLException {
        setCharacterStream(i, reader, -1L);
    }

    @Override
    public void setNCharacterStream(int i, Reader value, long length) throws SQLException {
        setCharacterStream(i, value, length);
    }

    @Override
    public void setNCharacterStream(int i, Reader value) throws SQLException {
        setCharacterStream(i, value, -1L);
    }

    @Override
    public void setBlob(int i, Blob x) throws SQLException {
        if (x == null) {
            setNull(i, Types.BLOB);
        } else {
            setBytes(i, x.getBytes(1, Math.toIntExact(x.length())));
        }
    }

    @Override
    public void setBlob(int i, InputStream inputStream, long length) throws SQLException {
        setBinaryStream(i, inputStream, length);
    }

    @Override
    public void setBlob(int i, InputStream inputStream) throws SQLException {
        setBinaryStream(i, inputStream, -1L);
    }

    @Override
    public void setClob(int i, Clob x) throws SQLException {
        if (x == null) {
            setNull(i, Types.CLOB);
        } else {
            setString(i, x.getSubString(1, Math.toIntExact(x.length())));
        }
    }

    @Override
    public void setClob(int i, Reader reader, long length) throws SQLException {
        setCharacterStream(i, reader, length);
    }

    @Override
    public void setClob(int i, Reader reader) throws SQLException {
        setCharacterStream(i, reader, -1L);
    }

    @Override
    public void setNClob(int i, NClob value) throws SQLException {
        setClob(i, value);
    }

    @Override
    public void setNClob(int i, Reader reader, long length) throws SQLException {
        setCharacterStream(i, reader, length);
    }

    @Override
    public void setNClob(int i, Reader reader) throws SQLException {
        setCharacterStream(i, reader, -1L);
    }

    @Override
    public void setURL(int i, URL x) throws SQLException {
        if (x == null) {
            setNull(i, Types.VARCHAR);
        } else {
            setString(i, x.toString());
        }
    }

    @Override
    public void setRef(int i, Ref x) throws SQLException {
        throw Errors.unsupported("REF");
    }

    @Override
    public void setArray(int i, Array x) throws SQLException {
        throw Errors.unsupported("SQL arrays");
    }

    @Override
    public void setRowId(int i, RowId x) throws SQLException {
        throw Errors.unsupported("RowId");
    }

    @Override
    public void setSQLXML(int i, SQLXML xmlObject) throws SQLException {
        throw Errors.unsupported("SQLXML");
    }

    @Override
    void closeUnderlying() {
        ps.close();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(ps)) {
            return iface.cast(ps);
        }
        return super.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(ps) || super.isWrapperFor(iface);
    }
}
