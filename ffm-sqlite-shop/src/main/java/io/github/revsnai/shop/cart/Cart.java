package io.github.revsnai.shop.cart;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record Cart(UUID id, Long customerId, List<Line> lines, BigDecimal total) {

    public record Line(long productId, String sku, String name, int quantity, BigDecimal unitPrice, BigDecimal lineTotal,
                       int inStock) {
    }
}
