package io.github.revsnai.ffmsqlite.db;

/**
 * Decides what to do when a database file is locked by another connection or process
 * ({@code sqlite3_busy_handler}), set with {@link Connection#setBusyHandler}. Replaces the busy timeout.
 *
 * <pre>{@code
 * conn.setBusyHandler(attempts -> {          // exponential backoff, give up after ~2 s
 *     if (attempts >= 10) return false;
 *     Thread.sleep(2L << attempts);
 *     return true;
 * });
 * }</pre>
 *
 * <p>May sleep, and may use <em>other</em> connections, but not the one that invoked it (SQLite forbids it;
 * the library throws). An exception gives up: the statement fails with SQLITE_BUSY and the exception as cause.
 * SQLite may skip the handler and fail at once when waiting could deadlock (e.g. two connections both
 * trying to upgrade a read transaction to a write transaction).
 */
@FunctionalInterface
public interface BusyHandler {

    /**
     * @param attempts how often the handler has already been called for this lock (0 on the first call)
     * @return {@code true} to retry the lock, {@code false} to give up with SQLITE_BUSY
     */
    boolean retry(int attempts) throws Exception;
}
