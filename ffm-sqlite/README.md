# ffm-sqlite

```xml
<dependency>
    <groupId>io.github.revsnai</groupId>
    <artifactId>ffm-sqlite</artifactId>
    <version>0.2.0</version>
</dependency>
```

SQLite for Java 25+ with the shape of JDBC (`Connection`, `PreparedStatement`, `ResultSet`, ...), but
implemented directly on the Foreign Function & Memory API. It has no `java.sql`, no JNI, no bundled
native code, and no dependencies. It calls the platform's `libsqlite3`.

```java
import io.github.revsnai.ffmsqlite.db.*;

try (Connection conn = SQLite.getConnection("app.db")) {
    try (Statement st = conn.createStatement()) {
        st.executeUpdate("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT, score REAL)");
    }

    conn.setAutoCommit(false);
    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO users (name, score) VALUES (?, ?)")) {
        ps.setString(1, "ada");
        ps.setDouble(2, 9.5);
        ps.executeUpdate();
    }
    conn.commit();

    try (PreparedStatement ps = conn.prepareStatement("SELECT id, name, score FROM users WHERE score > ?")) {
        ps.setDouble(1, 5);
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                System.out.println(rs.getLong("id") + " " + rs.getString("name") + " " + rs.getDouble(3));
            }
        }
    }
} catch (SQLiteException e) {
    System.err.println(e.getMessage() + " code=" + e.getErrorCode() + " ext=" + e.getExtendedErrorCode());
}
```

Run with `--enable-native-access=ALL-UNNAMED` (or the jar manifest's `Enable-Native-Access`). To use a
specific SQLite build, pass `-Dffmsqlite.lib=/path/to/libsqlite3.so`.

## JDBC mapping

| JDBC | ffm-sqlite | Notes |
|---|---|---|
| `DriverManager.getConnection(url)` | `SQLite.getConnection(path[, Config])` | Accepts a path, `:memory:`, or a `file:` URI. `Config`: readOnly, busyTimeoutMillis (3000), statementCacheSize (64) |
| `Connection` | `Connection` | `createStatement`, `prepareStatement`, `setAutoCommit`, `commit`, `rollback`, `close`, `isClosed` |
| `Statement` | `Statement` | `executeQuery`, `executeUpdate`, `execute`, `getResultSet`, `getUpdateCount`; accepts multi-statement scripts |
| `PreparedStatement` | `PreparedStatement` | `setInt/Long/Double/Float/Boolean/String/Bytes/BigDecimal/Null/Object`, `clearParameters`, `executeQuery/Update`, `execute`, `addBatch`, `executeBatch`, `clearBatch` |
| `ResultSet` | `ResultSet` | Forward-only. `next`, `get{Int,Long,Double,Float,Boolean,String,Bytes,BigDecimal,Object}` and `getObject(i, Class)` by index or label, `wasNull`, `findColumn`, `getMetaData` |
| `ResultSetMetaData` | `ResultSetMetaData` | `getColumnCount`, `getColumnLabel/Name`, `getColumnTypeName` |
| `SQLException` | `SQLiteException` (checked) | `getErrorCode()` is the primary SQLite code; `getExtendedErrorCode()` is the extended one |
| `getGeneratedKeys()` | `Connection.getLastInsertRowId()` | |
| (sqlite-jdbc `org.sqlite.Function`) | `Connection.createFunction` / `createAggregateFunction` / `createWindowFunction` / `removeFunction` | See [User-defined functions](#user-defined-functions) |
| (sqlite-jdbc `org.sqlite.Collation`) | `Connection.createCollation` / `removeCollation` | See [Custom collations](#custom-collations) |
| (sqlite-jdbc `backup to` / `restore from`) | `Connection.backup` / `restore` | See [Backup and restore](#backup-and-restore) |
| (sqlite-jdbc `addUpdateListener` / `addCommitListener` / `busy_handler`) | `addUpdateListener`, `addCommitListener`, `setBusyHandler`, `setBusyTimeout` | See [Update, commit and busy hooks](#update-commit-and-busy-hooks) |
| `Statement.setQueryTimeout` / `cancel` | same, plus `setQueryTimeout(Duration)` | Interrupts running work, not just lock waits |
| `Connection.getMetaData()` | `Connection.getMetaData()` | Typed records; see [Schema metadata](#schema-metadata) |
| (sqlite-jdbc `ProgressHandler`, `loadExtension`) | `Connection.setProgressHandler`, `loadExtension` | See [Timeouts…](#timeouts-cancellation-and-progress), [Extensions](#extensions) |
| (sqlite-jdbc `SQLiteConfig`) | `SQLite.Config` with `withPragma`, `withJournalMode`, … | See [PRAGMAs at connect time](#pragmas-at-connect-time) |
| (sqlite-jdbc `SQLiteConnection.serialize` / `deserialize`) | `Connection.serialize` / `deserialize`, `SQLite.getConnection(byte[])` | See [Serialize and deserialize](#serialize-and-deserialize) |

The JDBC semantics are kept:
- indexes are 1-based, and column labels match case-insensitively;
- `getInt` on NULL returns 0, so check `wasNull()`;
- parameters persist across executions, and executing a statement while a parameter is unset throws;
- re-executing a statement closes its previous `ResultSet`;
- closing a `Connection` closes its statements and result sets;
- `setAutoCommit(true)` commits an open transaction.

## java.time, BigDecimal and UUID

As in JDBC 4.2, java.time values are bound with `setObject` and read with `getObject(i, Type.class)`:

```java
ps.setObject(1, LocalDate.of(2024, 2, 29));
ps.setObject(2, Instant.now());
ps.setBigDecimal(3, new BigDecimal("19.99"));
...
LocalDate due     = rs.getObject("due", LocalDate.class);
Instant createdAt = rs.getObject("created_at", Instant.class);
BigDecimal price  = rs.getBigDecimal("price");
```

SQLite has no date or decimal types, so values are stored in SQLite's own conventions. That way SQL
functions such as `date()`, `datetime()`, `unixepoch()`, and plain string comparison and `ORDER BY` work on them:

| Java type | Stored as (TEXT) |
|---|---|
| `LocalDate` | `2024-02-29` |
| `LocalTime` | `13:45:30`, `13:45:30.5` (fraction without trailing zeros, so text order = time order) |
| `LocalDateTime` | `2024-02-29 13:45:30.5` |
| `Instant` | UTC, no suffix: `2024-02-29 13:45:30.5`, the same format as `CURRENT_TIMESTAMP` |
| `OffsetDateTime`, `ZonedDateTime` | `2024-02-29 13:45:30+02:00` (a zone ID is reduced to its offset) |
| `BigDecimal` | exact decimal text, `1.50` (exponent form only for extreme scales, e.g. `1E+500`) |
| `BigInteger` | INTEGER if it fits in 64 bits, else TEXT |
| `UUID` | canonical lower-case TEXT `123e4567-e89b-12d3-a456-426614174000`; reading also accepts upper case and 16-byte BLOBs |

Reading is lenient:
- TEXT in any SQLite time format: a `T` or space separator, optional seconds and fraction, `Z` or `±HH:MM`.
- INTEGER is read as Unix-epoch **seconds** (`unixepoch()`), and REAL as a Julian day (`julianday()`).
- Text without an offset is read as UTC when you ask for an `Instant` or `OffsetDateTime`.
- A date-only value read as a date-time gives midnight.
- Unparseable values throw `SQLiteException`.
- Dates are formatted and parsed by hand, straight from and to UTF-8 bytes; years outside 0000–9999 fall back
  to `DateTimeFormatter`, which produces the same text. Reading a date costs about as much as reading a
  plain string (~100–120 ns/row).

> **BigDecimal columns:** declare them `TEXT` (or leave the type off). SQLite converts numeric-looking text in
> `NUMERIC`/`DECIMAL` columns to INTEGER or REAL on insert, keeping only ~15 significant digits.
>
> **Migrating from sqlite-jdbc:** it stores dates as INTEGER *milliseconds* by default. Read those with
> `Instant.ofEpochMilli(rs.getLong(i))`; `getObject(i, Instant.class)` would treat them as seconds.

## User-defined functions

Java functions callable from SQL, implemented with FFM upcalls:

```java
// Scalar: one value per row. The result may be any type setObject accepts.
conn.createFunction("reverse", 1, args -> new StringBuilder(args.getString(0)).reverse().toString(),
        FunctionFlag.DETERMINISTIC);

// Variadic (-1 = any argument count); functions are overloaded by argument count.
conn.createFunction("join_all", -1, args -> { ... });

// Aggregate: a fresh accumulator per group.
final class Median implements AggregateFunction {
    private final List<Double> values = new ArrayList<>();
    public void step(FunctionArgs args) throws SQLiteException { if (!args.isNull(0)) values.add(args.getDouble(0)); }
    public Object result() { /* median of values, or null */ }
}
conn.createAggregateFunction("median", 1, Median::new);

// Window: an aggregate that can also remove rows, for sliding frames in OVER (...).
final class MovingSum implements WindowFunction {
    private long sum, count;
    public void step(FunctionArgs a) throws SQLiteException    { if (!a.isNull(0)) { sum += a.getLong(0); count++; } }
    public void inverse(FunctionArgs a) throws SQLiteException { if (!a.isNull(0)) { sum -= a.getLong(0); count--; } }
    public Object result() { return count == 0 ? null : sum; }   // value() defaults to result()
}
conn.createWindowFunction("moving_sum", 1, MovingSum::new);
// SELECT day, moving_sum(amount) OVER (ORDER BY day ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) FROM sales

conn.removeFunction("reverse", 1);
```

- **Arguments:** `FunctionArgs` indexes are **0-based**, like an array and like sqlite-jdbc's `Function`. The getters
  convert like `ResultSet`'s, including `getObject(i, LocalDate.class)`, `getBigDecimal` and UUID. The arguments
  are valid only during the call; using them afterwards throws, instead of reading freed native memory.
- **Errors:** an exception thrown by the function fails the SQL statement. The `SQLiteException` message is
  `name(): <message>`, and the original exception is its `getCause()`. Exceptions never cross the upcall
  boundary, so a throwing function cannot crash the JVM.
- **Flags:**
  - `DETERMINISTIC` is required for use in indexes, CHECK constraints and generated columns.
  - `DIRECT_ONLY` blocks use from views, triggers and schema objects.
  - `INNOCUOUS` marks the function as safe for untrusted schemas.
- **Aggregates:** each group gets its own accumulator, keyed by SQLite's per-group aggregate context.
  Accumulators are released when a group finishes, when a statement fails, or when it is reset. An empty input
  calls `result()` on a fresh, never-stepped instance.
- **Window functions:** `step` adds a row entering the frame, `inverse` removes the oldest row leaving it, and
  `value()` gives the current frame's result for each output row. The frame may be empty, and `value()`
  defaults to `result()`. One accumulator serves a whole partition. The same function also works as a plain
  aggregate, where `result()` is used. All frame kinds work: ROWS, RANGE, GROUPS, EXCLUDE and partitions.
  Functions registered with `createAggregateFunction` are rejected in `OVER (...)` by SQLite.
- **Rules:** functions belong to one connection and are freed on `close()`. Registering or removing a
  function while a statement is executing fails with SQLITE_BUSY, as SQLite requires.
- **Speed:** a Java function call costs ~50 ns over a built-in C function, against ~370 ns for sqlite-jdbc's
  `org.sqlite.Function`. A sliding-window function (3 callbacks per row) costs ~180 ns per row over the
  built-in `sum() OVER`, against ~870 ns for `org.sqlite.Function.Window` (see `../ffm-vs-jdbc/COMPARISON.md`).

## Custom collations

Java comparators as SQL collating sequences, for `ORDER BY`, comparisons, `DISTINCT`/`GROUP BY`, `min`/`max`,
column definitions, `UNIQUE` constraints and indexes:

```java
conn.createCollation("ci", String.CASE_INSENSITIVE_ORDER);

Collator french = Collator.getInstance(Locale.FRENCH);   // java.text.Collator works as-is
french.setStrength(Collator.PRIMARY);                     // ignore case and accents
conn.createCollation("fr", french);

// CREATE TABLE users (email TEXT COLLATE ci UNIQUE)      -- 'Ada@x.com' and 'ADA@X.COM' now collide
// SELECT word FROM words WHERE word = 'cote' COLLATE fr  -- matches cote, Côte, côté, coté
conn.removeCollation("ci");
```

- **Comparator type:** any `Comparator<? super String>`; it receives the exact decoded strings.
- **Consistency:** it must be a consistent total order. An index built with an inconsistent comparator is
  corrupt, as in any database.
- **Errors:** SQLite's comparison callback cannot report errors, so a comparator that throws aborts the
  statement with `sqlite3_interrupt`. You get a `SQLiteException` with code SQLITE_INTERRUPT (9), a message
  `… collation <name>: <message>`, and your exception as `getCause()`. The library also checks after every
  step, so a statement that finishes before SQLite notices the interrupt still fails instead of returning
  rows sorted with the fallback result.
- **Schemas:** a collation used in a table or index definition must be registered on every connection that
  touches that table, or SQLite reports `no such collation sequence`.
- **Names:** names are case-insensitive. A name that is an SQL keyword (e.g. `natural`) must be quoted in
  SQL, so prefer another name.
- **Speed:** a comparison costs ~60 ns over the built-in C `NOCASE`, against ~310 ns for sqlite-jdbc's
  `org.sqlite.Collation` (`ORDER BY` over 10,000 rows: 4.3× faster).

## Backup and restore

SQLite's online backup API: a consistent snapshot copied while the connection stays usable. It works
for in-memory databases too.

```java
conn.backup("backup.db");                    // main database -> file (created or overwritten)
conn.backup(otherConnection);                // -> another open connection, e.g. :memory: to :memory:
conn.restore("backup.db");                   // file -> this connection, replacing its content

// Large databases: copy 1,000 pages per step, pause between steps, report progress.
conn.backup("backup.db", BackupOptions.DEFAULT
        .withPagesPerStep(1000)
        .withPause(Duration.ofMillis(5))
        .withProgress((remaining, total) -> {
            System.out.printf("%d of %d pages left%n", remaining, total);
            return true;                         // false cancels
        }));

conn.backup("aux.db", BackupOptions.DEFAULT.withSchema("aux"));   // an ATTACHed database
```

- **All or nothing:** the destination is written in one transaction that commits only when the copy
  completes. A cancelled or failed backup leaves an existing destination file unchanged and deletes a newly
  created one. A cancelled or failed restore leaves this connection's data unchanged.
- **Writes during a backup:**
  - by default (`pagesPerStep = -1`), everything is copied in one step while holding the source's read lock;
  - with a positive `pagesPerStep`, the lock is released between steps, so other connections can write;
    if they do, SQLite restarts the copy;
  - writes through the source connection itself are carried into the copy, so the result is always a
    consistent snapshot.
- **Restore** opens the source file read-only, so a missing file is an error and is never created.
  Prepared statements on the connection stay valid; SQLite re-prepares them for the new schema. Restore is
  refused while a statement is running on the connection: SQLITE_ERROR, "destination database is in use".
- **Errors** carry SQLite's codes, e.g. 14 (CANTOPEN) for a bad path and 26 (NOTADB) for a file that is not
  a database. An exception thrown by the progress callback propagates, after the copy is rolled back.
- **Alternative:** `VACUUM INTO 'file.db'` (plain SQL) writes a compacted copy, but copy-out only.

## Serialize and deserialize

A whole database as a `byte[]`: the exact content of the equivalent database file. Useful for caching,
sending over the network, storing in a BLOB, or test fixtures.

```java
byte[] image = conn.serialize();                 // main database; serialize("aux") / serialize("temp") too
Files.write(Path.of("copy.db"), image);          // a valid database file

try (Connection copy = SQLite.getConnection(image)) { ... }   // new in-memory connection from bytes
conn.deserialize(image);                         // replace this connection's main database
conn.deserialize(image, "aux", true);            // into an ATTACHed schema, read-only
```

- **Becomes in-memory:** a deserialized schema is an in-memory database. Later writes are not saved to any
  file that previously backed it. To load bytes into a file database, use
  `SQLite.getConnection(image).backup(fileConnection)`.
- **Copying:** SQLite takes its own copy of the bytes, so your array can be reused. The database can grow
  beyond the image, up to SQLite's in-memory limit (1 GB by default), unless it is opened read-only.
- **Edge cases:**
  - an empty database serializes to an empty array (older SQLite, e.g. 3.45) or a single empty page (newer, e.g.
    3.50); either deserializes to an empty writable database;
  - images of WAL-mode databases are switched to rollback-journal mode, without which SQLite cannot open them
    in memory;
  - only the 16-byte SQLite header is checked up front (bad header: code 26, NOTADB, and the database is left
    untouched), so corrupt pages behind a valid header surface on first use as `SQLiteException`;
  - `serialize` is limited to databases under 2 GB, the maximum Java array; use `backup` beyond that.
- **Guards that SQLite itself lacks:**
  - deserializing while a statement is running on the connection is refused with code 5 (BUSY). Raw SQLite
    accepts it and the running statement's next step **crashes the JVM**; sqlite-jdbc 3.50.3.0 has this crash;
  - deserializing into a schema with an active transaction is also refused. SQLite would silently discard the
    uncommitted changes.

## Update, commit and busy hooks

```java
// Every row inserted, updated or deleted through this connection.
conn.addUpdateListener((type, database, table, rowId) -> cache.invalidate(table, rowId));

// Commits (which a listener may veto by throwing) and rollbacks.
conn.addCommitListener(new CommitListener() {
    public void onCommit() { if (!quotaOk()) throw new IllegalStateException("quota exceeded"); }
    public void onRollback() { pendingEvents.clear(); }
});

// Locked database: decide how long to wait (replaces the busy timeout).
conn.setBusyHandler(attempts -> {
    if (attempts >= 10) return false;     // give up: SQLITE_BUSY
    Thread.sleep(2L << attempts);         // exponential backoff
    return true;
});
conn.setBusyTimeout(5000);                // or back to plain timed waiting
```

- **Update listeners** run during the statement, before commit, so pair them with a commit listener to act
  only on committed changes. SQLite does not report:
  - WITHOUT ROWID tables;
  - internal tables;
  - `DELETE` without `WHERE` (the truncate optimization; `DELETE ... WHERE 1` is reported);
  - rows replaced by `ON CONFLICT REPLACE`.
- **Hooks must not use their own connection.** SQLite leaves this undefined; the library throws, and a
  busy handler may use *other* connections, e.g. to release the lock it is waiting for. Calling `close()` on
  the connection inside a hook throws `IllegalStateException`.
- **A listener that throws:**
  - *update listener:* the statement fails with your exception as the cause, nothing it did is committed, and
    in manual-commit mode the whole transaction becomes rollback-only, so `commit()` fails and rolls back;
  - *commit listener:* the commit is vetoed and rolled back, and the statement or `commit()` fails with
    SQLITE_CONSTRAINT_COMMITHOOK (extended code 531);
  - *busy handler:* it gives up, and the statement fails with SQLITE_BUSY and your exception as the cause.

  `commit()`/`rollback()` always leave the connection in manual-commit mode with a fresh transaction, even when
  they fail.
- **On close:** `onRollback` is not called when closing discards an open transaction. That is what SQLite
  documents, but `sqlite3_close_v2` does invoke the hook, so the library detaches hooks before closing.
- **Speed:** a counting update listener costs ~69 ns per event, against ~365 ns with sqlite-jdbc's
  `addUpdateListener`.

## Timeouts, cancellation and progress

```java
try (PreparedStatement ps = conn.prepareStatement(reportSql)) {
    ps.setQueryTimeout(5);                        // or setQueryTimeout(Duration.ofMillis(250))
    try (ResultSet rs = ps.executeQuery()) { ... }
} catch (SQLiteTimeoutException e) { ... }        // code 9 (INTERRUPT); changes rolled back

statement.cancel();                               // from any thread: aborts the running statement

conn.setProgressHandler(10_000, () -> !userPressedStop);   // every ~10k VM instructions; false aborts
```

- **Query timeouts interrupt running work.** sqlite-jdbc's `setQueryTimeout` only limits lock waiting: a
  CPU-bound query ran 10.8 s past a 1 s timeout there. The limit applies to each call into SQLite (an
  execute, or one `next()`), so time your code spends between `next()` calls does not count.
- **Cost:** implemented with the progress handler, checked every 1,000 VM instructions; no measurable cost on
  a CPU-bound query.
- **`cancel()`** uses `sqlite3_interrupt`. It aborts whatever is running on that connection at that moment.
- **The progress handler** follows the hook rules: it must not use its connection, and an exception aborts the
  statement and becomes the cause.

## Extensions

```java
conn.loadExtension("/opt/sqlite/vec0");                     // default entry point
conn.loadExtension("/opt/sqlite/myext.so", "myext_init");   // explicit entry point
```

- **Scoped to the call:** loading is enabled only for that call and only for the C API (`sqlite3_db_config`, a
  variadic C function bound with FFM's `firstVariadicArg`). The SQL function `load_extension()` stays disabled
  throughout, so SQL injection cannot load libraries. sqlite-jdbc's option enables that SQL function.
- **Trust:** extensions run native code inside the JVM; load only trusted files.
- **Availability:** builds without extension support (`SQLITE_OMIT_LOAD_EXTENSION`, e.g. macOS's system
  SQLite) still load the library; only `loadExtension` throws.
- **Tests:** they use a small `rot13` extension, `src/test/c/ffmtest.c`, built by
  `scripts/build-test-extension.sh`. Without the built file, the tests that need it are skipped.

## Schema metadata

```java
DatabaseMetaData md = conn.getMetaData();
for (TableInfo t : md.getTables()) { ... }   // name, TABLE/VIEW/VIRTUAL, column count, WITHOUT ROWID, STRICT, CREATE sql
md.getColumns("orders");                     // name, declared type, NOT NULL, default, PK position, generated, hidden
md.getPrimaryKey("orders");                  // in key order
md.getIndexes("orders");                     // unique, origin (CREATE INDEX / UNIQUE / PK), partial, columns (desc, collation)
md.getForeignKeys("order_items");            // referenced table, column pairs, ON UPDATE / ON DELETE
md.getSchemas();                             // main, temp, attached
md.getTables("aux");                         // every method also takes a schema
```

- **Typed records instead of JDBC's string-column ResultSets.** They are read from SQLite's
  `pragma_table_list`, `pragma_table_xinfo`, `pragma_index_list`/`_xinfo` and `pragma_foreign_key_list`,
  with schema names bound as parameters or safely quoted.
- **Unknown tables or schemas** give empty lists.
- **Internal `sqlite_*` tables** are not listed.

## PRAGMAs at connect time

```java
Config config = Config.DEFAULT
        .withJournalMode(JournalMode.WAL)
        .withSynchronous(Synchronous.NORMAL)
        .withForeignKeys(true)
        .withPragma("cache_size", -16000)
        .withPragma("temp_store", "MEMORY");
Connection conn = SQLite.getConnection("app.db", config);   // applied, in order, on every open
```

PRAGMA names are validated as identifiers. Numbers, booleans, enums and identifiers are used as-is; any other
string is quoted as an SQL literal, so a value cannot inject SQL.

## Design choices (why it is fast)

The benchmark in `../ffm-vs-jdbc` measures each choice below.

- **Statement cache.** `prepareStatement(sql)` reuses a compiled statement that an earlier, closed
  `PreparedStatement` with the same SQL left behind. The cache is LRU, per connection, and sized by
  `Config.statementCacheSize`. The idiomatic "prepare inside the loop" JDBC pattern therefore doesn't re-parse SQL.
- **No Arena per value.** Text and blobs are passed to SQLite by address, straight from the Java `byte[]`,
  through `Linker.Option.critical(true)` handles. SQLite copies them (`SQLITE_TRANSIENT`). Results are copied
  out with exactly `sqlite3_column_bytes` bytes, with no `strlen`.
- **Critical downcalls only where safe.** The short calls (`bind_*`, `column_*`, `reset`) are critical.
  `step`, `prepare`, `open`, and `close` are not, because they can block on locks or run long, and a critical
  call holds off GC safepoints.
- **No per-call locking.** Connections open with `SQLITE_OPEN_NOMUTEX`, and the Java side has no
  `synchronized`. Like JDBC connections, a `Connection` must not be used by two threads at the same time.
- Parameters are stored without boxing (`long[]`, `double[]`, `byte[][]`), and strings are encoded once, in `setString`.

Results (ns/row, in-memory, 1,000 rows per transaction; same machine and SQLite 3.50.3 as `../ffm-vs-jdbc`):

| | ffm-sqlite | hand-written FFM | sqlite-jdbc (best config) |
|---|---:|---:|---:|
| write, `executeUpdate` per row | **333** | 348 | 642 (`get_generated_keys=false`) |
| write, `addBatch`/`executeBatch` | **338** | — | 588 |
| read, `ResultSet` (int, String, int) | **115** | 173 | 380 |

## Not supported (yet)

- `java.sql` interoperability: this is deliberately not a JDBC driver, so JPA, Hibernate, and connection
  pools that expect `javax.sql.DataSource` cannot use it.
- A bundled native library: deliberately, the library uses the platform's `libsqlite3` or one passed with
  `-Dffmsqlite.lib` (see `../ffm-vs-jdbc/scripts/build-sqlite.sh`).
- Scrollable or updatable result sets, savepoints, `CallableStatement`, and the incremental blob API.
- `addBatch` copies parameter arrays (~200 B/row). A columnar batch buffer would remove that.

## Build

```bash
./scripts/build-test-extension.sh   # optional: enables the extension-loading tests
mvn test       # 144 tests
mvn install    # used by ../ffm-vs-jdbc
```
