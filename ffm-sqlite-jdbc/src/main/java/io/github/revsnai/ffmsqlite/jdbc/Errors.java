package io.github.revsnai.ffmsqlite.jdbc;

import io.github.revsnai.ffmsqlite.db.SQLiteException;
import io.github.revsnai.ffmsqlite.db.SQLiteTimeoutException;

import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;

/**
 * Translates ffm-sqlite errors into the {@code java.sql.SQLException} hierarchy, by SQLite result code, with a
 * SQLState. {@code getErrorCode()} is SQLite's primary result code (as in sqlite-jdbc); the original exception,
 * with the extended code, is the cause.
 */
final class Errors {

    static SQLException of(SQLiteException e) {
        String msg = e.getMessage();
        int code = e.getErrorCode();
        if (e instanceof SQLiteTimeoutException) {
            return new SQLTimeoutException(msg, "HYT00", code, e);
        }
        return switch (code) {
            case 19 -> new SQLIntegrityConstraintViolationException(msg, constraintState(e.getExtendedErrorCode()), code, e);
            case 5, 6 -> new SQLTransientException(msg, "40001", code, e);                            // BUSY, LOCKED
            case 9 -> new SQLTransientException(msg, "HY008", code, e);                               // INTERRUPT (cancel)
            case 8 -> new SQLNonTransientException(msg, "25006", code, e);                            // READONLY
            case 14 -> new SQLNonTransientConnectionException(msg, "08001", code, e);                 // CANTOPEN
            case 23 -> new SQLInvalidAuthorizationSpecException(msg, "28000", code, e);               // AUTH
            case 18, 20, 25 -> new SQLDataException(msg, "22000", code, e);                           // TOOBIG, MISMATCH, RANGE
            case 10, 13 -> new SQLNonTransientException(msg, "58030", code, e);                       // IOERR, FULL
            case 11, 26 -> new SQLNonTransientException(msg, "XX001", code, e);                       // CORRUPT, NOTADB
            case 1 -> isSyntax(msg) ? new SQLSyntaxErrorException(msg, "42000", code, e) : new SQLException(msg, "HY000", code, e);
            default -> new SQLException(msg, "HY000", code, e);
        };
    }

    /**
     * Standard SQLStates by SQLite's extended constraint code, so frameworks recognize them (e.g. Spring maps
     * 23505 to DuplicateKeyException).
     */
    private static String constraintState(int extended) {
        return switch (extended) {
            case 1555, 2067, 2579 -> "23505"; // PRIMARYKEY, UNIQUE, ROWID: unique violation
            case 1299 -> "23502";             // NOTNULL
            case 787 -> "23503";              // FOREIGNKEY
            case 275 -> "23514";              // CHECK
            default -> "23000";               // COMMITHOOK, TRIGGER, DATATYPE, …
        };
    }

    private static boolean isSyntax(String msg) {
        return msg != null && (msg.contains("syntax error") || msg.contains("no such table") || msg.contains("no such column")
                || msg.contains("no such function") || msg.contains("incomplete input") || msg.contains("unrecognized token"));
    }

    static SQLException closed(String what) {
        return new SQLNonTransientException(what + " is closed", "HY010");
    }

    static SQLFeatureNotSupportedException unsupported(String feature) {
        return new SQLFeatureNotSupportedException(feature + " is not supported by ffm-sqlite-jdbc", "0A000");
    }

    static SQLException forwardOnly() {
        return new SQLException("ResultSet is TYPE_FORWARD_ONLY", "24000");
    }

    private Errors() {
    }
}
