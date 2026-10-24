package ffmsqlite;

import java.sql.*;
public class BehaviorProbe {
  static String jdbc(String what, ThrowingSupplier s) { try { return what + " -> " + s.get(); } catch (Exception e) { return what + " -> throws " + e.getClass().getSimpleName() + ": " + e.getMessage(); } }
  interface ThrowingSupplier { Object get() throws Exception; }
  public static void main(String[] a) throws Exception {
    try (Connection j = DriverManager.getConnection("jdbc:sqlite::memory:"); io.github.revsnai.ffmsqlite.db.Connection f = io.github.revsnai.ffmsqlite.db.SQLite.getConnection(":memory:")) {
      j.createStatement().executeUpdate("CREATE TABLE u (id INTEGER PRIMARY KEY, email TEXT UNIQUE)");
      try (var st = f.createStatement()) { st.executeUpdate("CREATE TABLE u (id INTEGER PRIMARY KEY, email TEXT UNIQUE)"); }
      System.out.println("getObject(1) of SELECT 42: jdbc " + cls(j, "SELECT 42") + ", ffm " + clsF(f, "SELECT 42"));
      System.out.println("getObject(1) of SELECT 5000000000: jdbc " + cls(j, "SELECT 5000000000") + ", ffm " + clsF(f, "SELECT 5000000000"));
      System.out.println(jdbc("jdbc executeUpdate(\"SELECT 1\")", () -> j.createStatement().executeUpdate("SELECT 1")));
      System.out.println(jdbc("ffm  executeUpdate(\"SELECT 1\")", () -> { try (var st = f.createStatement()) { return st.executeUpdate("SELECT 1"); } }));
      System.out.println(jdbc("jdbc executeQuery(\"DELETE FROM u\")", () -> j.createStatement().executeQuery("DELETE FROM u")));
      System.out.println(jdbc("ffm  executeQuery(\"DELETE FROM u\")", () -> { try (var st = f.createStatement()) { return st.executeQuery("DELETE FROM u"); } }));
      j.createStatement().executeUpdate("INSERT INTO u VALUES (1, 'a')");
      try (var st = f.createStatement()) { st.executeUpdate("INSERT INTO u VALUES (1, 'a')"); }
      try { j.createStatement().executeUpdate("INSERT INTO u VALUES (2, 'a')"); } catch (SQLException e) { System.out.println("unique violation: jdbc getErrorCode=" + e.getErrorCode() + " SQLState=" + e.getSQLState() + " class=" + e.getClass().getName()); }
      try (var st = f.createStatement()) { st.executeUpdate("INSERT INTO u VALUES (2, 'a')"); } catch (io.github.revsnai.ffmsqlite.db.SQLiteException e) { System.out.println("unique violation: ffm  getErrorCode=" + e.getErrorCode() + " extended=" + e.getExtendedErrorCode() + " (no SQLState)"); }
      System.out.println("ResultSetMetaData.getColumnTypeName(1) for 'SELECT 1.5': jdbc " + j.createStatement().executeQuery("SELECT 1.5").getMetaData().getColumnTypeName(1));
      System.out.println(jdbc("jdbc getString(1) on SELECT 1.0", () -> { var rs = j.createStatement().executeQuery("SELECT 1.0"); rs.next(); return rs.getString(1); }));
      System.out.println(jdbc("ffm  getString(1) on SELECT 1.0", () -> { try (var st = f.createStatement(); var rs = st.executeQuery("SELECT 1.0")) { rs.next(); return rs.getString(1); } }));
      System.out.println(jdbc("jdbc getMetaData().getColumnCount() before next() then getString", () -> { var rs = j.createStatement().executeQuery("SELECT 1"); return rs.getString(1); }));
      System.out.println(jdbc("ffm  getString(1) before next()", () -> { try (var st = f.createStatement(); var rs = st.executeQuery("SELECT 1")) { return rs.getString(1); } }));
    }
  }
  static String cls(Connection j, String sql) throws Exception { var rs = j.createStatement().executeQuery(sql); rs.next(); return rs.getObject(1).getClass().getSimpleName(); }
  static String clsF(io.github.revsnai.ffmsqlite.db.Connection f, String sql) throws Exception { try (var st = f.createStatement(); var rs = st.executeQuery(sql)) { rs.next(); return rs.getObject(1).getClass().getSimpleName(); } }
}
