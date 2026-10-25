package io.github.revsnai.shop;

import io.micronaut.runtime.Micronaut;

/**
 * Starts the shop: Micronaut reads {@code application.yml}, the starter opens the database pools,
 * {@link ShopStartup} migrates the schema (and optionally places demo orders), then the HTTP server starts.
 * <pre>
 * java -jar target/ffm-sqlite-shop.jar [--shop.db=shop.db] [--micronaut.server.port=8080] [--shop.readers=4]
 *                                      [--shop.demo-orders=300]
 * </pre>
 */
public final class ShopApplication {
    private ShopApplication() {
    }

    public static void main(String[] args) {
        Micronaut.run(ShopApplication.class, args);
    }
}
