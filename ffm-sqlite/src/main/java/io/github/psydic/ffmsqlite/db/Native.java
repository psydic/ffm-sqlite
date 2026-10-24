package io.github.psydic.ffmsqlite.db;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM bindings to the SQLite C API.
 *
 * <p>Every handle is a {@code static final} field, so C2 constant-folds it and each wrapper below
 * compiles to a direct native call. Short, non-blocking functions that never call back into Java
 * use {@link Linker.Option#critical(boolean)}; functions that may block or run for long
 * ({@code step}, {@code reset}, {@code exec}, {@code prepare}, {@code open}, {@code close}) do not, so they
 * never delay a safepoint, and may call back into Java (user-defined functions).
 *
 * <p>The library is located via {@code -Dffmsqlite.lib=/path/to/libsqlite3.so}, else the first
 * of the usual platform names that loads.
 */
final class Native {
    static final int SQLITE_OK = 0;
    static final int SQLITE_ROW = 100;
    static final int SQLITE_DONE = 101;

    static final int SQLITE_INTEGER = 1;
    static final int SQLITE_FLOAT = 2;
    static final int SQLITE_TEXT = 3;
    static final int SQLITE_BLOB = 4;
    static final int SQLITE_NULL = 5;

    static final int SQLITE_OPEN_READONLY = 0x00000001;
    static final int SQLITE_OPEN_READWRITE = 0x00000002;
    static final int SQLITE_OPEN_CREATE = 0x00000004;
    static final int SQLITE_OPEN_URI = 0x00000040;
    static final int SQLITE_OPEN_NOMUTEX = 0x00008000;

    /** Destructor sentinel: SQLite copies the value before the bind call returns. */
    static final MemorySegment SQLITE_TRANSIENT = MemorySegment.ofAddress(-1L);

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB = loadLibrary();
    private static final Linker.Option CRITICAL = Linker.Option.critical(false);
    private static final Linker.Option CRITICAL_HEAP = Linker.Option.critical(true);

    private static SymbolLookup loadLibrary() {
        String configured = System.getProperty("ffmsqlite.lib");
        List<String> candidates = configured != null
                ? List.of(configured)
                : List.of("libsqlite3.so.0", "libsqlite3.so", "libsqlite3.dylib", "sqlite3.dll", "winsqlite3.dll");
        List<String> failures = new ArrayList<>();
        for (String name : candidates) {
            try {
                return SymbolLookup.libraryLookup(name, Arena.global());
            } catch (IllegalArgumentException e) {
                failures.add(name);
            }
        }
        throw new UnsatisfiedLinkError("Cannot load SQLite (tried " + failures + "); set -Dffmsqlite.lib=/path/to/libsqlite3");
    }

    private static MethodHandle downcall(String name, FunctionDescriptor fd, Linker.Option... options) {
        return LINKER.downcallHandle(LIB.findOrThrow(name), fd, options);
    }

    /** For functions that builds may omit (e.g. SQLITE_OMIT_LOAD_EXTENSION, as in macOS's system SQLite); null if absent. */
    private static MethodHandle optionalDowncall(String name, FunctionDescriptor fd, Linker.Option... options) {
        return LIB.find(name).map(addr -> LINKER.downcallHandle(addr, fd, options)).orElse(null);
    }

    private static final MethodHandle LIBVERSION = downcall("sqlite3_libversion", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle OPEN_V2 = downcall("sqlite3_open_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle CLOSE_V2 = downcall("sqlite3_close_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle BUSY_TIMEOUT = downcall("sqlite3_busy_timeout", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle ERRMSG = downcall("sqlite3_errmsg", FunctionDescriptor.of(ADDRESS, ADDRESS), CRITICAL);
    private static final MethodHandle ERRSTR = downcall("sqlite3_errstr", FunctionDescriptor.of(ADDRESS, JAVA_INT), CRITICAL);
    private static final MethodHandle EXTENDED_ERRCODE = downcall("sqlite3_extended_errcode", FunctionDescriptor.of(JAVA_INT, ADDRESS), CRITICAL);
    private static final MethodHandle CHANGES64 = downcall("sqlite3_changes64", FunctionDescriptor.of(JAVA_LONG, ADDRESS), CRITICAL);
    private static final MethodHandle TOTAL_CHANGES64 = downcall("sqlite3_total_changes64", FunctionDescriptor.of(JAVA_LONG, ADDRESS), CRITICAL);
    private static final MethodHandle LAST_INSERT_ROWID = downcall("sqlite3_last_insert_rowid", FunctionDescriptor.of(JAVA_LONG, ADDRESS), CRITICAL);
    private static final MethodHandle GET_AUTOCOMMIT = downcall("sqlite3_get_autocommit", FunctionDescriptor.of(JAVA_INT, ADDRESS), CRITICAL);

    private static final MethodHandle PREPARE_V2 = downcall("sqlite3_prepare_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle FINALIZE = downcall("sqlite3_finalize", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle STEP = downcall("sqlite3_step", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    // Not critical: resetting a statement that is still running halts it, and halting releases its
    // memory cells, which runs xFinal for any aggregate context still open; that may be a Java upcall,
    // and upcalls are not allowed during a critical downcall.
    private static final MethodHandle RESET = downcall("sqlite3_reset", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CLEAR_BINDINGS = downcall("sqlite3_clear_bindings", FunctionDescriptor.of(JAVA_INT, ADDRESS), CRITICAL);
    private static final MethodHandle BIND_PARAMETER_COUNT = downcall("sqlite3_bind_parameter_count", FunctionDescriptor.of(JAVA_INT, ADDRESS), CRITICAL);

    private static final MethodHandle BIND_INT64 = downcall("sqlite3_bind_int64", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG), CRITICAL);
    private static final MethodHandle BIND_DOUBLE = downcall("sqlite3_bind_double", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_DOUBLE), CRITICAL);
    private static final MethodHandle BIND_NULL = downcall("sqlite3_bind_null", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), CRITICAL);
    private static final MethodHandle BIND_TEXT = downcall("sqlite3_bind_text", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), CRITICAL_HEAP);
    private static final MethodHandle BIND_BLOB = downcall("sqlite3_bind_blob", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), CRITICAL_HEAP);

    private static final MethodHandle COLUMN_COUNT = downcall("sqlite3_column_count", FunctionDescriptor.of(JAVA_INT, ADDRESS), CRITICAL);
    private static final MethodHandle COLUMN_NAME = downcall("sqlite3_column_name", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT), CRITICAL);
    private static final MethodHandle COLUMN_DECLTYPE = downcall("sqlite3_column_decltype", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT), CRITICAL);
    // Need SQLITE_ENABLE_COLUMN_METADATA; null when the library was built without it.
    private static final MethodHandle COLUMN_TABLE_NAME = optionalDowncall("sqlite3_column_table_name", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT), CRITICAL);
    private static final MethodHandle COLUMN_DATABASE_NAME = optionalDowncall("sqlite3_column_database_name", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT), CRITICAL);
    private static final MethodHandle COLUMN_ORIGIN_NAME = optionalDowncall("sqlite3_column_origin_name", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT), CRITICAL);
    private static final MethodHandle COLUMN_TYPE = downcall("sqlite3_column_type", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), CRITICAL);
    private static final MethodHandle COLUMN_INT = downcall("sqlite3_column_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), CRITICAL);
    private static final MethodHandle COLUMN_INT64 = downcall("sqlite3_column_int64", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT), CRITICAL);
    private static final MethodHandle COLUMN_DOUBLE = downcall("sqlite3_column_double", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_INT), CRITICAL);
    private static final MethodHandle COLUMN_TEXT = downcall("sqlite3_column_text", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT), CRITICAL);
    private static final MethodHandle COLUMN_BLOB = downcall("sqlite3_column_blob", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT), CRITICAL);
    private static final MethodHandle COLUMN_BYTES = downcall("sqlite3_column_bytes", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), CRITICAL);

    // --- User-defined functions ---

    static final int SQLITE_UTF8 = 1;
    static final int SQLITE_DETERMINISTIC = 0x000000800;
    static final int SQLITE_DIRECTONLY = 0x000080000;
    static final int SQLITE_INNOCUOUS = 0x000200000;

    /** {@code void xFunc(sqlite3_context*, int argc, sqlite3_value **argv)}, also used for xStep. */
    static final FunctionDescriptor X_FUNC = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS);
    /** {@code void xFinal(sqlite3_context*)} */
    static final FunctionDescriptor X_FINAL = FunctionDescriptor.ofVoid(ADDRESS);

    private static final MethodHandle CREATE_FUNCTION_V2 = downcall("sqlite3_create_function_v2",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CREATE_WINDOW_FUNCTION = downcall("sqlite3_create_window_function",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    /** {@code int xCompare(void *pArg, int len1, const void *s1, int len2, const void *s2)}: UTF-8, not NUL-terminated. */
    static final FunctionDescriptor X_COMPARE = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
    static final int SQLITE_INTERRUPT = 9;

    private static final MethodHandle CREATE_COLLATION_V2 = downcall("sqlite3_create_collation_v2",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    /** Thread-safe and safe to call from inside a callback; makes the running statement fail with SQLITE_INTERRUPT. */
    private static final MethodHandle INTERRUPT = downcall("sqlite3_interrupt", FunctionDescriptor.ofVoid(ADDRESS), CRITICAL);
    // --- Online backup (step/init/finish do I/O and take locks: never critical) ---

    private static final MethodHandle BACKUP_INIT = downcall("sqlite3_backup_init",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle BACKUP_STEP = downcall("sqlite3_backup_step", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle BACKUP_FINISH = downcall("sqlite3_backup_finish", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle BACKUP_REMAINING = downcall("sqlite3_backup_remaining", FunctionDescriptor.of(JAVA_INT, ADDRESS), CRITICAL);
    private static final MethodHandle BACKUP_PAGECOUNT = downcall("sqlite3_backup_pagecount", FunctionDescriptor.of(JAVA_INT, ADDRESS), CRITICAL);
    // --- Hooks ---

    static final int SQLITE_DELETE = 9;
    static final int SQLITE_INSERT = 18;
    static final int SQLITE_UPDATE = 23;

    /** {@code void(void*, int op, const char *db, const char *table, sqlite3_int64 rowid)} */
    static final FunctionDescriptor X_UPDATE_HOOK = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG);
    /** {@code int(void*)}: non-zero turns the commit into a rollback. */
    static final FunctionDescriptor X_COMMIT_HOOK = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    /** {@code void(void*)} */
    static final FunctionDescriptor X_ROLLBACK_HOOK = FunctionDescriptor.ofVoid(ADDRESS);
    /** {@code int(void*, int attempts)}: non-zero retries the lock. */
    static final FunctionDescriptor X_BUSY_HANDLER = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);

    private static final MethodHandle UPDATE_HOOK = downcall("sqlite3_update_hook", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle COMMIT_HOOK = downcall("sqlite3_commit_hook", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle ROLLBACK_HOOK = downcall("sqlite3_rollback_hook", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle BUSY_HANDLER = downcall("sqlite3_busy_handler", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** {@code callback == NULL} removes the hook. Same for the three below. */
    static void update_hook(MemorySegment db, MemorySegment callback) {
        try {
            MemorySegment ignored = (MemorySegment) UPDATE_HOOK.invokeExact(db, callback, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static void commit_hook(MemorySegment db, MemorySegment callback) {
        try {
            MemorySegment ignored = (MemorySegment) COMMIT_HOOK.invokeExact(db, callback, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static void rollback_hook(MemorySegment db, MemorySegment callback) {
        try {
            MemorySegment ignored = (MemorySegment) ROLLBACK_HOOK.invokeExact(db, callback, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** Replaces any busy timeout (they are the same mechanism in SQLite). */
    static int busy_handler(MemorySegment db, MemorySegment callback) {
        try {
            return (int) BUSY_HANDLER.invokeExact(db, callback, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    // --- Progress handler ---

    /** {@code int(void*)}: non-zero interrupts the running statement. */
    static final FunctionDescriptor X_PROGRESS = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final MethodHandle PROGRESS_HANDLER = downcall("sqlite3_progress_handler",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    /** Calls {@code callback} every {@code instructions} VM instructions; NULL removes it. */
    static void progress_handler(MemorySegment db, int instructions, MemorySegment callback) {
        try {
            PROGRESS_HANDLER.invokeExact(db, instructions, callback, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    // --- Extensions ---

    static final int SQLITE_DBCONFIG_ENABLE_LOAD_EXTENSION = 1005;

    /**
     * {@code int sqlite3_db_config(sqlite3*, int op, ...)} is variadic; this op takes {@code (int onoff, int *pResult)}.
     * Variadic C functions need {@code firstVariadicArg}: the ABI may pass variadic arguments differently.
     */
    private static final MethodHandle DB_CONFIG_INT_PTR = downcall("sqlite3_db_config",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), Linker.Option.firstVariadicArg(2));
    private static final MethodHandle LOAD_EXTENSION = optionalDowncall("sqlite3_load_extension",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    static boolean extensionLoadingAvailable() {
        return LOAD_EXTENSION != null;
    }

    static int db_config(MemorySegment db, int op, int value) {
        try {
            return (int) DB_CONFIG_INT_PTR.invokeExact(db, op, value, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** {@code pzErrMsg} receives a sqlite3_malloc'd message on failure (free it with {@link #free}). */
    static int load_extension(MemorySegment db, MemorySegment file, MemorySegment entryPoint, MemorySegment pzErrMsg) {
        try {
            return (int) LOAD_EXTENSION.invokeExact(db, file, entryPoint, pzErrMsg);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    // --- Serialize / deserialize (copy whole databases: never critical) ---

    static final int SQLITE_DESERIALIZE_FREEONCLOSE = 1;
    static final int SQLITE_DESERIALIZE_RESIZEABLE = 2;
    static final int SQLITE_DESERIALIZE_READONLY = 4;

    private static final MethodHandle SERIALIZE = downcall("sqlite3_serialize",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle DESERIALIZE = downcall("sqlite3_deserialize",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT));
    private static final MethodHandle NEXT_STMT = downcall("sqlite3_next_stmt", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS), CRITICAL);
    private static final MethodHandle STMT_BUSY = downcall("sqlite3_stmt_busy", FunctionDescriptor.of(JAVA_INT, ADDRESS), CRITICAL);
    /** 0 = no transaction, 1 = read, 2 = write; -1 for an unknown schema. */
    private static final MethodHandle TXN_STATE = downcall("sqlite3_txn_state", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), CRITICAL);
    private static final MethodHandle MALLOC64 = downcall("sqlite3_malloc64", FunctionDescriptor.of(ADDRESS, JAVA_LONG));
    private static final MethodHandle FREE = downcall("sqlite3_free", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle AGGREGATE_CONTEXT = downcall("sqlite3_aggregate_context", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT), CRITICAL);

    private static final MethodHandle VALUE_TYPE = downcall("sqlite3_value_type", FunctionDescriptor.of(JAVA_INT, ADDRESS), CRITICAL);
    private static final MethodHandle VALUE_INT = downcall("sqlite3_value_int", FunctionDescriptor.of(JAVA_INT, ADDRESS), CRITICAL);
    private static final MethodHandle VALUE_INT64 = downcall("sqlite3_value_int64", FunctionDescriptor.of(JAVA_LONG, ADDRESS), CRITICAL);
    private static final MethodHandle VALUE_DOUBLE = downcall("sqlite3_value_double", FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS), CRITICAL);
    private static final MethodHandle VALUE_TEXT = downcall("sqlite3_value_text", FunctionDescriptor.of(ADDRESS, ADDRESS), CRITICAL);
    private static final MethodHandle VALUE_BLOB = downcall("sqlite3_value_blob", FunctionDescriptor.of(ADDRESS, ADDRESS), CRITICAL);
    private static final MethodHandle VALUE_BYTES = downcall("sqlite3_value_bytes", FunctionDescriptor.of(JAVA_INT, ADDRESS), CRITICAL);

    private static final MethodHandle RESULT_NULL = downcall("sqlite3_result_null", FunctionDescriptor.ofVoid(ADDRESS), CRITICAL);
    private static final MethodHandle RESULT_INT64 = downcall("sqlite3_result_int64", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG), CRITICAL);
    private static final MethodHandle RESULT_DOUBLE = downcall("sqlite3_result_double", FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE), CRITICAL);
    private static final MethodHandle RESULT_TEXT = downcall("sqlite3_result_text", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS), CRITICAL_HEAP);
    private static final MethodHandle RESULT_BLOB = downcall("sqlite3_result_blob", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS), CRITICAL_HEAP);
    private static final MethodHandle RESULT_ERROR = downcall("sqlite3_result_error", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT), CRITICAL_HEAP);

    static Linker linker() {
        return LINKER;
    }

    static int create_function_v2(MemorySegment db, MemorySegment name, int nArg, int flags,
                                  MemorySegment xFunc, MemorySegment xStep, MemorySegment xFinal) {
        try {
            return (int) CREATE_FUNCTION_V2.invokeExact(db, name, nArg, flags, MemorySegment.NULL, xFunc, xStep, xFinal, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** xValue has the xFinal signature, xInverse the xStep one. */
    static int create_window_function(MemorySegment db, MemorySegment name, int nArg, int flags, MemorySegment xStep,
                                      MemorySegment xFinal, MemorySegment xValue, MemorySegment xInverse) {
        try {
            return (int) CREATE_WINDOW_FUNCTION.invokeExact(db, name, nArg, flags, MemorySegment.NULL,
                    xStep, xFinal, xValue, xInverse, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** {@code xCompare == NULL} removes the collation. */
    static int create_collation_v2(MemorySegment db, MemorySegment name, MemorySegment xCompare) {
        try {
            return (int) CREATE_COLLATION_V2.invokeExact(db, name, SQLITE_UTF8, MemorySegment.NULL, xCompare, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** Returns NULL on failure; the error is then on {@code dest}. */
    static MemorySegment backup_init(MemorySegment dest, MemorySegment destName, MemorySegment source, MemorySegment sourceName) {
        try {
            return (MemorySegment) BACKUP_INIT.invokeExact(dest, destName, source, sourceName);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int backup_step(MemorySegment backup, int pages) {
        try {
            return (int) BACKUP_STEP.invokeExact(backup, pages);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int backup_finish(MemorySegment backup) {
        try {
            return (int) BACKUP_FINISH.invokeExact(backup);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int backup_remaining(MemorySegment backup) {
        try {
            return (int) BACKUP_REMAINING.invokeExact(backup);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int backup_pagecount(MemorySegment backup) {
        try {
            return (int) BACKUP_PAGECOUNT.invokeExact(backup);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** A sqlite3_malloc64'd copy of the schema (free with {@link #free}), or NULL; the size goes to {@code piSize}. */
    static MemorySegment serialize(MemorySegment db, MemorySegment schema, MemorySegment piSize) {
        try {
            return (MemorySegment) SERIALIZE.invokeExact(db, schema, piSize, 0);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int deserialize(MemorySegment db, MemorySegment schema, MemorySegment data, long size, long bufferSize, int flags) {
        try {
            return (int) DESERIALIZE.invokeExact(db, schema, data, size, bufferSize, flags);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** Whether any statement of {@code db} has been stepped and not yet reset or run to completion. */
    static boolean anyStatementBusy(MemorySegment db) {
        try {
            MemorySegment s = (MemorySegment) NEXT_STMT.invokeExact(db, MemorySegment.NULL);
            while (!s.equals(MemorySegment.NULL)) {
                if ((int) STMT_BUSY.invokeExact(s) != 0) {
                    return true;
                }
                s = (MemorySegment) NEXT_STMT.invokeExact(db, s);
            }
            return false;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int txn_state(MemorySegment db, MemorySegment schema) {
        try {
            return (int) TXN_STATE.invokeExact(db, schema);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static MemorySegment malloc64(long size) {
        try {
            return (MemorySegment) MALLOC64.invokeExact(size);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static void free(MemorySegment p) {
        try {
            FREE.invokeExact(p);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static void interrupt(MemorySegment db) {
        try {
            INTERRUPT.invokeExact(db);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static MemorySegment aggregate_context(MemorySegment ctx, int nBytes) {
        try {
            return (MemorySegment) AGGREGATE_CONTEXT.invokeExact(ctx, nBytes);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int value_type(MemorySegment v) {
        try {
            return (int) VALUE_TYPE.invokeExact(v);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int value_int(MemorySegment v) {
        try {
            return (int) VALUE_INT.invokeExact(v);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static long value_int64(MemorySegment v) {
        try {
            return (long) VALUE_INT64.invokeExact(v);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static double value_double(MemorySegment v) {
        try {
            return (double) VALUE_DOUBLE.invokeExact(v);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** Returns null for SQL NULL. */
    static byte[] value_text(MemorySegment v) {
        try {
            MemorySegment p = (MemorySegment) VALUE_TEXT.invokeExact(v);
            return copyOut(p, (int) VALUE_BYTES.invokeExact(v));
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** Returns null for SQL NULL; an empty array for a zero-length blob. */
    static byte[] value_blob(MemorySegment v, boolean isNull) {
        try {
            MemorySegment p = (MemorySegment) VALUE_BLOB.invokeExact(v);
            int n = (int) VALUE_BYTES.invokeExact(v);
            return isNull ? null : n == 0 ? new byte[0] : copyOut(p, n);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static void result_null(MemorySegment ctx) {
        try {
            RESULT_NULL.invokeExact(ctx);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static void result_int64(MemorySegment ctx, long v) {
        try {
            RESULT_INT64.invokeExact(ctx, v);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static void result_double(MemorySegment ctx, double v) {
        try {
            RESULT_DOUBLE.invokeExact(ctx, v);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static void result_text(MemorySegment ctx, byte[] utf8) {
        try {
            RESULT_TEXT.invokeExact(ctx, MemorySegment.ofArray(utf8), utf8.length, SQLITE_TRANSIENT);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static void result_blob(MemorySegment ctx, byte[] bytes) {
        try {
            RESULT_BLOB.invokeExact(ctx, MemorySegment.ofArray(bytes), bytes.length, SQLITE_TRANSIENT);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** SQLite copies the message before returning. */
    static void result_error(MemorySegment ctx, byte[] utf8) {
        try {
            RESULT_ERROR.invokeExact(ctx, MemorySegment.ofArray(utf8), utf8.length);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    // Downcalls cannot throw checked exceptions; a Throwable here means a binding bug (wrong type).

    static String libversion() {
        try {
            return cString((MemorySegment) LIBVERSION.invokeExact());
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int open_v2(MemorySegment filename, MemorySegment ppDb, int flags) {
        try {
            return (int) OPEN_V2.invokeExact(filename, ppDb, flags, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int close_v2(MemorySegment db) {
        try {
            return (int) CLOSE_V2.invokeExact(db);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int busy_timeout(MemorySegment db, int ms) {
        try {
            return (int) BUSY_TIMEOUT.invokeExact(db, ms);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static String errmsg(MemorySegment db) {
        try {
            return cString((MemorySegment) ERRMSG.invokeExact(db));
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static String errstr(int rc) {
        try {
            return cString((MemorySegment) ERRSTR.invokeExact(rc));
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int extended_errcode(MemorySegment db) {
        try {
            return (int) EXTENDED_ERRCODE.invokeExact(db);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static long changes(MemorySegment db) {
        try {
            return (long) CHANGES64.invokeExact(db);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static long total_changes(MemorySegment db) {
        try {
            return (long) TOTAL_CHANGES64.invokeExact(db);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static long last_insert_rowid(MemorySegment db) {
        try {
            return (long) LAST_INSERT_ROWID.invokeExact(db);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static boolean get_autocommit(MemorySegment db) {
        try {
            return (int) GET_AUTOCOMMIT.invokeExact(db) != 0;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int prepare_v2(MemorySegment db, MemorySegment sql, int nByte, MemorySegment ppStmt, MemorySegment pzTail) {
        try {
            return (int) PREPARE_V2.invokeExact(db, sql, nByte, ppStmt, pzTail);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int finalize(MemorySegment stmt) {
        try {
            return (int) FINALIZE.invokeExact(stmt);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int step(MemorySegment stmt) {
        try {
            return (int) STEP.invokeExact(stmt);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int reset(MemorySegment stmt) {
        try {
            return (int) RESET.invokeExact(stmt);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int clear_bindings(MemorySegment stmt) {
        try {
            return (int) CLEAR_BINDINGS.invokeExact(stmt);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int bind_parameter_count(MemorySegment stmt) {
        try {
            return (int) BIND_PARAMETER_COUNT.invokeExact(stmt);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int bind_int64(MemorySegment stmt, int index, long value) {
        try {
            return (int) BIND_INT64.invokeExact(stmt, index, value);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int bind_double(MemorySegment stmt, int index, double value) {
        try {
            return (int) BIND_DOUBLE.invokeExact(stmt, index, value);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int bind_null(MemorySegment stmt, int index) {
        try {
            return (int) BIND_NULL.invokeExact(stmt, index);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** {@code utf8} is passed by address straight from the Java heap; SQLite copies it (TRANSIENT). */
    static int bind_text(MemorySegment stmt, int index, byte[] utf8) {
        try {
            return (int) BIND_TEXT.invokeExact(stmt, index, MemorySegment.ofArray(utf8), utf8.length, SQLITE_TRANSIENT);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int bind_blob(MemorySegment stmt, int index, byte[] bytes) {
        try {
            return (int) BIND_BLOB.invokeExact(stmt, index, MemorySegment.ofArray(bytes), bytes.length, SQLITE_TRANSIENT);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int column_count(MemorySegment stmt) {
        try {
            return (int) COLUMN_COUNT.invokeExact(stmt);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static String column_name(MemorySegment stmt, int col) {
        try {
            return cString((MemorySegment) COLUMN_NAME.invokeExact(stmt, col));
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static String column_decltype(MemorySegment stmt, int col) {
        try {
            return cString((MemorySegment) COLUMN_DECLTYPE.invokeExact(stmt, col));
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** {@code which}: 0 = table, 1 = database (schema), 2 = origin column. Null for expressions or without column metadata. */
    static String column_source(MemorySegment stmt, int col, int which) {
        MethodHandle h = which == 0 ? COLUMN_TABLE_NAME : which == 1 ? COLUMN_DATABASE_NAME : COLUMN_ORIGIN_NAME;
        if (h == null) {
            return null;
        }
        try {
            return cString((MemorySegment) h.invokeExact(stmt, col));
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int column_type(MemorySegment stmt, int col) {
        try {
            return (int) COLUMN_TYPE.invokeExact(stmt, col);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static int column_int(MemorySegment stmt, int col) {
        try {
            return (int) COLUMN_INT.invokeExact(stmt, col);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static long column_int64(MemorySegment stmt, int col) {
        try {
            return (long) COLUMN_INT64.invokeExact(stmt, col);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static double column_double(MemorySegment stmt, int col) {
        try {
            return (double) COLUMN_DOUBLE.invokeExact(stmt, col);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** Returns null for SQL NULL. Reads exactly {@code column_bytes} bytes: no strlen. */
    static byte[] column_text(MemorySegment stmt, int col) {
        try {
            MemorySegment p = (MemorySegment) COLUMN_TEXT.invokeExact(stmt, col);
            return copyOut(p, (int) COLUMN_BYTES.invokeExact(stmt, col));
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** Returns null for SQL NULL; an empty array for a zero-length blob. */
    static byte[] column_blob(MemorySegment stmt, int col, boolean isNull) {
        try {
            MemorySegment p = (MemorySegment) COLUMN_BLOB.invokeExact(stmt, col);
            int n = (int) COLUMN_BYTES.invokeExact(stmt, col);
            return isNull ? null : n == 0 ? new byte[0] : copyOut(p, n);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    private static byte[] copyOut(MemorySegment p, int n) {
        if (p.equals(MemorySegment.NULL)) {
            return null;
        }
        byte[] out = new byte[n];
        MemorySegment.copy(p.reinterpret(n), java.lang.foreign.ValueLayout.JAVA_BYTE, 0, out, 0, n);
        return out;
    }

    static String cString(MemorySegment p) {
        return p.equals(MemorySegment.NULL) ? null : p.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private Native() {
    }
}
