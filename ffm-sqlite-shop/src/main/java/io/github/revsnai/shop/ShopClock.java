package io.github.revsnai.shop;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** The shop's clock: milliseconds, like SQLite's own timestamps; or a fixed instant ({@code shop.fixed-time}) for tests. */
@Factory
public class ShopClock {

    @Singleton
    @Requires(missingProperty = "shop.fixed-time")
    Clock clock() {
        return Clock.tickMillis(ZoneOffset.UTC);
    }

    @Singleton
    @Requires(property = "shop.fixed-time")
    Clock fixedClock(@Value("${shop.fixed-time}") Instant now) {
        return Clock.fixed(now, ZoneOffset.UTC);
    }
}
