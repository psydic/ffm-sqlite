package cmp;

import io.micronaut.data.annotation.DateCreated;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@MappedEntity("book")
public record Book(@Id @GeneratedValue Long id, String title, BigDecimal price, LocalDate published, UUID ref,
                   @DateCreated Instant created) {
}
