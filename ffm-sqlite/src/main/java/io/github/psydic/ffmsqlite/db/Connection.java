package io.github.psydic.ffmsqlite.db;

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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

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
    private Arena callbackArena;
    private final Map<String, CollationCallback> collations = new HashMap<>();
    private final Hooks hooks = new Hooks(this);
    private Throwable callbackFailure;
    private String callbackFailureMessage;
    private boolean autoCommit = true;
    private boolean closed;

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
        for (Map.Entry<String, String> pragma : config.pragmas().entrySet()) {
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
     * starting the next one immediately. Switching back to {@code true} commits the open transaction.
     */
    public void setAutoCommit(boolean autoCommit) throws SQLiteException {
        checkOpen();
        if (autoCommit == this.autoCommit) {
            return;
        }
        if (autoCommit) {
            endTransaction("COMMIT");
        } else {
            run("BEGIN");
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
        try {
            endTransaction("COMMIT");
        } finally {
            restartTransaction();
        }
    }

    public void rollback() throws SQLiteException {
        checkManualCommit("rollback");
        try {
            endTransaction("ROLLBACK");
        } finally {
            restartTransaction();
        }
    }

    private void restartTransaction() throws SQLiteException {
        if (!closed && !autoCommit && Native.get_autocommit(db)) {
            run("BEGIN");
        }
    }

    public boolean isClosed() {
        return closed;
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
        closed = true;
        Native.close_v2(db); // every statement is finalized and no hook is set, so this closes fully: no more upcalls
        functions.clear();
        collations.clear();
        if (callbackArena != null) {
            callbackArena.close();
        }
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
        if (udf != null) {
            ensureCallbackArena();
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cName = arena.allocateFrom(name);
            int rc = xValue != null
                    ? Native.create_window_function(db, cName, nArgs, textRep, stub(xStep, Native.X_FUNC),
                            stub(xFinal, Native.X_FINAL), stub(xValue, Native.X_FINAL), stub(xInverse, Native.X_FUNC))
                    : Native.create_function_v2(db, cName, nArgs, textRep,
                            stub(xFunc, Native.X_FUNC), stub(xStep, Native.X_FUNC), stub(xFinal, Native.X_FINAL));
            if (rc != Native.SQLITE_OK) {
                throw SQLiteException.of(db, rc, (udf == null ? "remove" : "create") + " function " + name + "/" + nArgs);
            }
        }
        String key = name.toLowerCase(Locale.ROOT) + "/" + nArgs;
        if (udf == null) {
            functions.remove(key);
        } else {
            functions.put(key, udf);
        }
    }

    private MemorySegment stub(MethodHandle target, java.lang.foreign.FunctionDescriptor descriptor) {
        return target == null ? MemorySegment.NULL : Native.linker().upcallStub(target, descriptor, callbackArena);
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
        ensureCallbackArena();
        CollationCallback callback = new CollationCallback(this, name, comparator);
        registerCollation(name, Native.linker().upcallStub(CollationCallback.X_COMPARE.bindTo(callback),
                Native.X_COMPARE, callbackArena), "create");
        collations.put(name.toLowerCase(Locale.ROOT), callback);
    }

    /** Removes a collation registered with {@link #createCollation}. */
    public void removeCollation(String name) throws SQLiteException {
        checkOpen();
        registerCollation(name, MemorySegment.NULL, "remove");
        collations.remove(name.toLowerCase(Locale.ROOT));
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
        return previous;
    }

    int deadlineMillis() {
        return hooks.deadlineMillis;
    }

    void popDeadline(long previous, int previousMillis) {
        hooks.deadline = previous;
        hooks.deadlineMillis = previousMillis;
    }

    /** Thread-safe: aborts whatever statement is running on this connection (for Statement.cancel()). */
    void interrupt() {
        if (!closed) {
            Native.interrupt(db);
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
            stepToEnd(s, sql); // attaches listener failures, e.g. a commit vetoed by a CommitListener
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
            MemorySegment text = arena.allocateFrom(sql);
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
            return new Stmt(sql, ptr);
        }
    }

    /** Runs every statement in {@code script}; returns the last one, still un-stepped, if it is the final query. */
    ScriptResult runScript(String script, boolean keepFinalQuery) throws SQLiteException {
        return runScript(script, keepFinalQuery, 0);
    }

    ScriptResult runScript(String script, boolean keepFinalQuery, int timeoutMillis) throws SQLiteException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocateFrom(script);
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
                    : withCallbackCause(SQLiteException.of(db, rc, context));
            Native.reset(s.ptr);
            throw e;
        }
        return Native.total_changes(db) == totalBefore ? 0 : Native.changes(db);
    }

    private static boolean isBlank(MemorySegment text, long from) {
        for (long i = from, end = text.byteSize() - 1; i < end; i++) {
            byte b = text.get(JAVA_BYTE, i);
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r' && b != ';') {
                return false;
            }
        }
        return true;
    }
}
