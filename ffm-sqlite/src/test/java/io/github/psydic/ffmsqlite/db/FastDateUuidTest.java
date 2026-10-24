package io.github.psydic.ffmsqlite.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.NANO_OF_SECOND;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hand-written date formatter/parser against DateTimeFormatter as the reference, plus UUIDs. */
class FastDateUuidTest {
    // Reference formatters: the definition of the stored format.
    static final DateTimeFormatter TIME = new DateTimeFormatterBuilder()
            .appendValue(HOUR_OF_DAY, 2).appendLiteral(':').appendValue(MINUTE_OF_HOUR, 2).appendLiteral(':')
            .appendValue(SECOND_OF_MINUTE, 2).appendFraction(NANO_OF_SECOND, 0, 9, true).toFormatter(Locale.ROOT);
    static final DateTimeFormatter DATETIME = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE).appendLiteral(' ').append(TIME).toFormatter(Locale.ROOT);
    static final DateTimeFormatter OFFSET_DATETIME = new DateTimeFormatterBuilder()
            .append(DATETIME).appendOffset("+HH:MM", "Z").toFormatter(Locale.ROOT);

    private Connection conn;
    private PreparedStatement echo;

    @BeforeEach
    void open() throws SQLiteException {
        conn = SQLite.getConnection(":memory:");
        echo = conn.prepareStatement("SELECT ?");
    }

    @AfterEach
    void close() {
        conn.close();
    }

    private <T> T bindAndRead(Object value, Class<T> type) throws SQLiteException {
        echo.setObject(1, value);
        try (ResultSet rs = echo.executeQuery()) {
            assertTrue(rs.next());
            return rs.getObject(1, type);
        }
    }

    private <T> T parse(String text, Class<T> type) throws SQLiteException {
        echo.setString(1, text);
        try (ResultSet rs = echo.executeQuery()) {
            assertTrue(rs.next());
            return rs.getObject(1, type);
        }
    }

    private static String written(Object v) {
        return new String(Conversions.text(v), UTF_8);
    }

    // ------------------------------------------------------------------ differential: random values

    @Test
    void randomValuesFormatLikeReferenceAndRoundTrip() throws SQLiteException {
        Random rnd = new Random(42);
        for (int n = 0; n < 20_000; n++) {
            // Mostly years 0000-9999 (fast path); some outside it (formatter fallback).
            int year = n % 20 == 0 ? rnd.nextInt(-50_000, 50_000) : rnd.nextInt(0, 10_000);
            LocalDate date = LocalDate.of(year, 1, 1).plusDays(rnd.nextInt(366));
            int nano = switch (n % 4) {
                case 0 -> 0;
                case 1 -> rnd.nextInt(1000) * 1_000_000;
                case 2 -> rnd.nextInt(1_000_000) * 1000;
                default -> rnd.nextInt(1_000_000_000);
            };
            LocalTime time = LocalTime.ofSecondOfDay(rnd.nextInt(86_400)).withNano(nano);
            LocalDateTime dt = LocalDateTime.of(date, time);
            ZoneOffset offset = ZoneOffset.ofTotalSeconds(rnd.nextInt(-18 * 60, 18 * 60 + 1) * 60);
            OffsetDateTime odt = dt.atOffset(offset);
            Instant instant = odt.toInstant();

            assertEquals(DateTimeFormatter.ISO_LOCAL_DATE.format(date), written(date));
            assertEquals(TIME.format(time), written(time));
            assertEquals(DATETIME.format(dt), written(dt));
            assertEquals(DATETIME.format(LocalDateTime.ofInstant(instant, ZoneOffset.UTC)), written(instant));
            assertEquals(OFFSET_DATETIME.format(odt), written(odt));

            if (n % 10 == 0) { // round trips through SQLite are slower; sample them
                assertEquals(date, bindAndRead(date, LocalDate.class));
                assertEquals(time, bindAndRead(time, LocalTime.class));
                assertEquals(dt, bindAndRead(dt, LocalDateTime.class));
                assertEquals(instant, bindAndRead(instant, Instant.class));
                assertEquals(odt, bindAndRead(odt, OffsetDateTime.class));
            }
        }
    }

    @Test
    void offsetsWithSecondsUseTheReferenceFormatter() {
        OffsetDateTime odt = LocalDateTime.of(1890, 1, 1, 0, 0).atOffset(ZoneOffset.ofHoursMinutesSeconds(0, 9, 21));
        assertEquals(OFFSET_DATETIME.format(odt), written(odt));
    }

    // ------------------------------------------------------------------ parser: every accepted shape

    @Test
    void acceptsAllSqliteTimeValueShapes() throws SQLiteException {
        LocalDateTime t = LocalDateTime.of(2024, 1, 15, 13, 45);
        assertEquals(t, parse("2024-01-15 13:45", LocalDateTime.class));
        assertEquals(t, parse("2024-01-15T13:45", LocalDateTime.class));
        assertEquals(t.withSecond(30), parse("2024-01-15 13:45:30", LocalDateTime.class));
        assertEquals(t.withSecond(30).withNano(500_000_000), parse("2024-01-15 13:45:30.5", LocalDateTime.class));
        assertEquals(t.withSecond(30).withNano(123_456_789), parse("2024-01-15T13:45:30.123456789", LocalDateTime.class));
        assertEquals(t.withSecond(30).withNano(1_000), parse("2024-01-15 13:45:30.000001", LocalDateTime.class));
        assertEquals(t, parse("  2024-01-15 13:45 \n", LocalDateTime.class));
        assertEquals(t.toLocalDate().atStartOfDay(), parse("2024-01-15", LocalDateTime.class));

        Instant utc = Instant.parse("2024-01-15T13:45:00Z");
        assertEquals(utc, parse("2024-01-15 13:45", Instant.class));
        assertEquals(utc, parse("2024-01-15 13:45Z", Instant.class));
        assertEquals(utc, parse("2024-01-15T13:45:00z", Instant.class));
        assertEquals(utc, parse("2024-01-15 15:45:00+02:00", Instant.class));
        assertEquals(utc, parse("2024-01-15 08:15:00-05:30", Instant.class));
        assertEquals(OffsetDateTime.of(2024, 1, 15, 8, 15, 0, 0, ZoneOffset.ofHoursMinutes(-5, -30)),
                parse("2024-01-15 08:15:00-05:30", OffsetDateTime.class));

        assertEquals(LocalTime.of(9, 5), parse("09:05", LocalTime.class));
        assertEquals(LocalTime.of(9, 5, 7, 250_000_000), parse("09:05:07.25", LocalTime.class));
        assertEquals(LocalTime.of(9, 5), parse("09:05+02:00", LocalTime.class));
        assertEquals(LocalTime.of(13, 45), parse("2024-01-15 13:45", LocalTime.class));
    }

    @Test
    void yearsOutsideFourDigitsUseTheFallbackBothWays() throws SQLiteException {
        for (LocalDate d : new LocalDate[]{LocalDate.of(-1, 3, 1), LocalDate.of(10_000, 12, 31), LocalDate.of(0, 1, 1)}) {
            assertEquals(d, bindAndRead(d, LocalDate.class));
            LocalDateTime dt = d.atTime(1, 2, 3, 4);
            assertEquals(dt, bindAndRead(dt, LocalDateTime.class));
            assertEquals(dt.toInstant(ZoneOffset.UTC), bindAndRead(dt.toInstant(ZoneOffset.UTC), Instant.class));
        }
        assertEquals(LocalDateTime.of(10_000, 1, 1, 10, 0), parse("+10000-01-01T10:00", LocalDateTime.class));
    }

    @Test
    void rejectsMalformedValues() {
        for (String bad : new String[]{"2024-02-30", "2024-13-01", "2024-01-15 24:00", "2024-01-15 13:60",
                "2024-01-15 13:45:30.1234567890", "2024-01-15X13:45", "2024-01-15 13:45+5:00",
                "2024-01-15 13:45+19:00", "2024-01-15 13:45:3", "2024-01-15 13:45:30.", "2024-1-15",
                "2024-01-15 13:45 junk", "yesterday", ""}) {
            assertThrows(SQLiteException.class, () -> parse(bad, LocalDateTime.class), bad);
        }
        assertThrows(SQLiteException.class, () -> parse("13:45", LocalDate.class));
        assertThrows(SQLiteException.class, () -> parse("2024-01-15", LocalTime.class));
    }

    // ------------------------------------------------------------------ UUID

    @Test
    void uuidRoundTripsAsCanonicalText() throws SQLiteException {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        echo.setObject(1, id);
        try (ResultSet rs = echo.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("123e4567-e89b-12d3-a456-426614174000", rs.getString(1));
            assertEquals(id, rs.getObject(1, UUID.class));
        }
        for (int i = 0; i < 1000; i++) {
            UUID random = UUID.randomUUID();
            assertEquals(random, bindAndRead(random, UUID.class));
        }
    }

    @Test
    void uuidReadsUpperCaseTextAndSixteenByteBlobs() throws SQLiteException {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        assertEquals(id, parse(" 123E4567-E89B-12D3-A456-426614174000 ", UUID.class));
        byte[] blob = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
        echo.setBytes(1, blob);
        try (ResultSet rs = echo.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(id, rs.getObject(1, UUID.class));
        }
    }

    @Test
    void uuidRejectsMalformedValues() throws SQLiteException {
        assertThrows(SQLiteException.class, () -> parse("not-a-uuid", UUID.class));
        assertThrows(SQLiteException.class, () -> parse("1-2-3-4-5", UUID.class)); // UUID.fromString would accept it
        assertThrows(SQLiteException.class, () -> parse("123e4567-e89b-12d3-a456-42661417400g", UUID.class));
        echo.setBytes(1, new byte[15]);
        try (ResultSet rs = echo.executeQuery()) {
            assertTrue(rs.next());
            assertThrows(SQLiteException.class, () -> rs.getObject(1, UUID.class));
        }
    }
}
