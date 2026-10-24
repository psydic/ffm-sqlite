package io.github.revsnai.ffmsqlite.db;

/**
 * A user-defined SQL function that maps one row's arguments to one value, registered with
 * {@link Connection#createFunction}.
 *
 * <pre>{@code
 * conn.createFunction("reverse", 1, args -> new StringBuilder(args.getString(0)).reverse().toString(),
 *         FunctionFlag.DETERMINISTIC);
 * }</pre>
 */
@FunctionalInterface
public interface ScalarFunction {

    /**
     * Computes the result. It may be any type {@link PreparedStatement#setObject} accepts, or
     * {@code null} for SQL NULL. A thrown exception fails the SQL statement with its message and
     * becomes the cause of the resulting {@link SQLiteException}.
     */
    Object apply(FunctionArgs args) throws Exception;
}
