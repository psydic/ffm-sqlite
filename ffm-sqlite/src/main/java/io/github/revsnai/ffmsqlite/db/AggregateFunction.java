package io.github.revsnai.ffmsqlite.db;

/**
 * The accumulator of a user-defined aggregate, registered with {@link Connection#createAggregateFunction}.
 * A new instance is created for every group, receives each row of the group through {@link #step},
 * and is asked for the group's value once by {@link #result}.
 *
 * <pre>{@code
 * final class Median implements AggregateFunction {
 *     private final List<Double> values = new ArrayList<>();
 *     public void step(FunctionArgs args) { if (!args.isNull(0)) values.add(args.getDouble(0)); }
 *     public Object result() {
 *         if (values.isEmpty()) return null;
 *         Collections.sort(values);
 *         int n = values.size();
 *         return n % 2 == 1 ? values.get(n / 2) : (values.get(n / 2 - 1) + values.get(n / 2)) / 2;
 *     }
 * }
 * conn.createAggregateFunction("median", 1, Median::new);
 * }</pre>
 */
public interface AggregateFunction {

    /** Adds one row of the group. A thrown exception fails the SQL statement. */
    void step(FunctionArgs args) throws Exception;

    /**
     * The group's value, as for {@link ScalarFunction#apply}. Also called, on a fresh instance with
     * no {@code step}, for an empty input (e.g. {@code SELECT median(x) FROM empty_table}).
     */
    Object result() throws Exception;
}
