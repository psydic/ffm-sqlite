package io.github.revsnai.ffmsqlite.db;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings for {@link Connection#backup} and {@link Connection#restore}.
 *
 * @param schema       which database of the connection to copy: {@code "main"} (default), {@code "temp"},
 *                     or the name of an ATTACHed database
 * @param pagesPerStep pages copied per step; {@code -1} (default) copies everything in one step, holding the
 *                     source's read lock throughout. A positive value releases the lock between steps, so other
 *                     connections can write while a large backup runs (SQLite restarts the copy if they do).
 * @param pause        sleep between steps when {@code pagesPerStep > 0}, giving other connections a chance
 * @param progress     called after every step, or {@code null}
 */
public record BackupOptions(String schema, int pagesPerStep, Duration pause, Progress progress) {

    /**
     * Reports progress after each step; return {@code false} to cancel the copy (it is then rolled back).
     * Closing either connection, or deserializing into it, from here throws: the backup is still using it.
     */
    @FunctionalInterface
    public interface Progress {
        boolean onProgress(int remainingPages, int totalPages);
    }

    public static final BackupOptions DEFAULT = new BackupOptions("main", -1, Duration.ZERO, null);

    public BackupOptions {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(pause, "pause");
        if (pagesPerStep == 0 || pagesPerStep < -1) {
            throw new IllegalArgumentException("pagesPerStep must be positive, or -1 for all at once");
        }
        if (pause.isNegative()) {
            throw new IllegalArgumentException("pause must not be negative");
        }
    }

    public BackupOptions withSchema(String schema) {
        return new BackupOptions(schema, pagesPerStep, pause, progress);
    }

    public BackupOptions withPagesPerStep(int pagesPerStep) {
        return new BackupOptions(schema, pagesPerStep, pause, progress);
    }

    public BackupOptions withPause(Duration pause) {
        return new BackupOptions(schema, pagesPerStep, pause, progress);
    }

    public BackupOptions withProgress(Progress progress) {
        return new BackupOptions(schema, pagesPerStep, pause, progress);
    }
}
