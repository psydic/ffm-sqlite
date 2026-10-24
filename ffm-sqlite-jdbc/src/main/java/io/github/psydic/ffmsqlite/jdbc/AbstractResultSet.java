package io.github.psydic.ffmsqlite.jdbc;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Calendar;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Everything of {@code java.sql.ResultSet} that can be expressed through a few core getters: column-label
 * variants, java.sql date types (with sqlite-jdbc's {@code date_class} semantics), streams and LOBs, JDBC 4.2
 * {@code getObject(i, Class)}, and the forward-only / read-only refusals.
 */
abstract class AbstractResultSet implements ResultSet {
    private static final Pattern TRAILING_OFFSET = Pattern.compile(".*\\d(Z|z|[+-]\\d\\d:\\d\\d)\\s*$");
    /** Time of day without a date: what setObject(LocalTime) stores and SQLite's time() returns. */
    private static final Pattern TIME_ONLY = Pattern.compile("\\s*\\d\\d:\\d\\d(:\\d\\d(\\.\\d+)?)?\\s*");
    private int fetchSize;

    // ------------------------------------------------------------------ the core, per subclass

    abstract JdbcConfig config();

    /** The stored value by SQLite storage class: Long, Double, String, byte[] or null (no conversion). */
    abstract Object storage(int columnIndex) throws SQLException;

    /** ffm-sqlite's own typed read (java.time from TEXT, UUID, BigInteger, …). */
    abstract <T> T typed(int columnIndex, Class<T> type) throws SQLException;

    abstract void checkOpen() throws SQLException;

    /** The value's storage class without materializing it; subclasses may answer more cheaply than storage(). */
    io.github.psydic.ffmsqlite.db.ResultSet.StorageClass storageClass(int columnIndex) throws SQLException {
        return switch (storage(columnIndex)) {
            case null -> io.github.psydic.ffmsqlite.db.ResultSet.StorageClass.NULL;
            case Long l -> io.github.psydic.ffmsqlite.db.ResultSet.StorageClass.INTEGER;
            case Double d -> io.github.psydic.ffmsqlite.db.ResultSet.StorageClass.FLOAT;
            case String str -> io.github.psydic.ffmsqlite.db.ResultSet.StorageClass.TEXT;
            default -> io.github.psydic.ffmsqlite.db.ResultSet.StorageClass.BLOB;
        };
    }

    // ------------------------------------------------------------------ derived getters

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return (byte) getInt(columnIndex);
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return (short) getInt(columnIndex);
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return (float) getDouble(columnIndex);
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        return getString(columnIndex);
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        BigDecimal v = getBigDecimal(columnIndex);
        return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ java.sql dates

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        Long ms = millis(columnIndex, null);
        return ms == null ? null : new Date(ms);
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        Long ms = millis(columnIndex, cal);
        return ms == null ? null : new Date(ms);
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        Long ms = millis(columnIndex, null);
        return ms == null ? null : new Time(ms);
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        Long ms = millis(columnIndex, cal);
        return ms == null ? null : new Time(ms);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        Long ms = millis(columnIndex, null);
        return ms == null ? null : new Timestamp(ms);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        Long ms = millis(columnIndex, cal);
        return ms == null ? null : new Timestamp(ms);
    }

    static ZoneId zone(Calendar cal) {
        return cal == null ? ZoneId.systemDefault() : cal.getTimeZone().toZoneId();
    }

    /**
     * Epoch milliseconds of a stored date, as sqlite-jdbc reads them: INTEGER as epoch ms (or s with
     * {@code date_precision=SECONDS}), REAL as a Julian day, TEXT in {@code date_string_format} or any SQLite
     * time format (local time in the calendar's zone unless the text has an offset).
     */
    Long millis(int columnIndex, Calendar cal) throws SQLException {
        Object v = storage(columnIndex);
        JdbcConfig c = config();
        return switch (v) {
            case null -> null;
            case Long l -> c.datePrecision == JdbcConfig.DatePrecision.SECONDS ? l * 1000 : l;
            case Double d -> Math.round((d - 2440587.5) * 86_400_000d);
            case String s -> {
                try {
                    yield LocalDateTime.parse(s.strip(), c.dateFormatter).atZone(zone(cal)).toInstant().toEpochMilli();
                } catch (DateTimeParseException e) {
                    if (TIME_ONLY.matcher(s).matches()) {
                        // 'HH:MM[:SS[.fff]]' (setObject(LocalTime), SQLite's time()): on 1970-01-01, as Time.valueOf
                        yield LocalDate.EPOCH.atTime(typed(columnIndex, LocalTime.class)).atZone(zone(cal)).toInstant().toEpochMilli();
                    }
                    yield TRAILING_OFFSET.matcher(s).matches()
                            ? typed(columnIndex, OffsetDateTime.class).toInstant().toEpochMilli()
                            : typed(columnIndex, LocalDateTime.class).atZone(zone(cal)).toInstant().toEpochMilli();
                }
            }
            default -> throw new SQLException("cannot convert a BLOB to a date", "22018");
        };
    }

    // ------------------------------------------------------------------ JDBC 4.2 getObject(i, Class)

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        if (type == null) {
            throw new SQLException("type must not be null", "HY009");
        }
        io.github.psydic.ffmsqlite.db.ResultSet.StorageClass sc = storageClass(columnIndex);
        if (sc == io.github.psydic.ffmsqlite.db.ResultSet.StorageClass.NULL) {
            return null;
        }
        Object v;
        if (type == String.class) {
            v = getString(columnIndex);
        } else if (type == Integer.class) {
            v = getInt(columnIndex);
        } else if (type == Long.class) {
            v = getLong(columnIndex);
        } else if (type == Short.class) {
            v = getShort(columnIndex);
        } else if (type == Byte.class) {
            v = getByte(columnIndex);
        } else if (type == Double.class) {
            v = getDouble(columnIndex);
        } else if (type == Float.class) {
            v = getFloat(columnIndex);
        } else if (type == Boolean.class) {
            v = getBoolean(columnIndex);
        } else if (type == BigDecimal.class) {
            v = getBigDecimal(columnIndex);
        } else if (type == BigInteger.class || type == UUID.class) {
            v = typed(columnIndex, type);
        } else if (type == byte[].class) {
            v = getBytes(columnIndex);
        } else if (type == Date.class) {
            v = getDate(columnIndex);
        } else if (type == Time.class) {
            v = getTime(columnIndex);
        } else if (type == Timestamp.class) {
            v = getTimestamp(columnIndex);
        } else if (type == java.util.Date.class) {
            v = new java.util.Date(getTimestamp(columnIndex).getTime());
        } else if (type == LocalDate.class || type == LocalTime.class || type == LocalDateTime.class
                || type == Instant.class || type == OffsetDateTime.class || type == ZonedDateTime.class) {
            v = sc == io.github.psydic.ffmsqlite.db.ResultSet.StorageClass.TEXT ? typed(columnIndex, type) : fromMillis(millis(columnIndex, null), type);
        } else if (type == Blob.class) {
            v = getBlob(columnIndex);
        } else if (type == Clob.class) {
            v = getClob(columnIndex);
        } else if (type == Object.class) {
            v = getObject(columnIndex);
        } else {
            Object o = getObject(columnIndex);
            if (!type.isInstance(o)) {
                throw new SQLException("cannot convert column " + columnIndex + " to " + type.getName(), "22018");
            }
            v = o;
        }
        return type.cast(v);
    }

    /** sqlite-jdbc semantics for numeric dates: an instant, viewed in the JVM's default zone for local types. */
    private static Object fromMillis(long ms, Class<?> type) {
        Instant i = Instant.ofEpochMilli(ms);
        ZoneId z = ZoneId.systemDefault();
        if (type == Instant.class) {
            return i;
        }
        if (type == LocalDate.class) {
            return i.atZone(z).toLocalDate();
        }
        if (type == LocalTime.class) {
            return i.atZone(z).toLocalTime();
        }
        if (type == LocalDateTime.class) {
            return i.atZone(z).toLocalDateTime();
        }
        if (type == OffsetDateTime.class) {
            return i.atZone(z).toOffsetDateTime();
        }
        return i.atZone(z);
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        if (map == null || map.isEmpty()) {
            return getObject(columnIndex);
        }
        throw Errors.unsupported("custom type maps");
    }

    // ------------------------------------------------------------------ streams and LOBs (values are materialized)

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        byte[] b = getBytes(columnIndex);
        return b == null ? null : new ByteArrayInputStream(b);
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s == null ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s == null ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s == null ? null : new StringReader(s);
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return getCharacterStream(columnIndex);
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        byte[] b = getBytes(columnIndex);
        return b == null ? null : new SerialBlob(b);
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        return s == null ? null : new SerialClob(s.toCharArray());
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        throw Errors.unsupported("NClob (use getString)");
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        try {
            return s == null ? null : URI.create(s).toURL();
        } catch (IllegalArgumentException | MalformedURLException e) {
            throw new SQLException("not a URL: " + s, "22018", e);
        }
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        throw Errors.unsupported("SQL arrays");
    }

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        throw Errors.unsupported("REF");
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        throw Errors.unsupported("RowId (select the rowid column)");
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        throw Errors.unsupported("SQLXML");
    }

    // ------------------------------------------------------------------ by label

    @Override
    public String getString(String l) throws SQLException {
        return getString(findColumn(l));
    }

    @Override
    public boolean getBoolean(String l) throws SQLException {
        return getBoolean(findColumn(l));
    }

    @Override
    public byte getByte(String l) throws SQLException {
        return getByte(findColumn(l));
    }

    @Override
    public short getShort(String l) throws SQLException {
        return getShort(findColumn(l));
    }

    @Override
    public int getInt(String l) throws SQLException {
        return getInt(findColumn(l));
    }

    @Override
    public long getLong(String l) throws SQLException {
        return getLong(findColumn(l));
    }

    @Override
    public float getFloat(String l) throws SQLException {
        return getFloat(findColumn(l));
    }

    @Override
    public double getDouble(String l) throws SQLException {
        return getDouble(findColumn(l));
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(String l, int scale) throws SQLException {
        return getBigDecimal(findColumn(l), scale);
    }

    @Override
    public BigDecimal getBigDecimal(String l) throws SQLException {
        return getBigDecimal(findColumn(l));
    }

    @Override
    public byte[] getBytes(String l) throws SQLException {
        return getBytes(findColumn(l));
    }

    @Override
    public Date getDate(String l) throws SQLException {
        return getDate(findColumn(l));
    }

    @Override
    public Date getDate(String l, Calendar cal) throws SQLException {
        return getDate(findColumn(l), cal);
    }

    @Override
    public Time getTime(String l) throws SQLException {
        return getTime(findColumn(l));
    }

    @Override
    public Time getTime(String l, Calendar cal) throws SQLException {
        return getTime(findColumn(l), cal);
    }

    @Override
    public Timestamp getTimestamp(String l) throws SQLException {
        return getTimestamp(findColumn(l));
    }

    @Override
    public Timestamp getTimestamp(String l, Calendar cal) throws SQLException {
        return getTimestamp(findColumn(l), cal);
    }

    @Override
    public InputStream getAsciiStream(String l) throws SQLException {
        return getAsciiStream(findColumn(l));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(String l) throws SQLException {
        return getUnicodeStream(findColumn(l));
    }

    @Override
    public InputStream getBinaryStream(String l) throws SQLException {
        return getBinaryStream(findColumn(l));
    }

    @Override
    public Reader getCharacterStream(String l) throws SQLException {
        return getCharacterStream(findColumn(l));
    }

    @Override
    public Reader getNCharacterStream(String l) throws SQLException {
        return getNCharacterStream(findColumn(l));
    }

    @Override
    public Object getObject(String l) throws SQLException {
        return getObject(findColumn(l));
    }

    @Override
    public Object getObject(String l, Map<String, Class<?>> map) throws SQLException {
        return getObject(findColumn(l), map);
    }

    @Override
    public <T> T getObject(String l, Class<T> type) throws SQLException {
        return getObject(findColumn(l), type);
    }

    @Override
    public Blob getBlob(String l) throws SQLException {
        return getBlob(findColumn(l));
    }

    @Override
    public Clob getClob(String l) throws SQLException {
        return getClob(findColumn(l));
    }

    @Override
    public NClob getNClob(String l) throws SQLException {
        throw Errors.unsupported("NClob (use getString)");
    }

    @Override
    public String getNString(String l) throws SQLException {
        return getNString(findColumn(l));
    }

    @Override
    public URL getURL(String l) throws SQLException {
        return getURL(findColumn(l));
    }

    @Override
    public Array getArray(String l) throws SQLException {
        throw Errors.unsupported("SQL arrays");
    }

    @Override
    public Ref getRef(String l) throws SQLException {
        throw Errors.unsupported("REF");
    }

    @Override
    public RowId getRowId(String l) throws SQLException {
        throw Errors.unsupported("RowId (select the rowid column)");
    }

    @Override
    public SQLXML getSQLXML(String l) throws SQLException {
        throw Errors.unsupported("SQLXML");
    }

    // ------------------------------------------------------------------ cursor properties

    @Override
    public int getType() throws SQLException {
        checkOpen();
        return TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        checkOpen();
        return CONCUR_READ_ONLY;
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        if (direction != FETCH_FORWARD) {
            throw Errors.forwardOnly();
        }
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return FETCH_FORWARD;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        checkOpen();
        if (rows < 0) {
            throw new SQLException("fetch size must be >= 0", "HY024");
        }
        fetchSize = rows; // a hint only: SQLite always streams rows
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return fetchSize;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return null;
    }

    @Override
    public void clearWarnings() {
    }

    /** Null: SQLite has no named cursors (sqlite-jdbc returns null too). */
    @Override
    public String getCursorName() throws SQLException {
        checkOpen();
        return null;
    }

    @Override
    public boolean isLast() throws SQLException {
        throw Errors.unsupported("isLast on a forward-only streaming result set");
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public boolean rowInserted() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        checkOpen();
        return false;
    }

    // ------------------------------------------------------------------ scrolling: forward-only

    @Override
    public void beforeFirst() throws SQLException {
        throw Errors.forwardOnly();
    }

    @Override
    public void afterLast() throws SQLException {
        throw Errors.forwardOnly();
    }

    @Override
    public boolean first() throws SQLException {
        throw Errors.forwardOnly();
    }

    @Override
    public boolean last() throws SQLException {
        throw Errors.forwardOnly();
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        throw Errors.forwardOnly();
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        throw Errors.forwardOnly();
    }

    @Override
    public boolean previous() throws SQLException {
        throw Errors.forwardOnly();
    }

    // ------------------------------------------------------------------ updates: read-only

    private static SQLException readOnly() {
        return Errors.unsupported("updatable result sets (use UPDATE statements)");
    }

    @Override
    public void updateNull(int c) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBoolean(int c, boolean x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateByte(int c, byte x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateShort(int c, short x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateInt(int c, int x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateLong(int c, long x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateFloat(int c, float x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDouble(int c, double x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBigDecimal(int c, BigDecimal x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateString(int c, String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBytes(int c, byte[] x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDate(int c, Date x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTime(int c, Time x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTimestamp(int c, Timestamp x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int c, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int c, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int c, Reader x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(int c, Object x, int scaleOrLength) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(int c, Object x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNull(String c) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBoolean(String c, boolean x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateByte(String c, byte x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateShort(String c, short x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateInt(String c, int x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateLong(String c, long x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateFloat(String c, float x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDouble(String c, double x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBigDecimal(String c, BigDecimal x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateString(String c, String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBytes(String c, byte[] x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDate(String c, Date x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTime(String c, Time x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTimestamp(String c, Timestamp x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String c, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String c, InputStream x, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String c, Reader reader, int length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(String c, Object x, int scaleOrLength) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(String c, Object x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void insertRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void deleteRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void refreshRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        throw readOnly();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRef(int c, Ref x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRef(String c, Ref x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int c, Blob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String c, Blob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int c, Clob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String c, Clob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateArray(int c, Array x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateArray(String c, Array x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRowId(int c, RowId x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRowId(String c, RowId x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNString(int c, String s) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNString(String c, String s) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int c, NClob n) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String c, NClob n) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateSQLXML(int c, SQLXML x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateSQLXML(String c, SQLXML x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(int c, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(String c, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int c, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int c, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int c, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String c, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String c, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String c, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int c, InputStream inputStream, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String c, InputStream inputStream, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int c, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String c, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int c, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String c, Reader reader, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(int c, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(String c, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int c, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int c, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int c, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String c, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String c, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String c, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int c, InputStream inputStream) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String c, InputStream inputStream) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int c, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String c, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int c, Reader reader) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String c, Reader reader) throws SQLException {
        throw readOnly();
    }

    // ------------------------------------------------------------------ wrapper

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("not a wrapper for " + iface.getName(), "HY000");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
