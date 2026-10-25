package io.github.revsnai.ffmsqlite.micronaut;

import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;

import java.math.BigDecimal;
import java.time.LocalDate;

@Singleton
public class BookService {
    private final BookRepository books;

    public BookService(BookRepository books) {
        this.books = books;
    }

    /** Saves two books, then fails: both must be rolled back. */
    @Transactional
    public void saveTwoThenFail() {
        books.save(new Book(null, "first", BigDecimal.ONE, LocalDate.now()));
        books.save(new Book(null, "second", BigDecimal.TWO, LocalDate.now()));
        throw new IllegalStateException("boom");
    }

    @Transactional
    public void saveTwo() {
        books.save(new Book(null, "third", BigDecimal.ONE, LocalDate.now()));
        books.save(new Book(null, "fourth", BigDecimal.TWO, LocalDate.now()));
    }
}
