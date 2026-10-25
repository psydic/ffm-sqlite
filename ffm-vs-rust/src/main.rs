//! rusqlite counterparts of the ffm-sqlite JMH benchmarks in ../ffm-vs-jdbc (same schema, SQL, data and column
//! reads), with a JMH-like harness: warmup and measurement iterations of fixed duration, one process per "fork".
//!
//! Usage: ffm-vs-rust <suite> [args]; suites: workload, concurrent, types, udf, bulk <dir>, wide <dir> <readers> <point|range>.
//! Output: one line per benchmark, `RESULT\t<name>\t<unit>\t<score per iteration, comma-separated>`.

use rusqlite::functions::{Aggregate, Context, FunctionFlags, WindowAggregate};
use rusqlite::{Connection, OptionalExtension, Statement, params};
use std::hint::black_box;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{Duration, Instant};

type R<T> = Result<T, Box<dyn std::error::Error + Send + Sync>>;

// ------------------------------------------------------------------ harness

const WARMUP: u32 = 3;
const MEASURE: u32 = 5;

/// Average time per operation: runs `op` (which performs `ops` operations) for `iter` per iteration.
fn avg_time(name: &str, unit_ns: f64, unit: &str, iter: Duration, ops: u64, mut op: impl FnMut() -> R<()>) -> R<()> {
    let mut scores = Vec::new();
    for i in 0..WARMUP + MEASURE {
        let start = Instant::now();
        let mut n = 0u64;
        while start.elapsed() < iter {
            op()?;
            n += ops;
        }
        let per_op = start.elapsed().as_nanos() as f64 / n as f64 / unit_ns;
        if i >= WARMUP {
            scores.push(per_op);
        }
    }
    report(name, unit, &scores);
    Ok(())
}

fn report(name: &str, unit: &str, scores: &[f64]) {
    let s: Vec<String> = scores.iter().map(|v| format!("{v:.4}")).collect();
    println!("RESULT\t{name}\t{unit}\t{}", s.join(","));
}

/// splitmix64, standing in for Java's SplittableRandom (same distribution, not the same sequence).
struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E3779B97F4A7C15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D049BB133111EB);
        z ^ (z >> 31)
    }
    fn below(&mut self, n: u64) -> u64 {
        self.next() % n
    }
}

fn temp_dir(base: &Path, prefix: &str) -> R<PathBuf> {
    let dir = base.join(format!("{prefix}-{}-{}", std::process::id(), Instant::now().elapsed().as_nanos()));
    std::fs::create_dir_all(&dir)?;
    Ok(dir)
}

// ------------------------------------------------------------------ WorkloadBenchmark

const USERS: u64 = 100_000;
const SELECT_USER: &str = "SELECT id, email, name, age FROM users WHERE id = ?";
const SELECT_BY_EMAIL: &str = "SELECT id, name FROM users WHERE email = ?";
const INSERT_EVENT: &str = "INSERT INTO events (user_id, kind, payload) VALUES (?, ?, ?)";
const UPDATE_AGE: &str = "UPDATE users SET age = age + 1 WHERE id = ?";
const INSERT_ORDER: &str = "INSERT INTO orders (user_id, amount, status) VALUES (?, ?, 'new')";
const RANGE: &str = "SELECT id, user_id, amount, status FROM orders WHERE user_id BETWEEN ? AND ?";
const JOIN: &str = "SELECT u.name, count(o.id), sum(o.amount) FROM users u JOIN orders o ON o.user_id = u.id \
                    WHERE u.id BETWEEN ? AND ? GROUP BY u.id";

fn open_app(path: &Path) -> R<Connection> {
    let c = Connection::open(path)?;
    c.execute_batch("PRAGMA synchronous = NORMAL")?;
    Ok(c)
}

fn read_user(ps: &mut Statement<'_>, id: u64) -> R<()> {
    let mut rows = ps.query([id as i64])?;
    while let Some(r) = rows.next()? {
        black_box(r.get::<_, i32>(0)?);
        black_box(r.get::<_, String>(1)?);
        black_box(r.get::<_, String>(2)?);
        black_box(r.get::<_, i32>(3)?);
    }
    Ok(())
}

/// A freshly populated database for one benchmark, as JMH's per-trial setup gives each benchmark (and fork) its
/// own: otherwise the insert benchmarks would grow `orders` for the scans that run after them.
fn fresh_app_db(base: &Path) -> R<(PathBuf, PathBuf)> {
    let dir = temp_dir(base, "rust-workload")?;
    let path = dir.join("app.db");
    Connection::open(&path)?.execute_batch(
        "PRAGMA journal_mode = WAL;
         CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
                             age INTEGER NOT NULL, created TEXT NOT NULL);
         CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users (id),
                              amount INTEGER NOT NULL, status TEXT NOT NULL);
         CREATE INDEX ix_orders_user ON orders (user_id);
         CREATE TABLE events (id INTEGER PRIMARY KEY, user_id INTEGER, kind TEXT, payload TEXT);
         BEGIN;
         WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM s WHERE i < 100000)
         INSERT INTO users SELECT i, 'user' || i || '@example.com', 'User ' || i, 20 + i % 50,
                                  datetime('2024-01-01', '+' || (i % 365) || ' days') FROM s;
         WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM s WHERE i < 300000)
         INSERT INTO orders (user_id, amount, status) SELECT 1 + i % 100000, i % 997, 'done' FROM s;
         COMMIT;",
    )?;
    Ok((dir, path))
}

/// Runs one workload benchmark on a fresh database with its two connections (`conn`, and `tx` for transactions).
fn with_app_db(base: &Path, bench: impl FnOnce(&Path, &Connection, &Connection) -> R<()>) -> R<()> {
    let (dir, path) = fresh_app_db(base)?;
    {
        let conn = open_app(&path)?;
        let tx = open_app(&path)?;
        bench(&path, &conn, &tx)?;
    }
    std::fs::remove_dir_all(dir)?;
    Ok(())
}

fn workload(base: &Path) -> R<()> {
    let mut rnd = Rng(42);
    let us = 1_000.0;
    let it = Duration::from_secs(1);
    with_app_db(base, |_, conn, _| {
        let mut ps = conn.prepare(SELECT_USER)?;
        avg_time("pointSelectById", us, "us/op", it, 1, || read_user(&mut ps, 1 + rnd.below(USERS)))
    })?;
    with_app_db(base, |_, conn, _| {
        let mut ps = conn.prepare(SELECT_BY_EMAIL)?;
        avg_time("pointSelectByIndexedEmail", us, "us/op", it, 1, || {
            let email = format!("user{}@example.com", 1 + rnd.below(USERS));
            let mut rows = ps.query([&email])?;
            while let Some(r) = rows.next()? {
                black_box(r.get::<_, i32>(0)?);
                black_box(r.get::<_, String>(1)?);
            }
            Ok(())
        })
    })?;
    with_app_db(base, |_, conn, _| {
        let mut ps = conn.prepare(INSERT_EVENT)?;
        avg_time("insertAutoCommit", us, "us/op", it, 1, || {
            ps.execute(params![(1 + rnd.below(USERS)) as i64, "click", "{\"page\":\"/home\"}"])?;
            Ok(())
        })
    })?;
    with_app_db(base, |_, conn, _| {
        let mut ps = conn.prepare(UPDATE_AGE)?;
        avg_time("updateAutoCommit", us, "us/op", it, 1, || {
            ps.execute([(1 + rnd.below(USERS)) as i64])?;
            Ok(())
        })
    })?;
    with_app_db(base, |_, _, tx| {
        let (mut sel, mut ins, mut upd) = (tx.prepare(SELECT_USER)?, tx.prepare(INSERT_ORDER)?, tx.prepare(UPDATE_AGE)?);
        avg_time("businessTransaction", us, "us/op", it, 1, || {
            let id = 1 + rnd.below(USERS);
            let t = tx.unchecked_transaction()?;
            read_user(&mut sel, id)?;
            ins.execute(params![id as i64, 42])?;
            upd.execute([id as i64])?;
            t.commit()?;
            Ok(())
        })
    })?;
    with_app_db(base, |_, conn, _| {
        let mut ps = conn.prepare(RANGE)?;
        avg_time("rangeScan100Rows", us, "us/op", it, 1, || {
            let from = (1 + rnd.below(USERS - 40)) as i64;
            let mut rows = ps.query([from, from + 32])?;
            while let Some(r) = rows.next()? {
                black_box(r.get::<_, i64>(0)?);
                black_box(r.get::<_, i32>(1)?);
                black_box(r.get::<_, i32>(2)?);
                black_box(r.get::<_, String>(3)?);
            }
            Ok(())
        })
    })?;
    with_app_db(base, |_, conn, _| {
        let mut ps = conn.prepare(JOIN)?;
        avg_time("joinGroupBy50Users", us, "us/op", it, 1, || {
            let from = (1 + rnd.below(USERS - 60)) as i64;
            let mut rows = ps.query([from, from + 49])?;
            while let Some(r) = rows.next()? {
                black_box(r.get::<_, String>(0)?);
                black_box(r.get::<_, i32>(1)?);
                black_box(r.get::<_, i64>(2)?);
            }
            Ok(())
        })
    })?;
    with_app_db(base, |_, conn, _| {
        avg_time("prepareInsideLoop", us, "us/op", it, 1, || {
            let mut ps = conn.prepare(SELECT_USER)?; // rusqlite's plain prepare: parses every time
            read_user(&mut ps, 1 + rnd.below(USERS))
        })
    })?;
    with_app_db(base, |_, conn, _| {
        avg_time("prepareInsideLoop_cached", us, "us/op", it, 1, || {
            let mut ps = conn.prepare_cached(SELECT_USER)?; // rusqlite's opt-in LRU statement cache
            read_user(&mut ps, 1 + rnd.below(USERS))
        })
    })?;
    with_app_db(base, |path, _, _| {
        avg_time("connectQueryClose", us, "us/op", it, 1, || {
            let c = open_app(path)?;
            let mut ps = c.prepare(SELECT_USER)?;
            read_user(&mut ps, 1 + rnd.below(USERS))
        })
    })?;
    Ok(())
}

// ------------------------------------------------------------------ ConcurrentReadBenchmark

/// Total throughput of `threads` workers, sampled at iteration boundaries (ops per `divisor` seconds). Each worker
/// runs `body(thread, stop, counter)` on its own thread, opening its own connection and preparing its statements
/// there (rusqlite statements are not `Send`), then counting operations until `stop` is set.
fn throughput(
    name: &str, iter: Duration, threads: usize, divisor: f64, unit: &str,
    body: impl Fn(usize, &AtomicBool, &AtomicU64) -> R<()> + Send + Sync + 'static,
) -> R<()> {
    let stop = Arc::new(AtomicBool::new(false));
    let counters: Vec<Arc<AtomicU64>> = (0..threads).map(|_| Arc::new(AtomicU64::new(0))).collect();
    let body = Arc::new(body);
    let mut handles = Vec::new();
    for t in 0..threads {
        let (stop, counter, body) = (stop.clone(), counters[t].clone(), body.clone());
        handles.push(std::thread::spawn(move || body(t, &stop, &counter)));
    }
    let total = || counters.iter().map(|c| c.load(Ordering::Relaxed)).sum::<u64>();
    let mut scores = Vec::new();
    let (mut last, mut last_t) = (total(), Instant::now());
    for i in 0..WARMUP + MEASURE {
        std::thread::sleep(iter);
        let (now, now_t) = (total(), Instant::now());
        if i >= WARMUP {
            scores.push((now - last) as f64 / now_t.duration_since(last_t).as_secs_f64() / divisor);
        }
        (last, last_t) = (now, now_t);
    }
    stop.store(true, Ordering::Relaxed);
    for h in handles {
        h.join().unwrap()?;
    }
    report(name, unit, &scores);
    Ok(())
}

fn concurrent(base: &Path) -> R<()> {
    let dir = temp_dir(base, "rust-concurrent")?;
    let path = dir.join("app.db");
    Connection::open(&path)?.execute_batch(
        "PRAGMA journal_mode = WAL;
         CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT, name TEXT, age INTEGER);
         WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM s WHERE i < 100000)
         INSERT INTO users SELECT i, 'user' || i || '@example.com', 'User ' || i, 20 + i % 50 FROM s;",
    )?;
    for threads in [1, 4, 8] {
        let p = path.clone();
        throughput(&format!("pointSelect_{threads}threads"), Duration::from_secs(1), threads, 1_000.0, "ops/ms",
            move |t, stop, counter| {
                let c = Connection::open(&p)?;
                let mut ps = c.prepare(SELECT_USER)?;
                let mut rnd = Rng(1000 + t as u64);
                while !stop.load(Ordering::Relaxed) {
                    read_user(&mut ps, 1 + rnd.below(USERS))?;
                    counter.fetch_add(1, Ordering::Relaxed);
                }
                Ok(())
            })?;
    }
    std::fs::remove_dir_all(dir)?;
    Ok(())
}

// ------------------------------------------------------------------ TypeBenchmark (INTEGER, REAL, TEXT, BLOB)

const TYPE_ROWS: usize = 1_000;

fn types() -> R<()> {
    let ints: Vec<i32> = (0..TYPE_ROWS as i32).map(|i| i.wrapping_mul(7919)).collect();
    let doubles: Vec<f64> = (0..TYPE_ROWS).map(|i| i as f64 * 1.25 + 0.001).collect();
    let strings: Vec<String> = (0..TYPE_ROWS).map(|i| format!("value-{i}-{:x}", i * 31)).collect();
    let blobs: Vec<Vec<u8>> = (0..TYPE_ROWS).map(|i| {
        let mut b = vec![0u8; 16];
        b[0] = i as u8;
        b
    }).collect();
    for ty in ["INTEGER", "REAL", "TEXT", "BLOB"] {
        let conn = Connection::open_in_memory()?;
        conn.execute_batch(&format!("CREATE TABLE t (v {ty}); BEGIN"))?;
        let mut insert = conn.prepare("INSERT INTO t (v) VALUES (?)")?;
        let mut delete = conn.prepare("DELETE FROM t")?;
        let mut select = conn.prepare("SELECT v FROM t")?;
        let mut commit = conn.prepare("COMMIT")?;
        let mut begin = conn.prepare("BEGIN")?;
        let mut write = || -> R<()> {
            delete.execute([])?;
            for i in 0..TYPE_ROWS {
                match ty {
                    "INTEGER" => insert.execute([ints[i]])?,
                    "REAL" => insert.execute([doubles[i]])?,
                    "TEXT" => insert.execute([&strings[i]])?,
                    _ => insert.execute([&blobs[i]])?,
                };
            }
            commit.execute([])?;
            begin.execute([])?;
            Ok(())
        };
        write()?;
        avg_time(&format!("write_{ty}"), 1.0, "ns/row", Duration::from_secs(1), TYPE_ROWS as u64, &mut write)?;
        avg_time(&format!("read_{ty}"), 1.0, "ns/row", Duration::from_secs(1), TYPE_ROWS as u64, || {
            let mut rows = select.query([])?;
            while let Some(r) = rows.next()? {
                match ty {
                    "INTEGER" => { black_box(r.get::<_, i32>(0)?); }
                    "REAL" => { black_box(r.get::<_, f64>(0)?); }
                    "TEXT" => { black_box(r.get::<_, String>(0)?); }
                    _ => { black_box(r.get::<_, Vec<u8>>(0)?); }
                }
            }
            Ok(())
        })?;
    }
    Ok(())
}

// ------------------------------------------------------------------ UdfBenchmark

const UDF_ROWS: u64 = 10_000;

struct JSum;
impl Aggregate<i64, i64> for JSum {
    fn init(&self, _: &mut Context<'_>) -> rusqlite::Result<i64> {
        Ok(0)
    }
    fn step(&self, ctx: &mut Context<'_>, sum: &mut i64) -> rusqlite::Result<()> {
        *sum += ctx.get::<i64>(0)?;
        Ok(())
    }
    fn finalize(&self, _: &mut Context<'_>, sum: Option<i64>) -> rusqlite::Result<i64> {
        Ok(sum.unwrap_or(0))
    }
}

struct WSum;
impl Aggregate<i64, i64> for WSum {
    fn init(&self, _: &mut Context<'_>) -> rusqlite::Result<i64> {
        Ok(0)
    }
    fn step(&self, ctx: &mut Context<'_>, sum: &mut i64) -> rusqlite::Result<()> {
        *sum += ctx.get::<i64>(0)?;
        Ok(())
    }
    fn finalize(&self, _: &mut Context<'_>, sum: Option<i64>) -> rusqlite::Result<i64> {
        Ok(sum.unwrap_or(0))
    }
}
impl WindowAggregate<i64, i64> for WSum {
    fn value(&self, sum: Option<&mut i64>) -> rusqlite::Result<i64> {
        Ok(sum.map_or(0, |s| *s))
    }
    fn inverse(&self, ctx: &mut Context<'_>, sum: &mut i64) -> rusqlite::Result<()> {
        *sum -= ctx.get::<i64>(0)?;
        Ok(())
    }
}

/// Java's String.CASE_INSENSITIVE_ORDER, same algorithm: equal chars are skipped; others compare by their
/// upper-case, then lower-case forms (one-to-one per char, like Character.toUpperCase/toLowerCase).
fn case_insensitive(a: &str, b: &str) -> std::cmp::Ordering {
    let (mut x, mut y) = (a.chars(), b.chars());
    loop {
        match (x.next(), y.next()) {
            (None, None) => return std::cmp::Ordering::Equal,
            (None, Some(_)) => return std::cmp::Ordering::Less,
            (Some(_), None) => return std::cmp::Ordering::Greater,
            (Some(c1), Some(c2)) if c1 == c2 => continue,
            (Some(c1), Some(c2)) => {
                let (u1, u2) = (c1.to_uppercase().next().unwrap_or(c1), c2.to_uppercase().next().unwrap_or(c2));
                if u1 != u2 {
                    let (l1, l2) = (u1.to_lowercase().next().unwrap_or(u1), u2.to_lowercase().next().unwrap_or(u2));
                    if l1 != l2 {
                        return l1.cmp(&l2);
                    }
                }
            }
        }
    }
}

fn udf() -> R<()> {
    let conn = Connection::open_in_memory()?;
    conn.execute_batch(&format!(
        "CREATE TABLE t AS WITH RECURSIVE s(v) AS (SELECT 1 UNION ALL SELECT v + 1 FROM s WHERE v < {UDF_ROWS}) \
         SELECT v AS n, 'name-' || v AS s FROM s"))?;
    let flags = FunctionFlags::SQLITE_UTF8;
    conn.create_scalar_function("plus_one", 1, flags, |ctx| Ok(ctx.get::<i64>(0)? + 1))?;
    conn.create_scalar_function("jupper", 1, flags, |ctx| Ok(ctx.get::<String>(0)?.to_uppercase()))?;
    conn.create_aggregate_function("jsum", 1, flags, JSum)?;
    conn.create_window_function("wsum", 1, flags, WSum)?;
    conn.create_collation("jci", case_insensitive)?;
    // As the Java benchmark's Statement.executeQuery: prepared on every call.
    let one = |sql: &str| -> R<()> {
        black_box(conn.query_row(sql, [], |r| r.get::<_, Option<i64>>(0)).optional()?);
        Ok(())
    };
    let it = Duration::from_secs(1);
    for (name, sql) in [
        ("builtinExpressionBaseline", "SELECT sum(n + 1) FROM t"),
        ("scalarLong", "SELECT sum(plus_one(n)) FROM t"),
        ("scalarText", "SELECT sum(length(jupper(s))) FROM t"),
        ("aggregate", "SELECT jsum(n) FROM t"),
        ("windowFunction", "SELECT sum(w) FROM (SELECT wsum(n) OVER (ORDER BY n ROWS BETWEEN 9 PRECEDING AND CURRENT ROW) AS w FROM t)"),
        ("windowBuiltinBaseline", "SELECT sum(w) FROM (SELECT sum(n) OVER (ORDER BY n ROWS BETWEEN 9 PRECEDING AND CURRENT ROW) AS w FROM t)"),
        ("collationCompare", "SELECT length(max(s COLLATE jci)) FROM t"),
        ("collationBuiltinBaseline", "SELECT length(max(s COLLATE NOCASE)) FROM t"),
    ] {
        avg_time(name, 1.0, "ns/call", it, UDF_ROWS, || one(sql))?;
    }
    avg_time("collationOrderBy", 1.0, "ns/call", it, UDF_ROWS, || {
        let mut ps = conn.prepare("SELECT s FROM t ORDER BY s COLLATE jci")?;
        let mut rows = ps.query([])?;
        while let Some(r) = rows.next()? {
            black_box(r.get::<_, String>(0)?);
        }
        Ok(())
    })?;
    avg_time("scalarPerRowResult", 1.0, "ns/call", it, UDF_ROWS, || {
        let mut ps = conn.prepare("SELECT plus_one(n) FROM t")?;
        let mut rows = ps.query([])?;
        while let Some(r) = rows.next()? {
            black_box(r.get::<_, i64>(0)?);
        }
        Ok(())
    })?;
    Ok(())
}

// ------------------------------------------------------------------ 1M wide rows (WideRows, BulkLoad, WideConcurrent)

const WIDE_ROWS: i64 = 1_000_000;
const POOL: i64 = 4096;
const WIDE_DDL: &str = "CREATE TABLE wide (
    id INTEGER PRIMARY KEY, tiny INTEGER NOT NULL, small INTEGER NOT NULL, medium INTEGER NOT NULL,
    big INTEGER NOT NULL, flag BOOLEAN NOT NULL, price REAL NOT NULL, ratio REAL, code TEXT NOT NULL,
    name TEXT NOT NULL, email TEXT NOT NULL, city TEXT NOT NULL, note TEXT, description TEXT NOT NULL,
    created TEXT NOT NULL, birth TEXT NOT NULL, uuid TEXT NOT NULL, amount TEXT NOT NULL, hash BLOB NOT NULL,
    payload BLOB NOT NULL);
    CREATE INDEX ix_wide_email ON wide (email);";
const WIDE_INSERT: &str = "INSERT INTO wide (id, tiny, small, medium, big, flag, price, ratio, code, name, email, city, \
    note, description, created, birth, uuid, amount, hash, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
const WIDE_ROW: &str = "SELECT id, tiny, small, medium, big, flag, price, ratio, code, name, email, city, note, \
    description, created, birth, uuid, amount, hash, payload FROM wide WHERE id = ?";
const WIDE_RANGE: &str = "SELECT id, name, price, created, payload FROM wide WHERE id BETWEEN ? AND ?";

/// One pool entry: the values the Java benchmarks bind for every id with `id % 4096 == index`.
struct PoolRow {
    tiny: i32, small: i32, medium: i32, big: i64, flag: bool, price: f64, ratio: Option<f64>,
    texts: [Option<String>; 10], // code, name, email, city, note, description, created, birth, uuid, amount
    hash: Vec<u8>, payload: Vec<u8>,
}

/// The value pool, read from the Java-built template (rows 1..=4096 hold every pool index once), so both
/// languages bind identical data.
fn load_pool(template: &Path) -> R<Vec<PoolRow>> {
    let c = Connection::open_with_flags(template, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    let mut ps = c.prepare("SELECT id, tiny, small, medium, big, flag, price, ratio, code, name, email, city, note, \
        description, created, birth, uuid, amount, hash, payload FROM wide WHERE id <= 4096 ORDER BY id % 4096")?;
    let rows = ps.query_map([], |r| {
        let mut texts: [Option<String>; 10] = Default::default();
        for (k, t) in texts.iter_mut().enumerate() {
            *t = r.get(8 + k)?;
        }
        Ok(PoolRow {
            tiny: r.get(1)?, small: r.get(2)?, medium: r.get(3)?, big: r.get(4)?, flag: r.get(5)?, price: r.get(6)?,
            ratio: r.get(7)?, texts, hash: r.get(18)?, payload: r.get(19)?,
        })
    })?;
    Ok(rows.collect::<Result<Vec<_>, _>>()?)
}

fn bind_wide(ps: &mut Statement<'_>, pool: &[PoolRow], id: i64) -> R<()> {
    let p = &pool[(id % POOL) as usize];
    let t = &p.texts;
    ps.execute(params![id, p.tiny, p.small, p.medium, p.big, p.flag, p.price, p.ratio, t[0], t[1], t[2], t[3], t[4],
        t[5], t[6], t[7], t[8], t[9], p.hash, p.payload])?;
    Ok(())
}

fn bulk(base: &Path, template: &Path) -> R<()> {
    let pool = load_pool(template)?;
    let mut scores = Vec::new();
    for i in 0..1 + 3 {   // as BulkLoadBenchmark: 1 warmup, 3 measured single shots
        let dir = temp_dir(base, "rust-bulk")?;
        let start = Instant::now();
        {
            let c = Connection::open(dir.join("wide.db"))?;
            c.execute_batch("PRAGMA journal_mode = WAL; PRAGMA synchronous = FULL;")?;
            c.execute_batch(WIDE_DDL)?;
            c.execute_batch("BEGIN")?;
            let mut ps = c.prepare(WIDE_INSERT)?;
            for id in 1..=WIDE_ROWS {
                bind_wide(&mut ps, &pool, id)?;
                if id % 10_000 == 0 {
                    c.execute_batch("COMMIT; BEGIN")?;
                }
            }
            c.execute_batch("COMMIT")?;
        }
        let secs = start.elapsed().as_secs_f64();
        std::fs::remove_dir_all(&dir)?;
        if i >= 1 {
            scores.push(secs);
        }
    }
    report("bulkLoad1M", "s", &scores);
    Ok(())
}

fn read_wide_row(ps: &mut Statement<'_>, id: i64) -> R<()> {
    let mut rows = ps.query([id])?;
    while let Some(r) = rows.next()? {
        black_box(r.get::<_, i64>(0)?);
        black_box(r.get::<_, i32>(1)?);
        black_box(r.get::<_, i32>(2)?);
        black_box(r.get::<_, i32>(3)?);
        black_box(r.get::<_, i64>(4)?);
        black_box(r.get::<_, bool>(5)?);
        black_box(r.get::<_, f64>(6)?);
        black_box(r.get::<_, Option<f64>>(7)?);
        for c in 8..17 {
            black_box(r.get::<_, Option<String>>(c)?);
        }
        let amount: String = r.get(17)?;
        black_box(amount.parse::<rust_decimal::Decimal>()?);   // Java: getBigDecimal
        black_box(r.get::<_, Vec<u8>>(18)?);
        black_box(r.get::<_, Vec<u8>>(19)?);
    }
    Ok(())
}

fn read_wide_range(ps: &mut Statement<'_>, from: i64) -> R<()> {
    let mut rows = ps.query([from, from + 99])?;
    while let Some(r) = rows.next()? {
        black_box(r.get::<_, i64>(0)?);
        black_box(r.get::<_, String>(1)?);
        black_box(r.get::<_, f64>(2)?);
        black_box(r.get::<_, String>(3)?);
        black_box(r.get::<_, Vec<u8>>(4)?);
    }
    Ok(())
}

/// 1 writer + `readers` readers, each with its own connection to a fresh WAL copy of the template, as
/// WideConcurrentBenchmark with `-tg readers,1`.
fn wide(base: &Path, template: &Path, readers: usize, kind: &str) -> R<()> {
    let pool = Arc::new(load_pool(template)?);
    let dir = temp_dir(base, "rust-wide")?;
    let db = dir.join("wide.db");
    std::fs::copy(template, &db)?;
    Connection::open(&db)?.execute_batch("PRAGMA journal_mode = WAL")?;

    fn open(db: &Path) -> R<Connection> {
        let c = Connection::open(db)?;
        c.execute_batch("PRAGMA synchronous = FULL")?;
        Ok(c)
    }

    let stop = Arc::new(AtomicBool::new(false));
    let reads = Arc::new(AtomicU64::new(0));
    let writes = Arc::new(AtomicU64::new(0));
    let mut handles = Vec::new();
    {
        let (stop, writes, pool, db) = (stop.clone(), writes.clone(), pool.clone(), db.clone());
        handles.push(std::thread::spawn(move || -> R<()> {
            let c = open(&db)?;
            let mut ps = c.prepare(WIDE_INSERT)?;
            let mut next = WIDE_ROWS + 1;
            while !stop.load(Ordering::Relaxed) {
                bind_wide(&mut ps, &pool, next)?;
                next += 1;
                writes.fetch_add(1, Ordering::Relaxed);
            }
            Ok(())
        }));
    }
    for t in 0..readers {
        let point = kind == "point";
        let (stop, reads, db) = (stop.clone(), reads.clone(), db.clone());
        handles.push(std::thread::spawn(move || -> R<()> {
            let c = open(&db)?;
            let mut ps = c.prepare(if point { WIDE_ROW } else { WIDE_RANGE })?;
            let mut rnd = Rng(7 + t as u64);
            while !stop.load(Ordering::Relaxed) {
                if point {
                    read_wide_row(&mut ps, 1 + rnd.below((WIDE_ROWS - 100) as u64) as i64)?;
                } else {
                    read_wide_range(&mut ps, 1 + rnd.below((WIDE_ROWS - 100) as u64) as i64)?;
                }
                reads.fetch_add(1, Ordering::Relaxed);
            }
            Ok(())
        }));
    }
    let iter = Duration::from_secs(2);
    // The fresh 700 MB copy starts outside the page cache: run.sh passes the same longer warmup to JMH (-wi).
    let warmup: u32 = std::env::var("WIDE_WARMUP").ok().and_then(|v| v.parse().ok()).unwrap_or(WARMUP);
    let (mut rs, mut ws) = (Vec::new(), Vec::new());
    let (mut lr, mut lw, mut lt) = (reads.load(Ordering::Relaxed), writes.load(Ordering::Relaxed), Instant::now());
    for i in 0..warmup + MEASURE {
        std::thread::sleep(iter);
        let (r, w, t) = (reads.load(Ordering::Relaxed), writes.load(Ordering::Relaxed), Instant::now());
        let secs = t.duration_since(lt).as_secs_f64();
        if i >= warmup {
            rs.push((r - lr) as f64 / secs);
            ws.push((w - lw) as f64 / secs);
        }
        (lr, lw, lt) = (r, w, t);
    }
    stop.store(true, Ordering::Relaxed);
    for h in handles {
        h.join().unwrap()?;
    }
    report(&format!("wide_{kind}_r{readers}_read"), "ops/s", &rs);
    report(&format!("wide_{kind}_r{readers}_write"), "ops/s", &ws);
    std::fs::remove_dir_all(dir)?;
    Ok(())
}

// ------------------------------------------------------------------ main

fn main() -> R<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let probe = Connection::open_in_memory()?;
    let version: String = probe.query_row("SELECT sqlite_version()", [], |r| r.get(0))?;
    let compiler: String = probe.query_row(
        "SELECT compile_options FROM pragma_compile_options WHERE compile_options LIKE 'COMPILER=%'", [], |r| r.get(0))?;
    eprintln!("rusqlite 0.41 on SQLite {version} ({compiler})");
    drop(probe);
    let tmp = PathBuf::from(std::env::var("BENCH_TMP").unwrap_or_else(|_| "/dev/shm".into()));
    match args.first().map(String::as_str) {
        Some("workload") => workload(&tmp),
        Some("concurrent") => concurrent(&tmp),
        Some("types") => types(),
        Some("udf") => udf(),
        Some("bulk") => bulk(Path::new(&args[1]), Path::new(&args[2])),
        Some("wide") => wide(Path::new(&args[1]), Path::new(&args[2]), args[3].parse()?, &args[4]),
        _ => Err("usage: ffm-vs-rust workload|concurrent|types|udf|bulk <dir> <template>|wide <dir> <template> <readers> <point|range>".into()),
    }
}
