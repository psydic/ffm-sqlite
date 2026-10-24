-- V2: full-text product search with FTS5, kept in sync with the product table by triggers.
-- An "external content" index: product_fts stores only the index; the text stays in product.

CREATE VIRTUAL TABLE product_fts USING fts5(
    name, description,
    content = 'product', content_rowid = 'id',
    tokenize = 'porter unicode61'                 -- stemming: "lamps" finds "lamp"
);

CREATE TRIGGER product_fts_insert AFTER INSERT ON product BEGIN
    INSERT INTO product_fts (rowid, name, description) VALUES (new.id, new.name, new.description);
END;

CREATE TRIGGER product_fts_delete AFTER DELETE ON product BEGIN
    INSERT INTO product_fts (product_fts, rowid, name, description) VALUES ('delete', old.id, old.name, old.description);
END;

CREATE TRIGGER product_fts_update AFTER UPDATE OF name, description ON product BEGIN
    INSERT INTO product_fts (product_fts, rowid, name, description) VALUES ('delete', old.id, old.name, old.description);
    INSERT INTO product_fts (rowid, name, description) VALUES (new.id, new.name, new.description);
END;

-- Index any rows that already exist.
INSERT INTO product_fts (product_fts) VALUES ('rebuild');
