package io.github.psydic.ffmsqlite.jdbc;

import java.sql.Types;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mapping SQLite declared types and storage classes to {@code java.sql.Types}, following SQLite's affinity rules. */
final class SqlTypes {
    private static final Pattern PRECISION = Pattern.compile("\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)");

    /** The JDBC type of a declared column type (e.g. {@code VARCHAR(20)}, {@code BIGINT}, {@code DATETIME}). */
    static int ofDeclared(String declared) {
        if (declared == null || declared.isBlank()) {
            return Types.NUMERIC;
        }
        String t = declared.toUpperCase(Locale.ROOT).strip();
        if (t.startsWith("BOOL")) {
            return Types.BOOLEAN;
        }
        if (t.startsWith("TINYINT")) {
            return Types.TINYINT;
        }
        if (t.startsWith("SMALLINT")) {
            return Types.SMALLINT;
        }
        if (t.startsWith("BIGINT")) {
            return Types.BIGINT;
        }
        if (t.startsWith("DATETIME") || t.startsWith("TIMESTAMP")) {
            return Types.TIMESTAMP;
        }
        if (t.startsWith("DATE")) {
            return Types.DATE;
        }
        if (t.startsWith("TIME")) {
            return Types.TIME;
        }
        if (t.contains("INT")) {
            return Types.INTEGER;
        }
        if (t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT")) {
            return Types.VARCHAR;
        }
        if (t.contains("BLOB")) {
            return Types.BLOB;
        }
        if (t.contains("REAL")) {
            return Types.REAL;
        }
        if (t.contains("FLOA")) {
            return Types.FLOAT;
        }
        if (t.contains("DOUB")) {
            return Types.DOUBLE;
        }
        if (t.startsWith("DECIMAL")) {
            return Types.DECIMAL;
        }
        return Types.NUMERIC;
    }

    /** The JDBC type of a stored value: Long, Double, String, byte[] or null. */
    static int ofStorage(Object value) {
        return switch (value) {
            case null -> Types.NULL;
            case Long l -> Types.INTEGER;
            case Integer i -> Types.INTEGER;
            case Double d -> Types.FLOAT;
            case String s -> Types.VARCHAR;
            case byte[] b -> Types.BLOB;
            default -> Types.OTHER;
        };
    }

    /** SQLite's name for a storage class, as sqlite-jdbc reports for columns without a declared type. */
    static String storageName(Object value) {
        return switch (value) {
            case null -> "NULL";
            case Long l -> "INTEGER";
            case Integer i -> "INTEGER";
            case Double d -> "FLOAT";
            case String s -> "TEXT";
            case byte[] b -> "BLOB";
            default -> "NUMERIC";
        };
    }

    static String name(int type) {
        return switch (type) {
            case Types.BOOLEAN -> "BOOLEAN";
            case Types.TINYINT -> "TINYINT";
            case Types.SMALLINT -> "SMALLINT";
            case Types.INTEGER -> "INTEGER";
            case Types.BIGINT -> "BIGINT";
            case Types.REAL -> "REAL";
            case Types.FLOAT -> "FLOAT";
            case Types.DOUBLE -> "DOUBLE";
            case Types.DECIMAL -> "DECIMAL";
            case Types.VARCHAR -> "VARCHAR";
            case Types.BLOB -> "BLOB";
            case Types.DATE -> "DATE";
            case Types.TIME -> "TIME";
            case Types.TIMESTAMP -> "TIMESTAMP";
            case Types.NULL -> "NULL";
            default -> "NUMERIC";
        };
    }

    static String className(int type) {
        return switch (type) {
            case Types.BOOLEAN -> "java.lang.Boolean";
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> "java.lang.Integer";
            case Types.BIGINT -> "java.lang.Long";
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> "java.lang.Double";
            case Types.DECIMAL, Types.NUMERIC -> "java.math.BigDecimal";
            case Types.VARCHAR -> "java.lang.String";
            case Types.BLOB -> "[B";
            case Types.DATE -> "java.sql.Date";
            case Types.TIME -> "java.sql.Time";
            case Types.TIMESTAMP -> "java.sql.Timestamp";
            default -> "java.lang.Object";
        };
    }

    static boolean signed(int type) {
        return switch (type) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.REAL, Types.FLOAT, Types.DOUBLE,
                 Types.DECIMAL, Types.NUMERIC -> true;
            default -> false;
        };
    }

    /** {@code DECIMAL(10,2)} → 10; 0 when the declaration has no precision. */
    static int precision(String declared) {
        Matcher m = declared == null ? null : PRECISION.matcher(declared);
        return m != null && m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** {@code DECIMAL(10,2)} → 2; 0 otherwise. */
    static int scale(String declared) {
        Matcher m = declared == null ? null : PRECISION.matcher(declared);
        return m != null && m.find() && m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
    }

    private SqlTypes() {
    }
}
