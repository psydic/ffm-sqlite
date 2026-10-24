package io.github.revsnai.shop.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record Order(long id, long customerId, Status status, BigDecimal total, Instant placedAt, List<Line> lines) {

    public enum Status { PLACED, PAID, SHIPPED, CANCELLED }

    public record Line(int lineNo, long productId, String name, int quantity, BigDecimal unitPrice) {
    }
}
