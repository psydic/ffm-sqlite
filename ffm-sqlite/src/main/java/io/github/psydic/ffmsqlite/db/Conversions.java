package io.github.psydic.ffmsqlite.db;

import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.NANO_OF_SECOND;
import static java.time.temporal.ChronoField.OFFSET_SECONDS;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;

/**
 * Mapping between Java types SQLite has no storage class for and SQLite values.
 *
 * <p><b>Dates and times</b> are written as ISO-8601 TEXT in SQLite's own format, so SQLite's
 * date functions and plain string comparison work on them:
 * <ul>
 *   <li>{@code LocalDate} {@code 2024-01-15}, {@code LocalTime} {@code 13:45:30.5},
 *       {@code LocalDateTime} {@code 2024-01-15 13:45:30.5};</li>
 *   <li>{@code Instant} as UTC without a suffix, exactly like {@code CURRENT_TIMESTAMP};</li>
 *   <li>{@code OffsetDateTime} / {@code ZonedDateTime} with their offset, e.g. {@code 2024-01-15 13:45:30+02:00}
 *       (a zone ID is reduced to its offset).</li>
 * </ul>
 * Fractional seconds are written without trailing zeros, which keeps lexical order chronological.
 * Reading accepts any of SQLite's time-value formats as TEXT ({@code T} or space separator, optional
 * seconds, fraction, {@code Z} or {@code ±HH:MM}), INTEGER as Unix-epoch seconds ({@code unixepoch()})
 * and REAL as a Julian day number ({@code julianday()}). Text without an offset is taken as UTC
 * when an {@code Instant} or {@code OffsetDateTime} is requested.
 *
 * <p>The common cases (years 0000-9999, offsets in whole minutes) are formatted and parsed by hand
 * straight from/to UTF-8 bytes; everything else goes through {@link DateTimeFormatter}, which
 * produces and accepts the same text.
 *
 * <p><b>BigDecimal</b> is written as TEXT, so it stays exact in columns with TEXT, BLOB or no type
 * affinity. Columns declared NUMERIC/DECIMAL convert numeric-looking text to INTEGER or REAL
 * (15 significant digits) on insert; that is SQLite behaviour this library cannot override.
 *
 * <p><b>UUID</b> is written as canonical lower-case TEXT ({@code 123e4567-e89b-12d3-a456-426614174000});
 * reading also accepts upper case and 16-byte BLOBs (most significant byte first).
 */
final class Conversions {

    private static final DateTimeFormatter WRITE_TIME = new DateTimeFormatterBuilder()
            .appendValue(HOUR_OF_DAY, 2).appendLiteral(':')
            .appendValue(MINUTE_OF_HOUR, 2).appendLiteral(':')
            .appendValue(SECOND_OF_MINUTE, 2)
            .appendFraction(NANO_OF_SECOND, 0, 9, true)
            .toFormatter(Locale.ROOT);
    private static final DateTimeFormatter WRITE_DATETIME = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE).appendLiteral(' ').append(WRITE_TIME)
            .toFormatter(Locale.ROOT);
    private static final DateTimeFormatter WRITE_OFFSET_DATETIME = new DateTimeFormatterBuilder()
            .append(WRITE_DATETIME).appendOffset("+HH:MM", "Z")
            .toFormatter(Locale.ROOT);

    /** {@code HH:MM[:SS[.fff]]} */
    private static final DateTimeFormatter READ_TIME = new DateTimeFormatterBuilder()
            .appendValue(HOUR_OF_DAY, 2).appendLiteral(':')
            .appendValue(MINUTE_OF_HOUR, 2)
            .optionalStart().appendLiteral(':').appendValue(SECOND_OF_MINUTE, 2)
            .optionalStart().appendFraction(NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .optionalEnd()
            .toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    /** {@code YYYY-MM-DD[ HH:MM[:SS[.fff]][Z|±HH:MM]]}, after a {@code T} separator is normalised to a space. */
    private static final DateTimeFormatter READ_DATETIME = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart().appendLiteral(' ').append(READ_TIME)
            .optionalStart().appendOffset("+HH:MM", "Z").optionalEnd()
            .optionalEnd()
            .toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    private static final double UNIX_EPOCH_JULIAN_DAY = 2440587.5;
    private static final int SECONDS_PER_DAY = 86_400;
    /** Marks "no offset" in the int-based formatter. */
    private static final int NO_OFFSET = Integer.MIN_VALUE;

    // ------------------------------------------------------------------ writing

    /** UTF-8 TEXT for a java.time value or UUID; {@code null} if {@code v} is not one this class handles. */
    static byte[] text(Object v) {
        return switch (v) {
            case LocalDate d -> fastDate(d) ? formatFast(d.getYear(), d.getMonthValue(), d.getDayOfMonth(), -1, 0, 0, 0, NO_OFFSET)
                    : ascii(DateTimeFormatter.ISO_LOCAL_DATE.format(d));
            case LocalTime t -> formatFast(-1, 0, 0, t.getHour(), t.getMinute(), t.getSecond(), t.getNano(), NO_OFFSET);
            case LocalDateTime dt -> fastDate(dt.toLocalDate()) ? formatFast(dt.getYear(), dt.getMonthValue(), dt.getDayOfMonth(),
                    dt.getHour(), dt.getMinute(), dt.getSecond(), dt.getNano(), NO_OFFSET)
                    : ascii(WRITE_DATETIME.format(dt));
            case Instant i -> instantText(i);
            case OffsetDateTime odt -> offsetText(odt);
            case ZonedDateTime zdt -> offsetText(zdt.toOffsetDateTime());
            case UUID u -> ascii(u.toString());
            default -> null;
        };
    }

    /** Plain notation (no exponent) unless that would be needlessly long, e.g. for 1E+500. */
    static String decimalText(BigDecimal v) {
        return Math.abs(v.scale()) <= 100 ? v.toPlainString() : v.toString();
    }

    private static boolean fastDate(LocalDate d) {
        return d.getYear() >= 0 && d.getYear() <= 9999;
    }

    private static byte[] instantText(Instant i) {
        long epochDay = Math.floorDiv(i.getEpochSecond(), SECONDS_PER_DAY);
        int secOfDay = (int) Math.floorMod(i.getEpochSecond(), SECONDS_PER_DAY);
        LocalDate d = LocalDate.ofEpochDay(epochDay);
        if (!fastDate(d)) {
            return ascii(WRITE_DATETIME.format(LocalDateTime.ofInstant(i, ZoneOffset.UTC)));
        }
        return formatFast(d.getYear(), d.getMonthValue(), d.getDayOfMonth(),
                secOfDay / 3600, secOfDay / 60 % 60, secOfDay % 60, i.getNano(), NO_OFFSET);
    }

    private static byte[] offsetText(OffsetDateTime odt) {
        int offset = odt.getOffset().getTotalSeconds();
        if (!fastDate(odt.toLocalDate()) || offset % 60 != 0) {
            return ascii(WRITE_OFFSET_DATETIME.format(odt));
        }
        return formatFast(odt.getYear(), odt.getMonthValue(), odt.getDayOfMonth(),
                odt.getHour(), odt.getMinute(), odt.getSecond(), odt.getNano(), offset);
    }

    /**
     * {@code [YYYY-MM-DD][ ][HH:MM:SS[.f]][Z|±HH:MM]} into an exactly-sized array: {@code year < 0}
     * omits the date, {@code hour < 0} the time, {@code offset == NO_OFFSET} the offset.
     */
    private static byte[] formatFast(int year, int month, int day, int hour, int minute, int second, int nano, int offset) {
        int fracDigits = 0;
        int frac = nano;
        if (frac != 0) {
            fracDigits = 9;
            while (frac % 10 == 0) {
                frac /= 10;
                fracDigits--;
            }
        }
        int len = (year >= 0 ? 10 : 0) + (year >= 0 && hour >= 0 ? 1 : 0)
                + (hour >= 0 ? 8 + (fracDigits > 0 ? 1 + fracDigits : 0) : 0)
                + (offset == NO_OFFSET ? 0 : offset == 0 ? 1 : 6);
        byte[] b = new byte[len];
        int p = 0;
        if (year >= 0) {
            p = digits(b, p, year, 4);
            b[p++] = '-';
            p = digits(b, p, month, 2);
            b[p++] = '-';
            p = digits(b, p, day, 2);
            if (hour >= 0) {
                b[p++] = ' ';
            }
        }
        if (hour >= 0) {
            p = digits(b, p, hour, 2);
            b[p++] = ':';
            p = digits(b, p, minute, 2);
            b[p++] = ':';
            p = digits(b, p, second, 2);
            if (fracDigits > 0) {
                b[p++] = '.';
                p = digits(b, p, frac, fracDigits);
            }
        }
        if (offset == 0) {
            b[p] = 'Z';
        } else if (offset != NO_OFFSET) {
            int abs = Math.abs(offset);
            b[p++] = (byte) (offset < 0 ? '-' : '+');
            p = digits(b, p, abs / 3600, 2);
            b[p++] = ':';
            digits(b, p, abs / 60 % 60, 2);
        }
        return b;
    }

    /** Writes {@code value} zero-padded to {@code width} digits; returns the next position. */
    private static int digits(byte[] b, int p, int value, int width) {
        for (int i = p + width - 1; i >= p; i--) {
            b[i] = (byte) ('0' + value % 10);
            value /= 10;
        }
        return p + width;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(UTF_8);
    }

    // ------------------------------------------------------------------ reading

    // Reads go through these so the same code serves result columns (ptr = sqlite3_stmt*, c = column)
    // and user-defined function arguments (ptr = sqlite3_value*, c = VALUE).

    static final int VALUE = -1;

    private static int type(MemorySegment p, int c) {
        return c == VALUE ? Native.value_type(p) : Native.column_type(p, c);
    }

    private static int int32(MemorySegment p, int c) {
        return c == VALUE ? Native.value_int(p) : Native.column_int(p, c);
    }

    private static long int64(MemorySegment p, int c) {
        return c == VALUE ? Native.value_int64(p) : Native.column_int64(p, c);
    }

    private static double float64(MemorySegment p, int c) {
        return c == VALUE ? Native.value_double(p) : Native.column_double(p, c);
    }

    private static byte[] text(MemorySegment p, int c) {
        return c == VALUE ? Native.value_text(p) : Native.column_text(p, c);
    }

    /** The value as a BLOB; only called for non-NULL values. */
    private static byte[] blob(MemorySegment p, int c) {
        return c == VALUE ? Native.value_blob(p, false) : Native.column_blob(p, c, false);
    }

    /** Reads column {@code c} of the current row (or a function argument, see {@link #VALUE}) as {@code type}; {@code null} for SQL NULL. */
    static <T> T read(MemorySegment stmt, int c, Class<T> type) throws SQLiteException {
        int storage = type(stmt, c);
        if (storage == Native.SQLITE_NULL) {
            if (type.isPrimitive()) {
                throw new SQLiteException("primitive type " + type + " requested; use the wrapper class");
            }
            return null;
        }
        Object v;
        if (type == String.class) {
            v = new String(text(stmt, c), UTF_8);
        } else if (type == Long.class) {
            v = int64(stmt, c);
        } else if (type == Integer.class) {
            v = int32(stmt, c);
        } else if (type == Short.class) {
            v = (short) int32(stmt, c);
        } else if (type == Byte.class) {
            v = (byte) int32(stmt, c);
        } else if (type == Double.class) {
            v = float64(stmt, c);
        } else if (type == Float.class) {
            v = (float) float64(stmt, c);
        } else if (type == Boolean.class) {
            v = int64(stmt, c) != 0;
        } else if (type == byte[].class) {
            v = blob(stmt, c);
        } else if (type == BigDecimal.class) {
            v = readDecimal(stmt, c, storage);
        } else if (type == BigInteger.class) {
            v = storage == Native.SQLITE_INTEGER
                    ? BigInteger.valueOf(int64(stmt, c))
                    : readDecimal(stmt, c, storage).toBigInteger();
        } else if (type == LocalDate.class || type == LocalTime.class || type == LocalDateTime.class
                || type == Instant.class || type == OffsetDateTime.class || type == ZonedDateTime.class) {
            v = readTemporal(stmt, c, storage, type);
        } else if (type == UUID.class) {
            v = readUuid(stmt, c, storage);
        } else if (type == Object.class) {
            v = readAny(stmt, c, storage);
        } else {
            throw new SQLiteException("unsupported target type: " + type.getName());
        }
        return type.cast(v);
    }

    /** By storage class: Long, Double, String, byte[]; never called for NULL. */
    static Object readAny(MemorySegment stmt, int c, int storage) {
        return switch (storage) {
            case Native.SQLITE_INTEGER -> int64(stmt, c);
            case Native.SQLITE_FLOAT -> float64(stmt, c);
            case Native.SQLITE_TEXT -> new String(text(stmt, c), UTF_8);
            case Native.SQLITE_BLOB -> blob(stmt, c);
            default -> null;
        };
    }

    private static BigDecimal readDecimal(MemorySegment stmt, int c, int storage) throws SQLiteException {
        return switch (storage) {
            case Native.SQLITE_INTEGER -> BigDecimal.valueOf(int64(stmt, c));
            // valueOf(double) uses the shortest decimal that round-trips: 0.1 -> 0.1, not 0.1000000000000000055...
            case Native.SQLITE_FLOAT -> BigDecimal.valueOf(float64(stmt, c));
            default -> {
                String text = new String(text(stmt, c), UTF_8).strip();
                try {
                    yield new BigDecimal(text);
                } catch (NumberFormatException e) {
                    throw new SQLiteException("cannot convert '" + text + "' to BigDecimal");
                }
            }
        };
    }

    private static UUID readUuid(MemorySegment stmt, int c, int storage) throws SQLiteException {
        if (storage == Native.SQLITE_BLOB) {
            byte[] b = blob(stmt, c);
            if (b.length != 16) {
                throw new SQLiteException("cannot convert a " + b.length + "-byte BLOB to UUID (need 16 bytes)");
            }
            ByteBuffer bb = ByteBuffer.wrap(b);
            return new UUID(bb.getLong(), bb.getLong());
        }
        String text = new String(text(stmt, c), UTF_8).strip();
        // UUID.fromString is lenient about field lengths ("1-2-3-4-5"); insist on the canonical 36 chars.
        if (text.length() == 36) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException e) {
                // fall through
            }
        }
        throw new SQLiteException("cannot convert '" + text + "' to UUID");
    }

    /** A date and/or time as read, without allocation; absent parts are flagged. */
    private static final class Parts {
        boolean hasDate, hasTime, hasOffset;
        int year, month, day, hour, minute, second, nano, offset;
    }

    private static Object readTemporal(MemorySegment stmt, int c, int storage, Class<?> type) throws SQLiteException {
        Parts p = new Parts();
        byte[] text = null;
        if (storage == Native.SQLITE_INTEGER) {
            fromEpoch(p, int64(stmt, c), 0);
        } else if (storage == Native.SQLITE_FLOAT) {
            long millis = Math.round((float64(stmt, c) - UNIX_EPOCH_JULIAN_DAY) * 86_400_000d);
            fromEpoch(p, Math.floorDiv(millis, 1000), (int) Math.floorMod(millis, 1000) * 1_000_000);
        } else {
            text = text(stmt, c);
            if (!parseFast(text, p) && !parseSlow(new String(text, UTF_8).strip(), p)) {
                throw new SQLiteException("cannot convert '" + new String(text, UTF_8) + "' to " + type.getSimpleName());
            }
        }
        try {
            return toTemporal(p, type);
        } catch (DateTimeException e) {
            String shown = text == null ? "value" : "'" + new String(text, UTF_8) + "'";
            throw new SQLiteException("cannot convert " + shown + " to " + type.getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Object toTemporal(Parts p, Class<?> type) {
        if (type == LocalTime.class) {
            if (!p.hasTime) {
                throw new DateTimeException("no time part");
            }
            return LocalTime.of(p.hour, p.minute, p.second, p.nano);
        }
        if (!p.hasDate) {
            throw new DateTimeException("no date part");
        }
        if (type == LocalDate.class) {
            return LocalDate.of(p.year, p.month, p.day);
        }
        if (type == LocalDateTime.class) {
            return LocalDateTime.of(p.year, p.month, p.day, p.hour, p.minute, p.second, p.nano);
        }
        int offset = p.hasOffset ? p.offset : 0;
        if (type == Instant.class) {
            LocalTime.of(p.hour, p.minute, p.second, p.nano); // validates the time fields
            long epochDay = LocalDate.of(p.year, p.month, p.day).toEpochDay();
            long seconds = epochDay * SECONDS_PER_DAY + p.hour * 3600L + p.minute * 60L + p.second - offset;
            return Instant.ofEpochSecond(seconds, p.nano);
        }
        OffsetDateTime odt = OffsetDateTime.of(p.year, p.month, p.day, p.hour, p.minute, p.second, p.nano,
                ZoneOffset.ofTotalSeconds(offset));
        return type == ZonedDateTime.class ? odt.toZonedDateTime() : odt;
    }

    private static void fromEpoch(Parts p, long epochSecond, int nano) {
        LocalDate d = LocalDate.ofEpochDay(Math.floorDiv(epochSecond, SECONDS_PER_DAY));
        int secOfDay = (int) Math.floorMod(epochSecond, SECONDS_PER_DAY);
        p.hasDate = p.hasTime = p.hasOffset = true;
        p.year = d.getYear();
        p.month = d.getMonthValue();
        p.day = d.getDayOfMonth();
        p.hour = secOfDay / 3600;
        p.minute = secOfDay / 60 % 60;
        p.second = secOfDay % 60;
        p.nano = nano;
        p.offset = 0;
    }

    /**
     * Parses {@code [YYYY-MM-DD][( |T)HH:MM[:SS[.f{1,9}]]][Z|±HH:MM]} (surrounding whitespace allowed)
     * into {@code p}. Field ranges are checked later, by the java.time factories. Returns false for
     * anything else, e.g. a signed or 5-digit year, which {@link #parseSlow} then handles.
     */
    private static boolean parseFast(byte[] b, Parts p) {
        int i = 0;
        int end = b.length;
        while (i < end && isSpace(b[i])) {
            i++;
        }
        while (end > i && isSpace(b[end - 1])) {
            end--;
        }
        if (end - i >= 5 && b[i + 2] == ':') {
            return (i = parseTime(b, i, end, p)) == end;
        }
        if (end - i < 10 || b[i + 4] != '-' || b[i + 7] != '-') {
            return false;
        }
        p.year = num(b, i, 4);
        p.month = num(b, i + 5, 2);
        p.day = num(b, i + 8, 2);
        if ((p.year | p.month | p.day) < 0) {
            return false;
        }
        p.hasDate = true;
        i += 10;
        if (i == end) {
            return true;
        }
        if (b[i] != ' ' && b[i] != 'T') {
            return false;
        }
        return parseTime(b, i + 1, end, p) == end;
    }

    /** Parses a time and optional offset starting at {@code i}; returns the end position or -1. */
    private static int parseTime(byte[] b, int i, int end, Parts p) {
        if (end - i < 5 || b[i + 2] != ':') {
            return -1;
        }
        p.hour = num(b, i, 2);
        p.minute = num(b, i + 3, 2);
        if ((p.hour | p.minute) < 0) {
            return -1;
        }
        i += 5;
        if (i < end && b[i] == ':') {
            if (end - i < 3 || (p.second = num(b, i + 1, 2)) < 0) {
                return -1;
            }
            i += 3;
            if (i < end && b[i] == '.') {
                int start = ++i;
                int nano = 0;
                while (i < end && b[i] >= '0' && b[i] <= '9' && i - start < 9) {
                    nano = nano * 10 + (b[i++] - '0');
                }
                int n = i - start;
                if (n == 0 || (i < end && b[i] >= '0' && b[i] <= '9')) {
                    return -1; // no digits, or more than 9
                }
                for (; n < 9; n++) {
                    nano *= 10;
                }
                p.nano = nano;
            }
        }
        p.hasTime = true;
        if (i < end && (b[i] == 'Z' || b[i] == 'z')) {
            p.hasOffset = true;
            p.offset = 0;
            i++;
        } else if (i < end && (b[i] == '+' || b[i] == '-')) {
            if (end - i < 6 || b[i + 3] != ':') {
                return -1;
            }
            int h = num(b, i + 1, 2);
            int m = num(b, i + 4, 2);
            if ((h | m) < 0 || h > 18 || m > 59) {
                return -1;
            }
            p.hasOffset = true;
            p.offset = (b[i] == '-' ? -1 : 1) * (h * 3600 + m * 60);
            i += 6;
        }
        return i;
    }

    /** The {@code n}-digit decimal at {@code i}, or -1 if any byte is not a digit. */
    private static int num(byte[] b, int i, int n) {
        int v = 0;
        for (int k = i; k < i + n; k++) {
            int d = b[k] - '0';
            if (d < 0 || d > 9) {
                return -1;
            }
            v = v * 10 + d;
        }
        return v;
    }

    private static boolean isSpace(byte c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /** DateTimeFormatter-based parsing for what {@link #parseFast} declines (signed or 5+ digit years). */
    private static boolean parseSlow(String s, Parts p) {
        try {
            if (s.length() >= 3 && s.charAt(2) == ':') {
                LocalTime t = LocalTime.from(READ_TIME.parse(s));
                setTime(p, t);
                return true;
            }
            if (s.length() > 10 && s.charAt(10) == 'T') {
                s = s.substring(0, 10) + ' ' + s.substring(11);
            }
            int t = s.indexOf('T', 1);
            if (t > 0 && s.indexOf(' ') < 0) {
                s = s.substring(0, t) + ' ' + s.substring(t + 1);
            }
            TemporalAccessor ta = READ_DATETIME.parse(s);
            LocalDate d = LocalDate.from(ta);
            p.hasDate = true;
            p.year = d.getYear();
            p.month = d.getMonthValue();
            p.day = d.getDayOfMonth();
            if (ta.isSupported(HOUR_OF_DAY)) {
                setTime(p, LocalTime.from(ta));
            }
            if (ta.isSupported(OFFSET_SECONDS)) {
                p.hasOffset = true;
                p.offset = ZoneOffset.from(ta).getTotalSeconds();
            }
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    private static void setTime(Parts p, LocalTime t) {
        p.hasTime = true;
        p.hour = t.getHour();
        p.minute = t.getMinute();
        p.second = t.getSecond();
        p.nano = t.getNano();
    }

    private Conversions() {
    }
}
