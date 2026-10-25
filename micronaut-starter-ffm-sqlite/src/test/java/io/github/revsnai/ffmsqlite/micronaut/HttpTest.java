package io.github.revsnai.ffmsqlite.micronaut;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A running HTTP server: a controller over a Micronaut Data repository, and the health endpoint. */
@MicronautTest(transactional = false)
class HttpTest {

    @Inject
    @Client("/")
    HttpClient client;
    @Inject
    FfmSqliteDatabase database;

    @BeforeEach
    void schema() throws Exception {
        try (Connection c = database.getDataSource().getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS book");
            st.executeUpdate("CREATE TABLE book (id INTEGER PRIMARY KEY, title TEXT NOT NULL, price DECIMAL(10,2), published DATE)");
        }
    }

    @Test
    void restRoundTrip() {
        HttpResponse<Book> created = client.toBlocking().exchange(
                HttpRequest.POST("/books", new Book(null, "Dune", new BigDecimal("9.99"), LocalDate.of(1965, 8, 1))), Book.class);
        assertEquals(HttpStatus.CREATED, created.getStatus());
        long id = created.body().id();

        Book got = client.toBlocking().retrieve(HttpRequest.GET("/books/" + id), Book.class);
        assertEquals("Dune", got.title());
        assertEquals(LocalDate.of(1965, 8, 1), got.published());
        assertEquals(1, client.toBlocking().retrieve(HttpRequest.GET("/books"), Argument.listOf(Book.class)).size());

        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().retrieve(HttpRequest.GET("/books/999"), Book.class));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthEndpoint() {
        Map<String, Object> health = client.toBlocking().retrieve(HttpRequest.GET("/health"), Map.class);
        assertEquals("UP", health.get("status"));
        Map<String, Object> sqlite = (Map<String, Object>) ((Map<String, Object>) health.get("details")).get("ffm-sqlite");
        assertEquals("UP", sqlite.get("status"));
        Map<String, Object> db = (Map<String, Object>) ((Map<String, Object>) sqlite.get("details")).get("default");
        assertEquals("memory", db.get("journalMode"));
        assertEquals(List.of("active", "idle", "total", "waiting"),
                ((Map<String, Object>) db.get("pool")).keySet().stream().sorted().toList());
    }
}
