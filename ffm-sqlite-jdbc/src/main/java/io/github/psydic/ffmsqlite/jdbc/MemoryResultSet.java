package io.github.psydic.ffmsqlite.jdbc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * A result set over rows held in memory: DatabaseMetaData results and generated keys. Values are Integer, Short,
 * Long, Boolean, String or null, typed per column as declared in {@code types}.
 */
final class MemoryResultSet extends AbstractResultSet {
    private final Statement statement;
    private final JdbcConfig config;
    final String[] columns;
    final int[] types;
    private final List<Object[]> rows;
    private int cursor = -1;
    private boolean closed;
    private boolean lastNull;

    MemoryResultSet(Statement statement, JdbcConfig config, String[] columns, int[] types, List<Object[]> rows) {
        this.statement = statement;
        this.config = config;
        this.columns = columns;
        this.types = types;
        this.rows = rows;
    }

    @Override
    JdbcConfig config() {
        return config;
    }

    @Override
    void checkOpen() throws SQLException {
        if (closed) {
            throw Errors.closed("ResultSet");
        }
    }

    private Object value(int columnIndex) throws SQLException {
        checkOpen();
        if (cursor < 0 || cursor >= rows.size()) {
            throw new SQLException("no current row; call next()", "24000");
        }
        if (columnIndex < 1 || columnIndex > columns.length) {
            throw new SQLException("column index " + columnIndex + " out of range 1.." + columns.length, "07009");
        }
        Object v = rows.get(cursor)[columnIndex - 1];
        lastNull = v == null;
        return v;
    }

    @Override
    public boolean next() throws SQLException {
        checkOpen();
        if (cursor < rows.size()) {
            cursor++;
        }
        return cursor < rows.size();
    }

    @Override
    public String getString(int c) throws SQLException {
        Object v = value(c);
        return v == null ? null : v instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : v.toString();
    }

    @Override
    public boolean getBoolean(int c) throws SQLException {
        return switch (value(c)) {
            case null -> false;
            case Boolean b -> b;
            case Number n -> n.longValue() != 0;
            case String s -> s.equalsIgnoreCase("true") || s.equals("1");
            default -> false;
        };
    }

    private long number(int c) throws SQLException {
        Object v = value(c);
        try {
            return switch (v) {
                case null -> 0;
                case Number n -> n.longValue();
                case Boolean b -> b ? 1 : 0;
                case String s -> Long.parseLong(s.strip());
                default -> throw new SQLException("not a number in column " + c, "22018");
            };
        } catch (NumberFormatException e) {
            throw new SQLException("not a number: " + v, "22018", e);
        }
    }

    @Override
    public int getInt(int c) throws SQLException {
        return (int) number(c);
    }

    @Override
    public long getLong(int c) throws SQLException {
        return number(c);
    }

    @Override
    public double getDouble(int c) throws SQLException {
        Object v = value(c);
        return v instanceof Number n ? n.doubleValue() : v == null ? 0 : Double.parseDouble(v.toString());
    }

    @Override
    public BigDecimal getBigDecimal(int c) throws SQLException {
        Object v = value(c);
        return v == null ? null : new BigDecimal(v.toString());
    }

    @Override
    public byte[] getBytes(int c) throws SQLException {
        Object v = value(c);
        return v == null ? null : v instanceof byte[] b ? b : v.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Object getObject(int c) throws SQLException {
        return value(c);
    }

    @Override
    Object storage(int c) throws SQLException {
        Object v = value(c);
        return switch (v) {
            case Boolean b -> b ? 1L : 0L;
            case Integer i -> (long) i;
            case Short s -> (long) s;
            default -> v;
        };
    }

    @Override
    <T> T typed(int c, Class<T> type) throws SQLException {
        Object v = value(c);
        if (type.isInstance(v)) {
            return type.cast(v);
        }
        throw new SQLException("cannot convert column " + c + " to " + type.getName(), "22018");
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return lastNull;
    }

    @Override
    public int findColumn(String label) throws SQLException {
        checkOpen();
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equalsIgnoreCase(label)) {
                return i + 1;
            }
        }
        throw new SQLException("no such column: " + label, "S0022");
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        return new MemoryResultSetMetaData(columns, types);
    }

    @Override
    public Statement getStatement() throws SQLException {
        checkOpen();
        return statement;
    }

    @Override
    public int getRow() throws SQLException {
        checkOpen();
        return cursor >= 0 && cursor < rows.size() ? cursor + 1 : 0;
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkOpen();
        return cursor < 0 && !rows.isEmpty();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        checkOpen();
        return cursor >= rows.size() && !rows.isEmpty();
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkOpen();
        return cursor == 0 && !rows.isEmpty();
    }

    @Override
    public boolean isLast() throws SQLException {
        checkOpen();
        return !rows.isEmpty() && cursor == rows.size() - 1;
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }
}
