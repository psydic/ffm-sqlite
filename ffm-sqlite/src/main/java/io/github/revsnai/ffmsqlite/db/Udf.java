package io.github.revsnai.ffmsqlite.db;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * One registered user-defined function: the targets SQLite calls back through upcall stubs.
 *
 * <p>An exception must never escape an upcall (the JVM would terminate), so every callback catches
 * {@code Throwable} and reports it with {@code sqlite3_result_error}, which fails the statement.
 *
 * <p>Aggregates: SQLite gives each group 8 bytes of zeroed "aggregate context" memory. The first
 * {@code xStep} of a group stores a key there that maps to the group's Java accumulator; {@code xFinal}
 * removes it. SQLite also runs {@code xFinal} when a statement is reset or fails mid-aggregate, so
 * accumulators never leak.
 *
 * <p>Window functions add {@code xInverse} (a row leaves the frame) and {@code xValue} (the current
 * frame's value, without ending the group). One accumulator serves a whole partition, and rows are
 * returned while it is alive, so closing a result set early reaches {@code xFinal} through
 * {@code sqlite3_reset}.
 */
final class Udf {
    static final MethodHandle X_FUNC, X_STEP, X_FINAL, X_VALUE, X_INVERSE;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodType xFunc = MethodType.methodType(void.class, MemorySegment.class, int.class, MemorySegment.class);
            X_FUNC = lookup.findVirtual(Udf.class, "xFunc", xFunc);
            X_STEP = lookup.findVirtual(Udf.class, "xStep", xFunc);
            MethodType xFinal = MethodType.methodType(void.class, MemorySegment.class);
            X_FINAL = lookup.findVirtual(Udf.class, "xFinal", xFinal);
            X_VALUE = lookup.findVirtual(Udf.class, "xValue", xFinal);
            X_INVERSE = lookup.findVirtual(Udf.class, "xInverse", xFunc);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Connection conn;
    private final String name;
    private final ScalarFunction scalar;
    private final Supplier<? extends AggregateFunction> factory;
    private final Map<Long, AggregateFunction> groups = new HashMap<>();
    private long lastKey;

    Udf(Connection conn, String name, ScalarFunction scalar, Supplier<? extends AggregateFunction> factory) {
        this.conn = conn;
        this.name = name;
        this.scalar = scalar;
        this.factory = factory;
    }

    void xFunc(MemorySegment ctx, int argc, MemorySegment argv) {
        FunctionArgs args = null;
        try {
            args = new FunctionArgs(argv, argc);   // inside the try: even an OutOfMemoryError must not escape
            setResult(ctx, scalar.apply(args));
        } catch (Throwable t) {
            fail(ctx, t);
        } finally {
            if (args != null) {
                args.invalidate();
            }
        }
    }

    void xStep(MemorySegment ctx, int argc, MemorySegment argv) {
        FunctionArgs args = null;
        try {
            args = new FunctionArgs(argv, argc);   // inside the try: even an OutOfMemoryError must not escape
            accumulator(ctx).step(args);
        } catch (Throwable t) {
            fail(ctx, t);
        } finally {
            if (args != null) {
                args.invalidate();
            }
        }
    }

    void xInverse(MemorySegment ctx, int argc, MemorySegment argv) {
        FunctionArgs args = null;
        try {
            args = new FunctionArgs(argv, argc);   // inside the try: even an OutOfMemoryError must not escape
            ((WindowFunction) accumulator(ctx)).inverse(args);
        } catch (Throwable t) {
            fail(ctx, t);
        } finally {
            if (args != null) {
                args.invalidate();
            }
        }
    }

    /** Called per output row; the frame may still be empty, so this may create the accumulator. */
    void xValue(MemorySegment ctx) {
        try {
            setResult(ctx, ((WindowFunction) accumulator(ctx)).value());
        } catch (Throwable t) {
            fail(ctx, t);
        }
    }

    /** The group's accumulator, created on first use and keyed by the group's aggregate context. */
    private AggregateFunction accumulator(MemorySegment ctx) {
        MemorySegment slot = Native.aggregate_context(ctx, Long.BYTES);
        if (slot.equals(MemorySegment.NULL)) {
            throw new OutOfMemoryError("sqlite3_aggregate_context");
        }
        slot = slot.reinterpret(Long.BYTES);
        long key = slot.get(JAVA_LONG, 0);
        if (key != 0) {
            return groups.get(key);
        }
        AggregateFunction acc = factory.get();
        key = ++lastKey;
        groups.put(key, acc);
        slot.set(JAVA_LONG, 0, key);
        return acc;
    }

    void xFinal(MemorySegment ctx) {
        try {
            MemorySegment slot = Native.aggregate_context(ctx, 0);
            AggregateFunction acc = slot.equals(MemorySegment.NULL)
                    ? factory.get() // no rows: the group never had an xStep
                    : groups.remove(slot.reinterpret(Long.BYTES).get(JAVA_LONG, 0));
            if (acc == null) {
                Native.result_null(ctx); // the group's first xStep failed; that error is already reported
                return;
            }
            setResult(ctx, acc.result());
        } catch (Throwable t) {
            fail(ctx, t);
        }
    }

    /** Groups whose accumulators are alive; 0 once every statement has completed or been reset. */
    int liveGroups() {
        return groups.size();
    }

    private static void setResult(MemorySegment ctx, Object v) throws SQLiteException {
        switch (v) {
            case null -> Native.result_null(ctx);
            case Integer i -> Native.result_int64(ctx, i);
            case Long l -> Native.result_int64(ctx, l);
            case Short s -> Native.result_int64(ctx, s);
            case Byte b -> Native.result_int64(ctx, b);
            case Boolean b -> Native.result_int64(ctx, b ? 1 : 0);
            case Double d -> Native.result_double(ctx, d);
            case Float f -> Native.result_double(ctx, f);
            case String s -> Native.result_text(ctx, s.getBytes(UTF_8));
            case byte[] bytes -> Native.result_blob(ctx, bytes);
            case BigDecimal d -> Native.result_text(ctx, Conversions.decimalText(d).getBytes(UTF_8));
            case BigInteger i when i.bitLength() < Long.SIZE -> Native.result_int64(ctx, i.longValue());
            case BigInteger i -> Native.result_text(ctx, i.toString().getBytes(UTF_8));
            default -> {
                byte[] text = Conversions.text(v);
                if (text == null) {
                    throw new SQLiteException("unsupported return type " + v.getClass().getName());
                }
                Native.result_text(ctx, text);
            }
        }
    }

    private void fail(MemorySegment ctx, Throwable t) {
        try {
            conn.recordCallbackFailure(t, null); // the SQLite error message already names the function
            String detail = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
            Native.result_error(ctx, (name + "(): " + detail).getBytes(UTF_8));
        } catch (Throwable ignored) {
            // nothing may escape an upcall
        }
    }
}
