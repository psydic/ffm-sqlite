package cmp;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.math.BigDecimal;

/** The 20-column row of ffm-vs-jdbc/data/wide-1m.db (dates and UUID are stored as text there). */
@MappedEntity("wide")
public record Wide(@Id Long id, int tiny, int small, int medium, long big, boolean flag, double price, @Nullable Double ratio,
                   String code, String name, String email, String city, @Nullable String note, String description,
                   String created, String birth, String uuid, BigDecimal amount, byte[] hash, byte[] payload) {
}
