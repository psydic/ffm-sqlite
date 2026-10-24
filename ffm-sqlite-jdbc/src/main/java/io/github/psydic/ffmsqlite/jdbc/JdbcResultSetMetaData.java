package io.github.psydic.ffmsqlite.jdbc;

import io.github.psydic.ffmsqlite.db.DatabaseMetaData.ColumnInfo;
import io.github.psydic.ffmsqlite.db.DatabaseMetaData.TableInfo;
import io.github.psydic.ffmsqlite.db.SQLiteException;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code java.sql.ResultSetMetaData} for a query: names and declared types from SQLite; table, nullability and
 * auto-increment from the source table's schema (SQLite's column metadata), cached per instance. Columns without
 * a declared type (expressions) report the storage class of the current row's value, as sqlite-jdbc does.
 */
final class JdbcResultSetMetaData implements ResultSetMetaData {
    private final JdbcConnection conn;
    private final io.github.psydic.ffmsqlite.db.ResultSetMetaData md;
    private final JdbcResultSet rs; // null for PreparedStatement.getMetaData()
    private final Map<Integer, ColumnInfo> columnInfo = new HashMap<>();
    private final Map<String, Boolean> autoIncrementTables = new HashMap<>();

    JdbcResultSetMetaData(JdbcConnection conn, io.github.psydic.ffmsqlite.db.ResultSetMetaData md, JdbcResultSet rs) {
        this.conn = conn;
        this.md = md;
        this.rs = rs;
    }

    private static SQLException translate(SQLiteException e) {
        return e.getErrorCode() == 0 ? new SQLException(e.getMessage(), "07009", e) : Errors.of(e);
    }

    private String declared(int column) throws SQLException {
        synchronized (conn) {
            try {
                return md.getColumnTypeName(column);
            } catch (SQLiteException e) {
                throw translate(e);
            }
        }
    }

    private String source(int column, int which) throws SQLException {
        synchronized (conn) {
            try {
                return which == 0 ? md.getTableName(column) : which == 1 ? md.getSchemaName(column) : md.getColumnOriginName(column);
            } catch (SQLiteException e) {
                throw translate(e);
            }
        }
    }

    /** The source column's schema entry, or null for expressions. */
    private ColumnInfo info(int column) throws SQLException {
        if (columnInfo.containsKey(column)) {
            return columnInfo.get(column);
        }
        String table = source(column, 0), schema = source(column, 1), origin = source(column, 2);
        ColumnInfo found = null;
        if (table != null && origin != null) {
            synchronized (conn) {
                try {
                    for (ColumnInfo c : conn.db.getMetaData().getColumns(schema == null ? "main" : schema, table)) {
                        if (c.name().equalsIgnoreCase(origin)) {
                            found = c;
                        }
                    }
                } catch (SQLiteException e) {
                    throw Errors.of(e);
                }
            }
        }
        columnInfo.put(column, found);
        return found;
    }

    @Override
    public int getColumnCount() {
        return md.getColumnCount();
    }

    /** True for an INTEGER PRIMARY KEY of a table declared with AUTOINCREMENT. */
    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        ColumnInfo c = info(column);
        if (c == null || c.primaryKeyPosition() != 1 || c.declaredType() == null
                || !c.declaredType().equalsIgnoreCase("INTEGER")) {
            return false;
        }
        String schema = source(column, 1), table = source(column, 0);
        String key = schema + "." + table;
        Boolean auto = autoIncrementTables.get(key);
        if (auto == null) {
            auto = false;
            synchronized (conn) {
                try {
                    List<TableInfo> tables = conn.db.getMetaData().getTables(schema == null ? "main" : schema);
                    for (TableInfo t : tables) {
                        if (t.name().equalsIgnoreCase(table) && t.sql() != null) {
                            auto = t.sql().toUpperCase(Locale.ROOT).contains("AUTOINCREMENT");
                        }
                    }
                } catch (SQLiteException e) {
                    throw Errors.of(e);
                }
            }
            autoIncrementTables.put(key, auto);
        }
        return auto;
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        check(column);
        return true;
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        check(column);
        return true;
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        check(column);
        return false;
    }

    @Override
    public int isNullable(int column) throws SQLException {
        ColumnInfo c = info(column);
        if (c == null) {
            return columnNullableUnknown;
        }
        boolean rowid = c.primaryKeyPosition() == 1 && "INTEGER".equalsIgnoreCase(c.declaredType());
        return c.notNull() || rowid ? columnNoNulls : columnNullable;
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        return SqlTypes.signed(getColumnType(column));
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        check(column);
        return Integer.MAX_VALUE;
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        synchronized (conn) {
            try {
                return md.getColumnLabel(column);
            } catch (SQLiteException e) {
                throw translate(e);
            }
        }
    }

    /** The column's name as SQLite reports it (its AS alias if any), as sqlite-jdbc does. */
    @Override
    public String getColumnName(int column) throws SQLException {
        return getColumnLabel(column);
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        check(column);
        return "";
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        return SqlTypes.precision(declared(column));
    }

    @Override
    public int getScale(int column) throws SQLException {
        return SqlTypes.scale(declared(column));
    }

    @Override
    public String getTableName(int column) throws SQLException {
        String t = source(column, 0);
        return t == null ? "" : t;
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        check(column);
        return "";
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        String declared = declared(column);
        if (declared != null) {
            return SqlTypes.ofDeclared(declared);
        }
        return rs != null && rs.onRow() ? SqlTypes.ofStorage(rs.storage(column)) : java.sql.Types.NUMERIC;
    }

    /** The declared type without its size, as sqlite-jdbc reports it ({@code VARCHAR(20)} → {@code VARCHAR}). */
    @Override
    public String getColumnTypeName(int column) throws SQLException {
        String declared = declared(column);
        if (declared != null) {
            int paren = declared.indexOf('(');
            return paren < 0 ? declared : declared.substring(0, paren).strip();
        }
        return rs != null && rs.onRow() ? SqlTypes.storageName(rs.storage(column)) : "NUMERIC";
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        check(column);
        return false;
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        check(column);
        return true;
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        check(column);
        return false;
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        return SqlTypes.className(getColumnType(column));
    }

    private void check(int column) throws SQLException {
        if (column < 1 || column > md.getColumnCount()) {
            throw new SQLException("column index " + column + " out of range 1.." + md.getColumnCount(), "07009");
        }
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        if (iface.isInstance(md)) {
            return iface.cast(md);
        }
        throw new SQLException("not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this) || iface.isInstance(md);
    }
}
