package io.github.revsnai.ffmsqlite.db;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A connection to one SQLite database, in the role of {@code java.sql.Connection}.
 *
 * <p>Not thread-safe: as with most JDBC connections, use it from one thread at a time (handing it
 * between threads, e.g. through a pool, is fine). The connection is opened with SQLITE_OPEN_NOMUTEX,
 * so SQLite does not lock internally on every call.
 *
 * <p>Prepared statements are cached per SQL string: {@code prepareStatement(sql)} after an earlier
 * statement with the same SQL was closed reuses the compiled statement instead of re-parsing it.
 */
public final class Connection implements AutoCloseable {

    /** Something that must be closed before the connection itself closes. */
    interface Child {
        void closeFromConnection();
    }

    final MemorySegment db;
    private final Map<String, Stmt> cache;
    private final Set<Child> children = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, Udf> functions = new HashMap<>();
    /** Hook stubs (created once per hook kind, reused). */
    private Arena callbackArena;
    private final Map<String, CollationCallback> collations = new HashMap<>();
    /**
     * The upcall stubs of each registered function ("name/nArgs") and collation ("name"), one arena per
     * registration, closed when SQLite has accepted its replacement or removal: re-registering in a loop (e.g. on
     * every pool checkout) does not accumulate native memory.
     */
    private final Map<String, Arena> functionStubs = new HashMap<>();
    private final Map<String, Arena> collationStubs = new HashMap<>();
    private final Hooks hooks = new Hooks(this);
    private Throwable callbackFailure;
    private String callbackFailureMessage;
    private boolean autoCommit = true;
    /**
     * Manual-commit mode only: SQLite rolled back the whole transaction when a statement failed. Until
     * {@link #rollback()}, statements and {@link #commit()} fail instead of silently auto-committing.
     */
    private SQLiteException transactionRolledBackBy;
    /** The busy timeout set by the config or {@link #setBusyTimeout}; a query timeout may shorten it per statement. */
    private int busyTimeoutMillis;
    /** Whether the running statement's busy timeout was shortened to its query timeout's deadline. */
    private boolean busyCapped;
    /** Volatile and set under {@link #interruptLock}: {@link #interrupt()} runs on other threads. */
    private volatile boolean closed;
    /** Orders {@link #interrupt()} against {@link #close()}, so sqlite3_interrupt never sees a freed handle. */
    private final Object interruptLock = new Object();

    private Connection(MemorySegment db, int cacheSize) {
        this.db = db;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Stmt> eldest) {
                if (size() > cacheSize) {
                    eldest.getValue().finalizeQuietly();
                    return true;
                }
                return false;
            }
        };
    }

    static Connection open(String path, SQLite.Config config) throws SQLiteException {
        int flags = (config.readOnly() ? Native.SQLITE_OPEN_READONLY
                : Native.SQLITE_OPEN_READWRITE | Native.SQLITE_OPEN_CREATE)
                | Native.SQLITE_OPEN_URI | Native.SQLITE_OPEN_NOMUTEX;
        MemorySegment db;
        int rc;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ppDb = arena.allocate(ADDRESS);
            rc = Native.open_v2(arena.allocateFrom(path), ppDb, flags);
            db = ppDb.get(ADDRESS, 0);
        }
        if (rc != Native.SQLITE_OK) {
            SQLiteException e = SQLiteException.of(db, rc, "open " + path);
            Native.close_v2(db);
            throw e;
        }
        if (config.busyTimeoutMillis() > 0) {
            Native.busy_timeout(db, config.busyTimeoutMillis());
        }
        Connection conn = new Connection(db, config.statementCacheSize());
        conn.busyTimeoutMillis = config.busyTimeoutMillis();
        for (Map.Entry<String, String> pragma : inApplyOrder(config.pragmas())) {
            String sql = "PRAGMA " + pragma.getKey() + " = " + pragma.getValue();
            try {
                conn.runScript(sql, false);
            } catch (SQLiteException e) {
                conn.close();
                throw e;
            }
        }
        return conn;
    }

    // ------------------------------------------------------------------ JDBC-style API

    public Statement createStatement() throws SQLiteException {
        checkOpen();
        return register(new Statement(this));
    }

    /** Compiles {@code sql} (exactly one statement, with {@code ?}, {@code ?NNN} or {@code :name} parameters). */
    public PreparedStatement prepareStatement(String sql) throws SQLiteException {
        checkOpen();
        return register(new PreparedStatement(this, acquire(sql)));
    }

    public boolean getAutoCommit() throws SQLiteException {
        checkOpen();
        return autoCommit;
    }

    /**
     * {@code false} starts a transaction that {@link #commit()} / {@link #rollback()} end, each
     * starting the next one immediately. Switching back to {@code true} commits the open transaction; if that
     * commit fails, SQLite has rolled it back, a new transaction is started and the connection stays in
     * manual-commit mode (as after a failed {@link #commit()}).
     */
    public void setAutoCommit(boolean autoCommit) throws SQLiteException {
        checkOpen();
        if (autoCommit == this.autoCommit) {
            return;
        }
        if (autoCommit) {
            failIfRolledBack();
            endTransactionOrRestart("COMMIT", false);
        } else {
            run("BEGIN");
            transactionRolledBackBy = null;
        }
        this.autoCommit = autoCommit;
    }

    /**
     * Commits and starts the next transaction. If the commit fails (e.g. vetoed by a {@link CommitListener})
     * SQLite has rolled the transaction back; a new one is started either way, so the connection stays in
     * manual-commit mode.
     */
    public void commit() throws SQLiteException {
        checkManualCommit("commit");
        failIfRolledBack();
        endTransactionOrRestart("COMMIT", true);
    }

    /** Rolls back and starts the next transaction; also the way out after {@link SQLiteTransactionRolledBackException}. */
    public void rollback() throws SQLiteException {
        checkManualCommit("rollback");
        transactionRolledBackBy = null;
        endTransactionOrRestart("ROLLBACK", true);
    }

    /**
     * If SQLite rolled the transaction back on an earlier failure: starts a new transaction (the state after a failed
     * commit) and throws, so the lost work is reported instead of the COMMIT silently doing nothing.
     */
    private void failIfRolledBack() throws SQLiteException {
        if (transactionRolledBackBy != null) {
            SQLiteException e = rolledBack();
            transactionRolledBackBy = null;
            restartTransaction();
            throw e;
        }
    }

    private SQLiteTransactionRolledBackException rolledBack() {
        SQLiteTransactionRolledBackException e = new SQLiteTransactionRolledBackException(
                "the transaction was rolled back by SQLite when a statement failed (" + transactionRolledBackBy.getMessage()
                        + "); its earlier changes are lost: call rollback() to start a new transaction");
        e.initCause(transactionRolledBackBy);
        return e;
    }

    /**
     * Before a statement runs. In manual-commit mode SQLite must have a transaction open: if a failure rolled it
     * back, fail (see {@link SQLiteTransactionRolledBackException}); if SQL text ended it (COMMIT/ROLLBACK sent as a
     * statement), start the next one, as {@link #commit()} would.
     */
    void beforeStatement() throws SQLiteException {
        if (autoCommit || !Native.get_autocommit(db)) {
            return;
        }
        if (transactionRolledBackBy != null) {
            throw rolledBack();
        }
        run("BEGIN");
    }

    /** After a statement failed: records whether SQLite rolled back the manual-commit transaction with it. */
    void afterStatementFailure(SQLiteException failure) {
        if (!autoCommit && !closed && transactionRolledBackBy == null && Native.get_autocommit(db)) {
            transactionRolledBackBy = failure;
        }
    }

    /**
     * Runs COMMIT or ROLLBACK. On failure the next transaction is started before rethrowing, and a failure of that
     * BEGIN is added as suppressed, so the caller sees why the COMMIT/ROLLBACK failed. On success the next
     * transaction is started only if {@code restartOnSuccess}.
     */
    private void endTransactionOrRestart(String sql, boolean restartOnSuccess) throws SQLiteException {
        try {
            endTransaction(sql);
        } catch (SQLiteException | RuntimeException e) {
            try {
                restartTransaction();
            } catch (SQLiteException | RuntimeException again) {
                e.addSuppressed(again);
            }
            throw e;
        }
        if (restartOnSuccess) {
            restartTransaction();
        }
    }

    private void restartTransaction() throws SQLiteException {
        if (!closed && !autoCommit && Native.get_autocommit(db)) {
            run("BEGIN");
            transactionRolledBackBy = null;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /** PRAGMAs that set the file format: they only take effect before the database file is first written. */
    private static final Set<String> FILE_FORMAT_PRAGMAS = Set.of("page_size", "auto_vacuum", "encoding");

    /**
     * File-format PRAGMAs first, then the others in their given order: e.g. {@code journal_mode = WAL} writes the
     * file header, after which {@code page_size} would be silently ignored on a new database.
     */
    private static List<Map.Entry<String, String>> inApplyOrder(Map<String, String> pragmas) {
        List<Map.Entry<String, String>> ordered = new ArrayList<>(pragmas.entrySet());
        ordered.sort(Comparator.comparing(
                (Map.Entry<String, String> e) -> !FILE_FORMAT_PRAGMAS.contains(
                        e.getKey().substring(e.getKey().indexOf('.') + 1).toLowerCase(Locale.ROOT))));
        return ordered;   // List.sort is stable: the order within each group is kept
    }

    /** Closes every open statement and result set, then the database. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        checkNotInHook();
        for (Child child : new ArrayList<>(children)) {
            child.closeFromConnection();
        }
        children.clear();
        for (Stmt s : cache.values()) {
            s.finalizeQuietly();
        }
        cache.clear();
        hooks.detachAll();
        synchronized (interruptLock) {
            closed = true;   // from here on interrupt() is a no-op, and none is still running
        }
        Native.close_v2(db); // every statement is finalized and no hook is set, so this closes fully: no more upcalls
        functions.clear();
        collations.clear();
        if (callbackArena != null) {
            callbackArena.close();
        }
        functionStubs.values().forEach(Arena::close);
        functionStubs.clear();
        collationStubs.values().forEach(Arena::close);
        collationStubs.clear();
    }

    /** Schema information: tables, columns, indexes, foreign keys, attached databases. */
    public DatabaseMetaData getMetaData() throws SQLiteException {
        checkOpen();
        return new DatabaseMetaData(this);
    }

    // ------------------------------------------------------------------ extensions beyond JDBC

    /** The rowid of the most recent successful INSERT on this connection ({@code getGeneratedKeys()} in JDBC). */
    public long getLastInsertRowId() throws SQLiteException {
        checkOpen();
        return Native.last_insert_rowid(db);
    }

    // ------------------------------------------------------------------ user-defined functions

    /**
     * Registers (or replaces) a scalar SQL function. {@code nArgs} is the exact argument count, or
     * -1 for any; functions with the same name but different counts coexist. Names are
     * case-insensitive. Fails if statements are executing on this connection.
     */
    public void createFunction(String name, int nArgs, ScalarFunction function, FunctionFlag... flags) throws SQLiteException {
        Objects.requireNonNull(function, "function");
        Udf udf = new Udf(this, name, function, null);
        register(name, nArgs, flags, Udf.X_FUNC.bindTo(udf), null, null, null, null, udf);
    }

    /** Registers (or replaces) an aggregate SQL function; {@code factory} creates one accumulator per group. */
    public void createAggregateFunction(String name, int nArgs, Supplier<? extends AggregateFunction> factory,
                                        FunctionFlag... flags) throws SQLiteException {
        Objects.requireNonNull(factory, "factory");
        Udf udf = new Udf(this, name, null, factory);
        register(name, nArgs, flags, null, Udf.X_STEP.bindTo(udf), Udf.X_FINAL.bindTo(udf), null, null, udf);
    }

    /**
     * Registers (or replaces) an aggregate window function, usable with {@code OVER (...)} and as a plain
     * aggregate; {@code factory} creates one accumulator per partition (or group).
     */
    public void createWindowFunction(String name, int nArgs, Supplier<? extends WindowFunction> factory,
                                     FunctionFlag... flags) throws SQLiteException {
        Objects.requireNonNull(factory, "factory");
        Udf udf = new Udf(this, name, null, factory);
        register(name, nArgs, flags, null, Udf.X_STEP.bindTo(udf), Udf.X_FINAL.bindTo(udf),
                Udf.X_VALUE.bindTo(udf), Udf.X_INVERSE.bindTo(udf), udf);
    }

    /** Removes the function registered under {@code name} with exactly {@code nArgs} arguments. */
    public void removeFunction(String name, int nArgs) throws SQLiteException {
        register(name, nArgs, new FunctionFlag[0], null, null, null, null, null, null);
    }

    private void register(String name, int nArgs, FunctionFlag[] flags, MethodHandle xFunc, MethodHandle xStep,
                          MethodHandle xFinal, MethodHandle xValue, MethodHandle xInverse, Udf udf) throws SQLiteException {
        checkOpen();
        int textRep = Native.SQLITE_UTF8;
        for (FunctionFlag f : flags) {
            textRep |= f.bit;
        }
        Arena stubs = udf == null ? null : Arena.ofShared();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cName = arena.allocateFrom(name);
            int rc = xValue != null
                    ? Native.create_window_function(db, cName, nArgs, textRep, stub(xStep, Native.X_FUNC, stubs),
                            stub(xFinal, Native.X_FINAL, stubs), stub(xValue, Native.X_FINAL, stubs), stub(xInverse, Native.X_FUNC, stubs))
                    : Native.create_function_v2(db, cName, nArgs, textRep,
                            stub(xFunc, Native.X_FUNC, stubs), stub(xStep, Native.X_FUNC, stubs), stub(xFinal, Native.X_FINAL, stubs));
            if (rc != Native.SQLITE_OK) {
                throw SQLiteException.of(db, rc, (udf == null ? "remove" : "create") + " function " + name + "/" + nArgs);
            }
        } catch (SQLiteException | RuntimeException e) {
            if (stubs != null) {
                stubs.close();   // SQLite kept the previous definition, and its stubs
            }
            throw e;
        }
        String key = sqliteFold(name) + "/" + nArgs;
        if (udf == null) {
            functions.remove(key);
        } else {
            functions.put(key, udf);
        }
        // SQLite no longer calls the previous definition's stubs: a replacement only succeeds while no statement
        // runs, and it expires prepared statements, which re-prepare against the new definition.
        Arena previous = udf == null ? functionStubs.remove(key) : functionStubs.put(key, stubs);
        if (previous != null) {
            previous.close();
        }
    }

    /**
     * The key SQLite matches function and collation names by: case-insensitive for ASCII letters only. (Java's
     * toLowerCase also folds other letters, so "Äf" and "äf", two functions to SQLite, would share a key and replacing
     * one would free the other's upcall stubs while SQLite still calls them.)
     */
    static String sqliteFold(String name) {
        char[] c = name.toCharArray();
        for (int i = 0; i < c.length; i++) {
            if (c[i] >= 'A' && c[i] <= 'Z') {
                c[i] += 'a' - 'A';
            }
        }
        return new String(c);
    }

    private static MemorySegment stub(MethodHandle target, java.lang.foreign.FunctionDescriptor descriptor, Arena arena) {
        return target == null ? MemorySegment.NULL : Native.linker().upcallStub(target, descriptor, arena);
    }

    Arena callbackArena() {
        ensureCallbackArena();
        return callbackArena;
    }

    private void ensureCallbackArena() {
        if (callbackArena == null) {
            // Upcall stubs must outlive every call SQLite can make; close() frees them after close_v2.
            callbackArena = Arena.ofShared();
        }
    }

    // ------------------------------------------------------------------ collations

    /**
     * Registers (or replaces) a collating sequence usable as {@code COLLATE name} in queries, column
     * definitions and indexes. Names are case-insensitive. A {@link java.text.Collator} works directly:
     * {@code conn.createCollation("de", Collator.getInstance(Locale.GERMAN))}.
     *
     * <p>The comparator must be a consistent total order (antisymmetric, transitive, and equal only for
     * strings that may be treated as interchangeable); an index built with an inconsistent one is corrupt.
     * If it throws, the running statement fails with SQLITE_INTERRUPT and the exception as its cause.
     * Fails if statements are executing on this connection.
     */
    public void createCollation(String name, Comparator<? super String> comparator) throws SQLiteException {
        Objects.requireNonNull(comparator, "comparator");
        checkOpen();
        CollationCallback callback = new CollationCallback(this, name, comparator);
        Arena stubs = Arena.ofShared();
        try {
            registerCollation(name, Native.linker().upcallStub(CollationCallback.X_COMPARE.bindTo(callback),
                    Native.X_COMPARE, stubs), "create");
        } catch (SQLiteException | RuntimeException e) {
            stubs.close();
            throw e;
        }
        String key = sqliteFold(name);
        collations.put(key, callback);
        Arena previous = collationStubs.put(key, stubs);   // no longer called: see register()
        if (previous != null) {
            previous.close();
        }
    }

    /** Removes a collation registered with {@link #createCollation}. */
    public void removeCollation(String name) throws SQLiteException {
        checkOpen();
        registerCollation(name, MemorySegment.NULL, "remove");
        collations.remove(sqliteFold(name));
        Arena previous = collationStubs.remove(sqliteFold(name));
        if (previous != null) {
            previous.close();
        }
    }

    private void registerCollation(String name, MemorySegment xCompare, String action) throws SQLiteException {
        try (Arena arena = Arena.ofConfined()) {
            int rc = Native.create_collation_v2(db, arena.allocateFrom(name), xCompare);
            if (rc != Native.SQLITE_OK) {
                throw SQLiteException.of(db, rc, action + " collation " + name);
            }
        }
    }

    // ------------------------------------------------------------------ backup and restore

    /** Copies the {@code main} database to the file at {@code destinationPath}, replacing its content. */
    public void backup(String destinationPath) throws SQLiteException {
        backup(destinationPath, BackupOptions.DEFAULT);
    }

    /**
     * Copies {@code options.schema()} to the database file at {@code destinationPath} (created if needed,
     * otherwise overwritten), using SQLite's online backup: this connection stays usable and the copy is a
     * consistent snapshot. On failure or cancellation a newly created file is deleted, and an existing one
     * is left unchanged.
     */
    public void backup(String destinationPath, BackupOptions options) throws SQLiteException {
        checkOpen();
        Path file = plainFile(destinationPath);
        boolean existed = file != null && Files.exists(file);
        boolean done = false;
        try (Connection dest = open(destinationPath, SQLite.Config.DEFAULT.withStatementCacheSize(0))) {
            Backup.copy(dest, "main", this, options.schema(), options,
                    "backup " + options.schema() + " to " + destinationPath);
            done = true;
        } finally {
            if (!done && file != null && !existed) {
                deleteQuietly(file);
            }
        }
    }

    /** Copies the {@code main} database into {@code destination}'s {@code main} database, replacing its content. */
    public void backup(Connection destination) throws SQLiteException {
        backup(destination, BackupOptions.DEFAULT);
    }

    /** Copies {@code options.schema()} into {@code destination}'s {@code main} database, replacing its content. */
    public void backup(Connection destination, BackupOptions options) throws SQLiteException {
        checkOpen();
        destination.checkOpen();
        if (destination == this) {
            throw new SQLiteException("cannot back up a connection into itself");
        }
        Backup.copy(destination, "main", this, options.schema(), options, "backup " + options.schema() + " to connection");
    }

    /** Replaces the {@code main} database's content with the database file at {@code sourcePath}. */
    public void restore(String sourcePath) throws SQLiteException {
        restore(sourcePath, BackupOptions.DEFAULT);
    }

    /**
     * Replaces the content of {@code options.schema()} with the database file at {@code sourcePath} (opened
     * read-only). Statements prepared on this connection remain valid; SQLite re-prepares them against the
     * new schema. Fails, leaving the content unchanged, while this connection has a statement running.
     */
    public void restore(String sourcePath, BackupOptions options) throws SQLiteException {
        checkOpen();
        try (Connection source = open(sourcePath, SQLite.Config.DEFAULT.withReadOnly(true).withStatementCacheSize(0))) {
            Backup.copy(this, options.schema(), source, "main", options, "restore " + options.schema() + " from " + sourcePath);
        }
    }

    // ------------------------------------------------------------------ serialize and deserialize

    private static final byte[] SQLITE_HEADER = "SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** The {@code main} database as bytes: exactly the content of the equivalent database file. */
    public byte[] serialize() throws SQLiteException {
        return serialize("main");
    }

    /**
     * The database {@code schema} ({@code main}, {@code temp} or an ATTACHed name) as bytes, i.e. the content
     * of the equivalent database file, including changes of a transaction open on this connection. An empty
     * database gives an empty array or one empty page, depending on the SQLite version. Limited to databases under 2 GB (the maximum Java array).
     */
    public byte[] serialize(String schema) throws SQLiteException {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment size = arena.allocate(java.lang.foreign.ValueLayout.JAVA_LONG);
            MemorySegment data = Native.serialize(db, arena.allocateFrom(schema), size);
            long n = size.get(java.lang.foreign.ValueLayout.JAVA_LONG, 0);
            if (data.equals(MemorySegment.NULL)) {
                if (n == 0) {
                    return new byte[0]; // a database with no pages yet
                }
                throw new SQLiteException(n < 0 ? "serialize: unknown database " + schema + " (or it does not exist yet)"
                        : "serialize: out of memory copying " + n + " bytes of " + schema);
            }
            try {
                if (n > Integer.MAX_VALUE - 8) {
                    throw new SQLiteException("serialize: " + schema + " is " + n + " bytes, larger than a Java array; use backup instead");
                }
                byte[] out = new byte[(int) n];
                MemorySegment.copy(data.reinterpret(n), java.lang.foreign.ValueLayout.JAVA_BYTE, 0, out, 0, (int) n);
                return out;
            } finally {
                Native.free(data);
            }
        }
    }

    /** Replaces the {@code main} database with {@code image} (bytes from {@link #serialize} or a database file). */
    public void deserialize(byte[] image) throws SQLiteException {
        deserialize(image, "main", false);
    }

    /**
     * Replaces database {@code schema} with {@code image}, which becomes an <b>in-memory</b> database: later
     * changes are not written to any file that previously backed the schema (to load bytes into a file
     * database, deserialize into a fresh connection and {@link #backup(Connection) back it up}). SQLite takes
     * a private copy of the bytes, so {@code image} may be reused. {@code readOnly} rejects writes; otherwise the
     * database can grow (up to SQLite's in-memory limit, 1 GB by default). Images of WAL-mode databases are
     * switched to rollback-journal mode, which in-memory databases require.
     *
     * <p>Only the SQLite header is checked up front; deeper corruption surfaces as SQLITE_CORRUPT or NOTADB on
     * first use. Fails, leaving the schema unchanged, while statements are running on this connection.
     */
    public void deserialize(byte[] image, String schema, boolean readOnly) throws SQLiteException {
        checkOpen();
        // sqlite3_deserialize does not check this itself: it re-attaches the schema, freeing the B-tree that a
        // running statement's cursors still point into, and the next step of that statement crashes the process.
        if (Native.anyStatementBusy(db)) {
            throw new SQLiteException("deserialize into " + schema + ": statements are running on this connection; "
                    + "close their result sets first", 5, 5); // SQLITE_BUSY
        }
        // Nor does it refuse an active transaction: it would silently discard uncommitted changes.
        try (Arena arena = Arena.ofConfined()) {
            if (Native.txn_state(db, arena.allocateFrom(schema)) > 0) {
                throw new SQLiteException("deserialize into " + schema + ": a transaction is active on it; "
                        + "commit or roll back first", 5, 5);
            }
        }
        if (image.length > 0 && (image.length < 100 || !java.util.Arrays.equals(image, 0, SQLITE_HEADER.length,
                SQLITE_HEADER, 0, SQLITE_HEADER.length))) {
            throw new SQLiteException("deserialize: not an SQLite database image (bad header)", 26, 26); // SQLITE_NOTADB
        }
        // SQLite owns this copy (FREEONCLOSE) and may realloc it as the database grows (RESIZEABLE);
        // it frees it itself if deserialize fails.
        MemorySegment copy = Native.malloc64(Math.max(1, image.length));
        if (copy.equals(MemorySegment.NULL)) {
            throw new SQLiteException("deserialize: out of memory allocating " + image.length + " bytes", 7, 7);
        }
        MemorySegment buf = copy.reinterpret(image.length);
        MemorySegment.copy(image, 0, buf, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, image.length);
        if (image.length > 0) {
            // File format read/write versions: 2 = WAL, which the in-memory VFS cannot open; 1 = rollback journal.
            buf.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 18, (byte) 1);
            buf.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 19, (byte) 1);
        }
        int flags = Native.SQLITE_DESERIALIZE_FREEONCLOSE
                | (readOnly ? Native.SQLITE_DESERIALIZE_READONLY : Native.SQLITE_DESERIALIZE_RESIZEABLE);
        try (Arena arena = Arena.ofConfined()) {
            int rc = Native.deserialize(db, arena.allocateFrom(schema), copy, image.length, image.length, flags);
            if (rc != Native.SQLITE_OK) {
                throw SQLiteException.of(db, rc, "deserialize into " + schema);
            }
        }
    }

    /** The file behind a plain path; null for {@code :memory:}, temporary ({@code ""}) and {@code file:} URI databases. */
    private static Path plainFile(String path) {
        return path.isEmpty() || path.equals(":memory:") || path.startsWith("file:") ? null : Path.of(path);
    }

    private static void deleteQuietly(Path file) {
        for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) {
            try {
                Files.deleteIfExists(Path.of(file + suffix));
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    // ------------------------------------------------------------------ hooks

    /** Adds a listener for every row inserted, updated or deleted through this connection. */
    public void addUpdateListener(UpdateListener listener) throws SQLiteException {
        checkOpen();
        hooks.updateListeners.add(Objects.requireNonNull(listener, "listener"));
        hooks.sync();
    }

    public void removeUpdateListener(UpdateListener listener) throws SQLiteException {
        checkOpen();
        hooks.updateListeners.remove(listener);
        hooks.sync();
    }

    /** Adds a listener for commits (which it may veto) and rollbacks. */
    public void addCommitListener(CommitListener listener) throws SQLiteException {
        checkOpen();
        hooks.commitListeners.add(Objects.requireNonNull(listener, "listener"));
        hooks.sync();
    }

    public void removeCommitListener(CommitListener listener) throws SQLiteException {
        checkOpen();
        hooks.commitListeners.remove(listener);
        hooks.sync();
    }

    /**
     * Sets the busy handler, replacing the busy timeout. {@code null} removes it, so a locked database
     * fails at once with SQLITE_BUSY; use {@link #setBusyTimeout} to go back to timed waiting.
     */
    public void setBusyHandler(BusyHandler handler) throws SQLiteException {
        checkOpen();
        hooks.busyHandler = handler;
        hooks.syncBusyHandler();
    }

    /** Waits up to {@code millis} for a locked database before failing with SQLITE_BUSY; replaces any busy handler. */
    public void setBusyTimeout(int millis) throws SQLiteException {
        checkOpen();
        if (millis < 0) {
            throw new IllegalArgumentException("millis must be >= 0");
        }
        hooks.busyHandler = null;
        busyTimeoutMillis = millis;
        Native.busy_timeout(db, millis);
    }

    // ------------------------------------------------------------------ progress, timeouts, cancellation

    /**
     * Calls {@code handler} about every {@code instructions} virtual-machine instructions while a statement
     * runs (a few thousand is typical); {@code null} removes it. Query timeouts keep working either way,
     * checked at the same interval.
     */
    public void setProgressHandler(int instructions, ProgressHandler handler) throws SQLiteException {
        checkOpen();
        if (handler != null && instructions <= 0) {
            throw new IllegalArgumentException("instructions must be positive");
        }
        hooks.progressHandler = handler;
        hooks.progressInstructions = instructions;
        hooks.syncProgress();
    }

    /**
     * A query timeout in milliseconds, saturated at Integer.MAX_VALUE (about 24.8 days, i.e. no practical limit):
     * frameworks pass huge values such as {@code Integer.MAX_VALUE} seconds to mean "unlimited".
     */
    static int timeoutMillis(java.time.Duration timeout) {
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be >= 0");
        }
        return timeout.compareTo(java.time.Duration.ofMillis(Integer.MAX_VALUE)) >= 0 ? Integer.MAX_VALUE : (int) timeout.toMillis();
    }

    /** Starts a query-timeout window; returns the previous deadline for {@link #popDeadline}. */
    long pushDeadline(int millis) {
        if (!hooks.timeoutsUsed) {
            hooks.timeoutsUsed = true;
            hooks.syncProgress();
        }
        long previous = hooks.deadline;
        long deadline = System.nanoTime() + millis * 1_000_000L;
        hooks.deadline = deadline == 0 ? 1 : deadline;
        hooks.deadlineMillis = millis;
        limitBusyWait();
        return previous;
    }

    int deadlineMillis() {
        return hooks.deadlineMillis;
    }

    void popDeadline(long previous, int previousMillis) {
        hooks.deadline = previous;
        hooks.deadlineMillis = previousMillis;
        limitBusyWait();
    }

    /**
     * Query timeouts are checked in the progress callback, which does not run while SQLite waits for a lock. So
     * while a deadline is set, the busy timeout is shortened to it (a custom {@link BusyHandler} checks the deadline
     * in {@code Hooks.xBusy} instead), and a lock wait cut short this way fails as a timeout.
     */
    private void limitBusyWait() {
        if (hooks.busyHandler != null) {
            return;
        }
        int wait = busyTimeoutMillis;
        busyCapped = false;
        if (hooks.deadline != 0) {
            long remaining = Math.max(1, (hooks.deadline - System.nanoTime()) / 1_000_000);
            if (remaining < wait) {
                wait = (int) remaining;
                busyCapped = true;
            }
        }
        Native.busy_timeout(db, wait);
    }

    private static final Pattern MENTIONS_BUSY_TIMEOUT = Pattern.compile("busy_timeout", Pattern.CASE_INSENSITIVE);
    private static final Pattern SETS_BUSY_TIMEOUT = Pattern.compile(
            "^\\s*PRAGMA\\s+(?:\\w+\\s*\\.\\s*)?busy_timeout\\s*[=(]", Pattern.CASE_INSENSITIVE);

    static boolean setsBusyTimeout(String sql) {
        return MENTIONS_BUSY_TIMEOUT.matcher(sql).find() && SETS_BUSY_TIMEOUT.matcher(sql).find();
    }

    /**
     * Call right after preparing {@code PRAGMA busy_timeout = N}, which SQLite applies while preparing: it has replaced
     * any busy handler with its own timeout. Adopt N, so restoring the timeout after a query timeout keeps it.
     */
    private void adoptBusyTimeout() {
        hooks.busyHandler = null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocateFrom("PRAGMA busy_timeout");
            MemorySegment ppStmt = arena.allocate(ADDRESS);
            if (Native.prepare_v2(db, text, (int) text.byteSize(), ppStmt, MemorySegment.NULL) == Native.SQLITE_OK) {
                MemorySegment p = ppStmt.get(ADDRESS, 0);
                if (Native.step(p) == Native.SQLITE_ROW) {
                    busyTimeoutMillis = Native.column_int(p, 0);
                }
                Native.finalize(p);
            }
        }
    }

    boolean busyCapped() {
        return busyCapped;
    }

    /**
     * The exception for a failed step: the callback's failure as cause, and SQLITE_BUSY after a lock wait that was
     * shortened to the query timeout reported as {@link SQLiteTimeoutException}.
     */
    SQLiteException stepFailure(int rc, String context, boolean capped) {
        SQLiteException e = withCallbackCause(SQLiteException.of(db, rc, context));
        if (capped && (rc & 0xff) == Native.SQLITE_BUSY && !(e instanceof SQLiteTimeoutException)) {
            return new SQLiteTimeoutException(e.getMessage() + ": query timeout of " + hooks.deadlineMillis
                    + " ms exceeded while waiting for a lock", e.getErrorCode(), e.getExtendedErrorCode());
        }
        return e;
    }

    /**
     * Thread-safe (may be called from any thread, also while another thread uses the connection): aborts whatever
     * statement is running on this connection, which fails with SQLITE_INTERRUPT. A no-op once closed.
     */
    public void interrupt() {
        synchronized (interruptLock) {
            if (!closed) {
                Native.interrupt(db);
            }
        }
    }

    // ------------------------------------------------------------------ extensions

    /** Loads a SQLite extension (a shared library), with its default entry point. */
    public void loadExtension(String file) throws SQLiteException {
        loadExtension(file, null);
    }

    /**
     * Loads a SQLite extension from {@code file} (SQLite also tries platform suffixes such as {@code .so}),
     * calling {@code entryPoint}, or when null, {@code sqlite3_extension_init} and then a name derived from
     * the file name (e.g. {@code sqlite3_vec_init} for {@code vec0.so}).
     *
     * <p>Only this call is allowed to load code: extension loading is enabled for the C API just around it,
     * and the SQL function {@code load_extension()} stays disabled throughout, so SQL injection cannot load
     * libraries. Extensions run native code inside the JVM; load only trusted files.
     */
    public void loadExtension(String file, String entryPoint) throws SQLiteException {
        checkOpen();
        if (!Native.extensionLoadingAvailable()) {
            throw new SQLiteException("this SQLite library was built without extension loading (SQLITE_OMIT_LOAD_EXTENSION)");
        }
        int rc = Native.db_config(db, Native.SQLITE_DBCONFIG_ENABLE_LOAD_EXTENSION, 1);
        if (rc != Native.SQLITE_OK) {
            throw SQLiteException.of(db, rc, "enable extension loading");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment errOut = arena.allocate(ADDRESS);
            rc = Native.load_extension(db, arena.allocateFrom(file),
                    entryPoint == null ? MemorySegment.NULL : arena.allocateFrom(entryPoint), errOut);
            if (rc != Native.SQLITE_OK) {
                MemorySegment err = errOut.get(ADDRESS, 0);
                String message = Native.cString(err);
                Native.free(err);
                throw new SQLiteException("load extension " + file + ": " + message, rc & 0xff, rc);
            }
        } finally {
            Native.db_config(db, Native.SQLITE_DBCONFIG_ENABLE_LOAD_EXTENSION, 0);
        }
    }

    // ------------------------------------------------------------------ callback failures

    /**
     * First exception thrown by a Java callback during the current step; becomes the error's cause.
     * {@code message} is set when the SQLite error itself cannot carry it (collations abort via interrupt).
     */
    void recordCallbackFailure(Throwable t, String message) {
        if (callbackFailure == null) {
            callbackFailure = t;
            callbackFailureMessage = message;
        }
    }

    void clearCallbackFailure() {
        callbackFailure = null;
        callbackFailureMessage = null;
    }

    boolean hasCallbackFailure() {
        return callbackFailure != null;
    }

    /**
     * For a step that returned ROW or DONE although a callback failed: a collation that threw has no way
     * to fail the step, and sqlite3_interrupt is only checked at certain points, so the statement may
     * finish anyway, with results computed from the fallback comparison. Never return those.
     */
    SQLiteException callbackError(String context) {
        String code = Native.errstr(Native.SQLITE_INTERRUPT);
        return withCallbackCause(new SQLiteException("[" + code + "] " + code + " (" + context + ")",
                Native.SQLITE_INTERRUPT, Native.SQLITE_INTERRUPT));
    }

    SQLiteException withCallbackCause(SQLiteException e) {
        if (callbackFailure == null) {
            return e;
        }
        SQLiteException withCause = callbackFailure instanceof java.util.concurrent.TimeoutException
                ? new SQLiteTimeoutException(e.getMessage() + ": " + callbackFailureMessage, e.getErrorCode(), e.getExtendedErrorCode())
                : callbackFailureMessage == null ? e
                : new SQLiteException(e.getMessage() + ": " + callbackFailureMessage, e.getErrorCode(), e.getExtendedErrorCode());
        withCause.initCause(callbackFailure);
        clearCallbackFailure();
        return withCause;
    }

    /** For tests: accumulators currently alive across all aggregate functions. */
    int liveAggregateGroups() {
        return functions.values().stream().mapToInt(Udf::liveGroups).sum();
    }

    // ------------------------------------------------------------------ internals

    private <T extends Child> T register(T child) {
        children.add(child);
        return child;
    }

    void unregister(Child child) {
        children.remove(child);
    }

    void checkOpen() throws SQLiteException {
        if (closed) {
            throw new SQLiteException("connection is closed");
        }
        if (hooks.depth > 0) {
            throw new SQLiteException(HOOK_REENTRY);
        }
    }

    private static final String HOOK_REENTRY = "the connection cannot be used inside its own update, commit, rollback "
            + "or busy hook (SQLite forbids it); defer the work until the statement has returned";

    /** For close() methods, which cannot throw a checked exception. */
    void checkNotInHook() {
        if (hooks.depth > 0) {
            throw new IllegalStateException(HOOK_REENTRY);
        }
    }

    private void checkManualCommit(String op) throws SQLiteException {
        checkOpen();
        if (autoCommit) {
            throw new SQLiteException("cannot " + op + " when autoCommit is enabled");
        }
    }

    /** Ends the transaction if one is open (SQL issued through a Statement may already have ended it). */
    private void endTransaction(String sql) throws SQLiteException {
        if (!Native.get_autocommit(db)) {
            run(sql);
        }
    }

    /** Runs a parameterless statement through the cache, e.g. BEGIN / COMMIT. */
    private void run(String sql) throws SQLiteException {
        Stmt s = acquire(sql);
        try {
            stepToEndUntimed(s, sql); // attaches listener failures, e.g. a commit vetoed by a CommitListener
        } finally {
            release(s);
        }
    }

    /** A cached statement for {@code sql}, or a newly prepared one. Must be returned with {@link #release}. */
    Stmt acquire(String sql) throws SQLiteException {
        Stmt s = cache.remove(sql);
        return s != null ? s : prepareSingle(sql);
    }

    /** Resets {@code s} and returns it to the cache, or finalizes it when the cache already holds one. */
    void release(Stmt s) {
        Native.reset(s.ptr);
        Native.clear_bindings(s.ptr);
        if (closed || cache.containsKey(s.sql)) {
            s.finalizeQuietly();
        } else {
            cache.put(s.sql, s);
        }
    }

    private Stmt prepareSingle(String sql) throws SQLiteException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocateFrom(withoutNul(sql));
            MemorySegment ppStmt = arena.allocate(ADDRESS);
            MemorySegment pzTail = arena.allocate(ADDRESS);
            clearCallbackFailure();
            int rc = Native.prepare_v2(db, text, (int) text.byteSize(), ppStmt, pzTail); // may wait in the busy handler
            MemorySegment ptr = ppStmt.get(ADDRESS, 0);
            if (rc != Native.SQLITE_OK) {
                throw withCallbackCause(SQLiteException.of(db, rc, "prepare: " + sql));
            }
            if (ptr.equals(MemorySegment.NULL)) {
                throw new SQLiteException("no SQL statement in: " + sql);
            }
            long tail = pzTail.get(ADDRESS, 0).address() - text.address();
            if (!isBlank(text, tail)) {
                Native.finalize(ptr);
                throw new SQLiteException("prepareStatement accepts a single statement; use Statement.execute for scripts: " + sql);
            }
            if (setsBusyTimeout(sql)) {
                adoptBusyTimeout();
            }
            return new Stmt(sql, ptr);
        }
    }

    /** Runs every statement in {@code script}; returns the last one, still un-stepped, if it is the final query. */
    ScriptResult runScript(String script, boolean keepFinalQuery) throws SQLiteException {
        return runScript(script, keepFinalQuery, 0);
    }

    ScriptResult runScript(String script, boolean keepFinalQuery, int timeoutMillis) throws SQLiteException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocateFrom(withoutNul(script));
            long end = text.byteSize() - 1;
            MemorySegment ppStmt = arena.allocate(ADDRESS);
            MemorySegment pzTail = arena.allocate(ADDRESS);
            long offset = 0;
            long updateCount = -1;
            while (offset < end) {
                MemorySegment at = text.asSlice(offset);
                clearCallbackFailure();
                int rc = Native.prepare_v2(db, at, (int) (text.byteSize() - offset), ppStmt, pzTail);
                if (rc != Native.SQLITE_OK) {
                    throw withCallbackCause(SQLiteException.of(db, rc, "prepare: " + script));
                }
                MemorySegment ptr = ppStmt.get(ADDRESS, 0);
                offset = pzTail.get(ADDRESS, 0).address() - text.address();
                if (ptr.equals(MemorySegment.NULL)) {
                    continue; // only whitespace or a comment
                }
                if (MENTIONS_BUSY_TIMEOUT.matcher(script).find()) {
                    long start = at.address() - text.address();
                    if (setsBusyTimeout(new String(text.asSlice(start, offset - start).toArray(JAVA_BYTE), UTF_8))) {
                        adoptBusyTimeout();
                    }
                }
                Stmt s = new Stmt(script, ptr);
                if (keepFinalQuery && s.columnCount > 0 && isBlank(text, offset)) {
                    return new ScriptResult(s, -1);
                }
                try {
                    updateCount = stepToEnd(s, "execute: " + script, timeoutMillis);
                } finally {
                    s.finalizeQuietly();
                }
            }
            return new ScriptResult(null, updateCount);
        }
    }

    record ScriptResult(Stmt query, long updateCount) {
    }

    /**
     * Steps until SQLITE_DONE and returns the rows changed by this statement itself: 0 for DDL and
     * queries (sqlite3_changes would report the previous DML's count), excluding trigger changes.
     */
    long stepToEnd(Stmt s, String context) throws SQLiteException {
        return stepToEnd(s, context, 0);
    }

    /** As {@link #stepToEnd(Stmt, String)}, interrupting the statement after {@code timeoutMillis} (0 = none). */
    long stepToEnd(Stmt s, String context, int timeoutMillis) throws SQLiteException {
        beforeStatement();
        try {
            if (timeoutMillis <= 0) {
                return stepToEndUntimed(s, context);
            }
            int previousMillis = deadlineMillis();
            long previous = pushDeadline(timeoutMillis);
            try {
                return stepToEndUntimed(s, context);
            } finally {
                popDeadline(previous, previousMillis);
            }
        } catch (SQLiteException e) {
            afterStatementFailure(e);
            throw e;
        }
    }

    private long stepToEndUntimed(Stmt s, String context) throws SQLiteException {
        long totalBefore = Native.total_changes(db);
        clearCallbackFailure();
        int rc;
        while ((rc = Native.step(s.ptr)) == Native.SQLITE_ROW && callbackFailure == null) {
            // e.g. INSERT ... RETURNING via executeUpdate: rows are discarded, as in JDBC drivers
        }
        if (rc != Native.SQLITE_DONE || callbackFailure != null) {
            SQLiteException e = rc == Native.SQLITE_ROW || rc == Native.SQLITE_DONE
                    ? callbackError(context)
                    : stepFailure(rc, context, busyCapped);
            Native.reset(s.ptr);
            throw e;
        }
        return Native.total_changes(db) == totalBefore ? 0 : Native.changes(db);
    }

    /**
     * SQLite reads SQL text up to the first NUL byte, so a NUL inside a string would make it see a different
     * statement, and the script loop could never move past it. NUL padding at the end (with whitespace) is dropped;
     * a NUL followed by more SQL is rejected.
     */
    static String withoutNul(String sql) throws SQLiteException {
        int nul = sql.indexOf('\0');
        if (nul < 0) {
            return sql;
        }
        for (int i = nul; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch != '\0' && !Character.isWhitespace(ch)) {
                throw new SQLiteException("SQL contains a NUL character (at index " + nul + "), which would end it early: " + sql.substring(0, nul));
            }
        }
        return sql.substring(0, nul);
    }

    /**
     * Whether the SQL from {@code from} to the terminating NUL holds no statement: only whitespace, semicolons and
     * comments ({@code -- ...} to the end of the line, {@code /* ... *}{@code /}, unterminated running to the end,
     * as in SQLite's tokenizer).
     */
    private static boolean isBlank(MemorySegment text, long from) {
        long end = text.byteSize() - 1;   // the NUL
        long i = from;
        while (i < end) {
            byte b = text.get(JAVA_BYTE, i);
            if (b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == '\f' || b == 0x0B || b == ';') {
                i++;
            } else if (b == '-' && i + 1 < end && text.get(JAVA_BYTE, i + 1) == '-') {
                while (i < end && text.get(JAVA_BYTE, i) != '\n') {
                    i++;
                }
            } else if (b == '/' && i + 1 < end && text.get(JAVA_BYTE, i + 1) == '*') {
                i += 2;
                while (i < end && !(text.get(JAVA_BYTE, i) == '*' && i + 1 < end && text.get(JAVA_BYTE, i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else {
                return false;
            }
        }
        return true;
    }
}
