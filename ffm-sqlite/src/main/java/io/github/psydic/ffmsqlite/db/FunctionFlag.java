package io.github.psydic.ffmsqlite.db;

/** Options for user-defined functions (SQLite's {@code eTextRep} flags). */
public enum FunctionFlag {
    /**
     * Same arguments always give the same result. Lets SQLite factor the call out of loops, and is
     * required to use the function in indexes, CHECK constraints and generated columns.
     */
    DETERMINISTIC(Native.SQLITE_DETERMINISTIC),
    /** Callable only from top-level SQL, not from triggers, views, CHECK constraints or indexes in the schema. */
    DIRECT_ONLY(Native.SQLITE_DIRECTONLY),
    /** Has no side effects and cannot leak information, so untrusted schemas may use it. */
    INNOCUOUS(Native.SQLITE_INNOCUOUS);

    final int bit;

    FunctionFlag(int bit) {
        this.bit = bit;
    }
}
