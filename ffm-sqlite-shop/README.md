# ffm-sqlite shop: a training application

A small but complete e-commerce backend (catalogue, search, customers, carts, checkout, order life cycle,
reports, backup) on **ffm-sqlite-jdbc**. It is meant to be read: every integration topic sits in one place,
explained in the code comments, and the tests show the guarantees each one gives.

Plain JDBC, HikariCP and the JDK's HTTP server only, no web framework or ORM, so nothing hides the database code.
Everything here carries over to Spring, jOOQ or JPA, since they all sit on the same `DataSource`.

## Run it

Needs JDK 25+ and Maven. SQLite comes from the system (`libsqlite3.so.0`, `libsqlite3.dylib`, `sqlite3.dll`);
on Debian/Ubuntu `apt install libsqlite3-0` if it is missing. FTS5 search needs a build with FTS5 (most
distributions enable it).

```bash
cd ffm-sqlite-shop
mvn package                                    # builds and runs the tests; target/ffm-sqlite-shop.jar
java -jar target/ffm-sqlite-shop.jar --demo-orders 300
# ffm-sqlite shop: schema v3, /…/shop.db, http://localhost:8080/api/products
```

Options: `--db shop.db`, `--port 8080`, `--readers N` (reader pool size), `--demo-orders N` (places N random orders
through the normal checkout so the reports have data). Use another SQLite library with
`-Dffmsqlite.lib=/path/to/libsqlite3.so`.

## A walk through the API

```bash
B=http://localhost:8080/api

curl "$B/products?limit=5"                     # keyset pagination: next page with &after=<last id>
curl "$B/products?category=2"
curl "$B/search?q=coffee%20ket"                # full-text, prefix on the last word, highlighted snippet

curl -X POST $B/customers -d '{"email":"you@example.com","name":"You"}'      # 201, again → 409
curl -X POST $B/carts -d '{"customerId":4}'                                   # → {"id":"<uuid>",…}
curl -X PUT  $B/carts/<uuid>/items/16 -d '{"quantity":2}'                     # add / change, 0 removes
curl -X POST $B/carts/<uuid>/checkout -d '{"customerId":4}'                   # 201 order, or 409 out of stock

curl $B/orders/<id>
curl -X POST $B/orders/<id>/pay                                               # PLACED → PAID, again → 409
curl -X POST $B/orders/<id>/cancel -d '{"from":"PAID"}'                       # puts the stock back
curl "$B/customers/4/orders"

curl "$B/reports/top-products?per=3"           # window functions: rank() per category
curl  $B/reports/daily-sales                   # running total
curl -X POST $B/admin/backup                   # online backup through the ffm-sqlite API
```

## The lessons

| # | Topic | Where | What to take away |
|---|---|---|---|
| 1 | Connecting | [`db/Database.java`](src/main/java/io/github/psydic/shop/db/Database.java) | `jdbc:ffmsqlite:` URL, PRAGMAs as URL parameters (applied to every pooled connection), **one writer pool of size 1 + a `query_only` reader pool**: SQLite has one writer at a time, so writes queue in the pool instead of failing with `SQLITE_BUSY` |
| 2 | Transactions | `Database.write` / `Database.read` | commit/rollback in one place; retry on `SQLTransientException` (SQLITE_BUSY, SQLState 40001); read transactions give a consistent snapshot in WAL mode |
| 3 | Migrations | [`db/Migrations.java`](src/main/java/io/github/psydic/shop/db/Migrations.java), [`db/migration/*.sql`](src/main/resources/db/migration) | version in `PRAGMA user_version`; multi-statement scripts (triggers included) run through `Statement.executeUpdate`; STRICT tables; money as integer cents |
| 4 | Queries | [`catalog/ProductRepository.java`](src/main/java/io/github/psydic/shop/catalog/ProductRepository.java) | bind everything with `?`; keyset pagination instead of OFFSET; FTS5 search with `bm25` and `snippet`, user input escaped for the MATCH syntax |
| 5 | A business transaction | [`order/OrderRepository.java`](src/main/java/io/github/psydic/shop/order/OrderRepository.java) `checkout` | conditional `UPDATE … WHERE stock >= ?` against overselling; `getGeneratedKeys()`; `addBatch`/`executeBatch`; all or nothing |
| 6 | Constraint errors | [`customer/CustomerRepository.java`](src/main/java/io/github/psydic/shop/customer/CustomerRepository.java) | let the UNIQUE index decide; `SQLIntegrityConstraintViolationException` with SQLState 23505 / 23503 / 23502 / 23514 |
| 7 | Upserts, UUID, java.time | [`cart/CartRepository.java`](src/main/java/io/github/psydic/shop/cart/CartRepository.java) | `ON CONFLICT DO UPDATE`; `setObject(uuid)` / `getObject(i, UUID.class)`; `setObject(instant)` / `getObject(i, Instant.class)`; `wasNull()` |
| 8 | State transitions | `OrderRepository.changeStatus` | `WHERE status = ?` as optimistic concurrency; `UPDATE … FROM` to restore stock |
| 9 | Beyond JDBC | `Database.backupTo` | `unwrap(io.github.psydic.ffmsqlite.db.Connection.class)` for ffm-sqlite features (backup, serialize, UDFs, hooks); translate its `SQLiteException` |
| 10 | Analytics | [`report/ReportRepository.java`](src/main/java/io/github/psydic/shop/report/ReportRepository.java) | `rank() OVER (PARTITION BY …)`, running totals, `date()` on stored timestamps |

[`Shop.java`](src/main/java/io/github/psydic/shop/Shop.java) is the service layer: it decides where transactions
begin and end; repositories only take a `Connection`. [`web/HttpApi.java`](src/main/java/io/github/psydic/shop/web/HttpApi.java)
maps `ShopException` to HTTP statuses.

## Integration checklist (for your own application)

1. **Dependency**: `io.github.psydic:ffm-sqlite-jdbc` (see [`pom.xml`](pom.xml)); it brings `ffm-sqlite`.
2. **Native access**: start the JVM with `--enable-native-access=ALL-UNNAMED`, or put
   `Enable-Native-Access: ALL-UNNAMED` in the jar manifest (done here by the shade plugin). Tests: surefire `argLine`.
3. **Fat jars**: merge `META-INF/services/java.sql.Driver` (`ServicesResourceTransformer`), or `DriverManager`
   will not find the driver. Or skip discovery and use `FfmSqliteDataSource`.
4. **Configuration**: `journal_mode=WAL`, `foreign_keys=true` (off by default in SQLite!), `busy_timeout`,
   `synchronous=NORMAL` with WAL; as URL parameters or driver properties.
5. **Pooling**: one writer connection, several readers. For a single pool, keep it small and expect to retry
   transient errors.
6. **Coming from sqlite-jdbc**: replace `jdbc:sqlite:` with `jdbc:ffmsqlite:`; the property names are the same.
   `setObject`/`getObject` with `java.time` and `UUID` work here but not in sqlite-jdbc; if the code must run on
   both, bind strings instead.
7. **Errors**: catch the standard subclasses (`SQLIntegrityConstraintViolationException`, `SQLTransientException`)
   and SQLStates rather than parsing messages.

## Tests

`mvn test` runs them against a fresh database in a temporary directory:

- [`CheckoutTest`](src/test/java/io/github/psydic/shop/CheckoutTest.java): totals, rollback on short stock,
  **40 concurrent buyers for 10 items sell exactly 10**, status transitions, duplicate emails, backup.
- [`MigrationsTest`](src/test/java/io/github/psydic/shop/MigrationsTest.java): idempotent migrations, search
  index kept in sync by triggers, FTS5 syntax in user input, reader connections cannot write.
- [`HttpApiTest`](src/test/java/io/github/psydic/shop/HttpApiTest.java): the API over real HTTP.

## Exercises

1. **Product reviews**: add `V4__reviews.sql` (a `review` table with a CHECK on a 1–5 rating and a UNIQUE
   (customer, product)), an endpoint to post one, and the average rating in `Product`. Which SQLState do you get
   for a second review by the same customer?
2. **Remove the writer pool limit**: set the writer pool to 4 and run `CheckoutTest.concurrentCheckoutsNeverOversell`.
   Does it still pass? What does `Database.write`'s retry do, and why does `BEGIN IMMEDIATE` help?
3. **Coupons**: a `coupon` table with a usage limit, applied during checkout. Make sure two simultaneous checkouts
   cannot use the last coupon twice (hint: the same pattern as stock).
4. **A user-defined function**: unwrap a connection and register `format_money(cents)` with
   `createFunction`. Why must it be registered on every pooled connection (HikariCP's
   `connectionInitSql` cannot do it)?
5. **Low-stock alerts**: use `addUpdateListener` on the unwrapped writer connection to log when a product's stock
   drops below 5.
6. **Spring Boot**: replace `Database` with a Spring `DataSource` bean (`spring.datasource.url=jdbc:ffmsqlite:shop.db?…`)
   and the repositories with `JdbcTemplate`. What stays the same?
