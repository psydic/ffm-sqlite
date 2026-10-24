# Interface comparison: ffm-sqlite vs sqlite-jdbc

> **Update:** the JDBC wrapper proposed in section 7 now exists: [`../ffm-sqlite-jdbc`](../ffm-sqlite-jdbc).
> **With it, the answer for most applications becomes yes:**
>
> - it implements `java.sql` (472 of 627 methods against sqlite-jdbc's 439, measured the same way);
> - it passes a test suite run against both drivers, and databases move between them in both directions;
> - HikariCP, Spring JDBC and jOOQ work unmodified;
> - it is thread-safe per connection, translates exceptions with SQLStates, and keeps sqlite-jdbc's date and
>   `getObject` behaviour.
>
> The remaining differences are listed in section 8. The analysis below is about the core library
> (`io.github.revsnai.ffmsqlite.db`) on its own, which is still not a drop-in replacement.

**Is ffm-sqlite (the core library) a drop-in replacement for sqlite-jdbc? No.**

| Level of compatibility | Verdict | Why |
|---|---|---|
| **Binary / runtime** (swap the jar, keep the code, frameworks keep working) | ❌ No | ffm-sqlite does not implement `java.sql.*`. Nothing that takes a `java.sql.Connection` or `DataSource` can use it: JPA/Hibernate, Spring JDBC, jOOQ, MyBatis, Flyway, Liquibase, HikariCP, DB tools. |
| **Source** (recompile hand-written JDBC code after changing imports) | ⚠️ No, but close for core usage | Same type and method names for the common path. But the exception type differs, 361 JDBC methods that sqlite-jdbc implements are absent or differ, and some behaviours differ (below). Plain JDBC code needs a mechanical port, not a rewrite. |
| **Data** (open the same database files) | ✅ Yes, with one caveat | Same SQLite file format and SQL. But dates that sqlite-jdbc stored as INTEGER **milliseconds** (its default for `java.sql.Date`/`Timestamp`) are read as **seconds** by ffm-sqlite's `getObject(i, Instant.class)`. |

This was a deliberate design choice ("JDBC look and feel without the JDBC interfaces"). The last section
describes how to get true drop-in compatibility if that is wanted.

---

## 1. API coverage, measured

`src/main/java/ffmsqlite/InterfaceComparison.java` takes every method of six core `java.sql` interfaces and checks:

- **implemented by sqlite-jdbc**: its implementation can return. Bytecode in which the method can only
  throw (sqlite-jdbc's `throw unsupported()` pattern) counts as unsupported. Read with Java 25's
  `java.lang.classfile` API.
- **present in ffm-sqlite**: same name and parameter types, with `java.sql.X` mapped to `io.github.revsnai.ffmsqlite.db.X`.

| java.sql interface | Methods | Implemented by sqlite-jdbc | ffm-sqlite: same signature | ffm-sqlite: same name, other parameters | Missing in ffm-sqlite (of those sqlite-jdbc implements) |
|---|---:|---:|---:|---:|---:|
| `Connection` | 60 | 48 | 9 | 8 | 39 |
| `Statement` | 56 | 46 | 12 | 9 | 34 |
| `PreparedStatement` | 114 | 81 | 26 | 19 | 55 |
| `ResultSet` | 195 | 74 | 26 | 4 | 48 |
| `ResultSetMetaData` | 23 | 23 | 4 | 0 | 19 |
| `DatabaseMetaData` | 179 | 167 | 1 | 3 | 166 |
| **Total** | **627** | **439** | **78** | **43** | **361** |

Full per-method lists: `results/interface-coverage.md`.

**How to read this:**

- **sqlite-jdbc covers most of JDBC, ffm-sqlite only its core.** sqlite-jdbc implements 439 of 627 methods
  (the rest throw `SQLFeatureNotSupportedException`, e.g. scrollable or updatable result sets, arrays, SQLXML).
  ffm-sqlite matches 78 exactly: the core.
- **Even matching methods are not source-compatible.** They throw `io.github.revsnai.ffmsqlite.db.SQLiteException`, which is
  not a `java.sql.SQLException`, so every `catch (SQLException e)` around them stops compiling.
- **Most of the 361 is JDBC ceremony with little meaning for SQLite:** 120+ `DatabaseMetaData` capability flags
  (`supportsX()`, `getMaxY()`), warnings, holdability, catalogs, type maps, cursor names, fetch hints and
  `Wrapper`. A smaller group is real friction for migrating code (section 3).
- **`DatabaseMetaData` is deliberately different:** typed records (`getTables()`, `getColumns("t")`,
  `getIndexes`, `getForeignKeys`, `getPrimaryKey`) instead of JDBC's ResultSets with 18 positional string columns.

## 2. The core API, side by side

| Task | sqlite-jdbc (JDBC) | ffm-sqlite | Same? |
|---|---|---|:---:|
| Connect | `DriverManager.getConnection("jdbc:sqlite:app.db")` | `SQLite.getConnection("app.db")` | ≈ |
| Connection options | `SQLiteConfig` / URL parameters / `Properties` | `SQLite.Config` (`withJournalMode`, `withForeignKeys`, `withPragma`, …) | ≈ |
| Statements | `createStatement()`, `prepareStatement(sql)` | same | ✅ |
| Parameters | `setInt/Long/Double/String/Bytes/Boolean/Null/Object/BigDecimal` | same | ✅ |
| `setDate/setTime/setTimestamp` (`java.sql` types) | ✅ | ❌; use `setObject(i, LocalDate/LocalDateTime/Instant/…)` | ❌ |
| Execute | `executeQuery()`, `executeUpdate()`, `execute()`, `executeLargeUpdate()` | same | ✅ |
| Batches | `PreparedStatement.addBatch/executeBatch`; `Statement.addBatch(sql)` | `PreparedStatement` only | ⚠️ |
| Rows | `next()`, `getInt/Long/Double/String/Bytes/Boolean/Object/BigDecimal`, by index or label, `wasNull()` | same | ✅ |
| `getByte/getShort` | ✅ | ❌; use `getInt` | ❌ |
| `getDate/getTime/getTimestamp` | ✅ | ❌; use `getObject(i, LocalDate.class)` etc. | ❌ |
| JDBC 4.2 `getObject(i, LocalDate.class)` | ✅ for local types; ❌ `Instant`/`OffsetDateTime`/`ZonedDateTime`/`UUID`/`BigInteger` | ✅ all of them | ffm ahead |
| Generated keys | `getGeneratedKeys()` (an extra query per INSERT by default) | `conn.getLastInsertRowId()` or `INSERT … RETURNING` | ❌ |
| Transactions | `setAutoCommit`, `commit`, `rollback` | same | ✅ |
| Savepoints | `setSavepoint()`, `rollback(Savepoint)`, `releaseSavepoint` | ❌; use SQL `SAVEPOINT x` / `ROLLBACK TO x` / `RELEASE x` | ❌ |
| Isolation / read-only | `setTransactionIsolation`, `setReadOnly` | `Config.withReadOnly`; `PRAGMA read_uncommitted` | ❌ |
| Timeout / cancel | `setQueryTimeout` (**only bounds lock waits**), `cancel()` | `setQueryTimeout(int)` / `(Duration)` (interrupts running work), `cancel()` | ffm ahead |
| Result metadata | `ResultSetMetaData` (23 methods) | `getColumnCount`, `getColumnLabel/Name`, `getColumnTypeName` | ⚠️ |
| Schema metadata | `DatabaseMetaData` (ResultSets) | `DatabaseMetaData` (typed records) | ❌ different shape |
| Errors | `org.sqlite.SQLiteException extends java.sql.SQLException`; `getErrorCode()` = 19 for a constraint | `io.github.revsnai.ffmsqlite.db.SQLiteException extends Exception`; `getErrorCode()` = 19, `getExtendedErrorCode()` = 2067 | ❌ type |
| Timeout error type | none (does not time out running queries) | `SQLiteTimeoutException` | – |
| UDFs, collations | `org.sqlite.Function` / `Function.Window` / `Collation` (abstract classes) | `createFunction` / `createAggregateFunction` / `createWindowFunction` / `createCollation` (lambdas) | ≈ |
| Hooks | `addUpdateListener`, `addCommitListener`, `BusyHandler`, `ProgressHandler` | same ideas, Connection methods | ≈ |
| Backup / serialize | `backup to x` / `restore from x` SQL; `serialize(schema)` / `deserialize` | `backup()` / `restore()` methods; `serialize()` / `deserialize()` | ≈ |
| Extensions | enables SQL `load_extension()` | `loadExtension(file)` (C API only) | ≈ |

## 3. Commonly used JDBC methods that ffm-sqlite lacks

These are the ones migrating code is likely to call. The rest of the 361 rarely matter for SQLite.

| Missing | Workaround in ffm-sqlite |
|---|---|
| `setDate/setTime/setTimestamp`, `getDate/getTime/getTimestamp` | `setObject(i, javaTimeValue)`, `getObject(i, LocalDate.class / LocalDateTime.class / Instant.class)` |
| `getGeneratedKeys()`, `prepareStatement(sql, RETURN_GENERATED_KEYS)` | `getLastInsertRowId()`, or `INSERT … RETURNING id` read with `executeQuery()` |
| `Statement.addBatch(String)` / `executeBatch()` | a `PreparedStatement` batch, or one `executeUpdate` with a multi-statement script |
| `setSavepoint` / `rollback(Savepoint)` / `releaseSavepoint` | SQL `SAVEPOINT` / `ROLLBACK TO` / `RELEASE` |
| `getByte/getShort`, `setByte/setShort` | `getInt` / `setInt` |
| `setBinaryStream/setCharacterStream/setBlob(InputStream)`, `getBinaryStream/getCharacterStream`, `Clob`/`Blob` objects | `setBytes`/`getBytes`, `setString`/`getString` (values are fully materialized) |
| `PreparedStatement.getMetaData()`, `getParameterMetaData()` | `executeQuery().getMetaData()`, `getParameterCount()` |
| `ResultSetMetaData.getColumnType` (`java.sql.Types`), `getTableName`, `isNullable`, `isAutoIncrement`, … | `getColumnTypeName`; `DatabaseMetaData.getColumns(table)` |
| `ResultSet.getStatement`, `getRow`, `isFirst`, `isBeforeFirst`, `isAfterLast` | track it in your code |
| `setFetchSize`, `setMaxRows`, `setMaxFieldSize`, `getWarnings`/`clearWarnings` | not needed (SQLite streams rows, has no warnings); use `LIMIT` |
| `isValid(timeout)`, `setReadOnly`, `setTransactionIsolation`, `getSchema`, `getCatalog` | `isClosed()`; `Config.withReadOnly`; PRAGMAs |
| `unwrap` / `isWrapperFor` | not applicable (no driver layering) |

## 4. Behaviour differences beyond signatures

Each was checked by running both libraries (`src/main/java/ffmsqlite/BehaviorProbe.java`, earlier probes).
These are the migration hazards a compiler will not catch.

| # | Behaviour | sqlite-jdbc | ffm-sqlite | Risk |
|---|---|---|---|---|
| 1 | **Thread safety of one connection** | Methods are `synchronized`: concurrent use from several threads is serialized | **Not thread-safe** (opened NOMUTEX, unsynchronized Java state). Concurrent use of one connection is undefined behaviour and can corrupt memory. Use one connection per thread, or hand it over between threads | **High** for code that shares a connection |
| 2 | `getObject(i)` on an integer that fits in `int` | `Integer` | `Long` | `(Integer) rs.getObject(1)` → `ClassCastException` |
| 3 | Reading a column before `next()` | Returns the first row's values (it pre-steps in `executeQuery`) | Throws "no current row; call next()" (the JDBC contract) | Breaks code that relies on the leniency |
| 4 | Exceptions | `java.sql.SQLException` subclass; SQLState null | Own checked type; no SQLState; extended code available | Every `catch` changes; code checking `SQLException` types/states |
| 5 | `java.sql.Date`/`Timestamp` storage (default) | INTEGER epoch **milliseconds**, timezone-dependent for `Date` | No `java.sql` types; java.time stored as ISO-8601 TEXT. INTEGER read as epoch **seconds** | **Data**: existing millisecond columns need `Instant.ofEpochMilli(rs.getLong(i))` |
| 6 | `setObject(LocalDateTime)` text | `2024-02-29T13:45:30.500` (`T`) | `2024-02-29 13:45:30.5` (SQLite's own format) | Both read each other's text; mixed formats compare wrongly as strings in SQL |
| 7 | `getColumnTypeName` of an expression | Inferred from the value (`FLOAT`) | `null` (only declared column types) | Code branching on type names |
| 8 | Query timeout | Only bounds waiting for locks (a CPU-bound query ran 10.8 s past a 1 s timeout) | Interrupts running work (`SQLiteTimeoutException`) | Behaviour improves, but code may now see timeouts it never saw |
| 9 | Throwing update listener | INSERT throws, **row committed anyway** | Statement fails and nothing is committed | Behaviour improves |
| 10 | `deserialize` while a statement runs | **JVM crash** (SIGSEGV) | Refused with SQLITE_BUSY | Behaviour improves |
| 11 | `prepareStatement` in a loop | Re-parses every time | Served from a per-connection statement cache | Faster; same semantics |
| 12 | Generated keys | Extra `SELECT last_insert_rowid()` after each INSERT unless `jdbc.get_generated_keys=false` | None; explicit `getLastInsertRowId()` | Faster; code must change |

**Same in both:** the SQL dialect, SQLite result codes (`getErrorCode()` = 19 for constraint violations), `executeUpdate("SELECT …")` returning 0, `executeQuery("DELETE …")` throwing, 1-based indexes, case-insensitive labels, `wasNull()`, and transaction semantics.

## 5. Runtime requirements

| | sqlite-jdbc | ffm-sqlite |
|---|---|---|
| Java | 8+ | **25+** (FFM API) |
| JVM flags | none (a warning on recent JDKs) | `--enable-native-access=ALL-UNNAMED` (or the jar manifest entry) |
| Native library | bundled for many OS/CPU combinations, extracted to a temp dir on every start | **system `libsqlite3`**, or one passed with `-Dffmsqlite.lib` |
| SQLite version | the bundled one (3.50.3 for 3.50.3.0) | whatever the system or configured library provides; features need recent versions (e.g. `RIGHT JOIN` 3.39+) |
| GraalVM native image | ships `META-INF/native-image` metadata | not tested here (FFM support in native image is still maturing) |

## 6. Migrating plain JDBC code

For code that uses JDBC directly, with no framework:

```java
// before (sqlite-jdbc)                                  // after (ffm-sqlite)
import java.sql.*;                                      import io.github.revsnai.ffmsqlite.db.*;
Connection c = DriverManager.getConnection(             Connection c = SQLite.getConnection("app.db");
        "jdbc:sqlite:app.db");
try (PreparedStatement ps = c.prepareStatement(sql)) {  try (PreparedStatement ps = c.prepareStatement(sql)) {   // unchanged
    ps.setTimestamp(1, Timestamp.from(now));                ps.setObject(1, now);                                  // java.time
    ps.executeUpdate();                                      ps.executeUpdate();
    ResultSet keys = ps.getGeneratedKeys();                  long id = c.getLastInsertRowId();
} catch (SQLException e) { … }                          } catch (SQLiteException e) { … }
```

Checklist:

1. Imports: `java.sql.*` → `io.github.revsnai.ffmsqlite.db.*`; `DriverManager.getConnection(url)` → `SQLite.getConnection(path, config)`.
2. Exceptions: `SQLException` → `SQLiteException`; `SQLTimeoutException` → `SQLiteTimeoutException`.
3. Dates: `java.sql.Date/Time/Timestamp` → java.time via `setObject`/`getObject(i, Type.class)`. Check existing data
   for INTEGER-millisecond columns (behaviour 5).
4. Replace `getGeneratedKeys`, `Statement` batches, `Savepoint` objects, streams, `getByte`/`getShort` (section 3).
5. Search for `(Integer) …getObject(` (behaviour 2) and column reads before `next()` (behaviour 3).
6. **Audit connection sharing across threads** (behaviour 1): one connection per thread, or a small pool.
7. Run on Java 25+ with `--enable-native-access`, and make sure a recent `libsqlite3` is available.

## 7. Making it a real drop-in

To support frameworks and pools, add a thin **JDBC facade** module, say `ffm-sqlite-jdbc`:

- a `java.sql.Driver` for URLs like `jdbc:ffmsqlite:app.db`, plus a `DataSource`;
- wrappers implementing `java.sql.Connection`/`Statement`/`PreparedStatement`/`ResultSet`/`ResultSetMetaData`/
  `DatabaseMetaData` over the existing classes, translating `SQLiteException` into `java.sql.SQLException`
  subclasses (with SQLState, `SQLTimeoutException`, `SQLIntegrityConstraintViolationException`);
- the 78 matching methods forward directly. The commonly used missing ones (section 3) are small additions
  (`getTimestamp`, `getGeneratedKeys` via `last_insert_rowid`, savepoints via SQL, streams over `byte[]`). The
  long tail throws `SQLFeatureNotSupportedException`, as sqlite-jdbc itself does for 188 methods;
- thread safety: synchronize the facade's connection methods, or open with SQLITE_OPEN_FULLMUTEX, to match
  sqlite-jdbc's behaviour 1;
- behaviour switches for compatibility: `getObject` returning `Integer` for small values, and an option to read
  and write `Timestamp` as epoch milliseconds.

The facade would add a virtual call per method. The performance difference comes from the JNI and
synchronization layers underneath, which the facade does not reintroduce (apart from optional
synchronization), so most of the speed advantage should remain. Measure it before relying on that.

## 8. With ffm-sqlite-jdbc: is it a drop-in replacement now?

| Level of compatibility | ffm-sqlite (core) | **ffm-sqlite-jdbc** |
|---|---|---|
| Binary / runtime (frameworks, pools, tools) | ❌ | ✅ HikariCP, Spring `JdbcTemplate` and transactions, and jOOQ run unmodified (`FrameworkTest`) |
| Source (plain JDBC code) | ⚠️ port needed | ✅ the same `java.sql` code; only the URL prefix changes (`jdbc:ffmsqlite:`), or none with `-Dffmsqlite.jdbc.acceptSqliteUrls=true` |
| Data (same files) | ✅ with a date caveat | ✅ same `date_class` default (epoch ms); `CompatibilityTest` moves databases between the drivers in both directions |

**Coverage, measured with the same bytecode rule for both** (`results/interface-coverage.md`):

| `java.sql` interface | Methods | sqlite-jdbc | ffm-sqlite-jdbc | Both |
|---|---:|---:|---:|---:|
| `Connection` | 60 | 48 | 45 | 45 |
| `Statement` | 56 | 46 | 52 | 46 |
| `PreparedStatement` | 114 | 81 | 92 | 81 |
| `ResultSet` | 195 | 74 | 84 | 74 |
| `ResultSetMetaData` | 23 | 23 | 23 | 23 |
| `DatabaseMetaData` | 179 | 167 | 176 | 167 |
| **Total** | **627** | **439** | **472** | **436** |

The three methods only sqlite-jdbc counts: `createArrayOf`, a stub that returns `null`, and two `prepareCall`
overloads that delegate to one that throws.

**Section 4's hazards, revisited:**

| # | Hazard with the core library | With ffm-sqlite-jdbc |
|---|---|---|
| 1 | Connection not thread-safe | ✅ every call synchronizes on the connection, as in sqlite-jdbc (`DriverTest`: 8 threads on one connection) |
| 2 | `getObject` returns `Long` | ✅ `Integer` when it fits (`integer_objects=true` by default) |
| 3 | Reading before `next()` throws | ⚠️ still throws (the JDBC contract); sqlite-jdbc's leniency is not replicated |
| 4 | Different exception type | ✅ `java.sql.SQLException` subclasses with SQLStates, original exception as the cause |
| 5 | Dates stored as millis read as seconds | ✅ `date_class=INTEGER`, epoch ms, as sqlite-jdbc |
| 6 | `LocalDateTime` text format | ⚠️ space instead of `T`; each driver reads both |
| 7 | Expression type names | ⚠️ inferred after `next()` as in sqlite-jdbc; before it, `NUMERIC` |

**Still different** (details in `../ffm-sqlite-jdbc/README.md`):

- sqlite-jdbc-specific classes (`SQLiteConnection`, `SQLiteConfig`, `org.sqlite.Function`, `backup to` SQL) do not
  exist. The equivalents are reached through `unwrap(io.github.revsnai.ffmsqlite.db.Connection.class)`.
- There is no `transaction_mode=IMMEDIATE` option.
- `DatabaseMetaData` reports `main`/`temp`/attached schema names where sqlite-jdbc reports null.
- The runtime is Java 25+ with a system `libsqlite3`.

**Performance through the JDBC layer:** see `PERFORMANCE.md`, which compares the wrapper with both ffm-sqlite and
sqlite-jdbc.
