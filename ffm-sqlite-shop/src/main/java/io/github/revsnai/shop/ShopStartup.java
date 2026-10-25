package io.github.revsnai.shop;

import io.github.revsnai.shop.db.Database;
import io.github.revsnai.shop.db.Migrations;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Singleton;

import java.sql.SQLException;

/**
 * Startup work, in order: migrate the schema, place demo orders if {@code shop.demo-orders} asks for them. Both run
 * on {@link StartupEvent}, before the HTTP server accepts requests; a failure stops the application.
 */
@Singleton
public class ShopStartup {
    private final Database db;
    private final Migrations migrations;
    private final Shop shop;
    private final int demoOrders;
    private int version;

    public ShopStartup(Database db, Migrations migrations, Shop shop, @Value("${shop.demo-orders:0}") int demoOrders) {
        this.db = db;
        this.migrations = migrations;
        this.shop = shop;
        this.demoOrders = demoOrders;
    }

    @EventListener
    void onStartup(StartupEvent event) throws SQLException {
        version = migrations.migrate();
        if (demoOrders > 0) {
            System.out.println("placed " + DemoData.placeOrders(db, shop, demoOrders) + " demo orders");
        }
    }

    @EventListener
    void onServerStarted(ServerStartupEvent event) {
        System.out.printf("ffm-sqlite shop: schema v%d, %s, %s%n",
                version, db.file().toAbsolutePath(), event.getSource().getURI().resolve("/api/products"));
    }
}
