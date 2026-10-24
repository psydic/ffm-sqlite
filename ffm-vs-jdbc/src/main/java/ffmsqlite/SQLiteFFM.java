package ffmsqlite;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;

/** Minimal SQLite connection over FFM: open, exec, prepare, close. */
public final class SQLiteFFM implements AutoCloseable {
    private final MemorySegment db;

    private SQLiteFFM(MemorySegment db) {
        this.db = db;
    }

    public static SQLiteFFM open(String path) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ppDb = arena.allocate(ADDRESS);
            int rc = (int) Sqlite3.open_v2.invokeExact(arena.allocateFrom(path), ppDb,
                    Sqlite3.SQLITE_OPEN_READWRITE | Sqlite3.SQLITE_OPEN_CREATE, MemorySegment.NULL);
            MemorySegment db = ppDb.get(ADDRESS, 0);
            if (rc != Sqlite3.SQLITE_OK) {
                String msg = errmsg(db);
                int ignored = (int) Sqlite3.close_v2.invokeExact(db);
                throw new IllegalStateException("sqlite3_open_v2(" + path + ") failed: " + rc + " " + msg);
            }
            return new SQLiteFFM(db);
        }
    }

    public MemorySegment handle() {
        return db;
    }

    public void exec(String sql) throws Throwable {
        exec(sql, MemorySegment.NULL, MemorySegment.NULL);
    }

    /** Runs {@code sql} via sqlite3_exec; {@code callback} is an upcall stub or NULL. */
    public void exec(String sql, MemorySegment callback, MemorySegment arg) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment errOut = arena.allocate(ADDRESS);
            int rc = (int) Sqlite3.exec.invokeExact(db, arena.allocateFrom(sql), callback, arg, errOut);
            if (rc != Sqlite3.SQLITE_OK) {
                MemorySegment err = errOut.get(ADDRESS, 0);
                String msg = err.equals(MemorySegment.NULL) ? "" : err.reinterpret(Long.MAX_VALUE).getString(0);
                Sqlite3.free.invokeExact(err);
                throw new IllegalStateException("sqlite3_exec failed: " + rc + " " + msg + " [" + sql + "]");
            }
        }
    }

    /** Compiles {@code sql} once; the returned sqlite3_stmt* must be released with {@link #finalize(MemorySegment)}. */
    public MemorySegment prepare(String sql) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ppStmt = arena.allocate(ADDRESS);
            int rc = (int) Sqlite3.prepare_v2.invokeExact(db, arena.allocateFrom(sql), -1, ppStmt, MemorySegment.NULL);
            check(rc, "sqlite3_prepare_v2");
            return ppStmt.get(ADDRESS, 0);
        }
    }

    public static void finalize(MemorySegment stmt) throws Throwable {
        int ignored = (int) Sqlite3.finalize_.invokeExact(stmt);
    }

    public void check(int rc, String what) throws Throwable {
        if (rc != Sqlite3.SQLITE_OK) {
            throw new IllegalStateException(what + " failed: " + rc + " " + errmsg(db));
        }
    }

    private static String errmsg(MemorySegment db) throws Throwable {
        MemorySegment msg = (MemorySegment) Sqlite3.errmsg.invokeExact(db);
        return msg.reinterpret(Long.MAX_VALUE).getString(0);
    }

    public static String libVersion() throws Throwable {
        MemorySegment v = (MemorySegment) Sqlite3.libversion.invokeExact();
        return v.reinterpret(Long.MAX_VALUE).getString(0);
    }

    @Override
    public void close() {
        try {
            int ignored = (int) Sqlite3.close_v2.invokeExact(db);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
