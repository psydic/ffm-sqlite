package io.github.revsnai.ffmsqlite.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the issues found in the third (0.2.4) review. */
class ReviewFixes3JdbcTest {
    @TempDir
    Path dir;

    @Test
    void unsupportedFeaturesDoNotMakeHikariEvictTheConnection() throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:ffmsqlite:" + dir.resolve("pool.db"));
        cfg.setMaximumPoolSize(1);
        try (HikariDataSource pool = new HikariDataSource(cfg)) {
            JdbcConnection physical;
            try (Connection c = pool.getConnection()) {
                physical = c.unwrap(JdbcConnection.class);
                SQLFeatureNotSupportedException e = assertThrows(SQLFeatureNotSupportedException.class,
                        () -> c.prepareCall("SELECT 1"));
                assertEquals("0A500", e.getSQLState());
                assertTrue(e.getSQLState().startsWith("0A"), "still class 0A, feature not supported");
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
                    rs.next();
                    assertThrows(SQLFeatureNotSupportedException.class, rs::isLast);   // via a proxied ResultSet
                }
                assertThrows(SQLFeatureNotSupportedException.class,
                        () -> c.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY));
            }
            try (Connection c = pool.getConnection()) {
                assertSame(physical, c.unwrap(JdbcConnection.class), "Hikari evicted a healthy connection");
            }
        }
    }

    @Test
    void driverVersionIsNotStale() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite::memory:")) {
            DatabaseMetaData md = c.getMetaData();
            assertEquals(0, md.getDriverMajorVersion());
            assertTrue(md.getDriverMinorVersion() >= 2, md.getDriverVersion());
            assertTrue(md.getDriverVersion().startsWith(md.getDriverMajorVersion() + "." + md.getDriverMinorVersion()),
                    md.getDriverVersion());
        }
    }
}
