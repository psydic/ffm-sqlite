package io.github.revsnai.ffmsqlite.db;

/**
 * Column information for a {@link ResultSet}, in the role of {@code java.sql.ResultSetMetaData}.
 *
 * <p>Everything is read from the statement when this object is created, so it stays valid after the result set,
 * the statement or the connection is closed (SQLite frees the statement, and the information with it).
 */
public final class ResultSetMetaData {
    private final int columnCount;
    private final String[] labels, declTypes, tables, schemas, origins;

    ResultSetMetaData(Stmt stmt) {
        columnCount = stmt.columnCount;
        labels = stmt.names().clone();
        declTypes = new String[columnCount];
        tables = new String[columnCount];
        schemas = new String[columnCount];
        origins = new String[columnCount];
        for (int i = 0; i < columnCount; i++) {
            declTypes[i] = Native.column_decltype(stmt.ptr, i);
            tables[i] = Native.column_source(stmt.ptr, i, 0);
            schemas[i] = Native.column_source(stmt.ptr, i, 1);
            origins[i] = Native.column_source(stmt.ptr, i, 2);
        }
    }

    public int getColumnCount() {
        return columnCount;
    }

    /** The column's name, or its {@code AS} alias. */
    public String getColumnLabel(int column) throws SQLiteException {
        return labels[index(column)];
    }

    public String getColumnName(int column) throws SQLiteException {
        return getColumnLabel(column);
    }

    /** The type as declared in CREATE TABLE (e.g. {@code "TEXT"}); {@code null} for expressions. */
    public String getColumnTypeName(int column) throws SQLiteException {
        return declTypes[index(column)];
    }

    /** The table the column comes from; null for expressions (or a SQLite built without column metadata). */
    public String getTableName(int column) throws SQLiteException {
        return tables[index(column)];
    }

    /** The schema ({@code main}, {@code temp}, attached name) of {@link #getTableName}'s table, or null. */
    public String getSchemaName(int column) throws SQLiteException {
        return schemas[index(column)];
    }

    /** The column's name in its table (unlike {@link #getColumnLabel}, unaffected by AS), or null. */
    public String getColumnOriginName(int column) throws SQLiteException {
        return origins[index(column)];
    }

    private int index(int column) throws SQLiteException {
        if (column < 1 || column > columnCount) {
            throw new SQLiteException("column index " + column + " out of range 1.." + columnCount);
        }
        return column - 1;
    }
}
