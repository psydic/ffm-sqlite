package io.github.psydic.ffmsqlite.db;

/**
 * Thrown when a statement exceeds its {@link Statement#setQueryTimeout query timeout}, in the role of
 * {@code java.sql.SQLTimeoutException}. The statement was interrupted (SQLITE_INTERRUPT) and its changes
 * rolled back; the connection remains usable.
 */
public class SQLiteTimeoutException extends SQLiteException {

    public SQLiteTimeoutException(String message, int errorCode, int extendedErrorCode) {
        super(message, errorCode, extendedErrorCode);
    }
}
