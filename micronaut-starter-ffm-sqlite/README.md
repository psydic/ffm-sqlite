# micronaut-starter-ffm-sqlite

Micronaut integration for [ffm-sqlite](../ffm-sqlite). It turns `application.yml` settings into pooled
`DataSource` beans that Micronaut Data JDBC and `@Transactional` use unchanged. It also gives each database
pooled connections for the ffm-sqlite API (functions, collations, backup, serialize, hooks) and a health
indicator. Built and tested with Micronaut 5.2 (platform 5.2.3).

```xml
<dependency>
    <groupId>io.github.revsnai</groupId>
    <artifactId>micronaut-starter-ffm-sqlite</artifactId>
    <version>0.2.7</version>
</dependency>
```

Requirements, as for ffm-sqlite: Java 25+, `--enable-native-access=ALL-UNNAMED` (the jar manifests set it for
`java -jar`), and a system `libsqlite3` (or `-Dffmsqlite.lib=/path/to/libsqlite3.so`).

## Configuration

```yaml
ffm-sqlite:
  datasources:
    default:                     # the primary bean; add more names for more databases
      path: data/app.db          # required: a file, :memory:, or a file: URI
      # Defaults shown below; they suit a web application.
      journal-mode: WAL          # readers do not block the writer
      synchronous: NORMAL        # safe with WAL; FULL also survives power loss
      foreign-keys: true         # SQLite's own default is off
      busy-timeout: 5s           # how long a write waits for another connection's write
      statement-cache-size: 64
      read-only: false
      create-directories: true   # create missing parent directories of path
      maximum-pool-size: 10
      minimum-idle: -1           # -1 = maximum-pool-size (a fixed pool)
      connection-timeout: 30s
      idle-timeout: 10m
      max-lifetime: 0s           # 0 = keep connections (and their caches) for the pool's life
      pragmas:                   # any other PRAGMA, on every new connection
        cache_size: -20000
        temp_store: MEMORY
        hard_heap_limit: 268435456 # caps SQLite's native heap for the whole process
      limits:                    # sqlite3_limit on every new connection (SQLite.Limit names, - for _)
        length: 10000000         # no string or BLOB over 10 MB
        sql-length: 1000000
      properties:                # other ffm-sqlite-jdbc driver properties (not the settings above or PRAGMAs)
        date_class: TEXT
    reports:
      path: data/reports.db
```

Each entry produces these beans, all qualified by the entry's name (`@Named("reports")`):

| Bean | What it is |
|---|---|
| `javax.sql.DataSource` | the HikariCP pool; with Micronaut Data present, wrapped to hand out the current transaction's connection |
| `FfmSqliteDatabase` | the pool, settings, `withConnection(...)` for the ffm-sqlite API, `openConnection()` |
| `FfmSqliteDataSourceConfiguration` | the bound settings |

Databases open at startup. A missing `path`, an out-of-range setting, an invalid PRAGMA name, or a file that
cannot be opened stops the application with a message naming the setting or the datasource. Under `properties`,
keys the typed settings own (`busy_timeout`, `readonly`, `journal_mode`, …) are rejected, so pooled connections
and `openConnection()` always get the same settings.

`:memory:` gives each pool one in-memory database, shared by all its connections. The database lives as long as
the application context, and each context gets its own. A plain SQLite `:memory:` would give every pooled
connection its own empty database; the starter opens a uniquely named `memdb` database instead and keeps one
connection open. Other in-memory URIs (`file::memory:`, `mode=memory`) are rejected for the same reason.

SQLite's memory is native, outside `-Xmx`. To bound it, set `hard_heap_limit` (and optionally `soft_heap_limit`,
lower) under `pragmas`, `length` under `limits`, and keep `cache_size × maximum-pool-size` within budget. With a
SQLite build that does not track memory use (`SQLITE_DEFAULT_MEMSTATUS=0`), startup fails rather than ignore the
limit, unless this is the first database opened or the JVM runs with `-Dffmsqlite.memstatus=true`.

In-memory databases have no WAL: while one connection has a write transaction open, reads on the others wait
(up to `busy-timeout`) and then fail with "database is locked". Keep write transactions short, or use a file
(on tmpfs if it must not touch disk) when reads and writes overlap.

## Micronaut Data JDBC

Add `micronaut-data-jdbc`, and `micronaut-data-processor` as an annotation processor. Use the SQLite dialect
(Micronaut Data 5.1+). Micronaut Data reads its own settings from `datasources.<name>`, so name the dialect
there; the DataSource itself still comes from the starter:

```yaml
datasources:
  default:
    dialect: SQLITE
    schema-generate: CREATE_DROP   # optional: Micronaut Data's SQLite DDL; use migrations in production
```

```java
@JdbcRepository(dialect = Dialect.SQLITE)
public interface BookRepository extends CrudRepository<Book, Long> {
    List<Book> findByPriceLessThanOrderByTitle(BigDecimal price);
}
```

The tests cover these on SQLite: CRUD, derived finders, counts, `BigDecimal`, `LocalDate`, `UUID` and `Instant`
columns, `@Transactional` commit and rollback, and `schema-generate`. `@Transactional(readOnly = true)` is
enforced: a write inside one fails, because the driver checks each statement before running it on a read-only
connection (since 0.2.4; switching costs nothing and keeps the statement cache). The generated
DDL declares `BigDecimal` columns as `DECIMAL`, which SQLite stores as `REAL` (about 15 significant digits);
for exact money, store integer cents or a TEXT column.

Do not add `micronaut-jdbc-hikari`: it would try to create a second `DataSource` from the same
`datasources.default` entry. Do not add `micronaut-jdbc-sqlite` either: it tells Micronaut Data that SQLite
connections cannot be made read-only, which turns off the read-only enforcement above.
[COMPARISON.md](COMPARISON.md) compares the starter with that official stack.

With Micronaut Data on the class path, the `DataSource` bean's `getConnection()` returns the current
transaction's connection. Outside `@Transactional` or `@Connectable`, the first use of that connection fails with
`NoConnectionException`. That is Micronaut Data's design, not the starter's. For work outside a transaction (startup DDL, maintenance),
use `FfmSqliteDatabase.getDataSource()`, the pool itself.

## The ffm-sqlite API

```java
@Inject FfmSqliteDatabase db;

long orders = db.withConnection(conn -> {          // a pooled io.github.revsnai.ffmsqlite.db.Connection
    try (var ps = conn.prepareStatement("SELECT count(*) FROM orders"); var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
    }
});

db.withConnection(conn -> { conn.backup("backup/app.db"); return null; });
```

The connection goes back to the pool, so a callback must undo what it changes. If it leaves a transaction open
(auto-commit off, or a `BEGIN` or `SAVEPOINT` in SQL), the uncommitted work is rolled back and `withConnection`
throws. If it closes the connection, the connection is dropped from the pool. Functions, listeners and handlers
it registers stay on that one pooled connection. For per-connection setup on every connection, use `pragmas`, or open your own
connection with `openConnection()`.

`withConnection` holds the core connection's lock while the callback runs, and ffm-sqlite-jdbc's `close()` takes
it too, so closing the pool on shutdown waits for the callback (interrupting its statement) instead of freeing the
connection under it. If you unwrap the core connection yourself (inside `@Transactional`), do the same:
`synchronized (core) { ... }`.

## Health

With `micronaut-management`, `/health` includes `ffm-sqlite`. (With Micronaut Data, Micronaut's generic `jdbc`
indicator appears too; it only runs a validity check.) For each database it runs a query on the
blocking executor and reports the journal mode, the SQLite version and pool usage (active, idle, total,
waiting). If no connection is free (all are in use and the pool is full, or callers are queued), it skips the query and reports UP with
`"check": "skipped: all connections in use"` instead of joining the queue. Set
`endpoints.health.ffm-sqlite.enabled: false` to turn it off. The details include the database path, which
Micronaut shows only to authenticated users by default (`endpoints.health.details-visible`).

```json
"ffm-sqlite": { "status": "UP", "details": { "default": {
    "path": "data/app.db", "status": "UP", "journalMode": "wal", "sqliteVersion": "3.50.3",
    "pool": { "active": 0, "idle": 10, "total": 10, "waiting": 0 } } } }
```

## Limitations

- GraalVM native image is not supported: FFM downcalls need reachability metadata that ffm-sqlite does not ship.
- Pooled connections are shared state. Per-request listeners or functions belong on a connection from
  `openConnection()`, closed after the request.

## Notes for SQLite under a web server

- Controllers that touch the database should run off the event loop:
  `@ExecuteOn(TaskExecutors.BLOCKING)`, as in the test's `BookController`.
- SQLite runs one write at a time. WAL plus the busy timeout make concurrent writers wait instead of failing;
  the tests run 8 writers on 8 pooled connections. Keep write transactions short.
- A transaction that reads first and then writes can fail at once with `SQLITE_BUSY` when another connection
  commits in between. SQLite does not wait in that case, because waiting could deadlock. Retry such
  transactions, or write first.
