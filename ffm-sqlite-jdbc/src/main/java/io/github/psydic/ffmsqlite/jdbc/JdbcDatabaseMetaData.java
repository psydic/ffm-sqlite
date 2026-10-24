package io.github.psydic.ffmsqlite.jdbc;

import io.github.psydic.ffmsqlite.db.DatabaseMetaData.ColumnInfo;
import io.github.psydic.ffmsqlite.db.DatabaseMetaData.ForeignKeyInfo;
import io.github.psydic.ffmsqlite.db.DatabaseMetaData.IndexColumn;
import io.github.psydic.ffmsqlite.db.DatabaseMetaData.IndexInfo;
import io.github.psydic.ffmsqlite.db.DatabaseMetaData.TableInfo;
import io.github.psydic.ffmsqlite.db.SQLite;
import io.github.psydic.ffmsqlite.db.SQLiteException;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@code java.sql.DatabaseMetaData} built on ffm-sqlite's typed metadata, in JDBC's result-set shapes. Schemas
 * are SQLite's databases ({@code main}, {@code temp}, ATTACHed names), reported in TABLE_SCHEM; there are no
 * catalogs. Name arguments are JDBC search patterns ({@code %}, {@code _}, escape {@code \}), matched
 * case-insensitively like SQLite identifiers.
 */
final class JdbcDatabaseMetaData implements DatabaseMetaData {
    private static final int V = Types.VARCHAR, I = Types.INTEGER, S = Types.SMALLINT, B = Types.BOOLEAN, L = Types.BIGINT;
    private final JdbcConnection conn;

    JdbcDatabaseMetaData(JdbcConnection conn) {
        this.conn = conn;
    }

    // ------------------------------------------------------------------ helpers

    private io.github.psydic.ffmsqlite.db.DatabaseMetaData md() {
        try {
            return conn.db.getMetaData();
        } catch (SQLiteException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResultSet rs(String[] columns, int[] types, List<Object[]> rows) {
        return new MemoryResultSet(null, conn.config, columns, types, rows);
    }

    private ResultSet empty(String... columns) {
        int[] types = new int[columns.length];
        Arrays.fill(types, V);
        return rs(columns, types, List.of());
    }

    /** JDBC search pattern match: null matches everything; % and _ are wildcards; \ escapes. */
    static boolean like(String pattern, String value) {
        if (pattern == null) {
            return true;
        }
        if (value == null) {
            return false;
        }
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                re.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
            } else if (c == '%') {
                re.append(".*");
            } else if (c == '_') {
                re.append('.');
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(value).matches();
    }

    private List<String> schemas(String schemaPattern) throws SQLException {
        synchronized (conn) {
            conn.checkOpen();
            try {
                String p = schemaPattern == null || schemaPattern.isEmpty() ? null : schemaPattern;
                return md().getSchemas().stream().filter(s -> like(p, s)).toList();
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
    }

    private record Table(String schema, TableInfo info) {
    }

    private List<Table> tables(String schemaPattern, String tablePattern) throws SQLException {
        List<Table> out = new ArrayList<>();
        synchronized (conn) {
            try {
                for (String schema : schemas(schemaPattern)) {
                    for (TableInfo t : md().getTables(schema)) {
                        if (like(tablePattern, t.name())) {
                            out.add(new Table(schema, t));
                        }
                    }
                }
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
        return out;
    }

    private static String tableType(Table t) {
        if (t.schema().equalsIgnoreCase("temp")) {
            return "LOCAL TEMPORARY";
        }
        return switch (t.info().type()) {
            case VIEW -> "VIEW";
            case SHADOW -> "SYSTEM TABLE";
            default -> "TABLE";
        };
    }

    private static boolean autoIncrement(TableInfo t, ColumnInfo c) {
        return c.primaryKeyPosition() == 1 && "INTEGER".equalsIgnoreCase(c.declaredType()) && t.sql() != null
                && t.sql().toUpperCase(Locale.ROOT).contains("AUTOINCREMENT");
    }

    private static short rule(String action) {
        return switch (action == null ? "" : action.toUpperCase(Locale.ROOT)) {
            case "CASCADE" -> (short) importedKeyCascade;
            case "RESTRICT" -> (short) importedKeyRestrict;
            case "SET NULL" -> (short) importedKeySetNull;
            case "SET DEFAULT" -> (short) importedKeySetDefault;
            default -> (short) importedKeyNoAction;
        };
    }

    // ------------------------------------------------------------------ tables, columns, keys, indexes

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
        List<String> wanted = types == null ? null : Arrays.stream(types).map(s -> s.toUpperCase(Locale.ROOT)).toList();
        List<Object[]> rows = new ArrayList<>();
        for (Table t : tables(schemaPattern, tableNamePattern)) {
            String type = tableType(t);
            if (wanted == null || wanted.contains(type)) {
                rows.add(new Object[]{null, t.schema(), t.info().name(), type, null, null, null, null, null, null});
            }
        }
        rows.sort((a, b) -> ((String) a[3] + a[1] + a[2]).compareTo((String) b[3] + b[1] + b[2]));
        return rs(new String[]{"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS", "TYPE_CAT", "TYPE_SCHEM",
                "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION"}, new int[]{V, V, V, V, V, V, V, V, V, V}, rows);
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        synchronized (conn) {
            try {
                for (Table t : tables(schemaPattern, tableNamePattern)) {
                    int position = 0;
                    for (ColumnInfo c : md().getColumns(t.schema(), t.info().name())) {
                        if (c.hidden()) {
                            continue;
                        }
                        position++;
                        if (!like(columnNamePattern, c.name())) {
                            continue;
                        }
                        int type = c.declaredType() == null ? Types.VARCHAR : SqlTypes.ofDeclared(c.declaredType());
                        int precision = SqlTypes.precision(c.declaredType());
                        boolean rowid = c.primaryKeyPosition() == 1 && "INTEGER".equalsIgnoreCase(c.declaredType());
                        boolean notNull = c.notNull() || rowid;
                        int size = precision > 0 ? precision : 2_000_000_000;
                        rows.add(new Object[]{null, t.schema(), t.info().name(), c.name(), type,
                                c.declaredType() == null ? "" : c.declaredType(), size, null, SqlTypes.scale(c.declaredType()), 10,
                                notNull ? columnNoNulls : columnNullable, null, c.defaultValue(), null, null,
                                type == Types.VARCHAR ? size : null, position, notNull ? "NO" : "YES", null, null, null, null,
                                autoIncrement(t.info(), c) ? "YES" : "NO",
                                c.generated() == io.github.psydic.ffmsqlite.db.DatabaseMetaData.Generated.NONE ? "NO" : "YES"});
                    }
                }
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
        return rs(new String[]{"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE",
                        "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE",
                        "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA",
                        "SCOPE_TABLE", "SOURCE_DATA_TYPE", "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN"},
                new int[]{V, V, V, V, I, V, I, I, I, I, I, V, V, I, I, I, I, V, V, V, V, S, V, V}, rows);
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        synchronized (conn) {
            try {
                for (Table t : tables(schema, escape(table))) {
                    List<String> pk = md().getPrimaryKey(t.schema(), t.info().name());
                    for (int i = 0; i < pk.size(); i++) {
                        rows.add(new Object[]{null, t.schema(), t.info().name(), pk.get(i), (short) (i + 1), null});
                    }
                }
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
        rows.sort((a, b) -> ((String) a[3]).compareTo((String) b[3]));
        return rs(new String[]{"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"},
                new int[]{V, V, V, V, S, V}, rows);
    }

    /** Exact-name arguments (not patterns) with pattern characters escaped. */
    private static String escape(String name) {
        return name == null ? null : name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        synchronized (conn) {
            try {
                for (Table t : tables(schema, escape(table))) {
                    for (IndexInfo ix : md().getIndexes(t.schema(), t.info().name())) {
                        if (unique && !ix.unique()) {
                            continue;
                        }
                        short pos = 0;
                        for (IndexColumn c : ix.columns()) {
                            rows.add(new Object[]{null, t.schema(), t.info().name(), !ix.unique(), null, ix.name(),
                                    (short) tableIndexOther, ++pos, c.name(), c.descending() ? "D" : "A", 0L, 0L, null});
                        }
                    }
                }
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
        return rs(new String[]{"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE", "INDEX_QUALIFIER", "INDEX_NAME", "TYPE",
                "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC", "CARDINALITY", "PAGES", "FILTER_CONDITION"},
                new int[]{V, V, V, B, V, V, S, S, V, V, L, L, V}, rows);
    }

    private static final String[] KEY_COLUMNS = {"PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME", "FKTABLE_CAT",
            "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ", "UPDATE_RULE", "DELETE_RULE", "FK_NAME", "PK_NAME",
            "DEFERRABILITY"};
    private static final int[] KEY_TYPES = {V, V, V, V, V, V, V, V, S, S, S, V, V, S};

    /** Foreign keys declared by {@code t}; implicit references to the parent's primary key are resolved. */
    private List<Object[]> keysOf(Table t) throws SQLiteException {
        List<Object[]> rows = new ArrayList<>();
        for (ForeignKeyInfo fk : md().getForeignKeys(t.schema(), t.info().name())) {
            List<String> to = fk.toColumns();
            if (to.stream().anyMatch(java.util.Objects::isNull)) {
                to = md().getPrimaryKey(t.schema(), fk.referencedTable());
            }
            for (int i = 0; i < fk.fromColumns().size(); i++) {
                rows.add(new Object[]{null, t.schema(), fk.referencedTable(), i < to.size() ? to.get(i) : null, null, t.schema(),
                        t.info().name(), fk.fromColumns().get(i), (short) (i + 1), rule(fk.onUpdate()), rule(fk.onDelete()),
                        null, null, (short) importedKeyNotDeferrable});
            }
        }
        return rows;
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        synchronized (conn) {
            try {
                for (Table t : tables(schema, escape(table))) {
                    rows.addAll(keysOf(t));
                }
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
        rows.sort((a, b) -> ((String) a[2] + a[8]).compareTo((String) b[2] + b[8]));
        return rs(KEY_COLUMNS, KEY_TYPES, rows);
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        synchronized (conn) {
            try {
                for (Table t : tables(schema, null)) {
                    for (Object[] r : keysOf(t)) {
                        if (((String) r[2]).equalsIgnoreCase(table)) {
                            rows.add(r);
                        }
                    }
                }
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
        rows.sort((a, b) -> ((String) a[6] + a[8]).compareTo((String) b[6] + b[8]));
        return rs(KEY_COLUMNS, KEY_TYPES, rows);
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable, String foreignCatalog,
                                       String foreignSchema, String foreignTable) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        synchronized (conn) {
            try {
                for (Table t : tables(foreignSchema, escape(foreignTable))) {
                    for (Object[] r : keysOf(t)) {
                        if (parentTable == null || ((String) r[2]).equalsIgnoreCase(parentTable)) {
                            rows.add(r);
                        }
                    }
                }
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
        return rs(KEY_COLUMNS, KEY_TYPES, rows);
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        synchronized (conn) {
            try {
                for (Table t : tables(schema, escape(table))) {
                    List<ColumnInfo> cols = md().getColumns(t.schema(), t.info().name());
                    List<String> pk = md().getPrimaryKey(t.schema(), t.info().name());
                    for (String name : pk) {
                        ColumnInfo c = cols.stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
                        rows.add(new Object[]{(short) bestRowSession, name, SqlTypes.ofDeclared(c.declaredType()),
                                c.declaredType() == null ? "" : c.declaredType(), 2_000_000_000, null, 0, (short) bestRowNotPseudo});
                    }
                    if (pk.isEmpty() && !t.info().withoutRowId() && t.info().type() == io.github.psydic.ffmsqlite.db.DatabaseMetaData.TableType.TABLE) {
                        rows.add(new Object[]{(short) bestRowSession, "rowid", Types.BIGINT, "INTEGER", 19, null, 0, (short) bestRowPseudo});
                    }
                }
            } catch (SQLiteException e) {
                throw Errors.of(e);
            }
        }
        return rs(new String[]{"SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS",
                "PSEUDO_COLUMN"}, new int[]{S, V, I, V, I, I, S, S}, rows);
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        return getSchemas(null, null);
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        for (String s : schemas(schemaPattern)) {
            rows.add(new Object[]{s, null});
        }
        return rs(new String[]{"TABLE_SCHEM", "TABLE_CATALOG"}, new int[]{V, V}, rows);
    }

    @Override
    public ResultSet getCatalogs() {
        return empty("TABLE_CAT");
    }

    @Override
    public ResultSet getTableTypes() {
        List<Object[]> rows = new ArrayList<>();
        for (String t : new String[]{"LOCAL TEMPORARY", "SYSTEM TABLE", "TABLE", "VIEW"}) {
            rows.add(new Object[]{t});
        }
        return rs(new String[]{"TABLE_TYPE"}, new int[]{V}, rows);
    }

    @Override
    public ResultSet getTypeInfo() {
        List<Object[]> rows = new ArrayList<>();
        Object[][] types = {{"BLOB", Types.BLOB, 0}, {"INTEGER", Types.INTEGER, 19}, {"NULL", Types.NULL, 0},
                {"REAL", Types.REAL, 17}, {"TEXT", Types.VARCHAR, 0}, {"NUMERIC", Types.NUMERIC, 0}};
        for (Object[] t : types) {
            boolean text = t[1].equals(Types.VARCHAR);
            boolean numeric = !text && !t[1].equals(Types.BLOB) && !t[1].equals(Types.NULL);
            rows.add(new Object[]{t[0], t[1], t[2], text ? "'" : null, text ? "'" : null, null, (short) typeNullable, text,
                    (short) typeSearchable, !numeric, false, t[1].equals(Types.INTEGER), t[0], (short) 0, (short) 0, null, null, 10});
        }
        return rs(new String[]{"TYPE_NAME", "DATA_TYPE", "PRECISION", "LITERAL_PREFIX", "LITERAL_SUFFIX", "CREATE_PARAMS", "NULLABLE",
                "CASE_SENSITIVE", "SEARCHABLE", "UNSIGNED_ATTRIBUTE", "FIXED_PREC_SCALE", "AUTO_INCREMENT", "LOCAL_TYPE_NAME",
                "MINIMUM_SCALE", "MAXIMUM_SCALE", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "NUM_PREC_RADIX"},
                new int[]{V, I, I, V, V, V, S, B, S, B, B, B, V, S, S, I, I, I}, rows);
    }

    // Objects SQLite does not have: correctly shaped, empty results.

    @Override
    public ResultSet getProcedures(String c, String s, String p) {
        return empty("PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "RESERVED4", "RESERVED5", "RESERVED6", "REMARKS",
                "PROCEDURE_TYPE", "SPECIFIC_NAME");
    }

    @Override
    public ResultSet getProcedureColumns(String c, String s, String p, String col) {
        return empty("PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "COLUMN_NAME", "COLUMN_TYPE", "DATA_TYPE", "TYPE_NAME",
                "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB",
                "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME");
    }

    @Override
    public ResultSet getFunctions(String c, String s, String f) {
        return empty("FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "REMARKS", "FUNCTION_TYPE", "SPECIFIC_NAME");
    }

    @Override
    public ResultSet getFunctionColumns(String c, String s, String f, String col) {
        return empty("FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "COLUMN_NAME", "COLUMN_TYPE", "DATA_TYPE", "TYPE_NAME",
                "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE", "REMARKS", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION",
                "IS_NULLABLE", "SPECIFIC_NAME");
    }

    @Override
    public ResultSet getColumnPrivileges(String c, String s, String t, String col) {
        return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE");
    }

    @Override
    public ResultSet getTablePrivileges(String c, String s, String t) {
        return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE");
    }

    @Override
    public ResultSet getVersionColumns(String c, String s, String t) {
        return empty("SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "PSEUDO_COLUMN");
    }

    @Override
    public ResultSet getUDTs(String c, String s, String t, int[] types) {
        return empty("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE", "REMARKS", "BASE_TYPE");
    }

    @Override
    public ResultSet getSuperTypes(String c, String s, String t) {
        return empty("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SUPERTYPE_CAT", "SUPERTYPE_SCHEM", "SUPERTYPE_NAME");
    }

    @Override
    public ResultSet getSuperTables(String c, String s, String t) {
        return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME");
    }

    @Override
    public ResultSet getAttributes(String c, String s, String t, String a) {
        return empty("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "ATTR_NAME", "DATA_TYPE", "ATTR_TYPE_NAME", "ATTR_SIZE", "DECIMAL_DIGITS",
                "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "ATTR_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH",
                "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE");
    }

    @Override
    public ResultSet getPseudoColumns(String c, String s, String t, String col) {
        return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "COLUMN_SIZE", "DECIMAL_DIGITS",
                "NUM_PREC_RADIX", "COLUMN_USAGE", "REMARKS", "CHAR_OCTET_LENGTH", "IS_NULLABLE");
    }

    @Override
    public ResultSet getClientInfoProperties() {
        return empty("NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION");
    }

    // ------------------------------------------------------------------ product and driver

    @Override
    public String getDatabaseProductName() {
        return "SQLite";
    }

    @Override
    public String getDatabaseProductVersion() {
        return SQLite.version();
    }

    private int versionPart(int i) {
        String[] parts = SQLite.version().split("\\.");
        return parts.length > i ? Integer.parseInt(parts[i]) : 0;
    }

    @Override
    public int getDatabaseMajorVersion() {
        return versionPart(0);
    }

    @Override
    public int getDatabaseMinorVersion() {
        return versionPart(1);
    }

    @Override
    public String getDriverName() {
        return "ffm-sqlite-jdbc";
    }

    @Override
    public String getDriverVersion() {
        return FfmSqliteDriver.MAJOR + "." + FfmSqliteDriver.MINOR;
    }

    @Override
    public int getDriverMajorVersion() {
        return FfmSqliteDriver.MAJOR;
    }

    @Override
    public int getDriverMinorVersion() {
        return FfmSqliteDriver.MINOR;
    }

    @Override
    public int getJDBCMajorVersion() {
        return 4;
    }

    @Override
    public int getJDBCMinorVersion() {
        return 3;
    }

    @Override
    public String getURL() {
        return conn.url;
    }

    @Override
    public String getUserName() {
        return null;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return conn.isReadOnly();
    }

    @Override
    public Connection getConnection() {
        return conn;
    }

    // ------------------------------------------------------------------ SQL dialect facts

    @Override
    public String getIdentifierQuoteString() {
        return "\"";
    }

    @Override
    public String getSQLKeywords() {
        return "ABORT,ANALYZE,ATTACH,AUTOINCREMENT,CONFLICT,DATABASE,DETACH,EXCLUSIVE,EXPLAIN,FAIL,GLOB,IGNORE,INDEXED,INSTEAD,"
                + "ISNULL,LIMIT,NOTNULL,OFFSET,PLAN,PRAGMA,QUERY,RAISE,REGEXP,REINDEX,RENAME,REPLACE,TEMP,VACUUM,VIRTUAL";
    }

    @Override
    public String getNumericFunctions() {
        return "abs,acos,asin,atan,atan2,ceil,ceiling,cos,degrees,exp,floor,ln,log,log10,log2,max,min,mod,pi,pow,power,radians,"
                + "random,round,sign,sin,sqrt,tan,trunc";
    }

    @Override
    public String getStringFunctions() {
        return "char,format,glob,hex,instr,length,like,lower,ltrim,printf,quote,replace,rtrim,soundex,substr,substring,trim,"
                + "unhex,unicode,upper";
    }

    @Override
    public String getSystemFunctions() {
        return "changes,coalesce,ifnull,iif,last_insert_rowid,nullif,sqlite_version,total_changes,typeof";
    }

    @Override
    public String getTimeDateFunctions() {
        return "date,datetime,julianday,strftime,time,timediff,unixepoch";
    }

    @Override
    public String getSearchStringEscape() {
        return "\\";
    }

    @Override
    public String getExtraNameCharacters() {
        return "";
    }

    @Override
    public String getSchemaTerm() {
        return "schema";
    }

    @Override
    public String getProcedureTerm() {
        return "procedure";
    }

    @Override
    public String getCatalogTerm() {
        return "catalog";
    }

    @Override
    public boolean isCatalogAtStart() {
        return true;
    }

    @Override
    public String getCatalogSeparator() {
        return ".";
    }

    @Override
    public int getDefaultTransactionIsolation() {
        return Connection.TRANSACTION_SERIALIZABLE;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) {
        return level == Connection.TRANSACTION_SERIALIZABLE || level == Connection.TRANSACTION_READ_UNCOMMITTED;
    }

    @Override
    public boolean supportsResultSetType(int type) {
        return type == ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) {
        return holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getResultSetHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getSQLStateType() {
        return sqlStateSQL;
    }

    @Override
    public RowIdLifetime getRowIdLifetime() {
        return RowIdLifetime.ROWID_UNSUPPORTED;
    }

    @Override
    public int getMaxTablesInSelect() {
        return 64;
    }

    // Supported (true)
    @Override public boolean allTablesAreSelectable() { return true; }
    @Override public boolean nullsAreSortedLow() { return true; }
    @Override public boolean usesLocalFiles() { return true; }
    @Override public boolean storesMixedCaseIdentifiers() { return true; }
    @Override public boolean storesMixedCaseQuotedIdentifiers() { return true; }
    @Override public boolean supportsAlterTableWithAddColumn() { return true; }
    @Override public boolean supportsAlterTableWithDropColumn() { return true; }
    @Override public boolean supportsColumnAliasing() { return true; }
    @Override public boolean nullPlusNonNullIsNull() { return true; }
    @Override public boolean supportsTableCorrelationNames() { return true; }
    @Override public boolean supportsExpressionsInOrderBy() { return true; }
    @Override public boolean supportsOrderByUnrelated() { return true; }
    @Override public boolean supportsGroupBy() { return true; }
    @Override public boolean supportsGroupByUnrelated() { return true; }
    @Override public boolean supportsGroupByBeyondSelect() { return true; }
    @Override public boolean supportsLikeEscapeClause() { return true; }
    @Override public boolean supportsMultipleTransactions() { return true; }
    @Override public boolean supportsNonNullableColumns() { return true; }
    @Override public boolean supportsMinimumSQLGrammar() { return true; }
    @Override public boolean supportsCoreSQLGrammar() { return true; }
    @Override public boolean supportsOuterJoins() { return true; }
    @Override public boolean supportsFullOuterJoins() { return true; }
    @Override public boolean supportsLimitedOuterJoins() { return true; }
    @Override public boolean supportsSchemasInDataManipulation() { return true; }
    @Override public boolean supportsSchemasInTableDefinitions() { return true; }
    @Override public boolean supportsSchemasInIndexDefinitions() { return true; }
    @Override public boolean supportsSubqueriesInComparisons() { return true; }
    @Override public boolean supportsSubqueriesInExists() { return true; }
    @Override public boolean supportsSubqueriesInIns() { return true; }
    @Override public boolean supportsSubqueriesInQuantifieds() { return true; }
    @Override public boolean supportsCorrelatedSubqueries() { return true; }
    @Override public boolean supportsUnion() { return true; }
    @Override public boolean supportsUnionAll() { return true; }
    @Override public boolean supportsOpenStatementsAcrossCommit() { return true; }
    @Override public boolean supportsOpenStatementsAcrossRollback() { return true; }
    @Override public boolean supportsTransactions() { return true; }
    @Override public boolean supportsDataDefinitionAndDataManipulationTransactions() { return true; }
    @Override public boolean supportsBatchUpdates() { return true; }
    @Override public boolean supportsSavepoints() { return true; }
    @Override public boolean supportsGetGeneratedKeys() { return true; }

    // Not supported (false)
    @Override public boolean allProceduresAreCallable() { return false; }
    @Override public boolean nullsAreSortedHigh() { return false; }
    @Override public boolean nullsAreSortedAtStart() { return false; }
    @Override public boolean nullsAreSortedAtEnd() { return false; }
    @Override public boolean usesLocalFilePerTable() { return false; }
    @Override public boolean supportsMixedCaseIdentifiers() { return false; }
    @Override public boolean storesUpperCaseIdentifiers() { return false; }
    @Override public boolean storesLowerCaseIdentifiers() { return false; }
    @Override public boolean supportsMixedCaseQuotedIdentifiers() { return false; }
    @Override public boolean storesUpperCaseQuotedIdentifiers() { return false; }
    @Override public boolean storesLowerCaseQuotedIdentifiers() { return false; }
    @Override public boolean supportsConvert() { return false; }
    @Override public boolean supportsConvert(int fromType, int toType) { return false; }
    @Override public boolean supportsDifferentTableCorrelationNames() { return false; }
    @Override public boolean supportsMultipleResultSets() { return false; }
    @Override public boolean supportsExtendedSQLGrammar() { return false; }
    @Override public boolean supportsANSI92EntryLevelSQL() { return false; }
    @Override public boolean supportsANSI92IntermediateSQL() { return false; }
    @Override public boolean supportsANSI92FullSQL() { return false; }
    @Override public boolean supportsIntegrityEnhancementFacility() { return false; }
    @Override public boolean supportsSchemasInProcedureCalls() { return false; }
    @Override public boolean supportsSchemasInPrivilegeDefinitions() { return false; }
    @Override public boolean supportsCatalogsInDataManipulation() { return false; }
    @Override public boolean supportsCatalogsInProcedureCalls() { return false; }
    @Override public boolean supportsCatalogsInTableDefinitions() { return false; }
    @Override public boolean supportsCatalogsInIndexDefinitions() { return false; }
    @Override public boolean supportsCatalogsInPrivilegeDefinitions() { return false; }
    @Override public boolean supportsPositionedDelete() { return false; }
    @Override public boolean supportsPositionedUpdate() { return false; }
    @Override public boolean supportsSelectForUpdate() { return false; }
    @Override public boolean supportsStoredProcedures() { return false; }
    @Override public boolean supportsOpenCursorsAcrossCommit() { return false; }
    @Override public boolean supportsOpenCursorsAcrossRollback() { return false; }
    @Override public boolean doesMaxRowSizeIncludeBlobs() { return false; }
    @Override public boolean supportsDataManipulationTransactionsOnly() { return false; }
    @Override public boolean dataDefinitionCausesTransactionCommit() { return false; }
    @Override public boolean dataDefinitionIgnoredInTransactions() { return false; }
    @Override public boolean ownUpdatesAreVisible(int type) { return false; }
    @Override public boolean ownDeletesAreVisible(int type) { return false; }
    @Override public boolean ownInsertsAreVisible(int type) { return false; }
    @Override public boolean othersUpdatesAreVisible(int type) { return false; }
    @Override public boolean othersDeletesAreVisible(int type) { return false; }
    @Override public boolean othersInsertsAreVisible(int type) { return false; }
    @Override public boolean updatesAreDetected(int type) { return false; }
    @Override public boolean deletesAreDetected(int type) { return false; }
    @Override public boolean insertsAreDetected(int type) { return false; }
    @Override public boolean supportsNamedParameters() { return false; }
    @Override public boolean supportsMultipleOpenResults() { return false; }
    @Override public boolean locatorsUpdateCopy() { return false; }
    @Override public boolean supportsStatementPooling() { return false; }
    @Override public boolean supportsStoredFunctionsUsingCallSyntax() { return false; }
    @Override public boolean autoCommitFailureClosesAllResultSets() { return false; }
    @Override public boolean generatedKeyAlwaysReturned() { return false; }

    // Limits: 0 means none or unknown
    @Override public int getMaxBinaryLiteralLength() { return 0; }
    @Override public int getMaxCharLiteralLength() { return 0; }
    @Override public int getMaxColumnNameLength() { return 0; }
    @Override public int getMaxColumnsInGroupBy() { return 0; }
    @Override public int getMaxColumnsInIndex() { return 0; }
    @Override public int getMaxColumnsInOrderBy() { return 0; }
    @Override public int getMaxColumnsInSelect() { return 0; }
    @Override public int getMaxColumnsInTable() { return 0; }
    @Override public int getMaxConnections() { return 0; }
    @Override public int getMaxCursorNameLength() { return 0; }
    @Override public int getMaxIndexLength() { return 0; }
    @Override public int getMaxSchemaNameLength() { return 0; }
    @Override public int getMaxProcedureNameLength() { return 0; }
    @Override public int getMaxCatalogNameLength() { return 0; }
    @Override public int getMaxRowSize() { return 0; }
    @Override public int getMaxStatementLength() { return 0; }
    @Override public int getMaxStatements() { return 0; }
    @Override public int getMaxTableNameLength() { return 0; }
    @Override public int getMaxUserNameLength() { return 0; }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        if (iface.isInstance(md())) {
            return iface.cast(md());
        }
        throw new SQLException("not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this) || iface == io.github.psydic.ffmsqlite.db.DatabaseMetaData.class;
    }
}
