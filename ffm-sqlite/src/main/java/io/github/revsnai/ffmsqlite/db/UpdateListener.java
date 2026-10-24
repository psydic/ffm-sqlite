package io.github.revsnai.ffmsqlite.db;

/**
 * Notified of every row inserted, updated or deleted through a connection ({@code sqlite3_update_hook}),
 * registered with {@link Connection#addUpdateListener}.
 *
 * <p>Called during the statement, before its transaction commits, so the change may still be rolled
 * back; pair with a {@link CommitListener} to act on committed changes only. SQLite does not report rows
 * of WITHOUT ROWID tables, rows of internal tables such as {@code sqlite_sequence}, rows removed by
 * {@code DELETE} without a WHERE clause (the truncate optimization), or rows replaced by
 * {@code ON CONFLICT REPLACE}.
 *
 * <p>Must not use the connection (SQLite forbids it; the library throws). An exception fails the
 * statement, with the exception as the cause, and makes the transaction rollback-only.
 */
@FunctionalInterface
public interface UpdateListener {

    enum Type { INSERT, UPDATE, DELETE }

    /**
     * @param database the schema: {@code main}, {@code temp} or an ATTACHed name
     * @param rowId    the row's rowid (for UPDATE, the rowid after the update)
     */
    void onUpdate(Type type, String database, String table, long rowId) throws Exception;
}
