# ffm-sqlite-jdbc

```xml
<dependency>
    <groupId>io.github.revsnai</groupId>
    <artifactId>ffm-sqlite-jdbc</artifactId>
    <version>0.2.0</version>
</dependency>
```

A JDBC driver (`java.sql.*`) on top of [ffm-sqlite](../ffm-sqlite): SQLite through Java's Foreign Function &
Memory API, with no JNI and no bundled native code. It lets frameworks, connection pools and existing JDBC code
use ffm-sqlite without changes. Tested unmodified with **HikariCP**, **Spring `JdbcTemplate` / transactions** and
**jOOQ**.

```java
// DriverManager finds it via META-INF/services; no Class.forName needed.
Connection c = DriverManager.getConnection("jdbc:ffmsqlite:app.db?journal_mode=WAL&foreign_keys=true");

// Or as a DataSource / in a pool:
HikariConfig cfg = new HikariConfig();
cfg.setJdbcUrl("jdbc:ffmsqlite:app.db?journal_mode=WAL");
DataSource ds = new HikariDataSource(cfg);
```

Requirements: Java 25+, `--enable-native-access=ALL-UNNAMED`, and a `libsqlite3` on the system (or
`-Dffmsqlite.lib=/path/to/libsqlite3.so`).

## URLs and options

`jdbc:ffmsqlite:<path>`, where the path may be `:memory:`, empty (a private temporary database) or a `file:` URI.
Options can go in the URL query or in `Properties` (properties win). Names follow sqlite-jdbc's where an equivalent
exists, so existing configurations carry over:

| Option | Meaning |
|---|---|
| any PRAGMA from sqlite-jdbc's list: `journal_mode`, `synchronous`, `foreign_keys`, `cache_size`, `temp_store`, `mmap_size`, `page_size`, `busy_timeout`, … | applied on every open (`true`/`false` become ON/OFF) |
| `readonly=true` or `open_mode` with bit 1 | open read-only |
| `statement_cache_size` | ffm-sqlite's per-connection statement cache (default 64) |
| `date_class` = `INTEGER` (default) / `TEXT` / `REAL` | how `java.sql.Date/Time/Timestamp` are stored: epoch ms, text, Julian day (sqlite-jdbc's option and default) |
| `date_precision` = `MILLISECONDS` (default) / `SECONDS` | unit for INTEGER dates |
| `date_string_format` (default `yyyy-MM-dd HH:mm:ss.SSS`) | pattern for TEXT dates |
| `jdbc.get_generated_keys` (default `true`) | `getGeneratedKeys()` after INSERT |
| `integer_objects` (default `true`) | `getObject()` returns `Integer` for integers that fit, as sqlite-jdbc does |

Unknown options (`user`, `password`, …) are ignored, as JDBC drivers usually do.

To take over **unchanged `jdbc:sqlite:` URLs**, remove sqlite-jdbc from the classpath and start the JVM with
`-Dffmsqlite.jdbc.acceptSqliteUrls=true`.

## Compatibility with sqlite-jdbc

`CompatibilityTest` runs the same JDBC code against both drivers and asserts the same results. It covers CRUD and
every common type, `getObject` types, NULLs, `java.sql` dates stored as epoch milliseconds, generated keys,
batches, transactions, savepoints, errors, result-set metadata and `DatabaseMetaData`. It also checks that
**databases move between the drivers in both directions** with dates intact.

**JDBC API coverage**, measured from bytecode by `../ffm-vs-jdbc/src/main/java/ffmsqlite/InterfaceComparison.java`.
It counts methods whose implementation can return rather than always throwing "not supported":

| `java.sql` interface | Methods | sqlite-jdbc implements | ffm-sqlite-jdbc implements |
|---|---:|---:|---:|
| `Connection` | 60 | 48 | 45 |
| `Statement` | 56 | 46 | 52 |
| `PreparedStatement` | 114 | 81 | 92 |
| `ResultSet` | 195 | 74 | 84 |
| `ResultSetMetaData` | 23 | 23 | 23 |
| `DatabaseMetaData` | 179 | 167 | 176 |
| **Total** | **627** | **439** | **472** |

ffm-sqlite-jdbc implements 436 of sqlite-jdbc's 439. The other three are sqlite-jdbc's `createArrayOf`, which
returns null, and two `prepareCall` overloads that end in an exception. It adds 36 more: `Blob`/`Clob`/`NClob`
parameters, `setObject(…, SQLType)`, `setURL`/`getURL`, `getBlob`, generated-key variants with column lists, and
`getFunctions`/`getPseudoColumns`/`getClientInfoProperties`.

### Where it behaves better

- **Real query timeouts.** `setQueryTimeout` interrupts running statements with `SQLTimeoutException`.
  sqlite-jdbc only bounds lock waits: a CPU-bound query ran 10.8 s past a 1 s timeout.
- **Exception classes and SQLStates.** Errors become `SQLIntegrityConstraintViolationException` (with the
  standard SQLStates 23505 unique, 23503 foreign key, 23502 not null, 23514 check), `SQLTimeoutException`,
  `SQLSyntaxErrorException`, `SQLTransientException` (busy/locked) and so on. Spring therefore maps a duplicate
  key to `DuplicateKeyException`. sqlite-jdbc throws plain `SQLException`s with a null SQLState.
- **`BatchUpdateException` carries SQLite's error code** (19); sqlite-jdbc's has 0.
- **The safety guards of ffm-sqlite** (e.g. `deserialize` cannot crash the JVM) and its statement cache, so
  `prepareStatement` in a loop is cheap.

### Remaining differences

| Behaviour | sqlite-jdbc | ffm-sqlite-jdbc |
|---|---|---|
| Reading a column before `next()` | returns the first row (it pre-steps) | throws, as the JDBC contract says |
| `getColumnTypeName` of an expression before the first `next()` | inferred from the first value | `NUMERIC` (after `next()`: inferred, as sqlite-jdbc) |
| `setObject(LocalDateTime)` text | `2024-02-29T13:45:30.500` | `2024-02-29 13:45:30.5` (SQLite's own format); each driver reads both |
| `DatabaseMetaData` TABLE_SCHEM | null | the SQLite schema name (`main`, `temp`, attached), consistent with `getSchemas()` |
| `BatchUpdateException.getUpdateCounts()` on failure | counts for all entries | counts for the entries before the failure (JDBC allows both) |
| sqlite-jdbc-specific classes (`SQLiteConnection`, `SQLiteConfig`, `org.sqlite.Function`, `backup to …` SQL) | ✅ | ❌: use `connection.unwrap(io.github.revsnai.ffmsqlite.db.Connection.class)` for functions, collations, hooks, backup, serialize, extensions |
| Options without an equivalent: `transaction_mode` (IMMEDIATE/EXCLUSIVE), `shared_cache`, `enable_load_extension` | ✅ | ❌ (`BEGIN IMMEDIATE` can be issued as SQL; extensions via `unwrap(...).loadExtension`) |
| Runtime | Java 8+, bundled natives | Java 25+, system `libsqlite3`, `--enable-native-access` |

### Threads

Like sqlite-jdbc, every call on a connection and on its statements and result sets synchronizes on the
connection, so sharing one connection across threads is safe: serialized, not parallel. `Statement.cancel()` is
lock-free so it can interrupt a running query from another thread. For parallelism, use one connection per
thread (e.g. a pool), as with any SQLite driver.

## Escape hatch to ffm-sqlite

```java
io.github.revsnai.ffmsqlite.db.Connection ffm = jdbcConnection.unwrap(io.github.revsnai.ffmsqlite.db.Connection.class);
ffm.createFunction("twice", 1, args -> args.getLong(0) * 2);   // now usable from SQL through JDBC
ffm.backup("backup.db");
```

## Build

```bash
cd ../ffm-sqlite-parent && mvn install   # builds and tests both modules (163 tests)
```
