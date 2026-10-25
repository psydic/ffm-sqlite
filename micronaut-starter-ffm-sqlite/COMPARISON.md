# micronaut-starter-ffm-sqlite vs Micronaut's own SQLite support

Micronaut has no single "SQLite starter". Its SQLite support is a combination of modules, and choosing **SQLite**
plus **Data JDBC** in Micronaut Launch adds all of them:

| Piece | Artifact | What it contributes to SQLite |
|---|---|---|
| Driver | `org.xerial:sqlite-jdbc` 3.53.4.0 (pulled in by `micronaut-jdbc-sqlite`) | the JDBC driver, a 21.7 MB jar bundling a JNI build of SQLite for many OS/CPU pairs, plus GraalVM native-image metadata |
| SQLite module | `io.micronaut.sql:micronaut-jdbc-sqlite` 7.2.0 | one class, `SqliteConnectionCapabilities`: tells Micronaut Data that SQLite connections cannot be made read-only |
| Configuration | `io.micronaut.sql:micronaut-jdbc` 7.2.0 | `datasources.<name>.*` settings; with no URL and the xerial driver, a default `jdbc:sqlite:file:<name>?mode=memory&cache=shared&foreign_keys=on&busy_timeout=5000` |
| Pool | `io.micronaut.sql:micronaut-jdbc-hikari` 7.2.0 | HikariCP from `datasources.<name>.*` |
| Queries | `io.micronaut.data:micronaut-data-jdbc` 5.2.3 | `Dialect.SQLITE` (since Micronaut Data 5.1), including `schema-generate` |
| Health | `io.micronaut:micronaut-management` | the generic `jdbc` indicator (a `Connection.isValid` check per DataSource) |

micronaut-starter-ffm-sqlite replaces the first four rows: the ffm-sqlite-jdbc driver, its own configuration,
and HikariCP. It keeps Micronaut Data and management. Everything below was measured with Micronaut platform
5.2.3 on the same machine, using the same scenarios and the same repository code on each stack. The harness is in
[`comparison/`](comparison). Each stack ran with the system SQLite 3.45.1, except xerial, which runs its own
bundled SQLite 3.53.

## Interface

### Dependencies

| | Official (Launch: `data-jdbc`, `sqlite`) | micronaut-starter-ffm-sqlite |
|---|---|---|
| Dependencies to add | `micronaut-data-jdbc`, `micronaut-jdbc-hikari`, `micronaut-jdbc-sqlite` | `micronaut-starter-ffm-sqlite` (+ `micronaut-data-jdbc` for repositories) |
| Native SQLite | bundled in the driver jar (JNI) | the system `libsqlite3`, or `-Dffmsqlite.lib=…` (FFM, no JNI) |
| JVM flags | none | `--enable-native-access=ALL-UNNAMED` (set in the jar manifests for `java -jar`) |
| Java | 25+ with Micronaut 5 (its own floor); the driver itself runs on 8+ | 25+ |
| GraalVM native image | supported by the driver | not supported |

### Configuration

| | Official | Starter |
|---|---|---|
| Namespace | `datasources.<name>.*` (shared with every JDBC database) | `ffm-sqlite.datasources.<name>.*`; Micronaut Data still reads `datasources.<name>.dialect` |
| Database location | `url: jdbc:sqlite:app.db?…` | `path: app.db` |
| PRAGMAs | URL parameters, in xerial's syntax; the docs show `foreign_keys=on&busy_timeout=5000&journal_mode=WAL&synchronous` | typed settings (`journal-mode`, `synchronous`, `foreign-keys`, `busy-timeout`, `read-only`, `statement-cache-size`) plus a `pragmas:` map for any other PRAGMA |
| Defaults with only a file name | SQLite's own: rollback journal, `synchronous=FULL`, foreign keys **off**, busy timeout 3 s (xerial's) | WAL, `synchronous=NORMAL`, foreign keys on, busy timeout 5 s |
| Defaults with nothing set | an in-memory database (shared cache) | startup fails: `path is required` |
| Pool settings | Hikari's names under `datasources.<name>` (`maximum-pool-size`, …) | the same names under `ffm-sqlite.datasources.<name>`; a fixed pool, and connections kept for the pool's life (`max-lifetime: 0`) |
| Validation | Hikari's, at pool start | every setting checked at startup; the error names the property (`…busy-timeout must be between …`) |
| Missing parent directory | fails to open | created (`create-directories: true`) |

### Beans

| | Official | Starter |
|---|---|---|
| `DataSource` per name | `HikariUrlDataSource` | `HikariDataSource` |
| SQLite-specific bean | none | `FfmSqliteDatabase`: the pool, the effective settings, `withConnection(conn -> …)` for the ffm-sqlite API (UDFs, collations, window functions, backup, serialize, hooks), `openConnection()` |
| Health | `jdbc`: UP/DOWN per URL | `ffm-sqlite`: UP/DOWN, journal mode, SQLite version and pool usage per database, off the event loop, skipped (not queued) when the pool is saturated; Micronaut's `jdbc` indicator also appears when Micronaut Data is present |

### Beyond JDBC

With the official stack, xerial's own API (`org.sqlite.SQLiteConnection`, `org.sqlite.Function`) is available
through `unwrap`; there is no Micronaut bean for it. The starter's `FfmSqliteDatabase.withConnection` hands out a
pooled ffm-sqlite `Connection`, and closes, rolls back or evicts as needed afterwards.

## Behaviour (measured)

Rows are scenarios, run by [`comparison/src/test/java/cmp/Scenarios.java`](comparison/src/test/java/cmp/Scenarios.java).

| Scenario | Official, Launch defaults (no URL) | Official, file + documented URL | Official stack + ffm-sqlite-jdbc driver | Starter, file (defaults) | Starter, `:memory:` |
|---|---|---|---|---|---|
| Effective journal_mode, synchronous, foreign_keys, busy_timeout | memory, FULL, on, 5000 | wal, NORMAL, on, 5000 | wal, NORMAL, on, 5000 | wal, NORMAL, on, 5000 | memory, NORMAL, on, (configured) |
| `schema-generate: CREATE_DROP` with `Dialect.SQLITE` | ✓ | ✓ | ✓ | ✓ | ✓ |
| CRUD round trip of `BigDecimal`, `LocalDate`, `UUID`, `Instant` | ✓ exact | ✓ exact | ✓ exact | ✓ exact | ✓ exact |
| Derived finder, `@Transactional` rollback | ✓ | ✓ | ✓ | ✓ | ✓ |
| A write inside `@Transactional(readOnly = true)` | **allowed** | **allowed** | **allowed** | rejected ("attempt to write a readonly database") | rejected |
| A read while another pooled connection has an open write | **fails at once**: `SQLITE_LOCKED_SHAREDCACHE` | ✓ immediate (WAL) | ✓ immediate (WAL) | ✓ immediate (WAL) | **fails** after `busy-timeout` (no WAL; "no such table" when its schema is stale) |
| 8 threads × 200 autocommit inserts, pool of 10 | – | 1600 rows, 0 failed | 1600 rows, 0 failed | 1600 rows, 0 failed | – |
| Two application contexts in one JVM | **share one in-memory database** | separate files | separate files | separate files | separate databases |

What the table shows:

1. **The repository layer is identical.** Micronaut Data with `Dialect.SQLITE` generated the same DDL and the
   same SQL on every stack, and every type round-tripped exactly. The driver underneath makes no difference to
   repository code.
2. **ffm-sqlite-jdbc is a drop-in driver for the official stack.** With only the URL and `driver-class-name`
   changed, every scenario behaved as it did with xerial. An application can switch drivers without the
   starter.
3. **Read-only transactions.** The official stack never enforces them. `micronaut-jdbc-sqlite`'s capability
   class makes Micronaut Data skip `setReadOnly` for any connection whose product name is "SQLite", ours
   included. Without that module (the starter does not use it), Micronaut Data calls `setReadOnly(true)`,
   ffm-sqlite-jdbc rejects writing statements while it is set (0.2.3 used `PRAGMA query_only`; 0.2.4 checks each
   statement), and the write fails. Verified both ways: adding
   `micronaut-jdbc-sqlite` to a starter application turns enforcement off.
4. **In-memory defaults have sharp edges on both sides.** The official default uses SQLite's shared cache:
   table-level locks that ignore the busy timeout, so concurrent read/write fails immediately, and a cache name
   (`default`) shared by every context in the JVM. The starter's memdb database is private to its context, but
   without WAL a reader still waits for the writer and then fails. Use a file (on tmpfs if needed) when reads and
   writes overlap.
5. **File databases need configuration on the official stack.** Its documented URL brings it to the same
   settings the starter uses by default. A bare `jdbc:sqlite:app.db` gets SQLite's defaults: rollback journal
   (readers and the writer block each other) and foreign keys off.
6. **`BigDecimal` is stored as REAL** on every stack, because the generated `DECIMAL` column has numeric
   affinity. Values beyond about 15 significant digits lose precision. Store integer cents or use a TEXT column.

## Performance: a 5-minute load test

> Measured with 0.2.3, before the `setReadOnly` fix in 0.2.4. Its reads were slowed by 6–8 µs each (see the next
> section), so the starter's margin here is understated.

Both stacks ran the same workload through the same Micronaut Data repositories
([`PerfWorkload.java`](comparison/src/test/java/cmp/PerfWorkload.java)):

- **Data and load:** 100k seeded rows, 8 client threads, a pool of 10.
- **Mix:** 70% `findById`, 10% indexed range (20 rows), 15% insert, 5% write-first transaction (update, then read
  back).
- **Settings:** WAL, `synchronous=NORMAL`, foreign keys on, busy timeout 5 s, database on NVMe.
- **Timing:** 30 s warmup, then 300 s measured, in a separate JVM per stack (`-Xms512m -Xmx512m`), pinned to CPUs
  2–15.
- **SQLite:** the same build on both: xerial 3.50.3.0 on the official stack, and on the starter the identical
  build from [`ffm-vs-jdbc/scripts/build-sqlite-gcc4.sh`](../ffm-vs-jdbc/scripts/build-sqlite-gcc4.sh). Both report SQLite 3.50.3,
  source id `2025-07-17 13:25:10 3ce993b865`.

| | Official (Hikari + `micronaut-jdbc-sqlite` + xerial) | Starter (ffm-sqlite-jdbc) | Starter vs official |
|---|---:|---:|---:|
| **Throughput, all operations** | 19,544 ops/s | 23,445 ops/s | **+20%** |
| CPU per operation | 83.7 µs | 64.4 µs | **−23%** |
| `findById`: ops/s, p50 / p99 / p99.9 | 13,680: 36 / 95 / 137 µs | 16,407: 29 / 79 / 115 µs | +20%, −19% p50 |
| range (20 rows): ops/s, p50 / p99 / p99.9 | 1,955: 376 / 841 / 1,082 µs | 2,346: 288 / 584 / 719 µs | +20%, −23% p50 |
| insert: ops/s, p50 / p99 / p99.9 | 2,934: 51 / 18,397 / 129,530 µs | 3,521: 37 / 13,730 / 104,257 µs | +20%, −27% p50 |
| write-first transaction: ops/s, p50 / p99 / p99.9 | 976: 98 / 18,526 / 179,234 µs | 1,172: 75 / 15,858 / 104,219 µs | +20%, −23% p50 |
| All operations: p50 / p99 / p99.9 | 42 / 3,263 / 33,412 µs | 33 / 3,201 / 18,757 µs | |
| Errors | 4 (inserts) | 0 | |
| GC in 300 s | 259 collections, 190 ms | 213 collections, 143 ms | |

Raw output: [`comparison/results/perf-5min.txt`](comparison/results/perf-5min.txt).

What the numbers say:

- **Reads:** about 20% higher throughput and 19–23% lower median latency. The Micronaut and pool code is the same
  on both stacks, so the gain is the driver's: FFM downcalls instead of JNI, and fewer allocations (fewer GCs).
  That matches the direct driver benchmarks in [../ffm-vs-jdbc/PERFORMANCE.md](../ffm-vs-jdbc/PERFORMANCE.md).
- **Writes:** throughput is bound by SQLite's single writer and by fsync at checkpoints. The medians differ by
  driver overhead; the tails (p99 of 14–18 ms, p99.9 of 100–180 ms, a few stalls over 200 ms on both) are WAL
  checkpoints and disk flushes, and are SQLite's, not the driver's.
- **Errors:** the official run had 4 failed inserts out of 880k. The harness counts failures but does not keep
  their messages, so the cause wasn't captured. A write waiting past the 5 s busy timeout during a checkpoint
  stall is the likely one.

Caveats:

- **One run per stack**, not repeated, so there are no confidence intervals.
- **Background load:** the machine was also running a VM (about 2.5 cores), and the load average rose from 6.9 to
  12 during the test. The starter ran second, under the higher load, so if anything the noise was against it.
  The same workload's 5-second smoke run gave a similar ratio (+23%).
- **Rerunning:** `mvn test -Dtest=PerfOfficialTest -Dperf.seconds=300 -Dsqlite-jdbc.version=3.50.3.0` and
  `mvn test -Dstarter -Dtest=PerfStarterTest -Dperf.seconds=300 -Dffmsqlite.lib=…/libsqlite3-gcc4.so` in
  `comparison/`.

A read-then-write transaction (find, then update) is a trap on any SQLite stack. Under concurrent writers, about
half of them failed at once with `SQLITE_BUSY` on both stacks in a trial run: SQLite will not upgrade a read
lock to a write lock while another connection commits. Write first (as above), or retry.

## Performance: reads on 1M rows × 20 columns (WAL + synchronous=FULL)

Read-only, through Micronaut Data repositories mapping all 20 columns
([`WideReadBench.java`](comparison/src/test/java/cmp/WideReadBench.java)):

- **Data:** the table of the driver benchmarks (`ffm-vs-jdbc/data/wide-1m.db`). It has mixed-width integers,
  REAL, texts of 8–128 bytes, date and UUID text, a decimal and two BLOBs; about 600 bytes per row, 704 MB.
  Each stack read its own copy on NVMe, in WAL mode with `synchronous=FULL`.
- **Reads:** a point read by random id, and a range of 100 consecutive rows (`findByIdBetweenOrderById`).
- **Threads and timing:** 1–16 threads, a pool of 16, 10 s warmup and 30 s measured per thread count, pinned to
  18 CPUs.
- **SQLite:** 3.50.3, the same build on both stacks.
- **Errors:** none on any stack.

Results for 0.2.4, with both stacks run back to back in one session. Each cell is reads/s, then mean / p99
latency in µs.

Point read by id:

| Threads | Official | Starter 0.2.4 | Starter vs official | Official mean / p99 | Starter mean / p99 | (Starter 0.2.3 ¹) |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 29,816 | 97,524 | **3.27×** | 33.0 / 40 | 9.7 / 14 | 1.31× |
| 2 | 51,967 | 161,261 | **3.10×** | 37.9 / 59 | 11.9 / 22 | 1.44× |
| 4 | 82,132 | 280,349 | **3.41×** | 48.2 / 80 | 13.7 / 28 | 1.27× |
| 8 | 110,704 | 331,606 | **3.00×** | 71.7 / 121 | 23.6 / 44 | 1.32× |
| 16 | 120,323 | 380,639 | **3.16×** | 132.4 / 169 | 41.4 / 59 | 1.35× |

Range of 100 rows:

| Threads | Official | Starter 0.2.4 | Starter vs official | Official mean / p99 | Starter mean / p99 | (Starter 0.2.3 ¹) |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1,879 | 4,157 | **2.21×** | 532 / 775 | 240 / 306 | 1.93× |
| 2 | 3,508 | 7,330 | **2.09×** | 570 / 869 | 272 / 422 | 2.08× |
| 4 | 5,042 | 11,272 | **2.24×** | 793 / 1,475 | 354 / 635 | 2.01× |
| 8 | 7,267 | 14,772 | **2.03×** | 1,100 / 2,218 | 541 / 1,015 | 2.05× |
| 16 | 7,305 | 14,875 | **2.04×** | 2,189 / 5,447 | 1,075 / 4,088 | 1.70× |

¹ From the earlier session (raw output in `wide-read-1m-0.2.3.txt`). On 0.2.3, Micronaut Data's
`setReadOnly(true/false)` around every read became `PRAGMA query_only`. Changing that PRAGMA makes SQLite expire
every prepared statement, so each read compiled its query again, adding 6–8 µs. 0.2.4 checks read-only per
statement instead, which keeps the statement cache and still rejects writes in read-only transactions.

- **Point reads:** 3.0–3.4× the official stack's throughput at a third of the latency (9.7 vs 33 µs mean at one
  thread). This is close to the gap between the drivers themselves, because Micronaut Data's own work per call is
  now small next to the official stack's driver cost.
- **Range reads:** 2.0–2.2× at every thread count. Decoding 100 rows × 20 columns per call is driver work.
- **Scaling:** both stacks flatten past 8 threads, from CPU limits rather than SQLite locks: readers never block
  each other in WAL. During the runs the machine also carried a VM, and the load average reached 18–19 on 28
  cores, so the 16-thread figures are the least reliable. Each figure is a single run.
- **Raw output:** [`comparison/results/wide-read-1m-0.2.4.txt`](comparison/results/wide-read-1m-0.2.4.txt).

## Which to choose

- **The official stack** if you need GraalVM native image, must stay on Micronaut 4 (the starter targets
  Micronaut 5), or want a driver that bundles SQLite (no system library to install). Set the file URL's PRAGMAs yourself, and avoid the in-memory default for anything
  concurrent.
- **The starter** for 2–3× faster reads (1M-row test, 0.2.4), about 20% more throughput in the mixed load test (measured on 0.2.3), safe defaults with only a path, read-only transactions that are enforced, startup
  validation with clear messages, an SQLite-aware `/health`, and the ffm-sqlite API (UDFs, collations, backup,
  serialize, hooks) on pooled connections.
- **Official stack + ffm-sqlite-jdbc** (just change the URL and driver class) if you want the FFM driver but
  prefer Micronaut's standard `datasources.*` configuration.

## Reproduce

```bash
cd micronaut-starter-ffm-sqlite/comparison
mvn test                   # official stack: xerial (Launch defaults, documented file URL) and ffm-sqlite-jdbc
mvn test -Dstarter         # the starter: :memory: and a file
# results: the lines starting with RESULT in the output
```

Sources: Micronaut Launch's `sqlite` feature (generated project, Micronaut 5.2.3),
[Micronaut SQL guide, SQLite section](https://micronaut-projects.github.io/micronaut-sql/latest/guide/#jdbc-sqlite),
[Micronaut 5.1.0 release notes](https://micronaut.io/2026/07/27/micronaut-framework-5-1-0-release/) (SQLite dialect
in Micronaut Data), and the module jars (`SqliteConnectionCapabilities`, `CalculatedSettings`).
