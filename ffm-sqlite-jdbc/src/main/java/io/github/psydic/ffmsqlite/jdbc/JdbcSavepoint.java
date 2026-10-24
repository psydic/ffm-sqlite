package io.github.psydic.ffmsqlite.jdbc;

import java.sql.SQLException;
import java.sql.Savepoint;

/** A savepoint implemented with SQL {@code SAVEPOINT}; unnamed ones get generated names. */
final class JdbcSavepoint implements Savepoint {
    private final int id;
    private final String name;

    JdbcSavepoint(int id, String name) {
        this.id = id;
        this.name = name;
    }

    static JdbcSavepoint of(Savepoint sp) throws SQLException {
        if (sp instanceof JdbcSavepoint s) {
            return s;
        }
        throw new SQLException("not a savepoint of this driver: " + sp, "3B001");
    }

    /** The name as a quoted SQL identifier. */
    String sqlName() {
        String n = name != null ? name : "SQLITE_SAVEPOINT_" + id;
        return "\"" + n.replace("\"", "\"\"") + "\"";
    }

    @Override
    public int getSavepointId() throws SQLException {
        if (name != null) {
            throw new SQLException("named savepoint has no id", "3B001");
        }
        return id;
    }

    @Override
    public String getSavepointName() throws SQLException {
        if (name == null) {
            throw new SQLException("unnamed savepoint has no name", "3B001");
        }
        return name;
    }
}
