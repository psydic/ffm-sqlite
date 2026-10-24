# Changelog

## 0.2.1

Fixes from a second, deeper code review. Upgrading is recommended: #1 can lose data silently and #3 can crash the
JVM.

### ffm-sqlite

1. **A transaction that SQLite rolled back on its own was not reported.** When a statement fails in a way that
   makes SQLite roll back the whole transaction (a query timeout or interrupt, SQLITE_FULL, an I/O error), later
   statements were each auto-committed and `commit()` returned normally, losing the earlier work silently. Now every
   statement and `commit()` fail with the new `SQLiteTransactionRolledBackException` until `rollback()`;
   `setAutoCommit(true)` fails the same way. A COMMIT or ROLLBACK sent as SQL text in manual-commit mode now starts
   the next transaction, as `commit()`/`rollback()` do.
2. **Cached statements returned the wrong columns after a schema change.** After `ALTER TABLE`, a migration from
   another connection, `restore()` or `deserialize()`, a re-prepared `SELECT *` kept its old column list, so
   `getString("c")` could return another column's value. Column information is now reloaded when SQLite re-prepares
   the statement.
3. **Two functions (or collations) whose names differ only in non-ASCII case crashed the JVM** (a regression of the
   0.1.1 stub cleanup): names are now matched the way SQLite matches them, ASCII case only.
4. A NUL character in SQL made `Statement.execute` loop forever. NUL padding at the end is now ignored; a NUL followed by
   more SQL is an error.
5. `DatabaseMetaData.getTables()` listed a table twice when a trigger had the same name.
6. A query timeout now also bounds the time spent waiting for a lock (busy timeout or a custom `BusyHandler`), failing
   with `SQLiteTimeoutException`.
7. Query timeouts above about 24.8 days (e.g. `Integer.MAX_VALUE` seconds, "no limit" for some frameworks) threw
   `ArithmeticException` on execute; they are now treated as no practical limit.
8. `getInt()`, `getObject(i, Integer/Short/Byte.class)` and `FunctionArgs.getInt()` silently wrapped values out of
   range (3000000000 read as -1294967296); they now fail with SQLITE_MISMATCH.
9. `Connection.interrupt()` is now public (thread-safe; used by the JDBC driver's `abort`).

### ffm-sqlite-jdbc

- Picks up all of the above: auto-rollbacks surface as `SQLTransactionRollbackException` (SQLState 40000),
  out-of-range reads as `SQLDataException` (22003), lock-wait timeouts as `SQLTimeoutException`.
- `getGeneratedKeys()` after an upsert that updated instead of inserting returned an unrelated rowid (from an earlier
  insert, possibly into another table); it is now empty. After `executeBatch` it no longer returns the key of an
  earlier `executeUpdate`.
- `isBeforeFirst()` returned true for an empty result set; it is now false, as JDBC requires.
- `abort()` now interrupts the running statement and marks the connection closed at once, instead of waiting for
  the statement to finish.
- A connection configured with `query_only=true` (URL or properties) stays read-only: `setReadOnly(false)`, which
  Spring calls after every read-only transaction, no longer makes it writable. `getShort`/`getByte` fail on
  out-of-range values instead of wrapping.

### ffm-sqlite-shop (sample)

- Cancelling an already cancelled (or shipped) order restocked it again on every call: orders now follow their life
  cycle (PLACED → PAID → SHIPPED, PLACED/PAID → CANCELLED), and cancel reads the current state from the database
  instead of the request.
- Checkout now refuses a cart that belongs to another customer (guest carts can still be checked out by anyone).
- `Database.write` retries only lock conflicts (SQLState 40001), not query or pool timeouts.

## 0.2.0

The project moved to the **revsnai** GitHub organization: https://github.com/revsnai/ffm-sqlite.

- **New Maven coordinates:** `io.github.revsnai:ffm-sqlite` and `io.github.revsnai:ffm-sqlite-jdbc`
  (were `io.github.psydic:*`).
- **New package names:** `io.github.revsnai.ffmsqlite.db` and `io.github.revsnai.ffmsqlite.jdbc` (were
  `io.github.psydic.ffmsqlite.*`); JPMS automatic module names changed the same way. To upgrade, change the
  dependency and replace `io.github.psydic.ffmsqlite` with `io.github.revsnai.ffmsqlite` in imports.
  The JDBC URL (`jdbc:ffmsqlite:`) and all APIs are unchanged.
- Everything in 0.1.1, below. 0.1.1 was only published to GitHub Packages, under the old coordinates.

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
