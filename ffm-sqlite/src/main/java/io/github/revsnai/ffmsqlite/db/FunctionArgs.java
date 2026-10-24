package io.github.revsnai.ffmsqlite.db;

import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The arguments of one call to a user-defined function. Indexes are 0-based, like an array
 * (and like sqlite-jdbc's {@code Function}). The getters convert like their {@link ResultSet}
 * counterparts: {@code getInt} on NULL is 0, {@code getString} on NULL is {@code null}, and
 * {@link #getObject(int, Class)} supports the same types, including java.time, BigDecimal and UUID.
 *
 * <p>Only valid during the call it was passed to: SQLite frees the values afterwards, so any use
 * after the function returns throws.
 */
public final class FunctionArgs {
    private final MemorySegment argv;
    private final int argc;
    private boolean valid = true;

    FunctionArgs(MemorySegment argv, int argc) {
        this.argv = argc == 0 ? argv : argv.reinterpret(argc * ADDRESS.byteSize());
        this.argc = argc;
    }

    void invalidate() {
        valid = false;
    }

    public int size() {
        return argc;
    }

    public boolean isNull(int index) throws SQLiteException {
        return Native.value_type(value(index)) == Native.SQLITE_NULL;
    }

    public int getInt(int index) throws SQLiteException {
        return Native.value_int(value(index));
    }

    public long getLong(int index) throws SQLiteException {
        return Native.value_int64(value(index));
    }

    public double getDouble(int index) throws SQLiteException {
        return Native.value_double(value(index));
    }

    public boolean getBoolean(int index) throws SQLiteException {
        return getLong(index) != 0;
    }

    public String getString(int index) throws SQLiteException {
        byte[] utf8 = Native.value_text(value(index));
        return utf8 == null ? null : new String(utf8, UTF_8);
    }

    public byte[] getBytes(int index) throws SQLiteException {
        MemorySegment v = value(index);
        return Native.value_blob(v, Native.value_type(v) == Native.SQLITE_NULL);
    }

    public BigDecimal getBigDecimal(int index) throws SQLiteException {
        return getObject(index, BigDecimal.class);
    }

    /** By storage class: {@code Long}, {@code Double}, {@code String}, {@code byte[]} or {@code null}. */
    public Object getObject(int index) throws SQLiteException {
        MemorySegment v = value(index);
        return Conversions.readAny(v, Conversions.VALUE, Native.value_type(v));
    }

    public <T> T getObject(int index, Class<T> type) throws SQLiteException {
        return Conversions.read(value(index), Conversions.VALUE, type);
    }

    private MemorySegment value(int index) throws SQLiteException {
        if (!valid) {
            throw new SQLiteException("function arguments used after the function returned");
        }
        if (index < 0 || index >= argc) {
            throw new SQLiteException("argument index " + index + " out of range 0.." + (argc - 1));
        }
        return argv.getAtIndex(ADDRESS, index);
    }
}
