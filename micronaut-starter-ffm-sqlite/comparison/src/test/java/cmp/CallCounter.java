package cmp;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** With -Dcount.calls=true, wraps DataSource beans to count every JDBC call (and each SQL text) Micronaut makes. */
@Singleton
@Requires(property = "count.calls", value = "true")
public class CallCounter implements BeanCreatedEventListener<DataSource> {
    public static final Map<String, LongAdder> CALLS = new ConcurrentHashMap<>();

    @Override
    public DataSource onCreated(BeanCreatedEvent<DataSource> event) {
        return wrap(event.getBean(), DataSource.class, "DataSource");
    }

    @SuppressWarnings("unchecked")
    static <T> T wrap(T target, Class<T> iface, String label) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (proxy, m, args) -> {
            String key = label + "." + m.getName();
            if (m.getName().startsWith("prepare") || m.getName().startsWith("execute")) {
                if (args != null && args.length > 0 && args[0] instanceof String sql) {
                    key += " [" + sql + "]";
                }
            } else if (m.getName().equals("setReadOnly") || m.getName().equals("setAutoCommit")) {
                key += "(" + args[0] + ")";
            }
            CALLS.computeIfAbsent(key, k -> new LongAdder()).increment();
            Object r;
            try {
                r = m.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (r instanceof PreparedStatement ps && m.getReturnType() == PreparedStatement.class) {
                return wrap(ps, PreparedStatement.class, "PreparedStatement");
            }
            if (r instanceof Statement st && m.getReturnType() == Statement.class) {
                return wrap(st, Statement.class, "Statement");
            }
            if (r instanceof Connection c && m.getReturnType() == Connection.class) {
                return wrap(c, Connection.class, "Connection");
            }
            if (r instanceof ResultSet rs && m.getReturnType() == ResultSet.class) {
                return wrap(rs, ResultSet.class, "ResultSet");
            }
            return r;
        });
    }

    public static void print(String stack, long ops) {
        Map<String, LongAdder> sorted = new TreeMap<>(CALLS);
        sorted.forEach((k, v) -> System.out.printf("CALLS\t%s\t%.2f per findById\t%s%n", stack, v.sum() / (double) ops, k));
    }
}
