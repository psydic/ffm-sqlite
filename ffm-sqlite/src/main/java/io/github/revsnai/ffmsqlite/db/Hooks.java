package io.github.revsnai.ffmsqlite.db;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

/**
 * A connection's update, commit, rollback and busy hooks: one native upcall stub per kind, installed only
 * while a Java listener needs it, dispatching to the registered listeners.
 *
 * <p>Rules enforced here, because SQLite leaves breaking them undefined or silent:
 * <ul>
 *   <li>While a hook runs, the connection must not be used: {@link Connection#checkOpen} throws when
 *       {@link #depth} is non-zero.</li>
 *   <li>A failing update listener fails the statement (via {@code sqlite3_interrupt} plus the after-step
 *       check) and makes the transaction <em>rollback-only</em>: the commit hook vetoes any commit until the
 *       transaction has rolled back. So a statement whose listener failed is never committed, even if it
 *       completed before SQLite noticed the interrupt. The commit hook is therefore installed whenever
 *       update listeners are.</li>
 *   <li>Exceptions never escape an upcall; they are recorded as the failing statement's cause.</li>
 * </ul>
 */
final class Hooks {
    private final Connection conn;
    final List<UpdateListener> updateListeners = new ArrayList<>();
    final List<CommitListener> commitListeners = new ArrayList<>();
    BusyHandler busyHandler;
    ProgressHandler progressHandler;
    int progressInstructions;

    /** System.nanoTime() after which the running statement is interrupted; 0 when no query timeout applies. */
    long deadline;
    /** The timeout behind {@link #deadline}, for the error message. */
    int deadlineMillis;
    /** Set once a statement uses a query timeout: from then on the progress callback stays installed. */
    boolean timeoutsUsed;

    /** Non-zero while a hook is running; the connection is unusable then. */
    int depth;
    private boolean rollbackOnly;
    private MemorySegment updateStub, commitStub, rollbackStub, busyStub, progressStub;

    /** Instructions between progress callbacks when only query timeouts need them. */
    private static final int TIMEOUT_CHECK_INSTRUCTIONS = 1000;

    Hooks(Connection conn) {
        this.conn = conn;
    }

    /** Installs or removes the native hooks to match the registered listeners. */
    void sync() {
        boolean updates = !updateListeners.isEmpty();
        boolean transactions = updates || !commitListeners.isEmpty();
        if (updates && updateStub == null) {
            updateStub = stub("xUpdate", Native.X_UPDATE_HOOK,
                    MethodType.methodType(void.class, MemorySegment.class, int.class, MemorySegment.class, MemorySegment.class, long.class));
        }
        if (transactions && commitStub == null) {
            commitStub = stub("xCommit", Native.X_COMMIT_HOOK, MethodType.methodType(int.class, MemorySegment.class));
            rollbackStub = stub("xRollback", Native.X_ROLLBACK_HOOK, MethodType.methodType(void.class, MemorySegment.class));
        }
        Native.update_hook(conn.db, updates ? updateStub : MemorySegment.NULL);
        Native.commit_hook(conn.db, transactions ? commitStub : MemorySegment.NULL);
        Native.rollback_hook(conn.db, transactions ? rollbackStub : MemorySegment.NULL);
        if (!transactions) {
            rollbackOnly = false;
        }
    }

    /**
     * Removes every native hook before the connection closes. sqlite3_close_v2 rolls back an open
     * transaction through sqlite3RollbackAll, which, contrary to the rollback-hook documentation, does
     * invoke the rollback hook: an upcall into a half-closed connection. Detaching first prevents it.
     */
    void detachAll() {
        if (updateStub != null) {
            Native.update_hook(conn.db, MemorySegment.NULL);
        }
        if (commitStub != null) {
            Native.commit_hook(conn.db, MemorySegment.NULL);
            Native.rollback_hook(conn.db, MemorySegment.NULL);
        }
        if (busyStub != null) {
            Native.busy_handler(conn.db, MemorySegment.NULL);
        }
        if (progressStub != null) {
            Native.progress_handler(conn.db, 0, MemorySegment.NULL);
        }
    }

    /**
     * Installs the progress callback when a {@link #progressHandler} is set or query timeouts are in use.
     * With a handler, the callback runs every {@link #progressInstructions} instructions, which also bounds
     * how promptly a timeout is noticed.
     */
    void syncProgress() {
        boolean needed = progressHandler != null || timeoutsUsed;
        if (needed && progressStub == null) {
            progressStub = stub("xProgress", Native.X_PROGRESS, MethodType.methodType(int.class, MemorySegment.class));
        }
        int every = progressHandler != null ? progressInstructions : TIMEOUT_CHECK_INSTRUCTIONS;
        Native.progress_handler(conn.db, needed ? every : 0, needed ? progressStub : MemorySegment.NULL);
    }

    /** Installs {@link #busyHandler}, or removes any handler (and timeout) when it is null. */
    void syncBusyHandler() {
        if (busyHandler != null && busyStub == null) {
            busyStub = stub("xBusy", Native.X_BUSY_HANDLER, MethodType.methodType(int.class, MemorySegment.class, int.class));
        }
        Native.busy_handler(conn.db, busyHandler != null ? busyStub : MemorySegment.NULL);
    }

    private MemorySegment stub(String method, java.lang.foreign.FunctionDescriptor descriptor, MethodType type) {
        try {
            var target = MethodHandles.lookup().findVirtual(Hooks.class, method, type).bindTo(this);
            return Native.linker().upcallStub(target, descriptor, conn.callbackArena());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------ upcall targets

    void xUpdate(MemorySegment arg, int op, MemorySegment zDb, MemorySegment zTable, long rowId) {
        depth++;
        try {
            UpdateListener.Type type = switch (op) {
                case Native.SQLITE_INSERT -> UpdateListener.Type.INSERT;
                case Native.SQLITE_DELETE -> UpdateListener.Type.DELETE;
                default -> UpdateListener.Type.UPDATE;
            };
            String database = Native.cString(zDb);
            String table = Native.cString(zTable);
            for (UpdateListener l : updateListeners) {
                l.onUpdate(type, database, table, rowId);
            }
        } catch (Throwable t) {
            try {
                rollbackOnly = true;
                conn.recordCallbackFailure(t, "update listener: " + describe(t));
                Native.interrupt(conn.db); // stop a long statement early
            } catch (Throwable ignored) {
                // nothing may escape an upcall
            }
        } finally {
            depth--;
        }
    }

    int xCommit(MemorySegment arg) {
        depth++;
        try {
            if (rollbackOnly) {
                return 1; // an update listener failed in this transaction: never commit it
            }
            for (CommitListener l : commitListeners) {
                l.onCommit();
            }
            return 0;
        } catch (Throwable t) {
            try {
                conn.recordCallbackFailure(t, "commit vetoed by listener: " + describe(t));
            } catch (Throwable ignored) {
                // nothing may escape an upcall
            }
            return 1;
        } finally {
            depth--;
        }
    }

    void xRollback(MemorySegment arg) {
        depth++;
        rollbackOnly = false; // the transaction is gone
        try {
            for (CommitListener l : commitListeners) {
                l.onRollback();
            }
        } catch (Throwable t) {
            try {
                conn.recordCallbackFailure(t, "rollback listener: " + describe(t));
            } catch (Throwable ignored) {
                // nothing may escape an upcall
            }
        } finally {
            depth--;
        }
    }

    int xBusy(MemorySegment arg, int attempts) {
        depth++;
        try {
            BusyHandler h = busyHandler;
            return h != null && h.retry(attempts) ? 1 : 0;
        } catch (Throwable t) {
            try {
                if (t instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                conn.recordCallbackFailure(t, "busy handler: " + describe(t));
            } catch (Throwable ignored) {
                // nothing may escape an upcall
            }
            return 0;
        } finally {
            depth--;
        }
    }

    int xProgress(MemorySegment arg) {
        depth++;
        try {
            if (deadline != 0 && System.nanoTime() - deadline > 0) {
                conn.recordCallbackFailure(new java.util.concurrent.TimeoutException(
                        "query timeout of " + deadlineMillis + " ms exceeded"), "query timeout of " + deadlineMillis + " ms exceeded");
                return 1;
            }
            ProgressHandler h = progressHandler;
            if (h != null && !h.proceed()) {
                conn.recordCallbackFailure(new java.util.concurrent.CancellationException("aborted by the progress handler"),
                        "aborted by the progress handler");
                return 1;
            }
            return 0;
        } catch (Throwable t) {
            try {
                conn.recordCallbackFailure(t, "progress handler: " + describe(t));
            } catch (Throwable ignored) {
                // nothing may escape an upcall
            }
            return 1;
        } finally {
            depth--;
        }
    }

    private static String describe(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getName();
    }
}
