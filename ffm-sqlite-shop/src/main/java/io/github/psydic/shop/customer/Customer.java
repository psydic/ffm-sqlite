package io.github.psydic.shop.customer;

import java.time.Instant;

public record Customer(long id, String email, String name, Instant createdAt) {
}
