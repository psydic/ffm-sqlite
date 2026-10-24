# SQLite from Java: FFM (JDK 25) vs sqlite-jdbc (JNI)

## Layout

| File | What |
|---|---|
| `src/main/java/ffmsqlite/Sqlite3.java` | Raw FFM bindings (`static final` downcall handles, plus `critical` variants) |
| `src/main/java/ffmsqlite/SQLiteFFM.java` | The FFM connection wrapper: open / exec / prepare / close |
| `src/main/java/ffmsqlite/SQLiteJDBC.java` | The JDBC equivalent: connect, `CREATE TABLE`, `INSERT` |
| `src/main/java/ffmsqlite/SQLiteBenchmark.java` | JMH suite: connect, write (1,000 rows), read (1,000 rows) |
| `scripts/build-sqlite.sh` | Builds `native/libsqlite3.so`: the same SQLite version (3.50.3) and compile options as the engine bundled in sqlite-jdbc 3.50.3.0 |

## Running

```bash
./scripts/build-sqlite.sh          # optional; without it FFM uses the system libsqlite3.so.0
mvn package
java -jar target/benchmarks.jar -prof gc -jvmArgsAppend -Dsqlite.lib=$PWD/native/libsqlite3.so
java -jar target/benchmarks.jar 'read_' -f 1    # a subset
```

## Fairness controls

- **Same engine version and options.** sqlite-jdbc statically links SQLite and does not export the `sqlite3_*`
  symbols, so FFM cannot call its copy. `build-sqlite.sh` rebuilds 3.50.3 with the options from
  `PRAGMA compile_options` (THREADSAFE=1, DEFAULT_MEMSTATUS=0, TEMP_STORE=1, ...).
- **Remaining confounder: the compiler.** sqlite-jdbc builds with gcc 4.1.2, for glibc compatibility. On a
  query that is pure engine work (a recursive CTE over 3M rows, one call), the bundled engine took ≈480 ms and
  the local build ≈390 ms. So up to ~15–20% of the *engine* time favours FFM. The results section accounts for this.
- In-memory databases for read and write, which isolates call overhead from fsync. Every write batch is one
  transaction: `BEGIN; DELETE FROM t; 1,000 x INSERT; COMMIT`. The `DELETE` uses SQLite's truncate
  optimisation and is the same on both sides.
- Strings are pre-built. Both sides UTF-8-encode each value on every row, as `setString` does.
- Scores are **ns per row** (`@OperationsPerInvocation(1000)`). Setup: 2 forks, 5×1 s warmup, 5×1 s
  measurement, `-Xmx1g`.

## Results

i7-14700HX (hybrid P/E cores), Linux 7.0, OpenJDK 25.0.4, sqlite-jdbc 3.50.3.0, SQLite 3.50.3.

### Write: ns/row (B/row allocated on the Java heap)

| Variant | ns/row | B/row |
|---|---:|---:|
| FFM `sqlite3_exec` per row (SQL built by concatenation) | 1,338 ± 50 | 80 |
| **FFM prepared statement** | **320 ± 17** | 24 |
| FFM prepared statement, critical bind/reset + heap segment | 315 ± 17 | 24 |
| JDBC `executeUpdate()` per row (default config) | 2,521 ± 196 | 873 |
| JDBC `executeUpdate()` per row, `jdbc.get_generated_keys=false` | 642 ± 44 | 56 |
| JDBC `addBatch` / `executeBatch` | 533 ± 31 | 69 |

### Read: ns/row (B/row)

| Variant | ns/row | B/row |
|---|---:|---:|
| FFM `sqlite3_exec` + upcall per row | 187 ± 12 | 216 |
| **FFM prepared statement, step + column_\*** | **152 ± 11** | 72 |
| FFM prepared statement, critical column_\* | 142 ± 14 | 72 |
| JDBC `ResultSet` | 465 ± 127 | 136 |

### Connect: µs/op (B/op)

| Variant | file | `:memory:` | B/op |
|---|---:|---:|---:|
| FFM open + close | 12.3 ± 1.5 | 7.7 ± 1.6 | 96 |
| JDBC `DriverManager.getConnection` + close | 21.2 ± 0.9 | 16.8 ± 1.3 | ~6,000 |
| JDBC `org.sqlite.JDBC.createConnection` + close | 21.3 ± 1.3 | 15.7 ± 0.4 | ~5,800 |
| FFM open + `SELECT 1` + close | 27.2 ± 9.7 | 18.0 ± 5.0 | 368 |
| JDBC open + `SELECT 1` + close | 35.5 ± 1.6 | 26.0 ± 5.1 | ~6,100 |

## Analysis

### 1. Transition cost: FFM downcalls vs JNI

Both mechanisms perform the same core VM work. The thread goes from `_thread_in_Java` to `_thread_in_native`,
and on the way back it polls for a pending safepoint or handshake. What differs is the rest of the call:

- **JNI** adds a generic native wrapper. It passes a `JNIEnv*` and a `jclass`/`jobject`, wraps object arguments
  in handles (`JNI_ArgumentPusher` and `HandleMark` both show up in the profile), and the native side calls back
  into the VM for arrays and strings (`GetByteArrayElements`, `NewStringUTF`, ...). The JIT cannot inline any of it.
- **FFM** with a `static final` `MethodHandle` and `invokeExact` is compiled by C2 into a specialised stub.
  It shuffles the Java arguments into C ABI registers and makes a direct `call`. There is no JNIEnv, no
  handles, no local-ref frame, and no glue C function. `MemorySegment` arguments pass as raw addresses.
- **`Linker.Option.critical`** also drops the thread-state transition. Here it gained only 2–7%, inside the
  error bars, because the transition is a few ns and each SQLite call does 50–200 ns of real work.

Most of the measured 2–3× gap is not the raw JNI transition. It is the work sqlite-jdbc does on every crossing:

- every `NativeDB` method is `synchronized`. Since JDK 18 removed biased locking, each call pays an atomic CAS
  to enter and leave the monitor.
- every call goes through a `SafeStmtPtr.safeRun*` lambda that checks whether the statement is closed.
- `ResultSet` bookkeeping: on each execute it fetches column metadata (`column_names` was ~10% of the profile)
  and builds strings through `utf8ByteBufferToString`.
- `executeUpdate()` by default runs a whole extra statement per INSERT to support `getGeneratedKeys()`
  (`CoreStatement.updateGeneratedKeys` was **66%** of the profile). That costs 4× the time and 15× the allocation.
  Set `jdbc.get_generated_keys=false`, or use batches.

Taking the compiler confounder into account: if the gcc 4.1.2 engine is ~20% slower on the engine share of
each row (at most ~250 ns of a write row), that explains at most ~50 ns. The measured write gap is 210–320 ns
and the read gap is ~310 ns, so most of the difference comes from the binding layer, not codegen.

### 2. GC: Arena (off-heap) vs JDBC allocations

- `Arena` memory is native. The GC never traces, copies, or scans it, and a confined arena frees it
  deterministically on `close()`, without finalizers or Cleaners. Heap allocation on the FFM side is only what
  you choose to materialise: the `byte[]` from encoding (24 B/row on write) and the `String` (72 B/row on read).
- sqlite-jdbc allocates more per row: 56–69 B/row on write, 136 B/row on read, and ~6 KB per connection
  (`SQLiteConfig`, `Properties`, the `DB` and `Connection` objects). With the default generated-keys mode it is
  873 B/row, because each INSERT creates a `Statement` and a `ResultSet`.
- **At these rates GC is not the bottleneck.** 136 B/row at ~2M rows/s is ~270 MB/s of short-lived TLAB
  allocation. Young-gen collectors handle that easily, and the runs show `gc.count ≈ 0` for most variants.
  The off-heap model matters more for blobs and large buffers, for latency-sensitive services where every
  young GC counts, and for avoiding the copy-to-native step JNI needs for `byte[]`.
- Arena choice matters. `Arena.ofConfined()` is cheap (a malloc and free). `Arena.ofShared().close()` performs a
  thread-local handshake with every thread (microseconds), and `Arena.ofAuto()` hands freeing to the GC.
  Don't create either per call in a hot path.

### 3. Bulk throughput: which wins?

FFM with prepared statements wins: ~1.7–2× on writes against the best JDBC configuration (batch), and
~3× on reads. The reasons are fewer and cheaper crossings, no per-call synchronisation layers, and no
`ResultSet` machinery. Two factors outweigh FFM vs JNI, though:

1. **API usage beats the binding.** A naive FFM `sqlite3_exec` per INSERT is 4× slower than FFM prepared
   statements, and more than 2× slower than JDBC batch. Default-config JDBC `executeUpdate` is 8× slower than FFM.
2. **Transactions beat everything.** These numbers assume one transaction per batch. In autocommit mode on a
   file database, every INSERT ends in an fsync (milliseconds), and the binding cost becomes noise.

For connections, FFM is ~1.7–2× faster (12 vs 21 µs on a file database) and allocates 60× less. This only
matters if you open connections often. Pool them in either approach.

## Optimisation tips for the FFM side

1. **Prepare once, then bind, step, and reset, inside explicit transactions.** Cache `sqlite3_stmt*` per SQL
   string. This is the 1,338 → 320 ns/row step. Keep every `MethodHandle` in a `static final` field and call it
   with `invokeExact` with exact types. A handle held in an instance field or called with `invoke` loses
   constant folding and inlining.
2. **Don't allocate an Arena per string.**
   - Write path: encode into a reused native scratch buffer and bind with `SQLITE_STATIC`. SQLite reads it
     during `step`, so it only needs to stay valid until then. The alternative: use a `critical(true)` handle,
     pass `MemorySegment.ofArray(bytes)`, and bind with `SQLITE_TRANSIENT`, because SQLite must copy heap
     memory before the call returns.
   - Read path: use `column_text` plus `column_bytes` and copy exactly `n` bytes. Avoid
     `reinterpret(Long.MAX_VALUE).getString(0)`, which runs a second `strlen`. Prefer the pull model
     (step + column_\*) over `sqlite3_exec` upcalls: in this benchmark the upcalls plus text parsing cost
     +23% time and 3× the allocation.
3. **Use `Linker.Option.critical` selectively.** It suits short, non-blocking calls that never call back into
   Java: `bind_*`, `column_*`, `reset`. Never use it on `sqlite3_step` or `sqlite3_exec`. They can block on
   locks, sleep in the busy handler, run user functions, or scan a large table. While they run, the thread can't
   reach a safepoint, so GC stalls for every thread. Expect a small gain (≤7% here).
4. *(Not measured here.)* If each connection is confined to one thread, open with `SQLITE_OPEN_NOMUTEX`
   (0x8000). That removes the pthread mutex lock and unlock SQLite does on every API call (~6% of samples in
   the JDBC profile).
