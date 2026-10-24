package io.github.psydic.shop;

import io.github.psydic.shop.db.Database;
import io.github.psydic.shop.db.Migrations;
import io.github.psydic.shop.web.HttpApi;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;

/**
 * Starts the shop: opens the database, migrates the schema, optionally places demo orders, serves the API.
 * <pre>
 * java -jar target/ffm-sqlite-shop.jar [--db shop.db] [--port 8080] [--readers 4] [--demo-orders 300]
 * </pre>
 */
public final class ShopApplication {
    private ShopApplication() {
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(arg(args, "--db", "shop.db"));
        int port = Integer.parseInt(arg(args, "--port", "8080"));
        int readers = Integer.parseInt(arg(args, "--readers", String.valueOf(Math.max(2, Runtime.getRuntime().availableProcessors() / 2))));
        int demoOrders = Integer.parseInt(arg(args, "--demo-orders", "0"));

        Database db = new Database(file, readers);
        int version = Migrations.migrate(db);
        Shop shop = new Shop(db, Clock.tickMillis(ZoneOffset.UTC));   // milliseconds, like SQLite's own timestamps
        if (demoOrders > 0) {
            System.out.println("placed " + DemoData.placeOrders(db, shop, demoOrders) + " demo orders");
        }
        HttpApi api = new HttpApi(shop, file);
        int bound = api.start(port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.stop();
            db.close();
        }));
        System.out.printf("ffm-sqlite shop: schema v%d, %s, http://localhost:%d/api/products%n",
                version, file.toAbsolutePath(), bound);
    }

    private static String arg(String[] args, String name, String otherwise) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return otherwise;
    }
}
