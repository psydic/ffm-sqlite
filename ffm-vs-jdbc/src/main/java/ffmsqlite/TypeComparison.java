package ffmsqlite;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Probes how sqlite-jdbc and ffm-sqlite store and read back each Java type: binds the value with each
 * library's idiomatic setter into a column with a typical declared type, then reports SQLite's
 * {@code typeof()} / {@code quote()} of what was stored and whether the idiomatic getter returns an
 * equal value. Prints a Markdown table.
 */
public class TypeComparison {

    interface JdbcBind { void bind(java.sql.PreparedStatement ps) throws Exception; }
    interface JdbcRead { Object read(java.sql.ResultSet rs) throws Exception; }
    interface LibBind { void bind(io.github.revsnai.ffmsqlite.db.PreparedStatement ps) throws Exception; }
    interface LibRead { Object read(io.github.revsnai.ffmsqlite.db.ResultSet rs) throws Exception; }

    record Case(String javaType, String declType, Object value,
                String jdbcApi, JdbcBind jdbcBind, JdbcRead jdbcRead,
                String libApi, LibBind libBind, LibRead libRead) {
    }

    static final LocalDate DATE = LocalDate.of(2024, 2, 29);
    static final LocalTime TIME = LocalTime.of(13, 45, 30, 500_000_000);
    static final LocalDateTime DATETIME = LocalDateTime.of(DATE, TIME);
    static final Instant INSTANT = Instant.parse("2024-02-29T13:45:30.500Z");
    static final OffsetDateTime ODT = DATETIME.atOffset(ZoneOffset.ofHours(2));
    static final ZonedDateTime ZDT = DATETIME.atZone(ZoneId.of("Europe/Paris"));
    static final BigDecimal DEC = new BigDecimal("12345678901234567890.123456789");
    static final BigInteger BIG = BigInteger.TWO.pow(70);
    static final byte[] BYTES = {1, 2, 3, (byte) 0xff};
    static final UUID ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    static <T> Case obj(String javaType, String decl, T v, Class<T> cls) {
        return new Case(javaType, decl, v,
                "setObject / getObject(i, " + cls.getSimpleName() + ".class)", ps -> ps.setObject(1, v), rs -> rs.getObject(1, cls),
                "setObject / getObject(i, " + cls.getSimpleName() + ".class)", ps -> ps.setObject(1, v), rs -> rs.getObject(1, cls));
    }

    static final List<Case> CASES = List.of(
            new Case("int", "INTEGER", 42,
                    "setInt / getInt", ps -> ps.setInt(1, 42), rs -> rs.getInt(1),
                    "setInt / getInt", ps -> ps.setInt(1, 42), rs -> rs.getInt(1)),
            new Case("long", "INTEGER", Long.MAX_VALUE,
                    "setLong / getLong", ps -> ps.setLong(1, Long.MAX_VALUE), rs -> rs.getLong(1),
                    "setLong / getLong", ps -> ps.setLong(1, Long.MAX_VALUE), rs -> rs.getLong(1)),
            new Case("double", "REAL", 3.141592653589793,
                    "setDouble / getDouble", ps -> ps.setDouble(1, 3.141592653589793), rs -> rs.getDouble(1),
                    "setDouble / getDouble", ps -> ps.setDouble(1, 3.141592653589793), rs -> rs.getDouble(1)),
            new Case("float", "REAL", 1.1f,
                    "setFloat / getFloat", ps -> ps.setFloat(1, 1.1f), rs -> rs.getFloat(1),
                    "setFloat / getFloat", ps -> ps.setFloat(1, 1.1f), rs -> rs.getFloat(1)),
            new Case("boolean", "BOOLEAN", true,
                    "setBoolean / getBoolean", ps -> ps.setBoolean(1, true), rs -> rs.getBoolean(1),
                    "setBoolean / getBoolean", ps -> ps.setBoolean(1, true), rs -> rs.getBoolean(1)),
            new Case("String", "TEXT", "héllo 世界 🌍",
                    "setString / getString", ps -> ps.setString(1, "héllo 世界 🌍"), rs -> rs.getString(1),
                    "setString / getString", ps -> ps.setString(1, "héllo 世界 🌍"), rs -> rs.getString(1)),
            new Case("byte[]", "BLOB", BYTES,
                    "setBytes / getBytes", ps -> ps.setBytes(1, BYTES), rs -> rs.getBytes(1),
                    "setBytes / getBytes", ps -> ps.setBytes(1, BYTES), rs -> rs.getBytes(1)),
            new Case("null", "TEXT", null,
                    "setNull / getObject", ps -> ps.setNull(1, java.sql.Types.VARCHAR), rs -> rs.getObject(1),
                    "setNull / getObject", ps -> ps.setNull(1), rs -> rs.getObject(1)),
            new Case("BigDecimal", "TEXT", DEC,
                    "setBigDecimal / getBigDecimal", ps -> ps.setBigDecimal(1, DEC), rs -> rs.getBigDecimal(1),
                    "setBigDecimal / getBigDecimal", ps -> ps.setBigDecimal(1, DEC), rs -> rs.getBigDecimal(1)),
            new Case("BigDecimal", "DECIMAL(30,9)", DEC,
                    "setBigDecimal / getBigDecimal", ps -> ps.setBigDecimal(1, DEC), rs -> rs.getBigDecimal(1),
                    "setBigDecimal / getBigDecimal", ps -> ps.setBigDecimal(1, DEC), rs -> rs.getBigDecimal(1)),
            obj("BigInteger (2^70)", "TEXT", BIG, BigInteger.class),
            obj("LocalDate", "DATE", DATE, LocalDate.class),
            obj("LocalTime", "TIME", TIME, LocalTime.class),
            obj("LocalDateTime", "DATETIME", DATETIME, LocalDateTime.class),
            obj("Instant", "TIMESTAMP", INSTANT, Instant.class),
            obj("OffsetDateTime", "TIMESTAMP", ODT, OffsetDateTime.class),
            obj("ZonedDateTime", "TIMESTAMP", ZDT, ZonedDateTime.class),
            obj("UUID", "TEXT", ID, UUID.class),
            new Case("java.sql.Date", "DATE", java.sql.Date.valueOf(DATE),
                    "setDate / getDate", ps -> ps.setDate(1, java.sql.Date.valueOf(DATE)), rs -> rs.getDate(1),
                    "(no java.sql types)", null, null),
            new Case("java.sql.Timestamp", "TIMESTAMP", java.sql.Timestamp.valueOf(DATETIME),
                    "setTimestamp / getTimestamp", ps -> ps.setTimestamp(1, java.sql.Timestamp.valueOf(DATETIME)), rs -> rs.getTimestamp(1),
                    "(no java.sql types)", null, null)
    );

    public static void main(String[] args) throws Exception {
        System.out.println("| Java type | Declared column | sqlite-jdbc API | sqlite-jdbc stores | sqlite-jdbc reads back | ffm-sqlite API | ffm-sqlite stores | ffm-sqlite reads back |");
        System.out.println("|---|---|---|---|---|---|---|---|");
        try (java.sql.Connection jc = DriverManager.getConnection("jdbc:sqlite::memory:");
             io.github.revsnai.ffmsqlite.db.Connection lc = io.github.revsnai.ffmsqlite.db.SQLite.getConnection(":memory:")) {
            for (Case c : CASES) {
                String ddl = "CREATE TABLE t (v " + c.declType + ")";
                try (var st = jc.createStatement()) {
                    st.executeUpdate("DROP TABLE IF EXISTS t");
                    st.executeUpdate(ddl);
                }
                try (var st = lc.createStatement()) {
                    st.executeUpdate("DROP TABLE IF EXISTS t; " + ddl);
                }
                String[] jdbc = probeJdbc(jc, c);
                String[] lib = probeLib(lc, c);
                System.out.printf("| %s | %s | %s | %s | %s | %s | %s | %s |%n", c.javaType, c.declType,
                        c.jdbcApi, jdbc[0], jdbc[1], c.libApi, lib[0], lib[1]);
            }
        }
    }

    static String[] probeJdbc(java.sql.Connection conn, Case c) throws Exception {
        try (var ps = conn.prepareStatement("INSERT INTO t VALUES (?)")) {
            c.jdbcBind.bind(ps);
            ps.executeUpdate();
        } catch (Exception e) {
            return new String[]{"bind fails: " + brief(e), "—"};
        }
        String stored = stored(conn.createStatement().executeQuery("SELECT typeof(v), quote(v) FROM t"));
        try (var rs = conn.createStatement().executeQuery("SELECT v FROM t")) {
            rs.next();
            return new String[]{stored, verdict(c.value, c.jdbcRead.read(rs))};
        } catch (Exception e) {
            return new String[]{stored, "❌ " + brief(e)};
        }
    }

    static String[] probeLib(io.github.revsnai.ffmsqlite.db.Connection conn, Case c) throws Exception {
        if (c.libBind == null) {
            return new String[]{"—", "—"};
        }
        try (var ps = conn.prepareStatement("INSERT INTO t VALUES (?)")) {
            c.libBind.bind(ps);
            ps.executeUpdate();
        } catch (Exception e) {
            return new String[]{"bind fails: " + brief(e), "—"};
        }
        String stored;
        try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT typeof(v), quote(v) FROM t")) {
            rs.next();
            stored = rs.getString(1) + " `" + rs.getString(2) + "`";
        }
        try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT v FROM t")) {
            rs.next();
            return new String[]{stored, verdict(c.value, c.libRead.read(rs))};
        } catch (Exception e) {
            return new String[]{stored, "❌ " + brief(e)};
        }
    }

    static String stored(java.sql.ResultSet rs) throws Exception {
        try (rs) {
            rs.next();
            return rs.getString(1) + " `" + rs.getString(2) + "`";
        }
    }

    static String verdict(Object expected, Object actual) {
        boolean same = expected instanceof byte[] e && actual instanceof byte[] a ? Arrays.equals(e, a)
                : expected instanceof BigDecimal e && actual instanceof BigDecimal a ? e.compareTo(a) == 0 && e.scale() == a.scale()
                : expected instanceof ZonedDateTime e && actual instanceof ZonedDateTime a ? e.toInstant().equals(a.toInstant())
                : Objects.equals(expected, actual);
        String shown = actual instanceof byte[] a ? Arrays.toString(a) : String.valueOf(actual);
        if (expected instanceof ZonedDateTime && same && !expected.equals(actual)) {
            return "≈ same instant, zone → `" + shown + "`";
        }
        return (same ? "✅ " : "⚠️ `" + shown + "`").trim();
    }

    static String brief(Exception e) {
        String m = String.valueOf(e.getMessage());
        m = m.replace('|', '/').replace('\n', ' ');
        return e.getClass().getSimpleName() + ": " + (m.length() > 70 ? m.substring(0, 70) + "…" : m);
    }
}
