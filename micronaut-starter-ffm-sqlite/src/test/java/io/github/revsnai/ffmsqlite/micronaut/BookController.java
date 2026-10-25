package io.github.revsnai.ffmsqlite.micronaut;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.net.URI;

/** SQLite calls block, so the controller runs on the blocking I/O pool rather than the event loop. */
@Controller("/books")
@ExecuteOn(TaskExecutors.BLOCKING)
public class BookController {
    private final BookRepository books;

    public BookController(BookRepository books) {
        this.books = books;
    }

    @Get("/{id}")
    public HttpResponse<Book> get(@PathVariable long id) {
        return books.findById(id).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Get
    public Iterable<Book> list() {
        return books.findAll();
    }

    @Post
    public HttpResponse<Book> create(@Body Book book) {
        Book saved = books.save(book);
        return HttpResponse.created(saved, URI.create("/books/" + saved.id()));
    }
}
