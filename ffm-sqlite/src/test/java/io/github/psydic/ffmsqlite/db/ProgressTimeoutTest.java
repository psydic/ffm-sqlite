package io.github.psydic.ffmsqlite.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressTimeoutTest {
    /** CPU-bound for many seconds: counts to 200 million. */
    static final String SLOW = "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) "
            + "SELECT count(*) FROM (SELECT x FROM c LIMIT 200000000)";

    private Connection conn;

    @BeforeEach
    void open() throws SQLiteException {
        conn = SQLite.getConnection(":memory:");
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE t (x)");
        }
    }

    @AfterEach
    void close() {
        conn.close();
    }

    private long count() throws SQLiteException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM t")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void queryTimeoutInterruptsRunningWork() throws SQLiteException {
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(Duration.ofMillis(200));
            long t0 = System.nanoTime();
            SQLiteTimeoutException e = assertThrows(SQLiteTimeoutException.class, () -> {
                try (ResultSet rs = st.executeQuery(SLOW)) {
                    rs.next();
                }
            });
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms >= 190 && ms < 2_000, "interrupted after " + ms + " ms");
            assertEquals(9, e.getErrorCode()); // SQLITE_INTERRUPT
            assertTrue(e.getMessage().contains("query timeout of 200 ms exceeded"), e.getMessage());
            assertInstanceOf(TimeoutException.class, e.getCause());
        }
        assertEquals(0, count(), "the connection stays usable, and untimed statements are not affected");
    }

    @Test
    void timedOutUpdatesAreRolledBack() throws SQLiteException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t " + SLOW.replace("SELECT count(*) FROM", "SELECT * FROM"))) {
            ps.setQueryTimeout(Duration.ofMillis(150));
            assertThrows(SQLiteTimeoutException.class, ps::executeUpdate);
        }
        assertEquals(0, count());
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(Duration.ofMillis(150));
            assertThrows(SQLiteTimeoutException.class, () -> st.executeUpdate("INSERT INTO t " + SLOW));
        }
        assertEquals(0, count());
    }

    @Test
    void timeoutBoundsEachCallNotTheTimeBetweenThem() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO t VALUES (1), (2), (3), (4), (5)");
            st.setQueryTimeout(Duration.ofMillis(100));
            int rows = 0;
            try (ResultSet rs = st.executeQuery("SELECT x FROM t")) {
                while (rs.next()) {
                    rows++;
                    Thread.sleep(60); // 300 ms in total between calls, each call itself is fast
                }
            }
            assertEquals(5, rows);
            assertEquals(1, st.getQueryTimeout()); // whole seconds, rounded up
        }
    }

    @Test
    void progressHandlerIsCalledAndCanAbort() throws SQLiteException {
        AtomicLong calls = new AtomicLong();
        conn.setProgressHandler(1_000, () -> calls.incrementAndGet() < 50);
        try (Statement st = conn.createStatement()) {
            SQLiteException e = assertThrows(SQLiteException.class, () -> st.executeQuery(SLOW).next());
            assertEquals(9, e.getErrorCode());
            assertInstanceOf(CancellationException.class, e.getCause());
            assertEquals(50, calls.get());

            conn.setProgressHandler(1_000, () -> {
                throw new IllegalStateException("stop now");
            });
            SQLiteException thrown = assertThrows(SQLiteException.class, () -> st.executeQuery(SLOW).next());
            assertInstanceOf(IllegalStateException.class, thrown.getCause());
            assertTrue(thrown.getMessage().contains("progress handler: stop now"), thrown.getMessage());

            conn.setProgressHandler(1_000, () -> count() >= 0); // uses its own connection: refused
            SQLiteException misuse = assertThrows(SQLiteException.class, () -> st.executeQuery(SLOW).next());
            assertTrue(misuse.getCause().getMessage().contains("cannot be used inside"), misuse.getCause().getMessage());

            conn.setProgressHandler(0, null);
            calls.set(0);
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM (SELECT 1 UNION ALL SELECT 2)")) {
                assertTrue(rs.next());
            }
            assertEquals(0, calls.get());
        }
    }

    @Test
    void timeoutsStillWorkWithAProgressHandler() throws SQLiteException {
        AtomicLong calls = new AtomicLong();
        conn.setProgressHandler(10_000, () -> calls.incrementAndGet() > 0);
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(Duration.ofMillis(100));
            assertThrows(SQLiteTimeoutException.class, () -> st.executeQuery(SLOW).next());
        }
        assertTrue(calls.get() > 0);
    }

    @Test
    void cancelFromAnotherThread() throws Exception {
        try (Statement st = conn.createStatement()) {
            Thread canceller = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
                st.cancel();
            });
            long t0 = System.nanoTime();
            SQLiteException e = assertThrows(SQLiteException.class, () -> {
                try (ResultSet rs = st.executeQuery(SLOW)) {
                    rs.next();
                }
            });
            long ms = (System.nanoTime() - t0) / 1_000_000;
            canceller.join();
            assertEquals(9, e.getErrorCode());
            assertTrue(ms < 2_000, "cancelled after " + ms + " ms");
        }
        assertEquals(0, count(), "the interrupt does not leak into the next statement");
    }
}
