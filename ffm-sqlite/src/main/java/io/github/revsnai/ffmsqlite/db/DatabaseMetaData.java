package io.github.revsnai.ffmsqlite.db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema information, in the role of {@code java.sql.DatabaseMetaData}, from {@link Connection#getMetaData()}.
 * Returns typed records instead of JDBC's ResultSets of string columns, built on SQLite's own
 * {@code pragma_table_list}, {@code pragma_table_xinfo}, {@code pragma_index_list}/{@code _xinfo} and
 * {@code pragma_foreign_key_list}. Methods without a schema argument look at {@code main}. Internal tables
 * ({@code sqlite_*}) are not listed. Each call reads the current schema; nothing is cached.
 */
public final class DatabaseMetaData {

    public enum TableType { TABLE, VIEW, VIRTUAL, SHADOW }

    /** A table or view. {@code sql} is its CREATE statement. */
    public record TableInfo(String schema, String name, TableType type, int columnCount, boolean withoutRowId,
                            boolean strict, String sql) {
    }

    public enum Generated { NONE, VIRTUAL, STORED }

    /**
     * A column, in declaration order.
     *
     * @param defaultValue        the DEFAULT clause as SQL text (e.g. {@code 'x'} or {@code CURRENT_TIMESTAMP}), or null
     * @param primaryKeyPosition  1-based position in the primary key, 0 if not part of it
     * @param hidden              a hidden column of a virtual table
     */
    public record ColumnInfo(String name, String declaredType, boolean notNull, String defaultValue,
                             int primaryKeyPosition, Generated generated, boolean hidden) {
    }

    public enum IndexOrigin { CREATE_INDEX, UNIQUE_CONSTRAINT, PRIMARY_KEY }

    /** One key column of an index; {@code name} is null for an expression. */
    public record IndexColumn(String name, boolean descending, String collation) {
    }

    public record IndexInfo(String name, boolean unique, IndexOrigin origin, boolean partial, List<IndexColumn> columns) {
    }

    /**
     * A foreign key. {@code toColumns} entries are null when the constraint references the parent's primary
     * key implicitly ({@code REFERENCES parent} without a column list).
     */
    public record ForeignKeyInfo(int id, String referencedTable, List<String> fromColumns, List<String> toColumns,
                                 String onUpdate, String onDelete) {
    }

    private final Connection conn;

    DatabaseMetaData(Connection conn) {
        this.conn = conn;
    }

    /** The version of the SQLite library, e.g. {@code "3.50.3"}. */
    public String getSQLiteVersion() {
        return SQLite.version();
    }

    /** The connection's databases: {@code main}, {@code temp} once used, and ATTACHed ones. */
    public List<String> getSchemas() throws SQLiteException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM pragma_database_list ORDER BY seq");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    public List<TableInfo> getTables() throws SQLiteException {
        return getTables("main");
    }

    /** Tables and views of {@code schema}, by name. */
    public List<TableInfo> getTables(String schema) throws SQLiteException {
        List<TableInfo> out = new ArrayList<>();
        if (getSchemas().stream().noneMatch(schema::equalsIgnoreCase)) {
            return out; // the sqlite_schema join below needs an existing schema
        }
        String sql = "SELECT t.name, t.type, t.ncol, t.wr, t.strict, s.sql FROM pragma_table_list t "
                + "LEFT JOIN " + quoteIdentifier(schema) + ".sqlite_schema s ON s.name = t.name "
                + "WHERE t.schema = ? AND t.name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY t.name";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TableInfo(schema, rs.getString(1), TableType.valueOf(rs.getString(2).toUpperCase(java.util.Locale.ROOT)),
                            rs.getInt(3), rs.getBoolean(4), rs.getBoolean(5), rs.getString(6)));
                }
            }
        }
        return out;
    }

    public List<ColumnInfo> getColumns(String table) throws SQLiteException {
        return getColumns("main", table);
    }

    /** Columns of a table or view in declaration order; empty if it does not exist. */
    public List<ColumnInfo> getColumns(String schema, String table) throws SQLiteException {
        List<ColumnInfo> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name, type, \"notnull\", dflt_value, pk, hidden FROM pragma_table_xinfo(?, ?) ORDER BY cid")) {
            ps.setString(1, table);
            ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int hidden = rs.getInt(6);
                    String type = rs.getString(2);
                    out.add(new ColumnInfo(rs.getString(1), type.isEmpty() ? null : type, rs.getBoolean(3), rs.getString(4),
                            rs.getInt(5), hidden == 2 ? Generated.VIRTUAL : hidden == 3 ? Generated.STORED : Generated.NONE,
                            hidden == 1));
                }
            }
        }
        return out;
    }

    public List<String> getPrimaryKey(String table) throws SQLiteException {
        return getPrimaryKey("main", table);
    }

    /** The primary-key columns in key order; empty for a table keyed only by its implicit rowid. */
    public List<String> getPrimaryKey(String schema, String table) throws SQLiteException {
        List<ColumnInfo> pk = new ArrayList<>(getColumns(schema, table).stream().filter(c -> c.primaryKeyPosition() > 0).toList());
        pk.sort((a, b) -> Integer.compare(a.primaryKeyPosition(), b.primaryKeyPosition()));
        return pk.stream().map(ColumnInfo::name).toList();
    }

    public List<IndexInfo> getIndexes(String table) throws SQLiteException {
        return getIndexes("main", table);
    }

    /** Indexes of a table, including those SQLite creates for UNIQUE and PRIMARY KEY constraints. */
    public List<IndexInfo> getIndexes(String schema, String table) throws SQLiteException {
        record Header(String name, boolean unique, IndexOrigin origin, boolean partial) {
        }
        List<Header> headers = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name, \"unique\", origin, partial FROM pragma_index_list(?, ?) ORDER BY name")) {
            ps.setString(1, table);
            ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    IndexOrigin origin = switch (rs.getString(3)) {
                        case "u" -> IndexOrigin.UNIQUE_CONSTRAINT;
                        case "pk" -> IndexOrigin.PRIMARY_KEY;
                        default -> IndexOrigin.CREATE_INDEX;
                    };
                    headers.add(new Header(rs.getString(1), rs.getBoolean(2), origin, rs.getBoolean(4)));
                }
            }
        }
        List<IndexInfo> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name, \"desc\", coll FROM pragma_index_xinfo(?, ?) WHERE key = 1 ORDER BY seqno")) {
            for (Header h : headers) {
                ps.setString(1, h.name());
                ps.setString(2, schema);
                List<IndexColumn> columns = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        columns.add(new IndexColumn(rs.getString(1), rs.getBoolean(2), rs.getString(3)));
                    }
                }
                out.add(new IndexInfo(h.name(), h.unique(), h.origin(), h.partial(), List.copyOf(columns)));
            }
        }
        return out;
    }

    public List<ForeignKeyInfo> getForeignKeys(String table) throws SQLiteException {
        return getForeignKeys("main", table);
    }

    /** Foreign keys declared by a table (the child side). */
    public List<ForeignKeyInfo> getForeignKeys(String schema, String table) throws SQLiteException {
        Map<Integer, ForeignKeyInfo> byId = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, \"table\", \"from\", \"to\", on_update, on_delete "
                + "FROM pragma_foreign_key_list(?, ?) ORDER BY id, seq")) {
            ps.setString(1, table);
            ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    ForeignKeyInfo fk = byId.computeIfAbsent(id, k -> new ForeignKeyInfo(k, null, new ArrayList<>(),
                            new ArrayList<>(), null, null));
                    if (fk.referencedTable() == null) {
                        fk = new ForeignKeyInfo(id, rs.getString(2), fk.fromColumns(), fk.toColumns(), rs.getString(5), rs.getString(6));
                        byId.put(id, fk);
                    }
                    fk.fromColumns().add(rs.getString(3));
                    fk.toColumns().add(rs.getString(4));
                }
            }
        }
        return byId.values().stream().map(fk -> new ForeignKeyInfo(fk.id(), fk.referencedTable(),
                java.util.Collections.unmodifiableList(fk.fromColumns()),
                java.util.Collections.unmodifiableList(fk.toColumns()), fk.onUpdate(), fk.onDelete())).toList();
    }

    /** A schema name as a quoted SQL identifier (sqlite_schema of a schema cannot be addressed by a parameter). */
    private static String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
