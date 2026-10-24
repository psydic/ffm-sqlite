package io.github.revsnai.ffmsqlite.db;

/**
 * Called periodically while SQLite executes a statement ({@code sqlite3_progress_handler}), set with
 * {@link Connection#setProgressHandler}. Useful for progress reporting, UI responsiveness and custom
 * cancellation; for plain time limits use {@link Statement#setQueryTimeout}.
 *
 * <p>Must not use the connection (SQLite forbids it; the library throws). Returning {@code false} or
 * throwing aborts the running statement with SQLITE_INTERRUPT (an exception becomes the cause).
 */
@FunctionalInterface
public interface ProgressHandler {

    /** @return {@code true} to continue, {@code false} to abort the running statement */
    boolean proceed() throws Exception;
}
