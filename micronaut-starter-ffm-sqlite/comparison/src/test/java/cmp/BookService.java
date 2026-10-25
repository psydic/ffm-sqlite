package cmp;

import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Singleton
public class BookService {
    final BookRepository books;

    BookService(BookRepository books) {
        this.books = books;
    }

    @Transactional
    public void saveThenFail() {
        books.save(new Book(null, "x", BigDecimal.ONE, LocalDate.now(), UUID.randomUUID(), null));
        throw new IllegalStateException("boom");
    }

    /**
     * Read-then-write in one transaction: under concurrent writers SQLite fails some of these at once with
     * SQLITE_BUSY (the read lock cannot be upgraded while another connection commits).
     */
    @Transactional
    public void reprice(long id) {
        books.findById(id).ifPresent(b -> books.update(new Book(b.id(), b.title(), b.price().add(BigDecimal.ONE),
                b.published(), b.ref(), b.created())));
    }

    /** Write-first transaction: update, then read back; takes the write lock first, so it waits instead of failing. */
    @Transactional
    public Book repriceWriteFirst(long id, BigDecimal price) {
        books.updatePrice(id, price);
        return books.findById(id).orElseThrow();
    }

    /** A write inside a read-only transaction: does anything stop it? */
    @Transactional(readOnly = true)
    public void writeInReadOnly() {
        books.save(new Book(null, "ro", BigDecimal.ONE, LocalDate.now(), UUID.randomUUID(), null));
    }
}
