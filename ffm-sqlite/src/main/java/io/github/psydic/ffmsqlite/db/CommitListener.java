package io.github.psydic.ffmsqlite.db;

/**
 * Notified when a transaction commits or rolls back ({@code sqlite3_commit_hook} / {@code sqlite3_rollback_hook}),
 * registered with {@link Connection#addCommitListener}. Covers explicit transactions and the implicit
 * transaction of every writing statement in auto-commit mode.
 *
 * <p>Must not use the connection (SQLite forbids it; the library throws).
 */
public interface CommitListener {

    /**
     * Called just before a transaction commits. Throwing <b>vetoes</b> the commit: SQLite rolls the
     * transaction back, {@link #onRollback} follows, and the COMMIT (or the auto-committing statement)
     * fails with SQLITE_CONSTRAINT_COMMITHOOK and this exception as the cause.
     */
    void onCommit() throws Exception;

    /**
     * Called after a transaction rolled back: by ROLLBACK, an error, or a vetoed commit. Not called when
     * closing a connection discards an open transaction. An exception here cannot undo anything; it is
     * reported as the error of the statement that caused the rollback.
     */
    default void onRollback() throws Exception {
    }
}
