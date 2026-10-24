# Performance: ffm-sqlite and ffm-sqlite-jdbc vs sqlite-jdbc

Three ways to use SQLite from Java, measured with JMH on the same SQLite engine:

- **sqlite-jdbc** 3.50.3.0 (JNI; bundled SQLite 3.50.3);
- **ffm-sqlite**, the FFM library's own JDBC-like API;
- **ffm-sqlite-jdbc**, the `java.sql` driver on top of ffm-sqlite. It runs the *identical* JDBC benchmark code as
  sqlite-jdbc, with only the URL prefix changed.

## Headline

| | ffm-sqlite vs sqlite-jdbc | ffm-sqlite-jdbc vs sqlite-jdbc |
|---|---:|---:|
| Application workloads (point lookups, range scans, joins, inserts, updates, transactions) | **1.6×** faster (geo-mean, 9 workloads) | **1.8×** |
| Concurrent reads, 1/4/8 threads | **1.6×** more throughput | **1.5×** |
| Per data type, read and write (11 types) | **2.6×** | **2.6×** |
| Bulk rows, 1,000 × (int, text, int) | 1.7× (batch) – 3.8× (read) | – |
| User-defined functions and collations | 4.0–4.7× per call (window functions 2.0×) | via `unwrap` |
| Hooks (update listener) | 2.7× | via `unwrap` |
| Schema, PRAGMA and metadata queries | 1.8–3.6× | – |
| Disk-, engine- or memcpy-bound work (create DB, build index, backup, serialize, aggregates, 1 MB BLOB reads) | tie | – |
| Cold start (fresh JVM to first result) | tie (151 vs 152 ms) | – |

- **No benchmark is slower** than sqlite-jdbc beyond noise, after the two fixes below.
- **The JDBC wrapper costs about nothing on top of ffm-sqlite:** −11% to +8% by category geo-mean, which is within
  noise. The exception is java.time reads (+24–35%), which are still about 22× faster than sqlite-jdbc's.

## Method

- **Machine:** i7-14700HX, Linux 7.0, OpenJDK 25.0.4, SQLite 3.50.3 for both. The FFM side uses a library built
  with sqlite-jdbc's compile options (`scripts/build-sqlite.sh`); that build is about 15–20% faster on pure engine
  work (gcc 13 vs sqlite-jdbc's gcc 4.1.2), which matters only for engine-bound rows.
- **JMH settings:** 2 forks, 3 × 1 s warmup, 5 × 1 s measurement, `-prof gc`. Benchmark JVMs were pinned to the
  performance cores (`taskset -c 2-15`).
- **Machine load:** a QEMU VM (about 2.5 cores busy), Chrome and k3s were running throughout. Error bars are wider
  than on a quiet machine.
- **File-database writes ran on tmpfs.** On the NVMe disk, both libraries showed multi-second write stalls during
  WAL checkpoints (e.g. 11 µs inserts spiking to 290–1,600 µs, in bursts across consecutive iterations). With the
  database on `/dev/shm` the outliers disappeared for both (0/20), confirming the disk, not the libraries.
  Disk-backed numbers are in `results/full.txt` and `results/writes-rerun.json`.
- **"Win" means non-overlapping 99.9% confidence intervals;** otherwise the result is reported as a tie.

## Three-way results

### Application workloads (file database, WAL, synchronous=NORMAL, on tmpfs). µs per operation

| Workload | sqlite-jdbc | ffm-sqlite | ffm-sqlite-jdbc |
|---|---:|---:|---:|
| Point select by primary key | 4.68 | 3.38 | 2.61 |
| Point select by indexed email | 7.23 | 4.10 | 4.79 |
| Insert, autocommit | 5.10 | 4.18 | 4.76 |
| Update, autocommit | 8.53 | 7.11 | 5.81 |
| Business transaction (read, insert, update, commit) | 18.5 | 13.8 | 12.6 |
| Range scan, 100 rows | 107 | 50.8 | 39.7 |
| Join + GROUP BY, 50 users | 178 | 116 | 74.2 |
| `prepareStatement` inside the loop | 10.4 | 3.14 | 2.96 |
| Open, query, close (no pool) | 66.2 | 46.2 | 43.4 |

`prepareStatement` inside the loop is the common unpooled pattern. ffm-sqlite's statement cache makes it 3.3–3.5×
faster, through either API.

### Concurrent reads: operations per ms, total

| Threads | sqlite-jdbc | ffm-sqlite | ffm-sqlite-jdbc |
|---|---:|---:|---:|
| 1 | 273 | 518 | 478 |
| 4 | 833 | 1,447 | 1,112 |
| 8 | 785 | 1,006 | 1,131 |

### Per data type: ns per row (1,000 rows)

| Type | Read: sqlite-jdbc / ffm-sqlite / wrapper | Write: sqlite-jdbc / ffm-sqlite / wrapper |
|---|---|---|
| INTEGER | 130 / 53 / 64 | 429 / 244 / 235 |
| TEXT | 264 / 89 / 106 | 628 / 299 / 269 |
| BLOB (16 B) | 176 / 87 / 96 | 586 / 316 / 259 |
| BigDecimal | 336 / 122 / 144 | 559 / 350 / 318 |
| LocalDate | 2,635 / 88 / 118 ¹ | 587 / 352 / 353 |
| LocalDateTime | 3,108 / 108 / 135 ¹ | 617 / 355 / 311 |
| UUID | 313 / 101 / 102 | 545 / 290 / 285 |

¹ Re-measured after the storage-class fix (`results/followup.json`). All rows: `results/three-way.md`.

### Large values: µs per operation (after the `setBytes` fix)

| BLOB size | Write: sqlite-jdbc / ffm-sqlite | Read: sqlite-jdbc / ffm-sqlite |
|---|---|---|
| 1 KB | 1.42 / **0.61** | 0.78 / **0.27** |
| 64 KB | 9.83 / **7.49** | 8.11 / 7.52 (tie) |
| 1 MB | 229 / **193** | 142 / 155 (tie) |

## WAL + synchronous=FULL on disk: ffm-sqlite-jdbc vs sqlite-jdbc

The same JDBC workload code, both drivers, on the NVMe disk (FULL means an fsync on every commit, so tmpfs would
be meaningless here). 3 forks × 5 iterations; µs per operation (`results/wal-full.json`, `results/wal-full-update.json`).

| Workload | sqlite-jdbc (mean / median) | ffm-sqlite-jdbc (mean / median) | Result |
|---|---:|---:|---|
| Point select by primary key | 4.3 / 4.0 | 3.0 / 3.0 | wrapper 1.4× |
| Point select by indexed email | 5.1 / 4.6 | 3.4 / 3.2 | wrapper 1.5× |
| Range scan, 100 rows | 76.4 / 75.1 | 47.4 / 50.3 | wrapper 1.6× |
| Join + GROUP BY, 50 users | 110 / 106 | 84.7 / 86.2 | wrapper 1.3× |
| `prepareStatement` inside the loop | 10.0 / 9.8 | 2.5 / 2.4 | wrapper 4.0× |
| Open, query, close | 63.1 / 61.4 | 43.8 / 41.7 | wrapper 1.4× |
| Insert, autocommit | 1,141 / 1,138 | 1,132 / 1,134 | tie (fsync-bound) |
| Update, autocommit ¹ | 1,368 / 1,236 | 1,308 / 1,207 | tie (fsync-bound) |
| Business transaction (read, insert, update, commit) | 1,175 / 1,178 | 1,264 / 1,220 | tie (fsync-bound) |

- **Writes cost ~1.1–1.2 ms per commit in both:** the fsync. The driver's ~1–5 µs is under 1% of that, so the
  drivers tie.
- **Reads are unaffected by `synchronous`** and keep the wrapper's 1.3–1.6× advantage (4× with the statement cache).
- The wrapper allocates about half as much per operation (e.g. 280 vs 965 B per point select).

¹ The first run had one wrapper fork hit a disk-stall burst (3–11 ms for 5 consecutive iterations; its other forks
ran steadily at ~1.16 ms). A 4-fork rerun of this benchmark alone showed occasional spikes in both drivers and
equal medians; the rerun's numbers are shown.

## 1M rows × 20 columns: bulk load, then 1 writer + N readers (WAL + synchronous=FULL)

`WideRows` table: 20 columns of mixed width (1/2/4/8-byte integers, boolean, REAL, texts of 8–128 bytes,
ISO date-time, date, UUID, decimal text, 32-byte hash BLOB, 100–300-byte payload BLOB; ~600 bytes/row,
plus an index on `email`). The database file is 704 MB at 1M rows. Values come from a precomputed
deterministic pool, so all libraries bind identical data. Pinned to cores 2-15; 2 forks.

### Bulk load of 1,000,000 rows (`BulkLoadBenchmark`). Seconds, lower is better

One prepared INSERT, `executeUpdate` per row, commit every 10,000 rows; fresh WAL + FULL file each run.

| | sqlite-jdbc | ffm-sqlite | ffm-sqlite-jdbc |
|---|---|---|---|
| disk | 17.6 ± 13.6 | 19.5 ± 24.5 | 21.8 ± 45.3 |
| tmpfs (library cost only) | 9.56 ± 3.7 | **6.82 ± 1.2** | 7.51 ± 1.3 |

On disk the result is decided by fsync and VM-induced disk stalls (error bars as large as the scores): no
significant difference. With I/O removed (tmpfs), ffm-sqlite loads 29% faster and the wrapper 21% faster.

### Concurrent: 1 writer + N readers (`WideConcurrentBenchmark`, on disk). Operations per second

Every thread has its own connection to a fresh copy of the 1M-row database. The writer inserts full
20-column rows in autocommit (one fsync per commit). `point` readers fetch a random row by id and read all 20
columns through typed getters; `range` readers scan 100 consecutive rows (id, name, price, created, payload).

| Readers | Workload | sqlite-jdbc read | ffm-sqlite read | ffm-sqlite-jdbc read | writer jdbc / ffm / wrapper |
|---|---|---|---|---|---|
| 1 | point | 97k | **272k (2.8×)** | 240k (2.5×) | 291 / 812 / 652 |
| 4 | point | 283k | **811k (2.9×)** | 768k (2.7×) | 686 / 746 / 742 |
| 8 | point | 287k | **759k (2.6×)** | 703k (2.4×) | 572 / 614 / 710 |
| 1 | range | 9.6k | **20.7k (2.2×)** | 16.3k (1.7×) | 749 / 794 / 321 |
| 4 | range | 27.9k | **64.7k (2.3×)** | 58.3k (2.1×) | 405 / 560 / 606 |
| 8 | range | 25.4k | **60.6k (2.4×)** | 54.8k (2.2×) | 335 / 433 / 411 |

- Reads: per-column cost dominates a 20-column row (sqlite-jdbc makes one JNI call per getter), so the
  FFM gap is larger than in the 4-column concurrent-read test. WAL readers never block each other or the
  writer; throughput scales to 4 readers and flattens at 8 (the VM shares the machine).
- Writer: bounded by fsync (~600–800 commits/s alone). It slows as readers add load (checkpoints cannot
  finish while readers hold old snapshots, and range readers stream more pages). Differences between
  libraries are within disk noise; the 291 ± 354 and 321 ± 405 cells are stall outliers.

## Fixes found by this comparison

1. **`setBytes` made a defensive copy.** 64 KB–1 MB BLOB writes were 16–35% slower and allocated the full blob
   per write. The library now binds the caller's array by reference, as sqlite-jdbc does (SQLite copies it at
   execution). It now allocates 0 B per write and is faster at every size.
2. **The wrapper's `getObject(i, LocalDate.class)` read every value twice:** once to learn its storage class,
   once to convert it. A new `ResultSet.getStorageClass(int)` (`sqlite3_column_type`) removed the first read, cutting
   the wrapper's java.time read cost from +73–75% to +24–35% over direct ffm-sqlite.

## The rest of the earlier full run

From `results/report.md`: 150 benchmarks, the same settings, on disk. Highlights not covered above:

- **Schema, PRAGMAs, functions:** PRAGMA reads 2.2×, `table_info` 3.6×, schema listing 2.1×, scalar functions over
  1,000 rows 2.0×, JSON 1.5×. Table create/alter 1.3×. Index builds, aggregates, backup, restore, serialize and
  deserialize are ties (engine or I/O).
- **UDFs:** scalar 4.1–4.7×, aggregate 4.6×, collation 4.0–4.5×, window 2.0×. The built-in baselines differ by
  1.2–1.4× from the engine build alone.
- **Hooks:** inserts with an update listener 2.7× (1.9× without).
- **sqlite-jdbc's default configuration** (`jdbc.get_generated_keys=true`) runs an extra statement per INSERT:
  2,496 ns per row with `executeUpdate`, against 639 with the option off and 306 for ffm-sqlite.

## Reproduce

```bash
(cd ../ffm-sqlite && mvn install) && (cd ../ffm-sqlite-jdbc && mvn install) && mvn package
L=$PWD/native/libsqlite3.so
taskset -c 2-15 java -jar target/benchmarks.jar 'WorkloadBenchmark|TypeBenchmark|ConcurrentReadBenchmark' \
  -f 2 -wi 3 -i 5 -prof gc -rf json -rff results/three-way.json \
  -jvmArgsAppend "-Dsqlite.lib=$L -Dffmsqlite.lib=$L -Djava.io.tmpdir=/dev/shm"
python3 scripts/report3.py results/three-way.json
python3 scripts/report.py results/full.json results/coldstart.json
```

1M-row tests (the template database is built once into `data/`, git-ignored):

```bash
taskset -c 2-15 java -jar target/benchmarks.jar BulkLoadBenchmark -jvmArgsAppend "-Dsqlite.lib=$L -Dffmsqlite.lib=$L -Dwide.dir=$PWD/data"
for n in 1 4 8; do   # -tg is readers,writer: JMH orders group methods by name
  taskset -c 2-15 java -jar target/benchmarks.jar WideConcurrentBenchmark -tg $n,1 -jvmArgsAppend "-Dsqlite.lib=$L -Dffmsqlite.lib=$L -Dwide.dir=$PWD/data"
done
```
