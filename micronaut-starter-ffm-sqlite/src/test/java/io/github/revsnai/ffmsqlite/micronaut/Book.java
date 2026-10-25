package io.github.revsnai.ffmsqlite.micronaut;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.serde.annotation.Serdeable;

import java.math.BigDecimal;
import java.time.LocalDate;

@Serdeable
@MappedEntity("book")
public record Book(@Id @GeneratedValue Long id, String title, BigDecimal price, LocalDate published) {
}
