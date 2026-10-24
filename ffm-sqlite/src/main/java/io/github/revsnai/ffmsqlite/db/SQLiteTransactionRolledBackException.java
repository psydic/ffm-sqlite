package io.github.revsnai.ffmsqlite.db;

/**
 * Thrown in manual-commit mode after SQLite has rolled back the whole transaction because a statement failed
 * (e.g. a query timeout or interrupt, SQLITE_FULL, an I/O error), in the role of
 * {@code java.sql.SQLTransactionRollbackException}. The work done earlier in the transaction is lost: every later
 * statement and {@link Connection#commit()} fail with this exception until {@link Connection#rollback()} starts a
 * new transaction, so the loss cannot go unnoticed. The failure that caused the rollback is the cause.
 */
public class SQLiteTransactionRolledBackException extends SQLiteException {

    public SQLiteTransactionRolledBackException(String message) {
        super(message);
    }
}
