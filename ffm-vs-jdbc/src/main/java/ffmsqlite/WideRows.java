package ffmsqlite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

/**
 * The 20-column table of the wide-row benchmarks, and a deterministic pool of row values so that every library
 * binds identical data and value generation stays out of the measured code. Rows are about 600 bytes on disk.
 */
final class WideRows {
    static final int ROWS = 1_000_000;
    static final int POOL = 4096;
    static final int COLUMNS = 20;

    static final String DDL = """
            CREATE TABLE wide (
                id          INTEGER PRIMARY KEY,  -- 1-8 bytes (rowid)
                tiny        INTEGER NOT NULL,     -- 1 byte: 0..100
                small       INTEGER NOT NULL,     -- 2 bytes: 0..30000
                medium      INTEGER NOT NULL,     -- 4 bytes
                big         INTEGER NOT NULL,     -- 8 bytes
                flag        BOOLEAN NOT NULL,     -- 0 bytes (0/1 are encoded in the record header)
                price       REAL NOT NULL,        -- 8 bytes
                ratio       REAL,                 -- 8 bytes, 10% NULL
                code        TEXT NOT NULL,        -- 8 bytes
                name        TEXT NOT NULL,        -- ~20 bytes
                email       TEXT NOT NULL,        -- ~30 bytes, indexed
                city        TEXT NOT NULL,        -- ~10 bytes
                note        TEXT,                 -- ~64 bytes, 20% NULL
                description TEXT NOT NULL,        -- ~128 bytes
                created     TEXT NOT NULL,        -- 23 bytes: ISO date-time
                birth       TEXT NOT NULL,        -- 10 bytes: ISO date
                uuid        TEXT NOT NULL,        -- 36 bytes
                amount      TEXT NOT NULL,        -- ~10 bytes: exact decimal
                hash        BLOB NOT NULL,        -- 32 bytes
                payload     BLOB NOT NULL         -- 100..300 bytes
            );
            CREATE INDEX ix_wide_email ON wide (email);
            """;

    static final String INSERT = "INSERT INTO wide (id, tiny, small, medium, big, flag, price, ratio, code, name, email, city, "
            + "note, description, created, birth, uuid, amount, hash, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    static final String SELECT_ROW = "SELECT id, tiny, small, medium, big, flag, price, ratio, code, name, email, city, note, "
            + "description, created, birth, uuid, amount, hash, payload FROM wide WHERE id = ?";
    static final String SELECT_RANGE = "SELECT id, name, price, created, payload FROM wide WHERE id BETWEEN ? AND ?";

    // Pool values, by column (index = row % POOL).
    static final int[] TINY = new int[POOL], SMALL = new int[POOL], MEDIUM = new int[POOL];
    static final long[] BIG = new long[POOL];
    static final boolean[] FLAG = new boolean[POOL];
    static final double[] PRICE = new double[POOL];
    static final Double[] RATIO = new Double[POOL];
    static final String[] CODE = new String[POOL], NAME = new String[POOL], EMAIL = new String[POOL], CITY = new String[POOL],
            NOTE = new String[POOL], DESCRIPTION = new String[POOL], CREATED = new String[POOL], BIRTH = new String[POOL],
            UUID_ = new String[POOL], AMOUNT = new String[POOL];
    static final byte[][] HASH = new byte[POOL][], PAYLOAD = new byte[POOL][];

    static {
        SplittableRandom r = new SplittableRandom(42);
        String[] cities = {"Pune", "Mumbai", "Bengaluru", "Delhi", "Chennai", "Kolkata", "Hyderabad", "Jaipur", "Lucknow", "Kochi"};
        String words = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore et dolore magna aliqua ";
        for (int i = 0; i < POOL; i++) {
            TINY[i] = r.nextInt(101);
            SMALL[i] = r.nextInt(30_001);
            MEDIUM[i] = r.nextInt();
            BIG[i] = r.nextLong();
            FLAG[i] = r.nextBoolean();
            PRICE[i] = Math.round(r.nextDouble() * 1_000_000) / 100.0;
            RATIO[i] = r.nextInt(10) == 0 ? null : r.nextDouble();
            CODE[i] = String.format("%08X", r.nextInt());
            NAME[i] = "User " + Integer.toString(r.nextInt(1 << 30), 36) + " " + Integer.toString(r.nextInt(1 << 20), 36);
            EMAIL[i] = "user" + i + "." + Integer.toString(r.nextInt(1 << 30), 36) + "@example.com";
            CITY[i] = cities[r.nextInt(cities.length)];
            NOTE[i] = r.nextInt(5) == 0 ? null : words.substring(0, 40 + r.nextInt(48));
            DESCRIPTION[i] = (words + words).substring(r.nextInt(40), r.nextInt(40) + 128);
            CREATED[i] = String.format("2024-%02d-%02d %02d:%02d:%02d.%03d", 1 + r.nextInt(12), 1 + r.nextInt(28),
                    r.nextInt(24), r.nextInt(60), r.nextInt(60), r.nextInt(1000));
            BIRTH[i] = String.format("%04d-%02d-%02d", 1950 + r.nextInt(55), 1 + r.nextInt(12), 1 + r.nextInt(28));
            UUID_[i] = new java.util.UUID(r.nextLong(), r.nextLong()).toString();
            AMOUNT[i] = (r.nextInt(1_000_000)) + "." + String.format("%02d", r.nextInt(100));
            HASH[i] = new byte[32];
            for (int b = 0; b < 32; b++) {
                HASH[i][b] = (byte) r.nextInt(256);
            }
            PAYLOAD[i] = new byte[100 + r.nextInt(201)];
            for (int b = 0; b < PAYLOAD[i].length; b++) {
                PAYLOAD[i][b] = (byte) r.nextInt(256);
            }
        }
    }

    static void bind(java.sql.PreparedStatement ps, long id) throws java.sql.SQLException {
        int i = (int) (id % POOL);
        ps.setLong(1, id);
        ps.setInt(2, TINY[i]);
        ps.setInt(3, SMALL[i]);
        ps.setInt(4, MEDIUM[i]);
        ps.setLong(5, BIG[i]);
        ps.setBoolean(6, FLAG[i]);
        ps.setDouble(7, PRICE[i]);
        if (RATIO[i] == null) {
            ps.setNull(8, java.sql.Types.REAL);
        } else {
            ps.setDouble(8, RATIO[i]);
        }
        ps.setString(9, CODE[i]);
        ps.setString(10, NAME[i]);
        ps.setString(11, EMAIL[i]);
        ps.setString(12, CITY[i]);
        ps.setString(13, NOTE[i]);
        ps.setString(14, DESCRIPTION[i]);
        ps.setString(15, CREATED[i]);
        ps.setString(16, BIRTH[i]);
        ps.setString(17, UUID_[i]);
        ps.setString(18, AMOUNT[i]);
        ps.setBytes(19, HASH[i]);
        ps.setBytes(20, PAYLOAD[i]);
    }

    static void bind(io.github.revsnai.ffmsqlite.db.PreparedStatement ps, long id) throws io.github.revsnai.ffmsqlite.db.SQLiteException {
        int i = (int) (id % POOL);
        ps.setLong(1, id);
        ps.setInt(2, TINY[i]);
        ps.setInt(3, SMALL[i]);
        ps.setInt(4, MEDIUM[i]);
        ps.setLong(5, BIG[i]);
        ps.setBoolean(6, FLAG[i]);
        ps.setDouble(7, PRICE[i]);
        if (RATIO[i] == null) {
            ps.setNull(8);
        } else {
            ps.setDouble(8, RATIO[i]);
        }
        ps.setString(9, CODE[i]);
        ps.setString(10, NAME[i]);
        ps.setString(11, EMAIL[i]);
        ps.setString(12, CITY[i]);
        ps.setString(13, NOTE[i]);
        ps.setString(14, DESCRIPTION[i]);
        ps.setString(15, CREATED[i]);
        ps.setString(16, BIRTH[i]);
        ps.setString(17, UUID_[i]);
        ps.setString(18, AMOUNT[i]);
        ps.setBytes(19, HASH[i]);
        ps.setBytes(20, PAYLOAD[i]);
    }

    /** All 20 columns of a SELECT_ROW row, each through its typed getter. */
    static void read(java.sql.ResultSet rs, org.openjdk.jmh.infra.Blackhole bh) throws java.sql.SQLException {
        bh.consume(rs.getLong(1));
        bh.consume(rs.getInt(2));
        bh.consume(rs.getInt(3));
        bh.consume(rs.getInt(4));
        bh.consume(rs.getLong(5));
        bh.consume(rs.getBoolean(6));
        bh.consume(rs.getDouble(7));
        bh.consume(rs.getDouble(8));
        for (int c = 9; c <= 17; c++) {
            bh.consume(rs.getString(c));
        }
        bh.consume(rs.getBigDecimal(18));
        bh.consume(rs.getBytes(19));
        bh.consume(rs.getBytes(20));
    }

    static void read(io.github.revsnai.ffmsqlite.db.ResultSet rs, org.openjdk.jmh.infra.Blackhole bh) throws io.github.revsnai.ffmsqlite.db.SQLiteException {
        bh.consume(rs.getLong(1));
        bh.consume(rs.getInt(2));
        bh.consume(rs.getInt(3));
        bh.consume(rs.getInt(4));
        bh.consume(rs.getLong(5));
        bh.consume(rs.getBoolean(6));
        bh.consume(rs.getDouble(7));
        bh.consume(rs.getDouble(8));
        for (int c = 9; c <= 17; c++) {
            bh.consume(rs.getString(c));
        }
        bh.consume(rs.getBigDecimal(18));
        bh.consume(rs.getBytes(19));
        bh.consume(rs.getBytes(20));
    }

    /** Where the 1M-row template database lives: -Dwide.dir, else ./data (git-ignored). */
    static Path dataDir() throws java.io.IOException {
        Path dir = Path.of(System.getProperty("wide.dir", "data")).toAbsolutePath();
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * The 1M-row template (rollback journal, so it is a single file to copy), built once with ffm-sqlite and reused
     * by later runs. Benchmarks copy it and switch the copy to WAL.
     */
    static synchronized Path template() throws Exception {
        Path file = dataDir().resolve("wide-1m.db");
        if (Files.exists(file)) {
            return file;
        }
        Path tmp = dataDir().resolve("wide-1m.db.building");
        Files.deleteIfExists(tmp);
        var config = io.github.revsnai.ffmsqlite.db.SQLite.Config.DEFAULT
                .withSynchronous(io.github.revsnai.ffmsqlite.db.SQLite.Synchronous.OFF);
        try (var c = io.github.revsnai.ffmsqlite.db.SQLite.getConnection(tmp.toString(), config)) {
            try (var st = c.createStatement()) {
                st.executeUpdate(DDL);
            }
            c.setAutoCommit(false);
            try (var ps = c.prepareStatement(INSERT)) {
                for (long id = 1; id <= ROWS; id++) {
                    bind(ps, id);
                    ps.executeUpdate();
                }
            }
            c.commit();
        }
        Files.move(tmp, file);
        return file;
    }

    /** A fresh copy of the template in its own directory under {@link #dataDir()}, switched to WAL. */
    static Path copyOfTemplate() throws Exception {
        Path template = template();
        Path dir = Files.createTempDirectory(dataDir(), "wide-run");
        Path db = dir.resolve("wide.db");
        Files.copy(template, db);
        try (var c = io.github.revsnai.ffmsqlite.db.SQLite.getConnection(db.toString());
             var st = c.createStatement()) {
            st.executeUpdate("PRAGMA journal_mode = WAL");
        }
        return db;
    }

    static void deleteDir(Path dir) throws java.io.IOException {
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) {
                Files.deleteIfExists(p);
            }
        }
        Files.delete(dir);
    }

    private WideRows() {
    }
}
