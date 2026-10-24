package ffmsqlite;

import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * For each method of a java.sql interface: does sqlite-jdbc implement it (its implementation can return, i.e.
 * is not an unconditional {@code throw unsupported()}), and does ffm-sqlite have an equivalent method (same
 * name and parameter types, java.sql types mapped to their io.github.revsnai.ffmsqlite.db counterparts)? Prints Markdown.
 */
public class InterfaceComparison {

    record Pair(Class<?> jdbcInterface, String sqliteJdbcImpl, String ffmClass, String wrapperImpl) {
    }

    static final List<Pair> PAIRS = List.of(
            new Pair(java.sql.Connection.class, "org.sqlite.jdbc4.JDBC4Connection", "io.github.revsnai.ffmsqlite.db.Connection", "io.github.revsnai.ffmsqlite.jdbc.JdbcConnection"),
            new Pair(java.sql.Statement.class, "org.sqlite.jdbc4.JDBC4Statement", "io.github.revsnai.ffmsqlite.db.Statement", "io.github.revsnai.ffmsqlite.jdbc.JdbcStatement"),
            new Pair(java.sql.PreparedStatement.class, "org.sqlite.jdbc4.JDBC4PreparedStatement", "io.github.revsnai.ffmsqlite.db.PreparedStatement", "io.github.revsnai.ffmsqlite.jdbc.JdbcPreparedStatement"),
            new Pair(java.sql.ResultSet.class, "org.sqlite.jdbc4.JDBC4ResultSet", "io.github.revsnai.ffmsqlite.db.ResultSet", "io.github.revsnai.ffmsqlite.jdbc.JdbcResultSet"),
            new Pair(java.sql.ResultSetMetaData.class, "org.sqlite.jdbc4.JDBC4ResultSet", "io.github.revsnai.ffmsqlite.db.ResultSetMetaData", "io.github.revsnai.ffmsqlite.jdbc.JdbcResultSetMetaData"),
            new Pair(java.sql.DatabaseMetaData.class, "org.sqlite.jdbc4.JDBC4DatabaseMetaData", "io.github.revsnai.ffmsqlite.db.DatabaseMetaData", "io.github.revsnai.ffmsqlite.jdbc.JdbcDatabaseMetaData"));

    static final Map<Class<?>, String> TYPE_MAP = Map.of(
            java.sql.Connection.class, "io.github.revsnai.ffmsqlite.db.Connection",
            java.sql.Statement.class, "io.github.revsnai.ffmsqlite.db.Statement",
            java.sql.PreparedStatement.class, "io.github.revsnai.ffmsqlite.db.PreparedStatement",
            java.sql.ResultSet.class, "io.github.revsnai.ffmsqlite.db.ResultSet",
            java.sql.ResultSetMetaData.class, "io.github.revsnai.ffmsqlite.db.ResultSetMetaData",
            java.sql.DatabaseMetaData.class, "io.github.revsnai.ffmsqlite.db.DatabaseMetaData");

    enum Jdbc { IMPLEMENTED, UNSUPPORTED, DEFAULT_ONLY }

    enum Ffm { SAME, OVERLOAD, MISSING }

    public static void main(String[] args) throws Exception {
        StringBuilder detail = new StringBuilder();
        System.out.println("| java.sql interface | Methods | Implemented by sqlite-jdbc | ffm-sqlite: same signature | ffm-sqlite: same name, other parameters | Missing in ffm-sqlite (of those sqlite-jdbc implements) |");
        System.out.println("|---|---:|---:|---:|---:|---:|");
        int[] totals = new int[5];
        for (Pair p : PAIRS) {
            Class<?> impl = Class.forName(p.sqliteJdbcImpl());
            Class<?> ffm = Class.forName(p.ffmClass());
            Map<String, Method> methods = new TreeMap<>();
            for (Method m : p.jdbcInterface().getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) {
                    methods.putIfAbsent(signature(m), m);
                }
            }
            int implemented = 0, same = 0, overload = 0, missingImplemented = 0;
            Map<String, List<String>> missingByJdbcStatus = new LinkedHashMap<>();
            for (Method m : methods.values()) {
                Jdbc j = jdbcStatus(impl, m);
                Ffm f = ffmStatus(ffm, m);
                if (j == Jdbc.IMPLEMENTED) {
                    implemented++;
                }
                if (f == Ffm.SAME) {
                    same++;
                } else if (f == Ffm.OVERLOAD) {
                    overload++;
                }
                if (f != Ffm.SAME && j == Jdbc.IMPLEMENTED) {
                    missingImplemented++;
                    missingByJdbcStatus.computeIfAbsent(f == Ffm.OVERLOAD ? "different parameters in ffm-sqlite" : "missing in ffm-sqlite",
                            k -> new ArrayList<>()).add(shortSig(m));
                }
            }
            System.out.printf("| `%s` | %d | %d | %d | %d | %d |%n", p.jdbcInterface().getSimpleName(), methods.size(),
                    implemented, same, overload, missingImplemented);
            totals[0] += methods.size();
            totals[1] += implemented;
            totals[2] += same;
            totals[3] += overload;
            totals[4] += missingImplemented;
            detail.append("\n#### `").append(p.jdbcInterface().getSimpleName()).append("`: implemented by sqlite-jdbc, not identical in ffm-sqlite\n\n");
            missingByJdbcStatus.forEach((k, v) -> detail.append("- **").append(k).append("** (").append(v.size()).append("): ")
                    .append(String.join(", ", v.stream().map(s -> "`" + s + "`").toList())).append("\n"));
        }
        System.out.printf("| **Total** | **%d** | **%d** | **%d** | **%d** | **%d** |%n", totals[0], totals[1], totals[2], totals[3], totals[4]);
        System.out.println(detail);
        wrapperCoverage();
    }

    /** sqlite-jdbc vs the ffm-sqlite-jdbc wrapper: both implement java.sql, measured with the same bytecode rule. */
    static void wrapperCoverage() throws Exception {
        System.out.println("\n## ffm-sqlite-jdbc (the JDBC wrapper) vs sqlite-jdbc\n");
        System.out.println("| java.sql interface | Methods | sqlite-jdbc implements | ffm-sqlite-jdbc implements | Both | Only sqlite-jdbc | Only ffm-sqlite-jdbc |");
        System.out.println("|---|---:|---:|---:|---:|---:|---:|");
        int[] t = new int[6];
        StringBuilder onlyJdbc = new StringBuilder(), onlyWrapper = new StringBuilder();
        for (Pair p : PAIRS) {
            Class<?> sj = Class.forName(p.sqliteJdbcImpl()), w = Class.forName(p.wrapperImpl());
            Map<String, Method> methods = new TreeMap<>();
            for (Method m : p.jdbcInterface().getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) {
                    methods.putIfAbsent(signature(m), m);
                }
            }
            int a = 0, b = 0, both = 0, oa = 0, ob = 0;
            List<String> la = new ArrayList<>(), lb = new ArrayList<>();
            for (Method m : methods.values()) {
                boolean x = jdbcStatus(sj, m) == Jdbc.IMPLEMENTED, y = jdbcStatus(w, m) == Jdbc.IMPLEMENTED;
                a += x ? 1 : 0;
                b += y ? 1 : 0;
                both += x && y ? 1 : 0;
                if (x && !y) { oa++; la.add(shortSig(m)); }
                if (y && !x) { ob++; lb.add(shortSig(m)); }
            }
            System.out.printf("| `%s` | %d | %d | %d | %d | %d | %d |%n", p.jdbcInterface().getSimpleName(), methods.size(), a, b, both, oa, ob);
            t[0] += methods.size(); t[1] += a; t[2] += b; t[3] += both; t[4] += oa; t[5] += ob;
            if (!la.isEmpty()) onlyJdbc.append("- `").append(p.jdbcInterface().getSimpleName()).append("`: ").append(String.join(", ", la.stream().map(s -> "`" + s + "`").toList())).append("\n");
            if (!lb.isEmpty()) onlyWrapper.append("- `").append(p.jdbcInterface().getSimpleName()).append("`: ").append(String.join(", ", lb.stream().map(s -> "`" + s + "`").toList())).append("\n");
        }
        System.out.printf("| **Total** | **%d** | **%d** | **%d** | **%d** | **%d** | **%d** |%n", t[0], t[1], t[2], t[3], t[4], t[5]);
        System.out.println("\n### Implemented by sqlite-jdbc only\n\n" + onlyJdbc);
        System.out.println("### Implemented by ffm-sqlite-jdbc only\n\n" + onlyWrapper);
    }

    static String signature(Method m) {
        return m.getName() + Arrays.toString(m.getParameterTypes());
    }

    static String shortSig(Method m) {
        return m.getName() + "(" + String.join(", ", Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName).toList()) + ")";
    }

    /** IMPLEMENTED unless sqlite-jdbc's implementation can only throw (no return instruction in its bytecode). */
    static Jdbc jdbcStatus(Class<?> impl, Method m) throws Exception {
        Method target = impl.getMethod(m.getName(), m.getParameterTypes());
        Class<?> declaring = target.getDeclaringClass();
        if (declaring.isInterface()) {
            return Jdbc.DEFAULT_ONLY; // a java.sql default method sqlite-jdbc does not override
        }
        ClassModel cm = parse(declaring);
        MethodTypeDesc desc = MethodTypeDesc.of(classDesc(target.getReturnType()),
                Arrays.stream(target.getParameterTypes()).map(InterfaceComparison::classDesc).toArray(ClassDesc[]::new));
        for (MethodModel mm : cm.methods()) {
            if (mm.methodName().equalsString(m.getName()) && mm.methodTypeSymbol().equals(desc)) {
                CodeModel code = mm.code().orElse(null);
                if (code == null) {
                    return Jdbc.IMPLEMENTED; // native or abstract
                }
                boolean returns = code.elementStream().anyMatch(e -> e instanceof ReturnInstruction);
                return returns ? Jdbc.IMPLEMENTED : Jdbc.UNSUPPORTED;
            }
        }
        return Jdbc.IMPLEMENTED; // bridge/synthetic edge cases: assume implemented
    }

    static Ffm ffmStatus(Class<?> ffm, Method m) {
        boolean sameName = false;
        for (Method fm : ffm.getMethods()) {
            if (!fm.getName().equals(m.getName())) {
                continue;
            }
            sameName = true;
            Class<?>[] a = m.getParameterTypes(), b = fm.getParameterTypes();
            if (a.length != b.length) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < a.length; i++) {
                String expected = TYPE_MAP.getOrDefault(a[i], a[i].getName());
                if (!expected.equals(b[i].getName())) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return Ffm.SAME;
            }
        }
        return sameName ? Ffm.OVERLOAD : Ffm.MISSING;
    }

    static ClassModel parse(Class<?> c) throws Exception {
        try (InputStream in = c.getClassLoader().getResourceAsStream(c.getName().replace('.', '/') + ".class")) {
            return ClassFile.of().parse(in.readAllBytes());
        }
    }

    static ClassDesc classDesc(Class<?> c) {
        return c.describeConstable().orElseThrow();
    }
}
