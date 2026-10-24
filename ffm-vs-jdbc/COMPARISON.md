# sqlite-jdbc vs ffm-sqlite: data types, databases, tables, indexes, PRAGMAs, functions

Both libraries ran the same engine (SQLite 3.50.3, built with sqlite-jdbc's compile options) on an i7-14700HX,
OpenJDK 25.0.4. sqlite-jdbc was 3.50.3.0; ffm-sqlite was 0.1.0-SNAPSHOT (`../ffm-sqlite`).

- **Storage table:** produced by `TypeComparison.java`. It binds each value with each library's idiomatic API,
  inspects what SQLite stored, and reads it back.
- **Performance:** from `TypeBenchmark.java` and `OperationsBenchmark.java`. Setup: JMH, 1 fork, 3×1 s
  warmup, 5×1 s measurement. Raw data is in `results/types-ops.{txt,json}`.
- **Speedup** = sqlite-jdbc time ÷ ffm-sqlite time; values above 1 mean ffm-sqlite is faster.
- **Noise:** the error bars are wide (often ±20–40%), because this is a hybrid P/E-core CPU and each
  benchmark ran in one fork. Treat differences under ~1.3× as noise.

---

## 1. Data types: what gets stored, and does it come back?

| Java type | Column | sqlite-jdbc stores | sqlite-jdbc reads back | ffm-sqlite stores | ffm-sqlite reads back |
|---|---|---|---|---|---|
| `int` | INTEGER | integer `42` | ✅ | integer `42` | ✅ |
| `long` | INTEGER | integer `9223372036854775807` | ✅ | integer `9223372036854775807` | ✅ |
| `double` | REAL | real | ✅ | real | ✅ |
| `float` | REAL | real | ✅ | real | ✅ |
| `boolean` | BOOLEAN | integer `1` | ✅ | integer `1` | ✅ |
| `String` (UTF-8, emoji) | TEXT | text | ✅ | text | ✅ |
| `byte[]` | BLOB | blob `X'010203FF'` | ✅ | blob `X'010203FF'` | ✅ |
| `null` | any | NULL | ✅ | NULL | ✅ |
| `BigDecimal` | TEXT | text `'12345678901234567890.123456789'` | ✅ exact | same | ✅ exact |
| `BigDecimal` | DECIMAL(30,9) | real (affinity converts it) | ⚠️ `1.23456789012346E+19` | real (same) | ⚠️ `1.2345678901234567E+19` |
| `BigInteger` 2^70 | TEXT | text | ❌ `getObject(i, BigInteger.class)` not implemented | text (INTEGER when it fits in 64 bits) | ✅ |
| `LocalDate` | DATE | text `'2024-02-29'` | ✅ | text `'2024-02-29'` | ✅ |
| `LocalTime` | TIME | text `'13:45:30.500'` | ✅ | text `'13:45:30.5'` | ✅ |
| `LocalDateTime` | DATETIME | text `'2024-02-29T13:45:30.500'` ¹ | ✅ | text `'2024-02-29 13:45:30.5'` | ✅ |
| `Instant` | TIMESTAMP | text `'2024-02-29T13:45:30.500Z'` | ❌ not implemented | text `'2024-02-29 13:45:30.5'` (UTC) | ✅ |
| `OffsetDateTime` | TIMESTAMP | text `'…T13:45:30.500+02:00'` | ❌ not implemented | text `'… 13:45:30.5+02:00'` | ✅ |
| `ZonedDateTime` | TIMESTAMP | text `'…+01:00[Europe/Paris]'` ² | ❌ not implemented | text `'…+01:00'` | ≈ same instant; zone becomes `+01:00` |
| `UUID` | TEXT | text `'123e4567-…'` | ❌ not implemented | text `'123e4567-…'` (also reads upper case and 16-byte BLOBs) | ✅ |
| `java.sql.Date` | DATE | integer `1709145000000` ³ | ✅ | n/a (no `java.sql`) | — |
| `java.sql.Timestamp` | TIMESTAMP | integer epoch millis | ✅ | n/a | — |
| SQLite-produced dates (`CURRENT_TIMESTAMP`, `unixepoch()`, `julianday()`) | — | — | `getObject` only for local types | — | ✅ TEXT, INTEGER seconds, and REAL Julian day all readable as any java.time type |

¹ The `T` separator means plain string comparison against SQLite's own `CURRENT_TIMESTAMP`/`datetime()`
(which use a space) is wrong. ffm-sqlite writes SQLite's format.
² SQLite's date functions cannot parse the `[Europe/Paris]` suffix.
³ Epoch millis of **local** midnight (here IST, so 2024-02-28 18:30 UTC). The stored value depends on the
JVM's default timezone.

**Summary:** both libraries store the core types identically, because the engine does the storing. They
differ only on types SQLite lacks. sqlite-jdbc writes java.time values with `toString()` and cannot read
back most of them, or `UUID`; ffm-sqlite round-trips all of them, with dates in SQLite's native format.
In a `DECIMAL` column, both lose `BigDecimal` precision to SQLite's type affinity.

## 2. Data types: performance (ns per row, 1,000 rows; heap bytes per row in brackets)

Each library used its idiomatic API for the type. sqlite-jdbc ran with `jdbc.get_generated_keys=false`,
its fastest per-row write path. For `INSTANT`, sqlite-jdbc used `setTimestamp`/`getTimestamp` (INTEGER millis),
and for `UUID` `setString(uuid.toString())`/`UUID.fromString(getString())`, because its `getObject` doesn't
support either type. The date and UUID rows come from a re-run after ffm-sqlite's hand-written date codec
replaced `DateTimeFormatter` (`results/types-dates-uuid.{txt,json}`).

| Type | Write: sqlite-jdbc | Write: ffm-sqlite | Speedup | Read: sqlite-jdbc | Read: ffm-sqlite | Speedup |
|---|---:|---:|---:|---:|---:|---:|
| INTEGER | 473 (16 B) | 209 (0 B) | **2.3×** | 137 (0 B) | 53 (0 B) | **2.6×** |
| REAL | 465 (24 B) | 209 (0 B) | **2.2×** | 183 (0 B) | 61 (0 B) | **3.0×** |
| TEXT | 567 (32 B) | 260 (32 B) | **2.2×** | 305 (152 B) | 91 (88 B) | **3.4×** |
| BLOB (16 B) | 620 (16 B) | 241 (32 B) | **2.6×** | 195 (32 B) | 83 (32 B) | **2.4×** |
| BOOLEAN | 476 (16 B) | 206 (0 B) | **2.3×** | 119 (0 B) | 49 (0 B) | **2.4×** |
| NULL | 454 (0 B) | 207 (0 B) | **2.2×** | 184 (0 B) | 79 (0 B) | **2.3×** |
| BigDecimal | 542 (39 B) | 312 (158 B) | **1.7×** | 396 (262 B) | 132 (198 B) | **3.0×** |
| LocalDate | 637 (120 B) | 302 (32 B) | **2.1×** | 3,015 (2,688 B) | 97 (56 B) | **31×** |
| LocalDateTime | 763 (152 B) | 337 (40 B) | **2.3×** | 3,548 (3,720 B) | 120 (160 B) | **30×** |
| Instant | 624 (584 B) | 335 (40 B) | **1.9×** | 186 (24 B) | 122 (112 B) | **1.5×** |
| UUID | 559 (136 B) | 274 (112 B) | **2.0×** | 352 (232 B) | 114 (168 B) | **3.1×** |

- **Primitives, TEXT and BLOB:** ffm-sqlite is consistently 2.2–3.4× faster. That is the per-call overhead
  measured in the main benchmark, now confirmed for every storage class.
- **java.time reads:** sqlite-jdbc's `getObject(i, LocalDate.class)` costs ~3 µs per value. ffm-sqlite parses
  and formats dates by hand, straight from and to UTF-8 bytes, so a date costs about the same as plain TEXT
  (97–122 ns, ~30× faster).
- **Instant:** ffm-sqlite's ISO text now decodes faster than sqlite-jdbc's millisecond integers (122 vs 186 ns),
  and the stored value stays readable by SQLite's date functions.
- Before the hand-written codec, ffm-sqlite read LocalDateTime in 802 ns and Instant in 844 ns, a loss
  against sqlite-jdbc on Instant.

## 3. Databases, tables, indexes, PRAGMAs, functions: the code

| Operation | sqlite-jdbc | ffm-sqlite |
|---|---|---|
| Create or open a database | `DriverManager.getConnection("jdbc:sqlite:app.db")` | `SQLite.getConnection("app.db")` |
| In-memory database | `"jdbc:sqlite::memory:"` | `":memory:"` |
| Read-only | `cfg.setReadOnly(true); cfg.createConnection(url)` | `SQLite.getConnection(p, Config.DEFAULT.withReadOnly(true))` |
| PRAGMAs at connect time | `SQLiteConfig cfg = new SQLiteConfig(); cfg.setJournalMode(WAL); cfg.enforceForeignKeys(true); cfg.createConnection(url)`, or URL/`Properties` keys | `Config.DEFAULT.withJournalMode(WAL).withSynchronous(NORMAL).withForeignKeys(true).withPragma("cache_size", -8000)` |
| Create a table | `st.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")` | identical |
| Alter a table | `st.executeUpdate("ALTER TABLE t ADD COLUMN c TEXT")` | identical |
| Create an index | `st.executeUpdate("CREATE UNIQUE INDEX ux ON t (name)")` | identical (partial and expression indexes too) |
| Set a PRAGMA | `st.executeUpdate("PRAGMA journal_mode = WAL")` | identical |
| Read a PRAGMA | `rs = st.executeQuery("PRAGMA user_version"); rs.next(); rs.getInt(1)` | identical |
| PRAGMA as a table-valued function | `st.executeQuery("SELECT name FROM pragma_table_info('t') WHERE pk = 0")` | identical |
| List tables, columns, indexes | `conn.getMetaData().getTables(…)`, `.getColumns(…)`, `.getIndexInfo(…)` (ResultSets of string columns) | `conn.getMetaData().getTables()`, `.getColumns("t")`, `.getIndexes("t")`, `.getForeignKeys("t")`, `.getPrimaryKey("t")` (typed records) |
| Query timeout | `st.setQueryTimeout(5)`: **only bounds lock waiting**; a CPU-bound query ran 10.8 s past a 1 s timeout | `st.setQueryTimeout(5)` or `Duration`: interrupts running work (`SQLiteTimeoutException`) |
| Cancel from another thread | `st.cancel()` | `st.cancel()` |
| Progress handler | `ProgressHandler.setHandler(conn, n, handler)` | `conn.setProgressHandler(n, () -> continue?)` |
| Load an extension | `cfg.enableLoadExtension(true)`, then SQL `SELECT load_extension('x')` (enables the SQL function) | `conn.loadExtension("x")` (C API only, just for that call; SQL `load_extension()` stays disabled) |
| Built-in SQL functions (`date`, `json_*`, `upper`, math, FTS5, R-tree) | via SQL | via SQL (FTS5, R-tree and math need a library built with them, e.g. `scripts/build-sqlite.sh`) |
| User-defined SQL function | `Function.create(conn, "f", new Function() { protected void xFunc() { result(value_long(0) + 1); } })` | `conn.createFunction("f", 1, args -> args.getLong(0) + 1)` |
| User-defined aggregate | `Function.create(conn, "agg", new Function.Aggregate() { xStep() {…} xFinal() {…} })` | `conn.createAggregateFunction("agg", 1, MyAgg::new)` (fresh accumulator per group) |
| User-defined window function | `Function.create(conn, "w", new Function.Window() { xStep() xInverse() xValue() xFinal() })` | `conn.createWindowFunction("w", 1, MyWindow::new)` (`step`, `inverse`, `result`; `value()` defaults to `result()`) |
| Custom collation | `Collation.create(conn, "ci", new Collation() { protected int xCompare(String a, String b) { … } })` | `conn.createCollation("ci", String.CASE_INSENSITIVE_ORDER)` (any `Comparator<? super String>`, incl. `java.text.Collator`) |
| Update hook | `((SQLiteConnection) conn).addUpdateListener((type, db, table, rowId) -> …)` | `conn.addUpdateListener((type, db, table, rowId) -> …)` |
| Commit / rollback hooks | `addCommitListener(new SQLiteCommitListener() { onCommit() onRollback() })` | `addCommitListener(new CommitListener() { onCommit() onRollback() })`; throwing in `onCommit` vetoes |
| Busy handler | `org.sqlite.BusyHandler.setHandler(conn, new BusyHandler() { protected int callback(int n) {…} })` | `conn.setBusyHandler(attempts -> retry?)`, `conn.setBusyTimeout(ms)` |
| Serialize / deserialize | `((SQLiteConnection) conn).serialize("main")`, `.deserialize("main", bytes)` | `conn.serialize()`, `conn.deserialize(bytes)`, `SQLite.getConnection(bytes)` |
| Backup / restore | `st.executeUpdate("backup to b.db")`, `st.executeUpdate("restore from b.db")` | `conn.backup("b.db")`, `conn.restore("b.db")`, `conn.backup(otherConn)`; options for schema, pages per step, pause, progress/cancel |

## 4. Databases, tables, indexes, PRAGMAs, functions: performance (µs per operation)

| Operation | sqlite-jdbc | ffm-sqlite | Speedup | What dominates |
|---|---:|---:|---:|---|
| **Database:** create new file (open, CREATE TABLE, close, delete) | 3,727 | 3,588 | 1.0× | fsync / filesystem |
| **Database:** open existing + 4 PRAGMAs (WAL, synchronous, foreign_keys, busy_timeout) | 94 | 83 | 1.1× | file open |
| **Database:** online backup of the 10k-row in-memory DB to a new file | 4,931 | 4,294 | 1.1× (noise: ±5,519 vs ±352) | file writes; same C backup loop |
| **Database:** restore the in-memory DB from that file | 199 | 179 | 1.1× | same C backup loop |
| **Database:** serialize the 10k-row in-memory DB (~600 KB) to `byte[]` | 81.8 | 81.6 | 1.0× | memory copy |
| **Database:** deserialize that image, replacing the DB | 28.7 | 31.1 | 0.9× (noise) | memory copy |
| **Table:** CREATE + DROP (6 columns, CHECK, UNIQUE) | 37.9 | 27.7 | 1.4× | schema change |
| **Table:** CREATE, ALTER ADD COLUMN, DROP | 45.0 | 38.1 | 1.2× | schema change |
| **Index:** CREATE + DROP on 10k rows | 2,820 | 2,277 | 1.2× | engine sort (noisy) |
| **Index:** unique composite on 10k rows | 3,128 | 2,632 | 1.2× | engine sort (noisy) |
| **Index:** partial + expression on 10k rows | 3,356 | 2,637 | 1.3× | engine sort (noisy) |
| **PRAGMA:** `PRAGMA user_version` (read) | 1.63 | 0.68 | **2.4×** | library overhead |
| **PRAGMA:** `PRAGMA cache_size = -8000` (write) | 0.75 | 0.57 | 1.3× | library overhead |
| **PRAGMA:** `PRAGMA table_info(big)` (5 rows × 6 cols) | 8.94 | 2.58 | **3.5×** | result-set overhead |
| **PRAGMA:** `pragma_table_info('big')` with WHERE | 8.41 | 4.65 | **1.8×** | result-set overhead |
| **PRAGMA:** schema listing (`sqlite_schema` ⋈ `pragma_table_info`) | 12.0 | 6.2 | **1.9×** | result-set overhead |
| **Function:** 4 scalar functions × 1,000 rows | 1,771 | 896 | **2.0×** | per-value `getString` |
| **Function:** `json_object`/`json_extract` × 1,000 rows | 693 | 416 | **1.7×** | per-value `getString` |
| **Function:** aggregates over 10k rows (1 row out) | 1,279 | 1,196 | 1.1× | engine |

- **Engine-bound or I/O-bound work is a tie:** creating databases, building indexes, and aggregating
  10,000 rows into one. Both libraries run the same C code, and the binding is a rounding error next to an
  fsync or a sort.
- **ffm-sqlite wins wherever there are many small calls:** PRAGMA reads and metadata queries (1.8–3.5×),
  and any query whose cost is fetching many result values (2×). Small DDL statements land in between
  (1.2–1.4×), because statement compilation and schema changes dominate them.
- ffm-sqlite also allocates 2–6× less per operation (see `results/types-ops.txt`).

## 5. User-defined functions: performance (ns per call, 10,000 calls per query)

The same function was written for each library: sqlite-jdbc's `org.sqlite.Function` (a JNI callback) and
ffm-sqlite's `createFunction` (an FFM upcall). Raw data: `results/udf.{txt,json}` (`UdfBenchmark.java`).

| Query | sqlite-jdbc | ffm-sqlite | Speedup |
|---|---:|---:|---:|
| Baseline: built-in C, `sum(n + 1)` | 48 | 34 | 1.4× (engine build difference) |
| Scalar `plus_one(n)` (long in, long out) | 419 | 85 | **4.9×** |
| Scalar `jupper(s)` (text in, text out) | 631 | 147 | **4.3×** |
| Aggregate `jsum(n)` (step per row) | 214 | 57 | **3.7×** |
| `SELECT plus_one(n)`: UDF plus returning every row | 463 | 98 | **4.7×** |
| Baseline: built-in `sum(n) OVER (ROWS 9 PRECEDING)` | 659 | 512 | 1.3× (engine build difference) |
| Window `wsum(n) OVER (ROWS 9 PRECEDING)`: step, inverse and value per row | 1,527 | 689 | **2.2×** |
| Baseline: built-in `max(s COLLATE NOCASE)` (ns per comparison) | 44 | 37 | 1.2× (engine build difference) |
| Collation `max(s COLLATE jci)` (case-insensitive in Java, ns per comparison) | 354 | 96 | **3.7×** |
| `ORDER BY s COLLATE jci` over 10,000 rows, reading every row (ns per row) | 3,053 | 705 | **4.3×** |
| Insert + delete 1,000 rows, no listener (ns per row) | 633 | 339 | 1.9× |
| Same with a counting update listener (2 events per row) | 1,362 | 477 | **2.9×** (per event: ~365 vs ~69 ns) |

Subtracting the built-in baselines, one Java call costs ~50 ns as an FFM upcall against ~370 ns as a
sqlite-jdbc JNI callback. The window function's three callbacks per row add ~180 vs ~870 ns; SQLite's own
window machinery (~500–660 ns/row) dilutes the end-to-end ratio to 2.2×. Window rows come from
`results/udf-window.{txt,json}`. The collation rows (`results/udf-collation.{txt,json}`) put one Java comparison at
~60 ns vs ~310 ns over the built-in; ffm-sqlite allocates more per comparison (183 vs 103 B: the two Strings plus
the `MemorySegment` wrappers the upcall creates for its pointer arguments). sqlite-jdbc's callback re-enters Java through JNI, and each `value_*()` and
`result()` is another synchronized JNI call. The FFM upcall is a direct stub, and argument reads and results
are critical downcalls.

## 6. Feature support

| Feature | sqlite-jdbc | ffm-sqlite |
|---|:---:|:---:|
| JDBC interfaces (JPA / Hibernate / jOOQ / `DataSource` pools) | ✅ | ❌ by design |
| Prepared statements, batches, transactions, `ResultSetMetaData` | ✅ | ✅ |
| Statement cache for `prepareStatement` | ❌ | ✅ |
| java.time write / read | ✅ / ⚠️ local types only | ✅ / ✅ |
| `BigDecimal`, `BigInteger` | ✅ / ⚠️ write only | ✅ / ✅ |
| `UUID` | ⚠️ write only | ✅ (TEXT; reads 16-byte BLOBs too) |
| Schema metadata | ✅ `DatabaseMetaData` (JDBC ResultSets) | ✅ `DatabaseMetaData` (typed records: tables, views, columns incl. generated, indexes incl. partial/expression, foreign keys, primary keys, schemas) |
| PRAGMA configuration at connect | ✅ `SQLiteConfig` (28 setters) | ✅ `Config` (journal mode, synchronous, foreign keys, any PRAGMA via `withPragma`, validated and quoted) |
| Query timeout | ⚠️ bounds lock waiting only | ✅ interrupts running statements |
| User-defined scalar and aggregate functions | ✅ | ✅ (3.7–4.9× faster calls) |
| User-defined window functions | ✅ | ✅ (2.2× faster end to end) |
| Custom collations | ✅ | ✅ (3.7–4.3× faster) |
| Update, commit/rollback and busy hooks | ✅, but a throwing update listener fails the INSERT **while its row is committed anyway**, and listeners may (undefined behaviour) use their own connection | ✅; a throwing listener fails the statement and nothing is committed (rollback-only transaction); commit veto by exception; re-entry refused; no hook runs during close |
| Progress handler, `cancel()` | ✅ | ✅ |
| Backup / restore (online backup API) | ✅ (`backup to` / `restore from` SQL commands) | ✅ (`backup`/`restore` methods; progress, cancel, incremental steps, attached schemas, connection-to-connection) |
| Serialize / deserialize to `byte[]` | ✅, but **crashes the JVM** (SIGSEGV) if a statement is running during `deserialize` | ✅; refuses (SQLITE_BUSY) while statements run or a transaction is active; fixes WAL images; header check |
| Load extensions | ✅ (by enabling the SQL `load_extension()` function) | ✅ (C API only, scoped to the call; the SQL function stays disabled) |
| Bundled native library (no system SQLite needed) | ✅ | ❌ by design: uses the system or a configured `libsqlite3` (`scripts/build-sqlite.sh` builds one) |
| JNI-free; no native code shipped | ❌ | ✅ |
| Min Java version | 8 | 25 (FFM API) |

## Reproduce

```bash
cd ../ffm-sqlite && mvn install && cd ../ffm-vs-jdbc && mvn package
L=$PWD/native/libsqlite3.so
java --enable-native-access=ALL-UNNAMED -Dsqlite.lib=$L -Dffmsqlite.lib=$L -cp target/benchmarks.jar ffmsqlite.TypeComparison
java -jar target/benchmarks.jar 'TypeBenchmark|OperationsBenchmark' -prof gc -jvmArgsAppend "-Dsqlite.lib=$L -Dffmsqlite.lib=$L"
```
