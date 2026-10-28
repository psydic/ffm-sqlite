package io.github.revsnai.ffmsqlite.db;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * The SQLite online backup loop: {@code sqlite3_backup_init}, {@code _step} until DONE, {@code _finish}.
 *
 * <p>The destination is written inside one transaction that {@code _finish} commits only after DONE;
 * finishing early (cancel, error) rolls it back, leaving the destination as it was. If the source is
 * changed through another connection between steps, SQLite restarts the copy; changes made through the
 * source connection itself are applied to the copy as well. Either way the result is a consistent snapshot.
 */
final class Backup {

    static void copy(Connection dest, String destSchema, Connection source, String sourceSchema, BackupOptions options,
                     String context) throws SQLiteException {
        MemorySegment backup;
        try (Arena arena = Arena.ofConfined()) {
            backup = Native.backup_init(dest.db, arena.allocateFrom(destSchema), source.db, arena.allocateFrom(sourceSchema));
        }
        if (backup.equals(MemorySegment.NULL)) {
            throw SQLiteException.of(dest.db, Native.extended_errcode(dest.db), context);
        }
        int rc = Native.SQLITE_OK;
        String abort = null;
        dest.beginBackup(true);
        source.beginBackup(false);
        try {
            do {
                rc = Native.backup_step(backup, options.pagesPerStep());
                if (rc != Native.SQLITE_OK && rc != Native.SQLITE_DONE) {
                    break; // BUSY (the busy timeout already elapsed), LOCKED, NOMEM, I/O errors, READONLY...
                }
                if (options.progress() != null
                        && !options.progress().onProgress(Native.backup_remaining(backup), Native.backup_pagecount(backup))) {
                    abort = "cancelled by the progress callback";
                    break;
                }
                if (rc == Native.SQLITE_OK && !options.pause().isZero()) {
                    try {
                        Thread.sleep(options.pause());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        abort = "thread interrupted";
                        break;
                    }
                }
            } while (rc == Native.SQLITE_OK);
        } finally {
            int finish = Native.backup_finish(backup); // also runs if the progress callback threw
            source.endBackup(false);
            dest.endBackup(true);
            if (finish != Native.SQLITE_OK && abort == null && rc == Native.SQLITE_DONE) {
                rc = finish;
            }
        }
        if (abort != null) {
            throw new SQLiteException(context + ": " + abort + "; the destination was left unchanged");
        }
        if (rc != Native.SQLITE_DONE) {
            // After _finish, the error code and message are available on the destination connection.
            throw SQLiteException.of(dest.db, rc, context);
        }
    }

    private Backup() {
    }
}
