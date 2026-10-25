# ffm-sqlite shop: a training application

A small but complete e-commerce backend (catalogue, search, customers, carts, checkout, order life cycle,
reports, backup) on **Micronaut** with **[micronaut-starter-ffm-sqlite](../micronaut-starter-ffm-sqlite)** and
**ffm-sqlite-jdbc**. It is meant to be read: every integration topic sits in one place, explained in the code
comments, and the tests show the guarantees each one gives.

Micronaut does the wiring, configuration and HTTP. The starter turns `application.yml` into connection pools.
The database code is still plain JDBC, with no ORM, so nothing hides it. Everything here carries over to
Micronaut Data, Spring, jOOQ or JPA, since they all sit on the same `DataSource`.

## Run it

Needs JDK 25+ and Maven. SQLite comes from the system (`libsqlite3.so.0`, `libsqlite3.dylib`, `sqlite3.dll`);
on Debian/Ubuntu `apt install libsqlite3-0` if it is missing. FTS5 search needs a build with FTS5 (most
distributions enable it).

```bash
cd ffm-sqlite-shop
mvn package                                    # builds and runs the tests; target/ffm-sqlite-shop.jar
java -jar target/ffm-sqlite-shop.jar --shop.demo-orders=300
# ffm-sqlite shop: schema v3, /…/shop.db, http://localhost:8080/api/products
```

Settings live in [`application.yml`](src/main/resources/application.yml). Any of them can be overridden on the
command line or through the environment:
- `--shop.db=shop.db` (or `SHOP_DB`)
- `--micronaut.server.port=8080`
- `--shop.readers=N`: the reader pool size
- `--shop.demo-orders=N`: places N random orders through the normal checkout, so the reports have data

Use another SQLite library with `-Dffmsqlite.lib=/path/to/libsqlite3.so`.

## A walk through the API

```bash
B=http://localhost:8080/api
J='Content-Type: application/json'               # Micronaut accepts JSON bodies only with this header

curl "$B/products?limit=5"                     # keyset pagination: next page with &after=<last id>
curl "$B/products?category=2"
curl "$B/search?q=coffee%20ket"                # full-text, prefix on the last word, highlighted snippet

curl -H "$J" -X POST $B/customers -d '{"email":"you@example.com","name":"You"}'      # 201, again → 409
curl -H "$J" -X POST $B/carts -d '{"customerId":4}'                                   # → {"id":"<uuid>",…}
curl -H "$J" -X PUT  $B/carts/<uuid>/items/16 -d '{"quantity":2}'                     # add / change, 0 removes
curl -H "$J" -X POST $B/carts/<uuid>/checkout -d '{"customerId":4}'                   # 201 order, or 409 out of stock

curl $B/orders/<id>
curl -X POST $B/orders/<id>/pay                                               # PLACED → PAID, again → 409
curl -X POST $B/orders/<id>/cancel                                             # PLACED/PAID only; restocks once
curl "$B/customers/4/orders"

curl "$B/reports/top-products?per=3"           # window functions: rank() per category
curl  $B/reports/daily-sales                   # running total
curl -X POST $B/admin/backup                   # online backup through the ffm-sqlite API
curl http://localhost:8080/health               # Micronaut health, with the starter's ffm-sqlite details per pool
```

## The lessons

| # | Topic | Where | What to take away |
|---|---|---|---|
| 1 | Connecting | [`application.yml`](src/main/resources/application.yml), [`db/Database.java`](src/main/java/io/github/revsnai/shop/db/Database.java) | two starter datasources on one file: **a `writer` pool of size 1 + a `query_only` `reader` pool**. SQLite has one writer at a time, so writes queue in the pool instead of failing with `SQLITE_BUSY`. The starter applies WAL, `synchronous=NORMAL`, foreign keys and the busy timeout to every pooled connection, and injects each pool as `@Named FfmSqliteDatabase` |
| 2 | Transactions | `Database.write` / `Database.read` | commit/rollback in one place; retry on `SQLTransientException` (SQLITE_BUSY, SQLState 40001); read transactions give a consistent snapshot in WAL mode |
| 3 | Migrations | [`db/Migrations.java`](src/main/java/io/github/revsnai/shop/db/Migrations.java), [`db/migration/*.sql`](src/main/resources/db/migration), [`ShopStartup.java`](src/main/java/io/github/revsnai/shop/ShopStartup.java) | run on Micronaut's `StartupEvent`, before the server accepts requests; version in `PRAGMA user_version`; multi-statement scripts (triggers included) run through `Statement.executeUpdate`; STRICT tables; money as integer cents |
| 4 | Queries | [`catalog/ProductRepository.java`](src/main/java/io/github/revsnai/shop/catalog/ProductRepository.java) | bind everything with `?`; keyset pagination instead of OFFSET; FTS5 search with `bm25` and `snippet`, user input escaped for the MATCH syntax |
| 5 | A business transaction | [`order/OrderRepository.java`](src/main/java/io/github/revsnai/shop/order/OrderRepository.java) `checkout` | conditional `UPDATE … WHERE stock >= ?` against overselling; `getGeneratedKeys()`; `addBatch`/`executeBatch`; all or nothing |
| 6 | Constraint errors | [`customer/CustomerRepository.java`](src/main/java/io/github/revsnai/shop/customer/CustomerRepository.java) | let the UNIQUE index decide; `SQLIntegrityConstraintViolationException` with SQLState 23505 / 23503 / 23502 / 23514 |
| 7 | Upserts, UUID, java.time | [`cart/CartRepository.java`](src/main/java/io/github/revsnai/shop/cart/CartRepository.java) | `ON CONFLICT DO UPDATE`; `setObject(uuid)` / `getObject(i, UUID.class)`; `setObject(instant)` / `getObject(i, Instant.class)`; `wasNull()` |
| 8 | State transitions | `OrderRepository.changeStatus` | `WHERE status = ?` as optimistic concurrency; `UPDATE … FROM` to restore stock |
| 9 | Beyond JDBC | `Database.backupTo` | `FfmSqliteDatabase.withConnection` lends a pooled connection as an ffm-sqlite `Connection` (the same object `unwrap(io.github.revsnai.ffmsqlite.db.Connection.class)` returns) for backup, serialize, UDFs, hooks; translate its `SQLiteException` |
| 10 | Analytics | [`report/ReportRepository.java`](src/main/java/io/github/revsnai/shop/report/ReportRepository.java) | `rank() OVER (PARTITION BY …)`, running totals, `date()` on stored timestamps |
| 11 | The web layer | [`web/ShopController.java`](src/main/java/io/github/revsnai/shop/web/ShopController.java), [`web/ShopExceptionHandler.java`](src/main/java/io/github/revsnai/shop/web/ShopExceptionHandler.java), [`web/JsonTypes.java`](src/main/java/io/github/revsnai/shop/web/JsonTypes.java) | JDBC blocks, so the controller runs on `TaskExecutors.BLOCKING`, never the event loop; `ShopException` → 400/404/409; `@SerdeImport` makes the domain records JSON without annotating them |
| 12 | Health | `/health` | the starter's indicator runs a query on each pool and shows journal mode, SQLite version and pool usage |

[`Shop.java`](src/main/java/io/github/revsnai/shop/Shop.java) is the service layer, a Micronaut singleton. It
decides where transactions begin and end. Repositories only take a `Connection` and have no framework
dependency.

## Integration checklist (for your own application)

1. **Dependency**: `io.github.revsnai:micronaut-starter-ffm-sqlite` (see [`pom.xml`](pom.xml)). It brings
   `ffm-sqlite-jdbc`, `ffm-sqlite` and HikariCP. Without Micronaut, depend on `ffm-sqlite-jdbc` and build the
   pools yourself, as shop 0.1.0 did.
2. **Native access**: start the JVM with `--enable-native-access=ALL-UNNAMED`, or put
   `Enable-Native-Access: ALL-UNNAMED` in the jar manifest (done here by the shade plugin). Tests: surefire `argLine`.
3. **Fat jars**: merge `META-INF/services` files (`ServicesResourceTransformer`). This sample uses the shade
   plugin; the Micronaut Maven plugin works too.
4. **Configuration**: `journal_mode=WAL`, `foreign_keys=true` (off by default in SQLite!), `busy_timeout`, and
   `synchronous=NORMAL` with WAL. These are the starter's defaults; any other PRAGMA goes under `pragmas:`.
5. **Pooling**: one writer connection, several readers. For a single pool, keep it small and expect to retry
   transient errors.
6. **Coming from sqlite-jdbc**: replace `jdbc:sqlite:` with `jdbc:ffmsqlite:`; the property names are the same.
   `setObject`/`getObject` with `java.time` and `UUID` work here but not in sqlite-jdbc; if the code must run on
   both, bind strings instead.
7. **Errors**: catch the standard subclasses (`SQLIntegrityConstraintViolationException`, `SQLTransientException`)
   and SQLStates rather than parsing messages.

## Tests

`mvn test` runs them against a fresh database in a temporary directory:

- [`CheckoutTest`](src/test/java/io/github/revsnai/shop/CheckoutTest.java): totals, rollback on short stock,
  **40 concurrent buyers for 10 items sell exactly 10**, status transitions, duplicate emails, backup.
- [`MigrationsTest`](src/test/java/io/github/revsnai/shop/MigrationsTest.java): idempotent migrations, search
  index kept in sync by triggers, FTS5 syntax in user input, reader connections cannot write.
- [`HttpApiTest`](src/test/java/io/github/revsnai/shop/HttpApiTest.java): the API over real HTTP, on the Micronaut
  server, and `/health`.

Each test starts its own application context ([`TestShop`](src/test/java/io/github/revsnai/shop/TestShop.java)),
with `shop.db` in a temporary directory and `shop.fixed-time` for a fixed clock.

## Exercises

1. **Product reviews**: add `V4__reviews.sql` (a `review` table with a CHECK on a 1–5 rating and a UNIQUE
   (customer, product)), an endpoint to post one, and the average rating in `Product`. Which SQLState do you get
   for a second review by the same customer?
2. **Remove the writer pool limit**: set `ffm-sqlite.datasources.writer.maximum-pool-size` to 4 and run `CheckoutTest.concurrentCheckoutsNeverOversell`.
   Does it still pass? What does `Database.write`'s retry do, and why does `BEGIN IMMEDIATE` help?
3. **Coupons**: a `coupon` table with a usage limit, applied during checkout. Make sure two simultaneous checkouts
   cannot use the last coupon twice (hint: the same pattern as stock).
4. **A user-defined function**: register `format_money(cents)` with `createFunction` inside
   `withConnection`. Why does the function then work on only one of the reader connections? How would you
   register it on every pooled connection?
5. **Low-stock alerts**: use `addUpdateListener` on the unwrapped writer connection to log when a product's stock
   drops below 5.
6. **Micronaut Data**: replace `CustomerRepository` with a `@JdbcRepository(dialect = Dialect.ANSI)` interface
   (see the starter's README for the `datasources.<name>.dialect` setting) and use `@Transactional` for
   `register`. Which pool should it use, and what happens to the retry in `Database.write`?
