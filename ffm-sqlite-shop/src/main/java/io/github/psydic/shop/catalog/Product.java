package io.github.psydic.shop.catalog;

import java.math.BigDecimal;
import java.time.Instant;

public record Product(long id, String sku, String category, String name, String description, BigDecimal price,
                      int stock, Instant createdAt) {
}
