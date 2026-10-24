package ffmsqlite;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Raw FFM bindings to the SQLite C API.
 *
 * <p>Every handle is a {@code static final} field: C2 treats it as a constant, so each
 * {@code invokeExact} compiles down to a direct native call stub with no MethodHandle dispatch.
 *
 * <p>The library defaults to the system {@code libsqlite3.so.0}; override with
 * {@code -Dsqlite.lib=/path/to/libsqlite3.so}.
 */
public final class Sqlite3 {
    public static final int SQLITE_OK = 0;
    public static final int SQLITE_ROW = 100;
    public static final int SQLITE_DONE = 101;

    public static final int SQLITE_OPEN_READWRITE = 0x00000002;
    public static final int SQLITE_OPEN_CREATE = 0x00000004;

    /** Destructor sentinel: SQLite does not copy; memory must stay valid until the next step/rebind. */
    public static final MemorySegment SQLITE_STATIC = MemorySegment.NULL;
    /** Destructor sentinel: SQLite copies the value before bind returns. */
    public static final MemorySegment SQLITE_TRANSIENT = MemorySegment.ofAddress(-1L);

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB =
            SymbolLookup.libraryLookup(System.getProperty("sqlite.lib", "libsqlite3.so.0"), Arena.global());

    private static MethodHandle downcall(String name, FunctionDescriptor fd, Linker.Option... options) {
        return LINKER.downcallHandle(LIB.findOrThrow(name), fd, options);
    }

    // --- Regular downcalls (full Java -> native thread-state transition) ---

    public static final MethodHandle libversion =
            downcall("sqlite3_libversion", FunctionDescriptor.of(ADDRESS));
    public static final MethodHandle open_v2 =
            downcall("sqlite3_open_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    public static final MethodHandle close_v2 =
            downcall("sqlite3_close_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    public static final MethodHandle exec =
            downcall("sqlite3_exec", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle errmsg =
            downcall("sqlite3_errmsg", FunctionDescriptor.of(ADDRESS, ADDRESS));
    public static final MethodHandle free =
            downcall("sqlite3_free", FunctionDescriptor.ofVoid(ADDRESS));
    public static final MethodHandle prepare_v2 =
            downcall("sqlite3_prepare_v2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    public static final MethodHandle finalize_ =
            downcall("sqlite3_finalize", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    public static final MethodHandle step =
            downcall("sqlite3_step", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    public static final MethodHandle reset =
            downcall("sqlite3_reset", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    public static final MethodHandle bind_int =
            downcall("sqlite3_bind_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    public static final MethodHandle bind_text =
            downcall("sqlite3_bind_text", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    public static final MethodHandle column_int =
            downcall("sqlite3_column_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    public static final MethodHandle column_text =
            downcall("sqlite3_column_text", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    public static final MethodHandle column_bytes =
            downcall("sqlite3_column_bytes", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    // --- Critical downcalls: no thread-state transition, no safepoint poll. ---
    // Only for short, non-blocking functions that never call back into Java.
    // critical(true) also allows passing heap segments (MemorySegment.ofArray) as pointers.

    private static final Linker.Option CRITICAL = Linker.Option.critical(false);
    private static final Linker.Option CRITICAL_HEAP = Linker.Option.critical(true);

    public static final MethodHandle reset_c =
            downcall("sqlite3_reset", FunctionDescriptor.of(JAVA_INT, ADDRESS), CRITICAL);
    public static final MethodHandle bind_int_c =
            downcall("sqlite3_bind_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), CRITICAL);
    public static final MethodHandle bind_text_c =
            downcall("sqlite3_bind_text", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), CRITICAL_HEAP);
    public static final MethodHandle column_int_c =
            downcall("sqlite3_column_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), CRITICAL);
    public static final MethodHandle column_text_c =
            downcall("sqlite3_column_text", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT), CRITICAL);
    public static final MethodHandle column_bytes_c =
            downcall("sqlite3_column_bytes", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), CRITICAL);

    /** {@code int (*)(void*, int argc, char **argv, char **colNames)} used by {@code sqlite3_exec}. */
    public static final FunctionDescriptor EXEC_CALLBACK =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);

    public static Linker linker() {
        return LINKER;
    }

    private Sqlite3() {
    }
}
