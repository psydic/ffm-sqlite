# Changelog

## 0.1.1

Fixes from a code review of 0.1.0. Upgrading is recommended: the first two can crash the JVM.

### ffm-sqlite

- **`ResultSetMetaData` used after its result set, statement or connection was closed read freed native memory**
  (JVM crash, or wrong values). It now copies everything from the statement when created, so it stays valid
  after close. Affects `ResultSet.getMetaData()` and `PreparedStatement.getMetaData()`, and through them the
  JDBC driver's metadata.
- **`Statement.cancel()` racing `Connection.close()` on another thread could call `sqlite3_interrupt` on a closed
  handle.** Interrupt and close are now ordered by a lock.
- `setAutoCommit(true)` whose COMMIT failed (e.g. vetoed by a `CommitListener`) left `getAutoCommit()` returning
  false while later statements auto-committed. Now the connection stays in manual-commit mode with a new
  transaction, as after a failed `commit()`.
- When starting the next transaction after a failed COMMIT/ROLLBACK also failed, that error replaced the original.
  The original is now thrown, with the second error suppressed.
- SQL ending in a comment (`SELECT 1; -- note`) was treated as a second statement: rejected by `prepareStatement`,
  and the final query of a script ran without returning its rows.
- `executeBatch` failures lost their cause (a UDF's or listener's exception) and turned a
  `SQLiteTimeoutException` into a plain `SQLiteException`.
- `DatabaseMetaData.getTables()` failed under a Turkish default locale for views and virtual tables.
- File-format PRAGMAs (`page_size`, `auto_vacuum`, `encoding`) now run before the others when a connection opens.
  Before, `journal_mode = WAL` could run first and make SQLite ignore `page_size` on a new database.
- Replacing or removing a function or collation now frees its upcall stubs. Before, they stayed allocated until
  the connection closed, so re-registering on every pool checkout leaked native memory.

### ffm-sqlite-jdbc

- Picks up all of the above (metadata after close, batch timeouts reported as `SQLTimeoutException` / HYT00,
  PRAGMA order for URL parameters).
- `getTime` / `getTimestamp` now read time-only text (`13:45:30`, as stored by `setObject(LocalTime)` and returned by
  SQLite's `time()`) on 1970-01-01, as `Time.valueOf` does.

## 0.1.0

First release.
