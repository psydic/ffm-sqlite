package io.github.revsnai.ffmsqlite.micronaut;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.math.BigDecimal;
import java.util.List;

@JdbcRepository(dialect = Dialect.SQLITE)
public interface BookRepository extends CrudRepository<Book, Long> {

    List<Book> findByPriceLessThanOrderByTitle(BigDecimal price);

    long countByTitleLike(String pattern);
}
