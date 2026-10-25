package io.github.revsnai.ffmsqlite.micronaut;

import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Micronaut Data JDBC repositories and @Transactional on the starter's DataSource (application-test.yml). */
@MicronautTest(transactional = false)
class MicronautDataTest {

    @Inject
    DataSource dataSource;
    @Inject
    FfmSqliteDatabase database;
    @Inject
    BookRepository books;
    @Inject
    BookService service;

    /** Outside a transaction, so on the pool itself: the DataSource bean hands out the current transaction's connection. */
    @BeforeEach
    void schema() throws Exception {
        try (Connection c = database.getDataSource().getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS book");
            st.executeUpdate("CREATE TABLE book (id INTEGER PRIMARY KEY, title TEXT NOT NULL, price DECIMAL(10,2), published DATE)");
        }
    }

    @Test
    void crudAndFinders() {
        Book saved = books.save(new Book(null, "SQLite internals", new BigDecimal("12.50"), LocalDate.of(2024, 5, 1)));
        assertNotNull(saved.id());
        books.save(new Book(null, "Advanced Java", new BigDecimal("40.00"), LocalDate.of(2025, 1, 2)));
        books.save(new Book(null, "A cheap book", new BigDecimal("3.99"), LocalDate.of(2023, 3, 3)));

        Book found = books.findById(saved.id()).orElseThrow();
        assertEquals("SQLite internals", found.title());
        assertEquals(0, new BigDecimal("12.50").compareTo(found.price()));
        assertEquals(LocalDate.of(2024, 5, 1), found.published());

        List<Book> cheap = books.findByPriceLessThanOrderByTitle(new BigDecimal("20"));
        assertEquals(List.of("A cheap book", "SQLite internals"), cheap.stream().map(Book::title).toList());
        assertEquals(2, books.countByTitleLike("A%"));

        books.update(new Book(saved.id(), "SQLite internals, 2nd ed.", found.price(), found.published()));
        assertEquals("SQLite internals, 2nd ed.", books.findById(saved.id()).orElseThrow().title());
        books.deleteById(saved.id());
        assertEquals(2, books.count());
    }

    @Test
    void transactionalRollsBackAndCommits() {
        assertThrows(IllegalStateException.class, service::saveTwoThenFail);
        assertEquals(0, books.count());
        service.saveTwo();
        assertEquals(2, books.count());
    }

    @Test
    void yamlConfigurationIsApplied() throws Exception {
        assertSame(database.getDataSource(), DelegatingDataSource.unwrapDataSource(dataSource));
        assertTrue(database.isInMemory());
        try (Connection c = database.getDataSource().getConnection(); Statement st = c.createStatement()) {
            assertEquals("-4000", one(st, "PRAGMA cache_size"));
            assertEquals("2", one(st, "PRAGMA temp_store"));
            assertEquals("1", one(st, "PRAGMA foreign_keys"));
            assertEquals("5000", one(st, "PRAGMA busy_timeout"));
        }
    }

    static String one(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getString(1);
        }
    }
}
