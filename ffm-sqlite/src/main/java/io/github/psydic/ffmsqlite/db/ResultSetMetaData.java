package io.github.psydic.ffmsqlite.db;

/** Column information for a {@link ResultSet}, in the role of {@code java.sql.ResultSetMetaData}. */
public final class ResultSetMetaData {
    private final Stmt stmt;

    ResultSetMetaData(Stmt stmt) {
        this.stmt = stmt;
    }

    public int getColumnCount() {
        return stmt.columnCount;
    }

    /** The column's name, or its {@code AS} alias. */
    public String getColumnLabel(int column) throws SQLiteException {
        return stmt.names()[index(column)];
    }

    public String getColumnName(int column) throws SQLiteException {
        return getColumnLabel(column);
    }

    /** The type as declared in CREATE TABLE (e.g. {@code "TEXT"}); {@code null} for expressions. */
    public String getColumnTypeName(int column) throws SQLiteException {
        return Native.column_decltype(stmt.ptr, index(column));
    }

    /** The table the column comes from; null for expressions (or a SQLite built without column metadata). */
    public String getTableName(int column) throws SQLiteException {
        return Native.column_source(stmt.ptr, index(column), 0);
    }

    /** The schema ({@code main}, {@code temp}, attached name) of {@link #getTableName}'s table, or null. */
    public String getSchemaName(int column) throws SQLiteException {
        return Native.column_source(stmt.ptr, index(column), 1);
    }

    /** The column's name in its table (unlike {@link #getColumnLabel}, unaffected by AS), or null. */
    public String getColumnOriginName(int column) throws SQLiteException {
        return Native.column_source(stmt.ptr, index(column), 2);
    }

    private int index(int column) throws SQLiteException {
        if (column < 1 || column > stmt.columnCount) {
            throw new SQLiteException("column index " + column + " out of range 1.." + stmt.columnCount);
        }
        return column - 1;
    }
}
