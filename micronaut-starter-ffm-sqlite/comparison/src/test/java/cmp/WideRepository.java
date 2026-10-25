package cmp;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.GenericRepository;

import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.SQLITE)
public interface WideRepository extends GenericRepository<Wide, Long> {
    Optional<Wide> findById(Long id);

    List<Wide> findByIdBetweenOrderById(Long from, Long to);
}
