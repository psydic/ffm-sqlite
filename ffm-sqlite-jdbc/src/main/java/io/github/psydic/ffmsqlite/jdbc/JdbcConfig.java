package io.github.psydic.ffmsqlite.jdbc;

import io.github.psydic.ffmsqlite.db.SQLite;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * Driver settings from the URL query ({@code jdbc:ffmsqlite:app.db?journal_mode=WAL&foreign_keys=true}) and the
 * connection {@code Properties} (properties win). Names follow sqlite-jdbc where it has an equivalent, so its
 * configurations carry over. Unknown names (e.g. {@code user}, {@code password}) are ignored, as JDBC drivers do.
 */
final class JdbcConfig {

    enum DateClass { INTEGER, TEXT, REAL }

    enum DatePrecision { MILLISECONDS, SECONDS }

    /** PRAGMAs applied on open when present (the same set sqlite-jdbc accepts as properties, minus obsolete ones). */
    static final Set<String> PRAGMAS = Set.of("application_id", "auto_vacuum", "automatic_index", "cache_size", "cache_spill",
            "case_sensitive_like", "cell_size_check", "checkpoint_fullfsync", "defer_foreign_keys", "encoding", "foreign_keys",
            "fullfsync", "hard_heap_limit", "ignore_check_constraints", "journal_mode", "journal_size_limit", "legacy_alter_table",
            "locking_mode", "max_page_count", "mmap_size", "page_size", "query_only", "read_uncommitted", "recursive_triggers",
            "reverse_unordered_selects", "secure_delete", "soft_heap_limit", "synchronous", "temp_store", "threads",
            "trusted_schema", "user_version", "wal_autocheckpoint");

    final String path;
    final SQLite.Config sqlite;
    /** How java.sql.Date/Time/Timestamp are written; INTEGER (epoch ms) is sqlite-jdbc's default. */
    final DateClass dateClass;
    final DatePrecision datePrecision;
    final String dateStringFormat;
    final DateTimeFormatter dateFormatter;
    /** sqlite-jdbc returns Integer from getObject() for integers that fit; true keeps that behaviour. */
    final boolean integerObjects;
    /** sqlite-jdbc's jdbc.get_generated_keys (default true): getGeneratedKeys() after INSERTs. */
    final boolean generatedKeys;

    private JdbcConfig(String path, Map<String, String> p) throws SQLException {
        this.path = path;
        SQLite.Config c = SQLite.Config.DEFAULT;
        if (bool(p.getOrDefault("readonly", p.getOrDefault("read_only", "false")))) {
            c = c.withReadOnly(true);
        }
        if (p.containsKey("open_mode") && (Integer.parseInt(p.get("open_mode")) & 1) != 0) {
            c = c.withReadOnly(true);
        }
        if (p.containsKey("busy_timeout")) {
            c = c.withBusyTimeoutMillis(Integer.parseInt(p.get("busy_timeout")));
        }
        if (p.containsKey("statement_cache_size")) {
            c = c.withStatementCacheSize(Integer.parseInt(p.get("statement_cache_size")));
        }
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (PRAGMAS.contains(e.getKey())) {
                String v = e.getValue();
                c = c.withPragma(e.getKey(), v.equalsIgnoreCase("true") ? (Object) true : v.equalsIgnoreCase("false") ? (Object) false : v);
            }
        }
        this.sqlite = c;
        try {
            this.dateClass = DateClass.valueOf(p.getOrDefault("date_class", "INTEGER").toUpperCase(Locale.ROOT));
            this.datePrecision = DatePrecision.valueOf(p.getOrDefault("date_precision", "MILLISECONDS").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SQLException("invalid date_class or date_precision: " + e.getMessage(), "HY024");
        }
        this.dateStringFormat = p.getOrDefault("date_string_format", "yyyy-MM-dd HH:mm:ss.SSS");
        this.dateFormatter = DateTimeFormatter.ofPattern(dateStringFormat, Locale.ROOT);
        this.integerObjects = bool(p.getOrDefault("integer_objects", "true"));
        this.generatedKeys = bool(p.getOrDefault("jdbc.get_generated_keys", "true"));
    }

    private static boolean bool(String v) {
        return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on") || v.equalsIgnoreCase("yes");
    }

    /**
     * Parses {@code jdbc:ffmsqlite:<path>[?k=v&...]} (or {@code jdbc:sqlite:}). For plain paths the query is
     * removed; {@code file:} URIs are passed to SQLite whole, since SQLite reads its own parameters from them.
     */
    static JdbcConfig parse(String url, Properties info) throws SQLException {
        String rest = url.substring(url.indexOf(':', "jdbc:".length()) + 1);
        Map<String, String> props = new TreeMap<>();
        String path = rest;
        int q = rest.indexOf('?');
        if (q >= 0) {
            for (String pair : rest.substring(q + 1).split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String k = decode(eq < 0 ? pair : pair.substring(0, eq));
                props.put(k.toLowerCase(Locale.ROOT), eq < 0 ? "" : decode(pair.substring(eq + 1)));
            }
            if (!rest.startsWith("file:")) {
                path = rest.substring(0, q);
            }
        }
        if (info != null) {
            for (String k : info.stringPropertyNames()) {
                props.put(k.toLowerCase(Locale.ROOT), info.getProperty(k));
            }
        }
        if (path.isEmpty() && !rest.startsWith("file:")) {
            path = ""; // a private temporary database, as in sqlite-jdbc
        }
        return new JdbcConfig(path, props);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
