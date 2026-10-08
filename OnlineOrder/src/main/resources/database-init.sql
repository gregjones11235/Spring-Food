-- 全部表结构 + 种子数据。每次启动都会执行（spring.sql.init.mode），所以必须幂等：
-- 只建不存在的表、只加不存在的列，种子数据只在表为空时插入，绝不删表删数据。


-- ================= 用户、登录、购物车 =================

CREATE TABLE IF NOT EXISTS customers
(
    id         SERIAL PRIMARY KEY   NOT NULL,
    email      TEXT UNIQUE          NOT NULL,
    enabled    BOOLEAN DEFAULT TRUE NOT NULL,
    password   TEXT                 NOT NULL,
    first_name TEXT,
    last_name  TEXT
);
-- 订单中心用到的用户资料：phone 故意用 TEXT（见 SQL_and_Redis_lab.md Q3）
ALTER TABLE customers ADD COLUMN IF NOT EXISTS phone TEXT;
ALTER TABLE customers ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();


CREATE TABLE IF NOT EXISTS carts
(
    id          SERIAL PRIMARY KEY NOT NULL,
    customer_id INTEGER UNIQUE     NOT NULL,
    total_price NUMERIC            NOT NULL,
    CONSTRAINT fk_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE
);


-- ================= 餐厅、菜品 =================

CREATE TABLE IF NOT EXISTS restaurants
(
    id        SERIAL PRIMARY KEY NOT NULL,
    name      TEXT               NOT NULL,
    address   TEXT,
    image_url TEXT,
    phone     TEXT
);
-- 品类（Burgers、Chinese、Pizza……），报表按品类统计用
ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS category TEXT;


CREATE TABLE IF NOT EXISTS menu_items
(
    id            SERIAL PRIMARY KEY NOT NULL,
    restaurant_id INTEGER            NOT NULL,
    name          TEXT               NOT NULL,
    price         NUMERIC            NOT NULL,
    description   TEXT,
    image_url     TEXT,
    CONSTRAINT fk_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE
);
-- 仅限时特价菜品有库存，普通菜品为 NULL（Q10、Q21）
ALTER TABLE menu_items ADD COLUMN IF NOT EXISTS stock INTEGER;


-- 购物车里的菜品
CREATE TABLE IF NOT EXISTS order_items
(
    id           SERIAL PRIMARY KEY NOT NULL,
    menu_item_id INTEGER            NOT NULL,
    cart_id      INTEGER            NOT NULL,
    price        NUMERIC            NOT NULL,
    quantity     INTEGER            NOT NULL,
    CONSTRAINT fk_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE CASCADE,
    CONSTRAINT fk_menu_item FOREIGN KEY (menu_item_id) REFERENCES menu_items (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_order_items_cart_menu ON order_items (cart_id, menu_item_id);


CREATE TABLE IF NOT EXISTS authorities
(
    id        SERIAL PRIMARY KEY NOT NULL,
    email     TEXT               NOT NULL,
    authority TEXT               NOT NULL,
    CONSTRAINT fk_customer FOREIGN KEY (email) REFERENCES customers (email) ON DELETE CASCADE
);
-- 登录时按 email 查权限。没有这个索引时，10 万用户下每次登录都要全表扫描，
-- 删除用户时的级联检查也会逐个扫全表（批量删 10 万个生成用户要 90 多秒）
CREATE INDEX IF NOT EXISTS idx_authorities_email ON authorities (email);


-- 商家账号属于哪家餐厅。商家账号也是 customers 里的一行（同一套登录），权限是 ROLE_MERCHANT，没有购物车。
-- email 是主键：一个商家账号只管一家店
CREATE TABLE IF NOT EXISTS restaurant_staff
(
    email         TEXT PRIMARY KEY NOT NULL,
    restaurant_id INTEGER          NOT NULL,
    CONSTRAINT fk_staff_customer FOREIGN KEY (email) REFERENCES customers (email) ON DELETE CASCADE,
    CONSTRAINT fk_staff_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE
);


-- ================= 订单中心 =================
-- 这里刻意不建任何二级索引，索引在各个优化步骤里按需添加（见 SQL_and_Redis_lab.md Q1–Q4）


-- 浏览量：Redis 里 INCR，定时批量回写到这里
CREATE TABLE IF NOT EXISTS menu_item_views
(
    menu_item_id INTEGER PRIMARY KEY NOT NULL,
    views        BIGINT              NOT NULL DEFAULT 0,
    CONSTRAINT fk_views_menu_item FOREIGN KEY (menu_item_id) REFERENCES menu_items (id) ON DELETE CASCADE
);


-- 正式订单。状态流转（只订餐，暂不含配送）：PAID → ACCEPTED → DONE / CANCELLED
CREATE TABLE IF NOT EXISTS orders
(
    id            SERIAL PRIMARY KEY NOT NULL,
    customer_id   BIGINT             NOT NULL,
    restaurant_id INTEGER,
    status        TEXT               NOT NULL DEFAULT 'PAID',
    total_price   NUMERIC            NOT NULL,
    version       INTEGER            NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ        NOT NULL DEFAULT now()
);


CREATE TABLE IF NOT EXISTS order_lines
(
    id           SERIAL PRIMARY KEY NOT NULL,
    order_id     INTEGER            NOT NULL,
    menu_item_id INTEGER            NOT NULL,
    price        NUMERIC            NOT NULL,
    quantity     INTEGER            NOT NULL,
    CONSTRAINT fk_line_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_line_menu_item FOREIGN KEY (menu_item_id) REFERENCES menu_items (id)
);


-- ================= 种子数据：3 家餐厅、30 个菜品（表为空时才插入） =================

INSERT INTO restaurants (name, address, image_url, phone)
SELECT *
FROM (VALUES ('Burger King', '773 N Mathilda Ave, Sunnyvale, CA 94085',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/store%2Fheader%2F10171.png',
        '(408) 736-0101'),
       ('SGD Tofu House', '3450 El Camino Real #105, Santa Clara, CA 95051',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/store%2Fheader%2F1579.jpg',
        '(408) 261-3030'),
       ('Fashion Wok', '163 S Murphy Ave, Sunnyvale, CA 94086',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/store%2Fheader%2F273997.jpg',
        '(408) 739-8866')) AS seed (name, address, image_url, phone)
WHERE NOT EXISTS (SELECT 1 FROM restaurants);


INSERT INTO menu_items (description, image_url, name, price, restaurant_id)
SELECT *
FROM (VALUES ('Made with white meat chicken, our Chicken Fries are coated in a light crispy breading seasoned with savory spices and herbs.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=300,format=auto,quality=50/https://cdn.doordash.com/media/photos/f439436f-c5ab-47af-bac4-7b73ab60a24b-retina-large.jpg',
        'Chicken Fries - 9 Pc', 4.89, 1),
       ('Our Whopper Sandwich is a 1/4 lb* of savory flame-grilled beef topped with juicy tomatoes, fresh lettuce, creamy mayonnaise, ketchup, crunchy pickles, and sliced white onions on a soft sesame seed bun.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=300,format=auto,quality=50/https://cdn.doordash.com/media/photos/f878a689-618b-4c70-a00f-e7b1f320adc9-retina-large.jpg',
        'Whopper Meal', 10.59, 1),
       ('Our Impossible™ Whopper Sandwich features a savory flame-grilled patty made from plants topped with juicy tomatoes, fresh lettuce, creamy mayonnaise, ketchup, crunchy pickles, and sliced white onions on a soft sesame seed bun',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/5c306a5f-fdd2-41d2-a660-9762aaa8eee8-retina-large.jpg',
        'Impossible™ Whopper', 7.99, 1),
       ('Say hello to our HERSHEY’S® Sundae Pie. One part crunchy chocolate crust and one part chocolate crème filling, garnished with a delicious topping and real HERSHEY’S® Chocolate Chips',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/80b1670d-e9c0-4886-a5b7-1ad48edd24ca-retina-large.jpg',
        'HERSHEYS® Sundae Pie', 3.09, 1),
       ('Our Whopper Sandwich is a 1/4 lb* of savory flame-grilled beef topped with juicy tomatoes, fresh lettuce, creamy mayonnaise, ketchup, crunchy pickles, and sliced white onions on a soft sesame seed bun.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/9b3d7985-e457-43b3-938d-5184f48c2687-retina-large-jpeg',
        'Whopper', 6.39, 1),
       ('Our Double Whopper Sandwich is a pairing of two 1/4 lb* savory flame-grilled beef patties topped with juicy tomatoes, fresh lettuce, creamy mayonnaise, ketchup, crunchy pickles, and sliced white onions on a soft sesame seed bun',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/45addf4a-e8a8-47cb-a705-cce1d10ce86d-retina-large.jpg',
        'Double Whopper Meal', 11.69, 1),
       ('Our Double Whopper Sandwich is a pairing of two 1/4 lb* savory flame-grilled beef patties topped with juicy tomatoes, fresh lettuce, creamy mayonnaise, ketchup, crunchy pickles, and sliced white onions on a soft sesame seed bun',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/31dd68c2-06ec-42ad-bcd4-da7bd3425437-retina-large-jpeg',
        'Spicy Crispy Chicken Sandwich', 6.09, 1),
       ('Our Original Chicken Sandwich is lightly breaded and topped with a simple combination of shredded lettuce and creamy mayonnaise on a sesame seed bun',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/3e437f54-fa4e-4e9d-bf80-8a1e5b120f32-retina-large-jpeg',
        'Original Chicken Sandwich', 6.09, 1),
       ('Our Bacon King Sandwich features two 1/4 lb* savory flame-grilled beef patties, topped a with hearty portion of thick-cut smoked bacon, melted American cheese and topped with ketchup and creamy mayonnaise all on a soft sesame seed bun.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/adb96c32-3c5b-4375-ba92-b30767d2513d-retina-large.jpg',
        'Bacon King Sandwich Meal', 12.19, 1),
       ('Cool down with our creamy hand spun OREO® Shake.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/c3ad483f-bad7-44f1-96af-4c3dcfc63c6d-retina-large.jpg',
        'Classic OREO® Shake', 3.99, 1),
       ('Tofu boiled with your choice of meat and mushrooms. Served with your choice of side and an assortment of kimchi dishes.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/b7055ca9-3caf-4d9d-9c99-04be1e36dbbf-retina-large-jpeg',
        'Original Soft Tofu', 17.06, 2),
       ('Tofu boiled with beef, shrimp, and clams. Served with your choice of side and an assortment of kimchi dishes.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/37ad1974-1395-4e5c-86ff-fdf120cf8c58-retina-large-jpeg',
        'Combination Soft Tofu', 17.06, 2),
       ('Tofu boiled with mussels, shrimp, and clam. Served with your choice of side and an assortment of kimchi dishes.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/96bc8289-1950-4b4f-823d-12f33349a5fe-retina-large-jpeg',
        'Seafood Soft Tofu', 17.06, 2),
       ('Squid, clam, imitation crab, and grilled onions fried in batter.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/0a94b7e9-903d-49b7-937a-7940c8b56ad5-retina-large-jpeg',
        'Seafood Pancake', 20.27, 2),
       ('Tofu boiled with kimchi and your choice of meat. Served with your choice of side and an assortment of kimchi dishes.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/0c062cff-1868-40e1-946d-29d3e46f1541-retina-large-jpeg',
        'Kimchi Soft Tofu', 17.06, 2),
       ('Beef short ribs served with rice and an assortment of kimchi dishes.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/6340c369-2485-4d60-afcf-ca9068448d84-retina-large.jpg',
        'Beef Short Ribs', 29.36, 2),
       ('Tofu boiled with dumplings, rice cake, and beef. Served with your choice of side and an assortment of kimchi dishes.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/b7055ca9-3caf-4d9d-9c99-04be1e36dbbf-retina-large-jpeg',
        'Dumpling Soft Tofu', 17.06, 2),
       ('Tofu boiled with assorted mushrooms. Served with your choice of side and an assortment of kimchi dishes',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/b7055ca9-3caf-4d9d-9c99-04be1e36dbbf-retina-large-jpeg',
        'Assorted Mushroom Tofu', 17.06, 2),
       ('Rice, BBQ beef, and vegetables served in stoneware with an assortment of kimchi dishes',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/9844dd4e-3c74-4942-8f90-2b3f4be25049-retina-large-jpeg',
        'BBQ Beef & Vegetables in Stoneware', 20.27, 2),
       ('Tofu boiled with ham and cheese. Served with your choice of side and an assortment of kimchi dishes.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/9c6b2a1c-1e2c-4d80-a111-2bebbcadd64c-retina-large.jpg',
        'Ham & Cheese Soft Tofu', 17.06, 2),
       ('Medium spicy.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/5b34852e-d253-461c-8be8-1bb0bc5e39be-retina-large.jpg',
        'Stir Fried Pork with Pepper', 13.99, 3),
       ('',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/bf70f262-0c55-41e1-89bc-84c061ae485f-retina-large.jpg',
        'Eggplant with Minced Pork, Garlic, Cilantro', 14.99, 3),
       ('Mild spicy.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/cb870c77-ace1-49ec-aa2f-9e18de102242-retina-large.jpg',
        'Stir Fried Cauliflower with Pork', 14.99, 3),
       ('Mild spicy.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/1acf9c6b-189d-4583-a151-7ef522c283d9-retina-large.jpg',
        'Poached Fish Fillets in Sour Soup', 17.99, 3),
       ('Very spicy.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/7f05859d-5e83-476d-a45a-73a3eb8a94e0-retina-large.jpg',
        'Stir Fried Beef with Pepper', 16.99, 3),
       ('Medium spicy.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/8b2ca9fc-2c1d-4bf2-96ff-d0bd3c415e8d-retina-large.jpg',
        'Stir Fried Shredded Tripe with Wugang Tofu', 19.99, 3),
       ('Very spicy.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/89ad8679-346e-41d8-b98f-3501fff4b277-retina-large.jpg',
        'Poached Sliced Beef in Hot Chili Oil', 17.99, 3),
       ('With chopped broccoli, peas, carrots, bok choy, egg.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/ec06c431-9426-4971-a129-920440e1c9ce-retina-large.jpg',
        'Fried Rice', 9.5, 3),
       ('Very spicy.',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/2fe1b87f-d41f-4fa4-8cae-5f2ee5bb97e4-retina-large.jpg',
        'Smashed Green Pepper, Chinese Eggplant & Preserved Egg', 11.99, 3),
       ('',
        'https://img.cdn4dd.com/cdn-cgi/image/fit=contain,width=1920,format=auto,quality=50/https://cdn.doordash.com/media/photos/a307e73d-dd12-4841-be14-6f5825a64c59-retina-large.jpg',
        'Stir Fried A-Choy with Minced Garlic', 10.99, 3)) AS seed (description, image_url, name, price, restaurant_id)
WHERE NOT EXISTS (SELECT 1 FROM menu_items);


-- ================= 种子数据：原始餐厅的商家账号（已存在就跳过） =================
-- 账号 merchant<餐厅id>@mail.com，密码 123456（下面是它的 BCrypt 哈希，和 gen_data.sh 里的相同）。
-- 原始餐厅就是带图片的那几家；生成餐厅的商家账号 merchant<餐厅id>@gen.example 由 gen_data.sh 创建

INSERT INTO customers (email, password, enabled, first_name)
SELECT 'merchant' || id || '@mail.com', '{bcrypt}$2a$10$.d6VRj2ebrP0E/p6kdBbGuzCXB9qLGwTDKssefNE7VVWKX1dJ4wo2', TRUE, name
FROM restaurants
WHERE image_url IS NOT NULL
ON CONFLICT (email) DO NOTHING;


INSERT INTO authorities (email, authority)
SELECT 'merchant' || r.id || '@mail.com', 'ROLE_MERCHANT'
FROM restaurants r
WHERE r.image_url IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM authorities a WHERE a.email = 'merchant' || r.id || '@mail.com');


INSERT INTO restaurant_staff (email, restaurant_id)
SELECT 'merchant' || id || '@mail.com', id
FROM restaurants
WHERE image_url IS NOT NULL
ON CONFLICT (email) DO NOTHING;
