# Changelog

## 0.2.5

Fixes from a review of hot paths, resilience, resource lifecycle and security. Upgrading is recommended for #1–#4;
each has a regression test that fails on 0.2.4.

### ffm-sqlite

1. **Reading one stored value could take minutes or exhaust the heap.** `getObject(i, BigInteger.class)` on TEXT
   such as `'1e600000000'` built a 600-million-digit number (10+ minutes of CPU on 0.2.4). Integer parts above
   100,000 digits are now rejected with `SQLiteException`, and values below 1 return 0 without computing
   10^scale. Applies to result sets and function arguments. A stored ±Infinity read as `BigDecimal` now fails
   with `SQLiteException` instead of a raw `NumberFormatException`.
2. **A transaction whose update listener failed could still commit.** Removing that listener before `commit()`
   (the usual `finally` block) lifted the veto, committing the row the listener had failed on. The veto now
   lasts until the transaction ends.
3. **A failing custom collation could commit an out-of-order index entry.** A comparator that throws answers
   "equal"; when SQLite completed the statement before noticing the interrupt, the write committed. A
   collation failure inside a write transaction now makes it rollback-only, as a failed update listener
   does (reads are unaffected).
4. **Closing a connection from a backup's progress callback freed it under the backup.** Closing either
   connection, or deserializing into it, from `BackupOptions.Progress` now throws `IllegalStateException`
   (or `SQLiteException`), and the backup is finished cleanly.
5. **Long scripts ran in quadratic time.** `Statement.execute` searched and copied the whole script once per
   statement: a 20,000-statement script took 79 s, now well under a second.
6. `Config` PRAGMA values run as a single statement, so a value cannot add a second statement
   (`"1; ATTACH ..."`). The driver and the starter already quoted values.
7. An `OutOfMemoryError` while preparing a function's arguments can no longer escape the native callback.

### ffm-sqlite-jdbc

- **Unsupported features no longer make HikariCP evict the connection.** They used SQLState `0A000`, which
  Hikari treats as a broken connection: after Hibernate `scroll()`, `ResultSet.isLast()` or `prepareCall`,
  Hikari closed the connection while the application still held it (its next call failed with "Connection
  is closed", mid-transaction) and discarded its statement cache and functions. They now use `0A500` (still
  class 0A, feature not supported).
- `getDriverVersion()` reports the release version from the jar manifest (it said 0.1).

### micronaut-starter-ffm-sqlite

- **`/health` no longer blocks for `connection-timeout` and reports DOWN on a busy pool.** The check was skipped
  only when callers were queued; with every connection held and nobody waiting, it joined the queue itself.
  It now also skips when no connection is idle and the pool is full.

## 0.2.4

A performance fix for frameworks that switch connections to read-only around every read (Micronaut Data, Spring
`@Transactional(readOnly = true)`).

### ffm-sqlite-jdbc

- **`setReadOnly` no longer empties the statement cache.** It ran `PRAGMA query_only`, and changing that PRAGMA
  makes SQLite expire every prepared statement, so the next execution of each cached statement was compiled again.
  Micronaut Data calls `setReadOnly(true)` and `setReadOnly(false)` around every repository read, which made a
  `findById` take 12–15 µs instead of about 6. Read-only is now checked per statement before it runs, with the
  same result for writes (SQLITE_READONLY) and no change to SQLite's state. Connections configured read-only
  (`readonly`, `query_only=true`) still use SQLite's own enforcement.

### ffm-sqlite

- `Connection.setReadOnly(boolean)` / `isReadOnly()`: reject statements that would write, checked with
  `sqlite3_stmt_readonly` before each statement runs; queries, transaction control, `ATTACH` and `DETACH` still run.

### micronaut-starter-ffm-sqlite

- Picks up the driver fix: `@Transactional(readOnly = true)` stays enforced at no cost per transaction.

## 0.2.3

A new Micronaut starter, a JVM crash fix from a security review, and small additions to the core and the driver.
Upgrading is recommended for #1.

### ffm-sqlite

1. **Closing or re-running a statement from inside a callback crashed the JVM.** A user-defined function or
   collation that closed its connection, or closed, reset or re-ran the statement that called it, freed memory
   SQLite was still executing. These now fail the running statement with an exception (`IllegalStateException`
   as its cause for close). Other statements on the connection still work inside callbacks.
2. `Connection.inTransaction()`: whether SQLite has a transaction open, including one started by `BEGIN` or
   `SAVEPOINT` in SQL, which `getAutoCommit()` does not see.

### micronaut-starter-ffm-sqlite (new)

Micronaut 5 integration: `ffm-sqlite.datasources.<name>` settings become HikariCP-pooled `DataSource` beans
(Micronaut Data JDBC and `@Transactional` work unchanged), `FfmSqliteDatabase` beans for the ffm-sqlite API,
a shared in-memory database for `:memory:`, and a `/health` indicator.

### ffm-sqlite-jdbc

- `pragma.<name>` properties set any PRAGMA, including ones sqlite-jdbc has no property for. When a bare name
  and `pragma.<name>` name the same PRAGMA, `pragma.<name>` wins.
- `FfmSqliteDataSource.getSqliteConfig()` and `getDatabasePath()`: the settings its connections open with, for
  opening an ffm-sqlite `Connection` configured the same way.

### Samples

- ffm-sqlite-shop now runs on Micronaut with micronaut-starter-ffm-sqlite (JDBC repositories unchanged).
- ffm-vs-rust: a benchmark comparison with rusqlite.

## 0.2.2

Fixes found by testing every connection-level PRAGMA with a range of values (`PragmaMatrixTest`). Upgrading is
recommended: #1 can return wrong data silently.

### ffm-sqlite

1. **Running out of memory while reading a value returned SQL NULL.** When SQLite could not allocate the converted
   value (e.g. UTF-8 text from a UTF-16 database, under `hard_heap_limit` or a real shortage), `getString`,
   `getBytes` and function arguments returned `null` with no error, and `getObject` threw `NullPointerException`.
   They now fail with SQLITE_NOMEM.
2. **A busy timeout set by `PRAGMA busy_timeout` was lost.** After any statement with a query timeout, the
   connection went back to the configured busy timeout (3 s by default), whether the PRAGMA came from
   `Config.withPragma`, a script or a prepared statement. The value set by the PRAGMA is now kept.
3. `Config.withPragma` accepts schema-qualified names such as `main.cache_size`.

### ffm-sqlite-jdbc

- Picks up all of the above: out-of-memory reads fail with `SQLException` instead of returning `null`, and a
  `busy_timeout` URL parameter or PRAGMA survives `setQueryTimeout`.

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
