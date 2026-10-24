package io.github.psydic.ffmsqlite.db;

/**
 * The accumulator of a user-defined aggregate window function, registered with
 * {@link Connection#createWindowFunction}. Usable both with {@code OVER (...)} and as a plain aggregate.
 *
 * <p>SQLite slides the frame over the partition: {@link #step} adds each row entering the frame,
 * {@link #inverse} removes each row leaving it (always the oldest one added), and {@link #value} is
 * asked for the current frame's result once per output row. One instance serves a whole partition.
 *
 * <pre>{@code
 * final class MovingSum implements WindowFunction {
 *     private long sum, count;
 *     public void step(FunctionArgs a) throws SQLiteException    { if (!a.isNull(0)) { sum += a.getLong(0); count++; } }
 *     public void inverse(FunctionArgs a) throws SQLiteException { if (!a.isNull(0)) { sum -= a.getLong(0); count--; } }
 *     public Object result() { return count == 0 ? null : sum; }
 * }
 * conn.createWindowFunction("moving_sum", 1, MovingSum::new);
 * // SELECT day, moving_sum(amount) OVER (ORDER BY day ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) FROM sales
 * }</pre>
 */
public interface WindowFunction extends AggregateFunction {

    /** Removes from the frame the oldest row previously passed to {@link #step}. */
    void inverse(FunctionArgs args) throws Exception;

    /**
     * The result for the current frame; the frame may be empty (e.g. {@code 1 PRECEDING} on the first
     * row). Unlike {@link #result()} it must leave the accumulator usable. Defaults to {@code result()},
     * which is right whenever computing the result does not consume state.
     */
    default Object value() throws Exception {
        return result();
    }
}
