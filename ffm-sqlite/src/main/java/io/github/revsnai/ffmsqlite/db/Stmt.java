package io.github.revsnai.ffmsqlite.db;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** A compiled {@code sqlite3_stmt*} plus metadata that is fixed once it is prepared. */
final class Stmt {
    final String sql;
    final MemorySegment ptr;
    final int paramCount;
    /** The result columns; may change when SQLite re-prepares the statement (see {@link #refreshIfReprepared}). */
    int columnCount;
    private String[] names;
    private Map<String, Integer> indexByLabel;
    private int reprepares;

    Stmt(String sql, MemorySegment ptr) {
        this.sql = sql;
        this.ptr = ptr;
        this.paramCount = Native.bind_parameter_count(ptr);
        this.columnCount = Native.column_count(ptr);
        this.reprepares = Native.stmt_status(ptr, Native.SQLITE_STMTSTATUS_REPREPARE);
    }

    /**
     * Call after the first step of an execution. A schema change (here or in another connection), restore() or
     * deserialize() makes SQLite re-prepare the statement on that step, and {@code SELECT *} may then return other
     * columns: reload the column information so names and indexes match the rows.
     */
    void refreshIfReprepared() {
        int n = Native.stmt_status(ptr, Native.SQLITE_STMTSTATUS_REPREPARE);
        if (n != reprepares) {
            reprepares = n;
            columnCount = Native.column_count(ptr);
            names = null;
            indexByLabel = null;
        }
    }

    String[] names() {
        if (names == null) {
            String[] n = new String[columnCount];
            for (int i = 0; i < columnCount; i++) {
                n[i] = Native.column_name(ptr, i);
            }
            names = n;
        }
        return names;
    }

    /** 1-based index of the first column with this label, case-insensitively (as JDBC); -1 if absent. */
    int indexOf(String label) {
        if (indexByLabel == null) {
            String[] n = names();
            Map<String, Integer> m = new HashMap<>(n.length * 2);
            for (int i = n.length - 1; i >= 0; i--) {
                m.put(n[i].toLowerCase(Locale.ROOT), i + 1);
            }
            indexByLabel = m;
        }
        Integer idx = indexByLabel.get(label.toLowerCase(Locale.ROOT));
        return idx == null ? -1 : idx;
    }

    void finalizeQuietly() {
        Native.finalize(ptr);
    }
}
