# Profiling the shop under load: findings

> Measured on shop 0.1.0, the version before the Micronaut port (plain JDK HTTP server and two hand-built HikariCP
> pools; the database code was the same). `run.sh` profiles the current version.

The sample shop (ffm-sqlite-jdbc 0.2.1 on SQLite 3.45.1) ran in a container under sustained HTTP load. It was
profiled with async-profiler 4.1 (CPU, Java allocation, lock contention and native memory, with leak tracking),
JVM Native Memory Tracking and `/proc` memory maps. The focus was native memory.

**Summary:** no native memory leak, either in SQLite or in the FFM layer. Three abnormal behaviours, all caused by
how the application uses SQLite and the JDK HTTP server, not by the library:

| # | Finding | Severity | Evidence |
|---|---|---|---|
| 1 | **WAL checkpoint starvation:** the write-ahead log grows to gigabytes and the file never shrinks | High (disk, slower reads) | WAL 2.35 GB for an 18 MB database; 1.67 GB within 96 s in a 3-minute run; bounded at ≤142 MB with periodic TRUNCATE checkpoints |
| 2 | **Reports that rescan everything:** two report queries scan and sort all orders on every call | High (throughput collapse, native malloc churn) | 68% of CPU; 76% of 207 MB/s native malloc traffic; throughput 6,700 → 1,200 req/s in 3 minutes as orders accumulate |
| 3 | **Lock contention in the JDK HTTP server** (`sun.net.httpserver.ServerImpl.addEvent`) | Medium (demo server only) | 1,148 s of monitor contention in 480 s, 99.8% of all contention |

## Setup

- **Container:** `eclipse-temurin:25-jdk-noble` (JDK 25.0.4.1) with the system `libsqlite3` 3.45.1 and
  async-profiler 4.1, limited to 4 CPUs and 1 GB. The JVM ran with `-Xms256m -Xmx256m
  -XX:NativeMemoryTracking=detail`. With 4 CPUs the shop opens 1 writer and 2 reader connections. See
  [`Containerfile`](Containerfile).
- **Load:** [`LoadGen.java`](LoadGen.java) with 32 concurrent clients, as a mix of:
  - 35% product lists, 15% product pages, 15% searches;
  - 20% create a cart, add 1–3 items and check out;
  - 5% cancels, 5% order history, 5% reports;
  - plus an online backup every 30 s.

  Stock was raised so that checkouts keep succeeding.
- **Run** ([`run.sh`](run.sh)):
  1. 60 s warmup.
  2. 480 s under load, with async-profiler attached (`-e itimer --alloc 512k --lock 1ms --nativemem 32k`), an NMT
     baseline, and RSS, heap, GC, thread and file-descriptor samples every 5 s.
  3. NMT diff and memory maps, then 90 s idle.
- **A/B** ([`ab.sh`](ab.sh)): 2 × 180 s without the profiler. A is the app as is; B forces
  `PRAGMA wal_checkpoint(TRUNCATE)` every 5 s.

The host disallows kernel perf events (`perf_event_paranoid=4`), so CPU sampling used `itimer`.

## Native memory: no leak

**Leak tracking.** async-profiler's `nativemem` mode hooks malloc/realloc/free in every loaded library, including
`libsqlite3` and the JVM. Allocations made during the 480 s window that were still unfreed at the end
([`nativemem-leak.html`](results/nativemem-leak.html)) totalled **0.22 MB**. All of it is JIT-compiled code
(`nmethod::new_nmethod`, `CodeBlob`) and deoptimization metadata: normal JVM warm-up. **No unfreed allocation comes
from SQLite, the FFM layer or the driver.**

**JVM view (NMT,** [`nmt-summary-diff-load.txt`](results/nmt-summary-diff-load.txt)**).** Committed native memory
changed by −2.3 MB over the 8-minute load and +1.7 MB after idle. Only Code (+1.2 MB, JIT) and NMT's own bookkeeping
grew. FFM allocations (`Arena`, upcall stubs) fall under "Other", which changed by +6 KB.

**Process view (RSS,** [`memory.csv`](results/memory.csv)**):**

| Phase | RSS | Notes |
|---|---|---|
| After start-up | 115 MB | |
| End of warmup | 258 MB | heap pages touched for the first time (`-Xms` reserves the heap, the kernel only backs touched pages) |
| End of profiled load (8 min) | 271 MB | +13 MB, while NMT stayed flat |
| After 90 s idle | 272 MB | |
| A/B runs, no profiler, after warmup | 229–246 MB | **flat** for 3 minutes |

The 13 MB creep appears only with the profiler attached. It's async-profiler's own call-trace storage (1.86M samples
plus native-allocation records), malloc'd outside NMT. Without the profiler RSS is flat. Threads (28 under load) and
file descriptors (≈62) were stable, and there were no full GCs (657 young GCs).

**Native allocation churn** ([`nativemem-total.html`](results/nativemem-total.html)): SQLite mallocs and frees about
**207 MB/s** (97 GB in 480 s), 99.6% of all native allocation. FFM / `Unsafe` accounts for 0.3% (270 MB in total:
the per-call confined arenas for prepare, binding and backup). The churn is not a leak, since nearly all of it is
freed, but it costs CPU and makes glibc heaps fragment:

| Share | Where |
|---|---|
| 70.4% | `sqlite3VdbeSorterWrite → sqlite3Realloc`: the sorter growing its buffer for `GROUP BY` / `ORDER BY` |
| 38.3% | ↳ `ReportRepository.topProducts` (attributed by the Java caller; the rows overlap) |
| 37.9% | ↳ `ReportRepository.dailySales` |
| 12.2% | `OrderRepository.checkout` |
| 4.2% | `sqlite3WalCheckpoint` (auto-checkpoint) |
| 4.6% | `ProductRepository.search` (FTS5) |

## Finding 1: WAL checkpoint starvation

**Observed.** At the end of the profiled run the WAL file (`shop.db-wal`) was **2,351,135,712 bytes (2.35 GB) for an
18 MB database** (100k orders). Its index, `shop.db-shm`, had grown to 4.5 MB and was mapped **140 times** in the
process (in 32 KB regions). In the 3-minute A/B baseline the WAL reached 639 MB after 33 s and 1.67 GB after 96 s.

**Cause.** In WAL mode, SQLite can only rewind the log to its start when a writer begins and no reader is still
reading a snapshot that ends inside the WAL. The shop wraps every read in a transaction (`Database.read`) on two
reader connections, and under constant load one of them is almost always mid-snapshot. Auto-checkpoints
(`wal_autocheckpoint=1000`, PASSIVE) copy pages back to the database, but they can't reset the log, so every commit
appends at the end. Without `journal_size_limit`, the file also never shrinks: even after a later reset its size
stays at the high-water mark (which is why the A/B baseline plateaus at 1.67 GB).

**Impact.**
- **Disk:** gigabytes held permanently.
- **Speed:** each read must search a growing WAL index.
- **Memory:** every connection maps the whole `-shm` file.

**Confirmed by A/B** ([`ab-baseline.csv`](results/ab-baseline.csv), [`ab-checkpoint.csv`](results/ab-checkpoint.csv)):

| t | baseline WAL | with `wal_checkpoint(TRUNCATE)` every 5 s |
|---|---|---|
| 33 s | 639 MB | 142 MB |
| 65 s | 1,321 MB | 97 MB |
| 130 s | 1,669 MB | 73 MB |
| 180 s | 1,669 MB | 53 MB |

33 of 34 checkpoints succeeded. The B run had 6 HTTP 500 errors. They probably came from the external `sqlite3`
process taking locks, but the container log was not kept, so the cause is unconfirmed. An in-process checkpoint on the
writer connection (below) avoids cross-process lock interplay.

**Fix (application):** run a checkpoint from the writer connection regularly, and cap the file size:
- `PRAGMA journal_size_limit = 67108864` (64 MB) on the writer, so the file is truncated back after each reset;
- a scheduled `PRAGMA wal_checkpoint(TRUNCATE)` (or RESTART) every few seconds through `Database.write`, or after N
  commits. It waits only for in-flight reads, which here last milliseconds.

**For the library:** sqlite-jdbc has the same behaviour, since this is SQLite's design. ffm-sqlite could make it easier
to avoid, for example with a `Config.withWalCheckpointInterval(Duration)` that runs the checkpoint on the connection,
plus a section on this in the README.

## Finding 2: report queries that grow with the data (throughput collapse, malloc churn)

**Observed.** Throughput fell continuously in every run: 6,700 → 1,200 req/s over 3 minutes in the A/B runs, and
1,910 → 800 req/s over the 8-minute profiled run (at the same point in time the profiled run was about 15% slower). The fall is the same with and without
the WAL fix, so the WAL isn't the cause. CPU ([`cpu.html`](results/cpu.html)):
- 94% is inside `sqlite3_step`;
- **67.7% is in `ReportRepository.topProducts` alone**, though it is only 2.5% of requests;
- `dailySales` adds more.

**Cause.** Both reports aggregate *every* order on each call:
- **`topProducts`:** joins all order lines with orders and products, then `GROUP BY` product and `rank()`.
- **`dailySales`:** `GROUP BY date(placed_at)`, which no index can serve, so every order is sorted.

Their cost grows linearly with the order count: 100k orders and 195k order lines after 8 minutes. The sorter's
growing buffer (`sqlite3VdbeSorterWrite → realloc`) is also 70% of SQLite's native malloc traffic.

**Fix (application), in order of effect:**
1. Keep running totals in summary tables (`product_sales`, `daily_sales`), updated inside the checkout and cancel
   transactions. The reports then read a few dozen rows.
2. Or cache report results for a few seconds: reports don't need to be up to the millisecond.
3. At least add covering indexes: `order_line(product_id, quantity, unit_price_cents)`, and an expression index or a
   stored generated column for `date(placed_at)` together with `status`.

## Finding 3: lock contention in the JDK HTTP server

**Observed** ([`lock.html`](results/lock.html)). 1,148 s of monitor contention in 480 s (about 2.4 threads waiting on
average), 99.8% of it on one lock: `sun.net.httpserver.ServerImpl.addEvent`, taken by
`FixedLengthOutputStream.close` when each response finishes. The database layer had no measurable contention: the
writer pool queues writes in HikariCP, and it doesn't appear among the contended locks.

**Cause.** `com.sun.net.httpserver` funnels every completed exchange through one synchronized event list for its
single dispatcher thread. It is fine for tools and demos; it is not built for thousands of requests per second.

**Fix.** The shop uses the JDK server on purpose, to keep the database code in view. An application serving real
traffic should use a production server (Jetty, Helidon, Netty, or the Spring/Quarkus default). Separately, 79% of Java
allocation (62 MB/s, [`alloc.html`](results/alloc.html)) comes from the HTTP layer, mostly Jackson's pretty-printer and
reading request bodies. That's harmless here (young GCs only), but it would be the next thing to trim.

## What was checked and found normal

- **SQLite memory:** no unfreed allocations; per-connection page caches are bounded (default `cache_size`, 3
  connections).
- **FFM:** 270 MB of short-lived confined-arena allocations over 8 minutes, all freed, and no growth in NMT "Other".
  Upcall stubs are created only for registrations, and none happened under load.
- **Online backups** (16 during the run): `sqlite3_backup_step` was negligible in CPU and allocation.
- **JVM:** heap use stable at about 22 MB young plus a small old generation, no full GC, no thread or file-descriptor
  growth.

## Reproduce

```bash
cd ffm-sqlite-shop && mvn package && cp target/ffm-sqlite-shop.jar profiling/
cd profiling && podman build -t ffm-sqlite-shop:prof .
./run.sh      # ~12 min: profiled load, results in out/
./ab.sh       # ~7 min: WAL A/B
```

Converting the JFR: `asprof`'s `jfrconv --cpu|--alloc|--lock|--nativemem [--leak] profile.jfr out.html`.
