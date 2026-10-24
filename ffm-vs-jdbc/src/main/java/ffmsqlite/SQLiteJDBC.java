package ffmsqlite;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/** The JDBC equivalent of {@link SQLiteFFM}: connect, CREATE TABLE, INSERT. */
public final class SQLiteJDBC {

    public static Connection open(String path) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + path);
    }

    public static void createTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY, name TEXT NOT NULL, score INTEGER NOT NULL)");
        }
    }

    /** Inserts {@code n} rows in one transaction using a single prepared statement. */
    public static void insertRows(Connection conn, int n) throws SQLException {
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t (id, name, score) VALUES (?, ?, ?)")) {
            for (int i = 0; i < n; i++) {
                ps.setInt(1, i);
                ps.setString(2, "name-" + i);
                ps.setInt(3, i * 7);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    public static void main(String[] args) throws SQLException {
        try (Connection conn = open(":memory:")) {
            createTable(conn);
            insertRows(conn, 1_000);
            try (Statement st = conn.createStatement();
                 var rs = st.executeQuery("SELECT count(*), sqlite_version() FROM t")) {
                rs.next();
                System.out.println("rows=" + rs.getInt(1) + " sqlite=" + rs.getString(2));
            }
        }
    }

    private SQLiteJDBC() {
    }
}
