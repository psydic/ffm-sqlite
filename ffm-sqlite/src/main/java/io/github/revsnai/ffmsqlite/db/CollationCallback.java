package io.github.revsnai.ffmsqlite.db;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Comparator;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The upcall target of one custom collation: decodes both UTF-8 operands and asks the comparator.
 *
 * <p>SQLite's comparison callback has no way to report an error, and an exception must not escape an
 * upcall. So a throwing comparator is recorded on the connection, the running statement is aborted
 * with {@code sqlite3_interrupt}, and 0 is returned; the statement then fails with SQLITE_INTERRUPT
 * and the exception attached as the cause. SQLite checks the interrupt only at certain points, so a write
 * may still complete with the 0 in an index; the transaction is therefore made rollback-only
 * ({@link Hooks#collationFailed}), and its commit is vetoed.
 */
final class CollationCallback {
    static final MethodHandle X_COMPARE;

    static {
        try {
            X_COMPARE = MethodHandles.lookup().findVirtual(CollationCallback.class, "xCompare", MethodType.methodType(
                    int.class, MemorySegment.class, int.class, MemorySegment.class, int.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Connection conn;
    private final String name;
    private final Comparator<? super String> comparator;

    CollationCallback(Connection conn, String name, Comparator<? super String> comparator) {
        this.conn = conn;
        this.name = name;
        this.comparator = comparator;
    }

    /**
     * Reused copy buffers, so a comparison allocates only its two Strings. Safe under re-entrancy (a
     * comparator querying this connection): both operands are decoded before the comparator runs.
     */
    private byte[] scratch1 = new byte[64], scratch2 = new byte[64];

    int xCompare(MemorySegment arg, int len1, MemorySegment s1, int len2, MemorySegment s2) {
        try {
            if (scratch1.length < len1) {
                scratch1 = new byte[Math.max(len1, scratch1.length * 2)];
            }
            if (scratch2.length < len2) {
                scratch2 = new byte[Math.max(len2, scratch2.length * 2)];
            }
            String a = decode(s1, len1, scratch1);
            String b = decode(s2, len2, scratch2);
            return Integer.signum(comparator.compare(a, b));
        } catch (Throwable t) {
            try {
                String detail = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
                conn.recordCallbackFailure(t, "collation " + name + ": " + detail);
                conn.hooks.collationFailed();
                Native.interrupt(conn.db);
            } catch (Throwable ignored) {
                // nothing may escape an upcall
            }
            return 0;
        }
    }

    private static String decode(MemorySegment s, int len, byte[] scratch) {
        if (len == 0) {
            return "";
        }
        MemorySegment.copy(s.reinterpret(len), JAVA_BYTE, 0, scratch, 0, len);
        return new String(scratch, 0, len, UTF_8);
    }
}
