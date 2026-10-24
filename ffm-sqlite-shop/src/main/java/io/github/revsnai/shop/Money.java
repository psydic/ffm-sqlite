package io.github.revsnai.shop;

import java.math.BigDecimal;

/** Money is stored as integer cents and shown as BigDecimal: exact in both places. */
public final class Money {
    private Money() {
    }

    public static BigDecimal of(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
