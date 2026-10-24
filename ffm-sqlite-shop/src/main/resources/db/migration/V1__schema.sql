-- V1: the shop schema.
-- STRICT tables (SQLite 3.37+) reject values of the wrong type, like other databases do.
-- Money is stored as INTEGER cents, never REAL: binary floating point cannot represent 0.10 exactly.
-- Timestamps are TEXT in SQLite's own format ('YYYY-MM-DD HH:MM:SS.SSS', UTC): what ffm-sqlite-jdbc
-- writes for setObject(Instant), and what SQLite's date functions read.

CREATE TABLE category (
    id   INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
) STRICT;

CREATE TABLE product (
    id          INTEGER PRIMARY KEY,               -- alias of the rowid: getGeneratedKeys() returns it
    sku         TEXT    NOT NULL UNIQUE,
    category_id INTEGER NOT NULL REFERENCES category (id),
    name        TEXT    NOT NULL,
    description TEXT    NOT NULL DEFAULT '',
    price_cents INTEGER NOT NULL CHECK (price_cents >= 0),
    stock       INTEGER NOT NULL CHECK (stock >= 0),  -- the CHECK is the last line of defence against overselling
    created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
) STRICT;
CREATE INDEX ix_product_category ON product (category_id, id);

CREATE TABLE customer (
    id         INTEGER PRIMARY KEY,
    email      TEXT NOT NULL UNIQUE COLLATE NOCASE,  -- unique regardless of case
    name       TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
) STRICT;

CREATE TABLE cart (
    id          TEXT PRIMARY KEY,                    -- a random UUID: setObject(uuid) / getObject(i, UUID.class)
    customer_id INTEGER REFERENCES customer (id),     -- NULL for a guest cart
    created_at  TEXT NOT NULL
) STRICT;

CREATE TABLE cart_item (
    cart_id    TEXT    NOT NULL REFERENCES cart (id) ON DELETE CASCADE,
    product_id INTEGER NOT NULL REFERENCES product (id),
    quantity   INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (cart_id, product_id)
) STRICT, WITHOUT ROWID;

CREATE TABLE orders (
    id          INTEGER PRIMARY KEY,
    customer_id INTEGER NOT NULL REFERENCES customer (id),
    status      TEXT    NOT NULL CHECK (status IN ('PLACED', 'PAID', 'SHIPPED', 'CANCELLED')),
    total_cents INTEGER NOT NULL CHECK (total_cents >= 0),
    placed_at   TEXT    NOT NULL
) STRICT;
CREATE INDEX ix_orders_customer ON orders (customer_id, id);
CREATE INDEX ix_orders_placed ON orders (placed_at);

CREATE TABLE order_line (
    order_id         INTEGER NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    line_no          INTEGER NOT NULL,
    product_id       INTEGER NOT NULL REFERENCES product (id),
    quantity         INTEGER NOT NULL CHECK (quantity > 0),
    unit_price_cents INTEGER NOT NULL,               -- the price at the time of the order
    PRIMARY KEY (order_id, line_no)
) STRICT;
CREATE INDEX ix_order_line_product ON order_line (product_id);
