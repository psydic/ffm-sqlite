package io.github.revsnai.ffmsqlite.db;

import io.github.revsnai.ffmsqlite.db.DatabaseMetaData.ColumnInfo;
import io.github.revsnai.ffmsqlite.db.DatabaseMetaData.ForeignKeyInfo;
import io.github.revsnai.ffmsqlite.db.DatabaseMetaData.Generated;
import io.github.revsnai.ffmsqlite.db.DatabaseMetaData.IndexColumn;
import io.github.revsnai.ffmsqlite.db.DatabaseMetaData.IndexInfo;
import io.github.revsnai.ffmsqlite.db.DatabaseMetaData.IndexOrigin;
import io.github.revsnai.ffmsqlite.db.DatabaseMetaData.TableInfo;
import io.github.revsnai.ffmsqlite.db.DatabaseMetaData.TableType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaDataTest {
    private Connection conn;
    private DatabaseMetaData md;

    @BeforeEach
    void open() throws SQLiteException {
        conn = SQLite.getConnection(":memory:");
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE parent (
                        id INTEGER PRIMARY KEY,
                        code TEXT NOT NULL UNIQUE,
                        created TEXT DEFAULT CURRENT_TIMESTAMP,
                        doubled INTEGER GENERATED ALWAYS AS (id * 2) STORED
                    );
                    CREATE INDEX ix_parent_code_lower ON parent (lower(code));
                    CREATE TABLE child (
                        id INTEGER,
                        name TEXT COLLATE NOCASE,
                        parent_id INTEGER REFERENCES parent (id) ON DELETE CASCADE,
                        owner INTEGER REFERENCES parent,
                        PRIMARY KEY (name, id)
                    ) WITHOUT ROWID;
                    CREATE INDEX ix_child_parent ON child (parent_id DESC) WHERE parent_id IS NOT NULL;
                    CREATE TABLE strict_t (a INTEGER, b TEXT) STRICT;
                    CREATE TABLE plain (x);
                    CREATE VIEW parent_codes AS SELECT code FROM parent;
                    INSERT INTO parent (code) VALUES ('a');
                    """);
        }
        md = conn.getMetaData();
    }

    @AfterEach
    void close() {
        conn.close();
    }

    @Test
    void tablesAndViews() throws SQLiteException {
        List<TableInfo> tables = md.getTables();
        assertEquals(List.of("child", "parent", "parent_codes", "plain", "strict_t"), tables.stream().map(TableInfo::name).toList());
        TableInfo child = tables.getFirst();
        assertEquals(TableType.TABLE, child.type());
        assertTrue(child.withoutRowId());
        assertEquals(4, child.columnCount());
        assertTrue(child.sql().startsWith("CREATE TABLE child"), child.sql());
        assertEquals(TableType.VIEW, tables.get(2).type());
        assertTrue(tables.get(4).strict());
        assertFalse(tables.get(1).strict());
        assertEquals(md.getSQLiteVersion(), SQLite.version());
    }

    @Test
    void columns() throws SQLiteException {
        List<ColumnInfo> cols = md.getColumns("parent");
        assertEquals(List.of("id", "code", "created", "doubled"), cols.stream().map(ColumnInfo::name).toList());
        assertEquals(new ColumnInfo("id", "INTEGER", false, null, 1, Generated.NONE, false), cols.get(0));
        assertTrue(cols.get(1).notNull());
        assertEquals("CURRENT_TIMESTAMP", cols.get(2).defaultValue());
        assertEquals(Generated.STORED, cols.get(3).generated());
        assertNull(md.getColumns("plain").getFirst().declaredType(), "no declared type");
        assertEquals(List.of(), md.getColumns("no_such_table"));
    }

    @Test
    void primaryKeys() throws SQLiteException {
        assertEquals(List.of("id"), md.getPrimaryKey("parent"));
        assertEquals(List.of("name", "id"), md.getPrimaryKey("child"), "in key order, not column order");
        assertEquals(List.of(), md.getPrimaryKey("plain"));
    }

    @Test
    void indexes() throws SQLiteException {
        List<IndexInfo> parent = md.getIndexes("parent");
        IndexInfo unique = parent.stream().filter(i -> i.origin() == IndexOrigin.UNIQUE_CONSTRAINT).findFirst().orElseThrow();
        assertTrue(unique.unique());
        assertEquals(List.of(new IndexColumn("code", false, "BINARY")), unique.columns());
        IndexInfo expr = parent.stream().filter(i -> i.name().equals("ix_parent_code_lower")).findFirst().orElseThrow();
        assertEquals(IndexOrigin.CREATE_INDEX, expr.origin());
        assertNull(expr.columns().getFirst().name(), "expression column");

        List<IndexInfo> child = md.getIndexes("child");
        IndexInfo pk = child.stream().filter(i -> i.origin() == IndexOrigin.PRIMARY_KEY).findFirst().orElseThrow();
        assertEquals(List.of(new IndexColumn("name", false, "NOCASE"), new IndexColumn("id", false, "BINARY")), pk.columns());
        IndexInfo partial = child.stream().filter(i -> i.name().equals("ix_child_parent")).findFirst().orElseThrow();
        assertTrue(partial.partial());
        assertFalse(partial.unique());
        assertEquals(List.of(new IndexColumn("parent_id", true, "BINARY")), partial.columns());
    }

    @Test
    void foreignKeys() throws SQLiteException {
        List<ForeignKeyInfo> fks = md.getForeignKeys("child");
        assertEquals(2, fks.size());
        ForeignKeyInfo explicit = fks.stream().filter(f -> f.fromColumns().equals(List.of("parent_id"))).findFirst().orElseThrow();
        assertEquals("parent", explicit.referencedTable());
        assertEquals(List.of("id"), explicit.toColumns());
        assertEquals("CASCADE", explicit.onDelete());
        assertEquals("NO ACTION", explicit.onUpdate());
        ForeignKeyInfo implicit = fks.stream().filter(f -> f.fromColumns().equals(List.of("owner"))).findFirst().orElseThrow();
        assertEquals(Arrays.asList((String) null), implicit.toColumns(), "references the parent's primary key implicitly");
        assertEquals(List.of(), md.getForeignKeys("parent"));
    }

    @Test
    void attachedSchemasIncludingHostileNames() throws SQLiteException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("ATTACH ':memory:' AS aux; CREATE TABLE aux.notes (id INTEGER PRIMARY KEY, txt TEXT NOT NULL); "
                    + "ATTACH ':memory:' AS \"we\"\"ird; DROP TABLE parent\"; CREATE TABLE \"we\"\"ird; DROP TABLE parent\".t (v)");
        }
        List<String> schemas = md.getSchemas();
        assertTrue(schemas.containsAll(List.of("main", "aux", "we\"ird; DROP TABLE parent")), schemas.toString());
        assertEquals(List.of("notes"), md.getTables("aux").stream().map(TableInfo::name).toList());
        assertEquals(List.of("id", "txt"), md.getColumns("aux", "notes").stream().map(ColumnInfo::name).toList());
        assertEquals(List.of("t"), md.getTables("we\"ird; DROP TABLE parent").stream().map(TableInfo::name).toList());
        assertEquals(1, md.getTables().stream().filter(t -> t.name().equals("parent")).count(), "nothing was injected");
        assertEquals(List.of(), md.getTables("no_such_schema"));
    }
}
