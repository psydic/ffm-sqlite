package io.github.psydic.ffmsqlite.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unmodified third-party JDBC consumers on top of the driver. */
class FrameworkTest {
    @TempDir
    Path dir;

    private HikariDataSource pool() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:ffmsqlite:" + dir.resolve("app.db") + "?journal_mode=WAL&foreign_keys=true");
        cfg.setMaximumPoolSize(4);
        return new HikariDataSource(cfg);
    }

    @Test
    void hikariPoolWithSpringJdbcTemplateAndTransactions() {
        try (HikariDataSource ds = pool()) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, email TEXT NOT NULL UNIQUE, age INTEGER)");

            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("INSERT INTO users (email, age) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, "ada@example.com");
                ps.setInt(2, 36);
                return ps;
            }, keys);
            assertEquals(1L, keys.getKey().longValue());

            int[] counts = jdbc.batchUpdate("INSERT INTO users (email, age) VALUES (?, ?)",
                    List.of(new Object[]{"alan@example.com", 41}, new Object[]{"grace@example.com", 85}));
            assertEquals(2, counts.length);
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM users", Integer.class));
            assertEquals("grace@example.com", jdbc.queryForObject("SELECT email FROM users WHERE age > ?", String.class, 80));
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT email, age FROM users ORDER BY id");
            assertEquals(36, ((Number) rows.getFirst().get("age")).intValue());

            NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
            assertEquals(2, named.queryForObject("SELECT count(*) FROM users WHERE age BETWEEN :lo AND :hi",
                    new MapSqlParameterSource().addValue("lo", 30).addValue("hi", 50), Integer.class));

            // Spring maps SQLITE_CONSTRAINT (SQLIntegrityConstraintViolationException) to DuplicateKeyException.
            assertThrows(DuplicateKeyException.class, () -> jdbc.update("INSERT INTO users (email) VALUES ('ada@example.com')"));

            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO users (email, age) VALUES ('rolled@example.com', 1)");
                throw new IllegalStateException("roll back");
            }));
            tx.executeWithoutResult(status -> jdbc.update("UPDATE users SET age = age + 1"));
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM users", Integer.class));
            assertEquals(37, jdbc.queryForObject("SELECT age FROM users WHERE id = 1", Integer.class));
        }
    }

    @Test
    void jooqWithTheSqliteDialect() throws Exception {
        try (HikariDataSource ds = pool(); Connection c = ds.getConnection()) {
            DSLContext db = DSL.using(c, SQLDialect.SQLITE);
            db.createTable("book").column("id", org.jooq.impl.SQLDataType.INTEGER.identity(true))
                    .column("title", org.jooq.impl.SQLDataType.VARCHAR(100).notNull())
                    .column("year", org.jooq.impl.SQLDataType.INTEGER)
                    .primaryKey("id").execute();
            db.insertInto(table("book"), field("title"), field("year"))
                    .values("SICP", 1985).values("TAOCP", 1968).values("Dragon Book", 1986).execute();
            Result<Record> recent = db.select().from(table("book")).where(field("year", Integer.class).gt(1980))
                    .orderBy(field("year")).fetch();
            assertEquals(List.of("SICP", "Dragon Book"), recent.getValues(field("title", String.class)));
            assertEquals(3, db.fetchCount(table("book")));
            // jOOQ's metadata API reads tables and columns through DatabaseMetaData.
            var meta = db.meta().getTables().stream().filter(t -> t.getName().equalsIgnoreCase("book")).findFirst().orElseThrow();
            assertTrue(meta.fields().length >= 3, meta.toString());
        }
    }
}
