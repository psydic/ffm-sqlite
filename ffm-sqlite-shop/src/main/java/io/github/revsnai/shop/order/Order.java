package io.github.revsnai.shop.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record Order(long id, long customerId, Status status, BigDecimal total, Instant placedAt, List<Line> lines) {

    public enum Status {
        PLACED, PAID, SHIPPED, CANCELLED;

        /** The order life cycle: PLACED → PAID → SHIPPED, and PLACED or PAID → CANCELLED. */
        public boolean canBecome(Status next) {
            return switch (this) {
                case PLACED -> next == PAID || next == CANCELLED;
                case PAID -> next == SHIPPED || next == CANCELLED;
                case SHIPPED, CANCELLED -> false;
            };
        }
    }

    public record Line(int lineNo, long productId, String name, int quantity, BigDecimal unitPrice) {
    }
}
