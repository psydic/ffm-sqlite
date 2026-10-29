package io.github.revsnai.ffmsqlite.jdbc;

import io.github.revsnai.ffmsqlite.db.SQLite;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryLimitsJdbcTest {

    @Test
    void limitPropertiesSetRunTimeLimits() throws Exception {
        Properties p = new Properties();
        p.setProperty("limit.length", "100000");
        try (Connection c = DriverManager.getConnection("jdbc:ffmsqlite::memory:?limit.SQL_LENGTH=5000", p);
             Statement st = c.createStatement()) {
            io.github.revsnai.ffmsqlite.db.Connection core = c.unwrap(io.github.revsnai.ffmsqlite.db.Connection.class);
            assertEquals(100_000, core.getLimit(SQLite.Limit.LENGTH));
            assertEquals(5000, core.getLimit(SQLite.Limit.SQL_LENGTH));
            SQLException e = assertThrows(SQLException.class, () -> {
                try (ResultSet rs = st.executeQuery("SELECT randomblob(200000)")) {
                    rs.next();
                }
            });
            assertEquals(18, e.getErrorCode());   // SQLITE_TOOBIG
            try (ResultSet rs = st.executeQuery("SELECT length(randomblob(1000))")) {
                assertTrue(rs.next());
                assertEquals(1000, rs.getInt(1));
            }
        }
    }

    @Test
    void invalidLimitPropertiesAreRejected() {
        for (String url : new String[] {"jdbc:ffmsqlite::memory:?limit.nonsense=1", "jdbc:ffmsqlite::memory:?limit.length=lots",
                "jdbc:ffmsqlite::memory:?limit.length=-1"}) {
            SQLException e = assertThrows(SQLException.class, () -> DriverManager.getConnection(url).close(), url);
            assertEquals("HY024", e.getSQLState());
        }
    }
}
