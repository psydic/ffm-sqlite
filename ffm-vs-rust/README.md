# ffm-sqlite vs rusqlite

How does ffm-sqlite (Java 25, FFM) compare with **rusqlite**, the standard Rust SQLite library? Like ffm-sqlite,
rusqlite is a thin safe wrapper over the SQLite C library. Both ran on the *same* SQLite binary, with the same
workloads, data and timing method. Raw results are in [`results/`](results/); the tables come from
[`results/report.md`](results/report.md).

## Summary

| Area | Result |
|---|---|
| Application workloads: point selects, inserts, updates, transactions, scans, joins | **Tie** (8 of 9; rusqlite 7% faster at open-query-close) |
| Concurrent reads, 1/4/8 threads | **Tie** |
| Per data type, read and write | Tie, except ffm-sqlite faster at INTEGER reads (1.5×) and BLOB writes (1.2×) |
| `prepareStatement` inside a loop | ffm-sqlite **1.7×** over rusqlite's `prepare`; tie with `prepare_cached` |
| 1M wide rows: bulk load; 1 writer + 4 readers | **Tie** |
| User-defined functions and collations | rusqlite **1.3–1.8×** faster per call |
| Start-up to first result | rusqlite **< 10 ms vs 140–220 ms** |
| Memory for a minimal program | rusqlite **4 MB vs 69–82 MB** |

For database work the two are equally fast. Both make the C calls about as cheaply as possible, and the SQLite
engine dominates the time. They differ in three places:

1. **Calls from SQLite into user code.** For UDFs and collations, an FFM upcall has to switch the thread from
   native into Java state, and text arguments become Java strings. Rust calls a native closure directly. That
   costs ffm-sqlite about 20–90 ns per call.
2. **Process cost.** A JVM needs ~150 ms and ~70 MB before running any query; a Rust binary needs neither. This
   matters for CLIs, short jobs and serverless; not for long-running services.
3. **Statement caching defaults.** ffm-sqlite caches every prepared statement automatically (64 per connection).
   rusqlite caches only with `prepare_cached` (16 per connection). Code that calls `prepare` in a loop is 1.7×
   slower in Rust unless it switches to `prepare_cached`.

## Method

- **Same engine:** rusqlite links dynamically to `../ffm-vs-jdbc/native/libsqlite3.so`, the SQLite 3.50.3 build
  (gcc 13.3) the Java benchmarks use. Each run prints `SQLite 3.50.3 (COMPILER=gcc-13.3.0)` to confirm it.
- **Same workloads:** [`src/main.rs`](src/main.rs) mirrors the JMH benchmarks in `../ffm-vs-jdbc`: the same schema,
  SQL, data, rows read and columns read, each through the typed getter. Statements are prepared once unless a
  benchmark says otherwise. The 1M-row tests bind the exact Java value pool, read from the Java-built template.
- **Same timing:** a JMH-like harness. 3 warmup + 5 measured 1-second iterations (2 s and 8 warmups for the 1M-row
  concurrent test); 2 separate processes, like JMH's 2 forks; a fresh database per benchmark, as with JMH's per-trial
  setup. Scores are the mean ± 99.9% confidence interval (Student's t over all measured iterations, as JMH reports).
  "Tie" means the intervals overlap.
- **Same conditions:** [`run.sh`](run.sh) runs each suite in Java, then immediately in Rust, both pinned to cores
  2–15, file databases on tmpfs except the 1M-row concurrent test (on disk, as in `../ffm-vs-jdbc`).
- **Versions:** OpenJDK 25.0.4, ffm-sqlite 0.2.2 plus the re-entry guard from the security review (not yet released);
  rusqlite 0.41.0, Rust 1.93, release build with LTO, `codegen-units=1` and `target-cpu=native`.
- **Machine:** i7-14700HX, Linux 7.0. Other workloads (a VM, a browser) were running, as in the earlier reports;
  absolute numbers move by up to ~30% between runs, so compare within a table.

Two harness mistakes were found and fixed before the final run. First, the Rust workloads shared one database, so the
insert benchmarks grew the tables for the scans that ran after them. Second, the Rust collation did more work than
Java's `CASE_INSENSITIVE_ORDER`; it now follows the same algorithm. The workload, UDF and 1M-row suites were then
rerun on both sides.

## Results

### Application workloads (WAL, synchronous=NORMAL). µs per operation

| Benchmark | ffm-sqlite | rusqlite | Result |
|---|---:|---:|---|
| Point select by primary key | 2.28 ± 0.33 | 2.13 ± 0.14 | tie |
| Point select by indexed email | 3.24 ± 0.20 | 3.20 ± 0.14 | tie |
| Insert, autocommit | 4.16 ± 0.36 | 3.79 ± 0.16 | tie |
| Update, autocommit | 5.84 ± 0.79 | 5.38 ± 0.37 | tie |
| Business transaction (read, insert, update, commit) | 13.4 ± 0.4 | 13.5 ± 1.0 | tie |
| Range scan, 100 rows | 36.7 ± 2.1 | 40.6 ± 2.7 | tie |
| Join + GROUP BY, 50 users | 76.4 ± 6.4 | 77.1 ± 6.7 | tie |
| Prepare inside the loop: rusqlite `prepare` | 2.49 ± 0.23 | 4.24 ± 0.26 | **ffm-sqlite 1.70×** |
| Prepare inside the loop: rusqlite `prepare_cached` | 2.49 ± 0.23 | 2.22 ± 0.19 | tie |
| Open, query, close (no pool) | 42.5 ± 1.4 | 39.6 ± 1.4 | **rusqlite 1.07×** |

### Concurrent point selects, one connection per thread. Operations per ms, total

| Threads | ffm-sqlite | rusqlite | Result |
|---|---:|---:|---|
| 1 | 533 ± 25 | 539 ± 28 | tie |
| 4 | 1,720 ± 67 | 1,837 ± 60 | tie |
| 8 | 1,648 ± 44 | 1,588 ± 193 | tie |

### Per data type, 1,000 single-column rows (in-memory). ns per row

| Type | Read: ffm-sqlite / rusqlite | Write: ffm-sqlite / rusqlite |
|---|---|---|
| INTEGER | **49.6** / 73.3 (ffm-sqlite 1.48×) | 207 / 210 (tie) |
| REAL | 56.6 / 78.1 (tie) | 212 / 225 (tie) |
| TEXT | 85.2 / 115 (tie) | 248 / 282 (tie) |
| BLOB (16 B) | 79.3 / 103 (tie) | **224** / 276 (ffm-sqlite 1.23×) |

The rusqlite reads had wide intervals (±19–31 ns); most of these differences are within noise.

### User-defined functions and collations, 10,000 rows. ns per call

| Benchmark | ffm-sqlite | rusqlite | Result |
|---|---:|---:|---|
| Built-in expression (baseline) | 26.2 ± 2.1 | 29.3 ± 2.3 | tie |
| Scalar function, integer | 73.9 ± 11.0 | 45.3 ± 5.6 | **rusqlite 1.63×** |
| Scalar function, text | 199 ± 36 | 109 ± 9 | **rusqlite 1.83×** |
| Aggregate function | 46.3 ± 2.3 | 27.8 ± 2.6 | **rusqlite 1.66×** |
| Window function (sliding, 10 rows) | 749 ± 64 | 550 ± 33 | **rusqlite 1.36×** |
| Built-in window (baseline) | 592 ± 43 | 566 ± 75 | tie |
| Collation, `max()` | 88.5 ± 6.2 | 66.0 ± 4.2 | **rusqlite 1.34×** |
| Built-in NOCASE (baseline) | 43.9 ± 23.9 | 37.5 ± 3.2 | tie |
| Collation, `ORDER BY` (per row) | 787 ± 88 | 518 ± 42 | **rusqlite 1.52×** |
| Scalar result read per row | 123 ± 7 | 87.9 ± 11.7 | **rusqlite 1.40×** |

Subtracting the built-in baseline, a scalar call costs ffm-sqlite ~48 ns and rusqlite ~16 ns: the price of an FFM
upcall. Calls with text add Java string decoding (UTF-8 → UTF-16) on top.

### 1M rows × 20 columns

| Benchmark | ffm-sqlite | rusqlite | Result |
|---|---:|---:|---|
| Bulk load, WAL + FULL, tmpfs (s) | 6.26 ± 0.55 | 5.32 ± 0.79 | tie |
| Point reads, 4 readers + 1 writer, disk (reads/s) | 688k ± 63k | 747k ± 29k | tie |
| Writer commits during point reads (/s) | 774 ± 102 | 764 ± 96 | tie |
| Range reads (100 rows), 4 readers + 1 writer, disk (reads/s) | 48.8k ± 14.4k | 38.7k ± 8.8k | tie |
| Writer commits during range reads (/s) | 549 ± 165 | 552 ± 118 | tie |

The writer is fsync-bound in both (WAL + FULL on NVMe). The disk-backed reads are noisy because the 700 MB copy
competes for page cache on this machine.

### Start-up and footprint

Fresh process → open `:memory:` → `SELECT sqlite_version()` → exit; median of 10 runs.

| | Time | Max RSS |
|---|---:|---:|
| ffm-sqlite, default JVM flags | 220 ms | 82 MB |
| ffm-sqlite, `-XX:+UseSerialGC -XX:TieredStopAtLevel=1` | 140 ms | 69 MB |
| rusqlite | < 10 ms | 3.8 MB (0.7 MB binary) |

## Features

| Feature | ffm-sqlite | rusqlite 0.41 |
|---|---|---|
| Prepared statements, typed binding and getters | ✓ | ✓ |
| Automatic statement cache | ✓ (64, always on) | opt-in `prepare_cached` (16) |
| Transactions | JDBC-style `setAutoCommit` / `commit` / `rollback` | RAII `Transaction` (rolls back on drop), savepoints |
| Scalar, aggregate, window functions | ✓ | ✓ (`functions`, `window`) |
| Collations | ✓ | ✓ (`collation`) |
| Backup / restore, serialize / deserialize | ✓ | ✓ (`backup`, `serialize`) |
| Update / commit / rollback hooks, busy and progress handlers | ✓ | ✓ (`hooks`) |
| Query timeout per statement | ✓ | – (build one with a progress handler) |
| Cancel from another thread | `Statement.cancel` / `Connection.interrupt` | `InterruptHandle` |
| Extension loading | ✓ (C API, enabled only during the call) | ✓ (`load_extension`, `unsafe`) |
| Schema metadata API | ✓ `DatabaseMetaData` | column metadata, `pragma_*` helpers |
| Dates, decimals, UUIDs | built in: java.time, `BigDecimal`, `UUID` (as text) | optional: chrono / time / jiff, uuid (as BLOB), serde_json, url; no built-in decimal |
| Incremental BLOB I/O, virtual tables, tracing, unlock-notify, limits | – | ✓ |
| Bundled SQLite / SQLCipher | – (system library or a path) | ✓ (`bundled`, `bundled-sqlcipher`) |
| Standard data-access API | `java.sql` driver (`ffm-sqlite-jdbc`): Spring, Hikari, Flyway, jOOQ | – (async alternatives: sqlx, diesel) |

## Safety model

Both wrap unsafe native code in a safe API, but they enforce the rules differently.

| Misuse | ffm-sqlite | rusqlite |
|---|---|---|
| Statement used after its connection is closed | runtime check: exception | **compile error**: a `Statement` borrows its `Connection` |
| Connection or running statement closed from inside its own UDF/collation | runtime check: exception (since the security review) | **not expressible**: callbacks are `'static`, and `close` consumes the connection |
| Connection shared between threads | documented single-thread rule; the JDBC driver locks | **compile error**: `Connection` is `Send`, not `Sync`; `Statement` is neither |
| Exception or panic in a callback | caught (`Throwable`) and turned into an SQL error | caught (`catch_unwind`) and turned into an SQL error |
| Values outside the type's range | checked: SQLITE_MISMATCH | checked: `FromSqlError::OutOfRange` |

Rust's borrow checker rules out, at compile time, the classes of use-after-free that the ffm-sqlite reviews found
and fixed with runtime checks. Writing these benchmarks showed it directly: moving a prepared statement to another
thread was a compile error. ffm-sqlite's defence is checks in the library: they work, but each new path needs its own.

## When to choose which

- **ffm-sqlite:** JVM applications. Same database speed as Rust, a JDBC driver for the Java ecosystem, built-in
  java.time, BigDecimal and query timeouts, and automatic statement caching.
- **rusqlite:** Rust applications. Also where start-up time and memory matter (CLIs, serverless, embedded), where
  UDFs or collations are on the hot path, and where you need what ffm-sqlite lacks (virtual tables, incremental BLOB
  I/O, bundled SQLite or SQLCipher).

## Reproduce

```bash
(cd ../ffm-vs-jdbc && mvn package)     # JMH benchmarks.jar; data/wide-1m.db is built on first use
./run.sh                                # all suites, ~25 min; or e.g. ./run.sh workload udf
python3 report.py                       # results/report.md
```
