-- V3: a starter catalogue and three customers.

INSERT INTO category (id, name) VALUES
    (1, 'Books'), (2, 'Electronics'), (3, 'Home & Kitchen'), (4, 'Outdoors');

INSERT INTO product (sku, category_id, name, description, price_cents, stock) VALUES
    ('BK-1001', 1, 'The Pragmatic Programmer', 'Classic book on software craftsmanship, 20th anniversary edition', 4299, 120),
    ('BK-1002', 1, 'Designing Data-Intensive Applications', 'Reliable, scalable and maintainable data systems', 4999, 80),
    ('BK-1003', 1, 'SQLite Internals', 'How the most deployed database engine stores pages, journals and indexes', 3499, 60),
    ('BK-1004', 1, 'Java Concurrency in Practice', 'Threads, locks, executors and the Java memory model', 4599, 90),
    ('BK-1005', 1, 'Effective Java', 'Best practices for the Java platform, third edition', 4199, 150),
    ('BK-1006', 1, 'Cooking for Geeks', 'Real science, great hacks and good food', 2999, 40),
    ('EL-2001', 2, 'Mechanical Keyboard', 'Tenkeyless keyboard with hot-swappable tactile switches', 8999, 75),
    ('EL-2002', 2, 'Wireless Mouse', 'Ergonomic mouse with silent buttons and USB-C charging', 2999, 200),
    ('EL-2003', 2, '27-inch 4K Monitor', 'IPS panel, USB-C power delivery, height adjustable stand', 32999, 30),
    ('EL-2004', 2, 'Noise Cancelling Headphones', 'Over-ear headphones with 30 hours of battery', 19999, 50),
    ('EL-2005', 2, 'USB-C Dock', 'Dual display dock with ethernet and SD card reader', 12999, 45),
    ('EL-2006', 2, 'Portable SSD 1TB', 'Rugged external drive, 1050 MB/s', 9999, 110),
    ('HK-3001', 3, 'Pour-Over Coffee Kit', 'Ceramic dripper, glass carafe and paper filters', 3499, 70),
    ('HK-3002', 3, 'Cast Iron Skillet', 'Pre-seasoned 26 cm skillet for stove, oven and campfire', 3999, 65),
    ('HK-3003', 3, 'Chef Knife', '20 cm stainless steel knife with full tang', 5999, 55),
    ('HK-3004', 3, 'Desk Lamp', 'Dimmable LED lamp with adjustable colour temperature', 4499, 85),
    ('HK-3005', 3, 'Electric Kettle', '1.7 litre kettle with temperature control for tea and coffee', 5499, 60),
    ('HK-3006', 3, 'Bamboo Cutting Board', 'Large board with juice groove', 2499, 100),
    ('OD-4001', 4, 'Camping Lantern', 'Rechargeable LED lantern, 500 lumens, also a power bank', 3999, 90),
    ('OD-4002', 4, 'Hiking Backpack 30L', 'Lightweight daypack with rain cover', 7999, 40),
    ('OD-4003', 4, 'Insulated Water Bottle', 'Keeps drinks cold for 24 hours and hot for 12', 2799, 160),
    ('OD-4004', 4, 'Two-Person Tent', 'Freestanding tent, 2.1 kg, waterproof fly', 17999, 25),
    ('OD-4005', 4, 'Trekking Poles', 'Carbon fibre poles with cork grips, pair', 8999, 35),
    ('OD-4006', 4, 'Headlamp', '400 lumen headlamp with red light mode', 2499, 130);

INSERT INTO customer (email, name) VALUES
    ('ada@example.com', 'Ada Lovelace'),
    ('grace@example.com', 'Grace Hopper'),
    ('linus@example.com', 'Linus Torvalds');
