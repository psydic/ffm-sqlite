package io.github.revsnai.ffmsqlite.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypesTest {
    private Connection conn;

    @BeforeEach
    void open() throws SQLiteException {
        conn = SQLite.getConnection(":memory:");
    }

    @AfterEach
    void close() {
        conn.close();
    }

    /** Binds {@code value}, selects it back, and returns its stored text and the value read as {@code type}. */
    private <T> T roundTrip(Object value, Class<T> type, String expectedText) throws SQLiteException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT ?1, typeof(?1)")) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                if (expectedText != null) {
                    assertEquals(expectedText, rs.getString(1));
                }
                return rs.getObject(1, type);
            }
        }
    }

    private <T> T select(String sql, Class<T> type) throws SQLiteException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getObject(1, type);
        }
    }

    // ------------------------------------------------------------------ java.time round trips

    @Test
    void localDate() throws SQLiteException {
        LocalDate d = LocalDate.of(2024, 2, 29);
        assertEquals(d, roundTrip(d, LocalDate.class, "2024-02-29"));
    }

    @Test
    void localTime() throws SQLiteException {
        assertEquals(LocalTime.of(9, 5), roundTrip(LocalTime.of(9, 5), LocalTime.class, "09:05:00"));
        LocalTime nanos = LocalTime.of(23, 59, 59, 123_456_789);
        assertEquals(nanos, roundTrip(nanos, LocalTime.class, "23:59:59.123456789"));
        assertEquals(LocalTime.of(1, 2, 3, 500_000_000), roundTrip(LocalTime.of(1, 2, 3, 500_000_000), LocalTime.class, "01:02:03.5"));
    }

    @Test
    void localDateTime() throws SQLiteException {
        LocalDateTime dt = LocalDateTime.of(2024, 1, 15, 13, 45, 30, 120_000_000);
        assertEquals(dt, roundTrip(dt, LocalDateTime.class, "2024-01-15 13:45:30.12"));
    }

    @Test
    void instantIsStoredAsUtcInSqliteFormat() throws SQLiteException {
        Instant i = Instant.parse("2024-01-15T13:45:30.250Z");
        assertEquals(i, roundTrip(i, Instant.class, "2024-01-15 13:45:30.25"));
    }

    @Test
    void offsetAndZonedDateTimeKeepTheirOffset() throws SQLiteException {
        OffsetDateTime odt = OffsetDateTime.of(2024, 6, 1, 8, 0, 0, 0, ZoneOffset.ofHours(2));
        assertEquals(odt, roundTrip(odt, OffsetDateTime.class, "2024-06-01 08:00:00+02:00"));
        assertEquals(odt.toInstant(), roundTrip(odt, Instant.class, null));
        assertEquals(LocalDateTime.of(2024, 6, 1, 8, 0), roundTrip(odt, LocalDateTime.class, null));

        ZonedDateTime zdt = ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, ZoneId.of("America/New_York"));
        ZonedDateTime back = roundTrip(zdt, ZonedDateTime.class, "2024-01-01 12:00:00-05:00");
        assertEquals(zdt.toInstant(), back.toInstant());
        assertEquals(ZoneOffset.ofHours(-5), back.getZone());
        assertEquals(OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                roundTrip(OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC), OffsetDateTime.class, "2024-01-01 00:00:00Z"));
    }

    @Test
    void nullsComeBackAsNull() throws SQLiteException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT ?, ?")) {
            ps.setObject(1, null);
            ps.setBigDecimal(2, null);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertNull(rs.getObject(1, LocalDate.class));
                assertNull(rs.getObject(1, Instant.class));
                assertNull(rs.getBigDecimal(2));
                assertNull(rs.getObject(2, Integer.class));
                assertThrows(SQLiteException.class, () -> rs.getObject(1, int.class));
            }
        }
    }

    // ------------------------------------------------------------------ interop with SQLite's date functions

    @Test
    void readsValuesProducedBySqlite() throws SQLiteException {
        assertEquals(LocalDate.of(2024, 3, 1), select("SELECT date('2024-02-29', '+1 day')", LocalDate.class));
        assertEquals(LocalDateTime.of(2024, 1, 15, 10, 0), select("SELECT datetime('2024-01-15T10:00')", LocalDateTime.class));
        // CURRENT_TIMESTAMP is UTC with no suffix: read as an Instant it must be "now".
        Instant now = select("SELECT CURRENT_TIMESTAMP", Instant.class);
        assertTrue(Math.abs(ChronoUnit.SECONDS.between(now, Instant.now())) <= 2, now.toString());
        // unixepoch() -> INTEGER seconds; julianday() -> REAL days
        Instant expected = Instant.parse("2024-01-15T10:00:00Z");
        assertEquals(expected, select("SELECT unixepoch('2024-01-15 10:00:00')", Instant.class));
        assertEquals(expected, select("SELECT julianday('2024-01-15 10:00:00')", Instant.class));
        assertEquals(LocalDate.of(2024, 1, 15), select("SELECT unixepoch('2024-01-15 10:00:00')", LocalDate.class));
        // date-only text read as a date-time is midnight
        assertEquals(LocalDateTime.of(2024, 1, 15, 0, 0), select("SELECT '2024-01-15'", LocalDateTime.class));
        assertEquals(LocalTime.of(10, 0), select("SELECT time('2024-01-15 10:00:00')", LocalTime.class));
    }

    @Test
    void sqliteDateFunctionsUnderstandWhatWeWrite() throws SQLiteException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT unixepoch(?), date(?, '+1 month'), strftime('%H', ?)")) {
            ps.setObject(1, Instant.parse("2024-01-15T13:45:30.250Z"));
            ps.setObject(2, LocalDate.of(2024, 1, 31));
            ps.setObject(3, OffsetDateTime.of(2024, 1, 1, 8, 0, 0, 0, ZoneOffset.ofHours(2)));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(Instant.parse("2024-01-15T13:45:30Z").getEpochSecond(), rs.getLong(1));
                assertEquals("2024-03-02", rs.getString(2));
                assertEquals("06", rs.getString(3)); // SQLite normalises to UTC
            }
        }
    }

    @Test
    void storedDateTimesSortChronologically() throws SQLiteException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE ev (at TEXT)");
        }
        LocalDateTime base = LocalDateTime.of(2024, 1, 1, 12, 0);
        List<LocalDateTime> values = List.of(base.plusNanos(500_000_000), base, base.plusNanos(50_000_000),
                base.plusSeconds(1), base.plusNanos(123_456_789), base.minusDays(1));
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO ev VALUES (?)")) {
            for (LocalDateTime v : values) {
                ps.setObject(1, v);
                ps.executeUpdate();
            }
        }
        List<LocalDateTime> read = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT at FROM ev WHERE at >= ? ORDER BY at")) {
            ps.setObject(1, base);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    read.add(rs.getObject("at", LocalDateTime.class));
                }
            }
        }
        assertEquals(values.stream().filter(v -> !v.isBefore(base)).sorted().toList(), read);
    }

    @Test
    void unparseableTemporalsThrow() {
        assertThrows(SQLiteException.class, () -> select("SELECT 'yesterday'", LocalDate.class));
        assertThrows(SQLiteException.class, () -> select("SELECT '2024-13-01'", LocalDate.class));
        assertThrows(SQLiteException.class, () -> select("SELECT '10:00'", LocalDate.class));
        assertThrows(SQLiteException.class, () -> select("SELECT '2024-01-01'", LocalTime.class));
    }

    // ------------------------------------------------------------------ BigDecimal / BigInteger

    @Test
    void bigDecimalStaysExactInTextColumns() throws SQLiteException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE money (amount TEXT, approx DECIMAL(30, 10))");
        }
        BigDecimal exact = new BigDecimal("12345678901234567890.123456789012345");
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO money VALUES (?, ?)")) {
            ps.setBigDecimal(1, exact);
            ps.setObject(2, exact);
            ps.executeUpdate();
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT amount, approx, typeof(approx) FROM money")) {
            assertTrue(rs.next());
            assertEquals(exact, rs.getBigDecimal("amount"));
            // NUMERIC/DECIMAL affinity converts to a REAL: documented precision loss.
            assertEquals("real", rs.getString(3));
            assertTrue(exact.compareTo(rs.getBigDecimal("approx")) != 0);
        }
    }

    @Test
    void bigDecimalKeepsScaleAndAvoidsExponents() throws SQLiteException {
        assertEquals(new BigDecimal("1.50"), roundTrip(new BigDecimal("1.50"), BigDecimal.class, "1.50"));
        assertEquals(new BigDecimal("1000"), roundTrip(new BigDecimal("1E+3"), BigDecimal.class, "1000"));
        assertEquals(new BigDecimal("-0.000001"), roundTrip(new BigDecimal("-0.000001"), BigDecimal.class, "-0.000001"));
        BigDecimal huge = new BigDecimal("1E+500");
        assertEquals(0, huge.compareTo(roundTrip(huge, BigDecimal.class, "1E+500")));
    }

    @Test
    void bigDecimalFromNumericStorage() throws SQLiteException {
        assertEquals(new BigDecimal("42"), select("SELECT 42", BigDecimal.class));
        assertEquals(new BigDecimal("0.1"), select("SELECT 0.1", BigDecimal.class));
        assertEquals(new BigDecimal("3.14"), select("SELECT ' 3.14 '", BigDecimal.class));
        assertThrows(SQLiteException.class, () -> select("SELECT 'abc'", BigDecimal.class));
    }

    @Test
    void bigInteger() throws SQLiteException {
        BigInteger small = BigInteger.valueOf(Long.MIN_VALUE);
        assertEquals(small, roundTrip(small, BigInteger.class, null));
        BigInteger huge = BigInteger.TWO.pow(100);
        assertEquals(huge, roundTrip(huge, BigInteger.class, huge.toString()));
        try (PreparedStatement ps = conn.prepareStatement("SELECT typeof(?), typeof(?)")) {
            ps.setObject(1, small);
            ps.setObject(2, huge);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("integer", rs.getString(1));
                assertEquals("text", rs.getString(2));
            }
        }
    }

    // ------------------------------------------------------------------ getObject(int, Class) basics

    @Test
    void typedGetObjectForBasicTypes() throws SQLiteException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT 7, 2.5, 'x', x'0102', 1")) {
            assertTrue(rs.next());
            assertEquals(7, rs.getObject(1, Integer.class));
            assertEquals(7L, rs.getObject(1, Long.class));
            assertEquals(2.5, rs.getObject(2, Double.class));
            assertEquals("x", rs.getObject(3, String.class));
            assertEquals(2, rs.getObject(4, byte[].class).length);
            assertEquals(true, rs.getObject(5, Boolean.class));
            assertEquals(7L, rs.getObject(1, Object.class));
            assertThrows(SQLiteException.class, () -> rs.getObject(1, java.util.Currency.class));
        }
    }

    @Test
    void unsupportedParameterTypeThrows() throws SQLiteException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT ?")) {
            assertThrows(SQLiteException.class, () -> ps.setObject(1, new Object()));
        }
    }
}
