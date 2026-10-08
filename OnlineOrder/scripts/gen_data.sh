#!/usr/bin/env bash
# 订单中心造数：用 gawk 生成餐厅、菜品、用户、订单的 CSV，再在一个事务里 COPY 进 onlineorder 库（和主业务同一个库、同一套表）。
#
# 用法（在 OnlineOrder 目录下，先 docker compose up -d，并至少启动过一次后端，让它建好表和种子数据）：
#   scripts/gen_data.sh [--reset] [订单数=2000000] [用户数=100000] [餐厅总数=5000]
#
#   --reset   清空之前生成的数据：orders / order_lines 全清；用户和餐厅只删生成的，真实注册的用户和种子餐厅保留
#   SEED=42   环境变量，随机种子；种子和参数相同则数据完全相同（下单时间以运行时刻为终点）
#
# 数据特征：
#   - 餐厅：种子数据里的 3 家（有图片的那几家）+ 生成的餐厅，16 个品类，每家 10~60 个菜品
#   - 用户：邮箱 user<序号>@gen.example，密码都是 123456，带权限和购物车，可以直接登录前端
#   - 商家账号：每家生成的餐厅一个，merchant<餐厅id>@gen.example，密码 123456（原始餐厅的在 database-init.sql 里）
#   - 餐厅热度 Zipf(s=0.8)：第 k 名的权重是 k^-0.8，排名和 id 随机打乱；原始 3 家固定排第 2、4、7 名
#   - 店内菜品热度 Zipf(s=1.0)：招牌菜约占全店 1/4
#   - 用户分三档：5% 重度（权重 100）、25% 普通（30）、70% 轻度（10）
#   - 下单时间：最近 365 天，订单量逐日增长到约 2 倍，周末多 20%，午餐和晚餐高峰（按 UTC-8 太平洋时间）
#   - 状态：30 分钟内的订单处于 PAID / ACCEPTED，更早的 95% DONE、5% CANCELLED
#
# 导入方式：先删掉 order_lines 的外键，COPY 完再加回（逐行外键检查会慢约 30 倍，见文档 Q7）。
# 整个过程在一个事务里，中途失败会整体回滚，原有数据不受影响。
set -euo pipefail
cd "$(dirname "$0")/.."

RESET=0
if [[ ${1:-} == --reset ]]; then
    RESET=1
    shift
fi
ORDERS=${1:-2000000}
CUSTOMERS=${2:-100000}
RESTAURANTS=${3:-5000}
SEED=${SEED:-42}
HOT_RANKS="2 4 7"   # 原始餐厅在热度榜上的名次
TMP=bench/tmp/gen
mkdir -p "$TMP"

AWK=$(command -v gawk || true)
if [[ -z $AWK ]]; then
    echo "需要 gawk：sudo apt install gawk" >&2
    exit 1
fi

psql_() {
    docker compose exec -T -e PGOPTIONS=--client-min-messages=warning db \
        psql -U postgres -d onlineorder -v ON_ERROR_STOP=1 -qtA "$@"
}
now() { date +%s.%N; }
elapsed() { awk -v a="$1" -v b="$(now)" 'BEGIN{printf "%.1fs", b - a}'; }

GEN_EMAIL_DOMAIN=gen.example
# 生成用户的统一密码 123456 的 BCrypt 哈希（格式与 Spring Security 的 DelegatingPasswordEncoder 一致）
PASSWORD_HASH='{bcrypt}$2a$10$.d6VRj2ebrP0E/p6kdBbGuzCXB9qLGwTDKssefNE7VVWKX1dJ4wo2'

# 1. 表结构（幂等，和后端启动时执行的是同一个文件）+ 本地观测用的扩展（慢 SQL 统计、模糊搜索）
psql_ -f - < src/main/resources/database-init.sql > /dev/null
psql_ -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements; CREATE EXTENSION IF NOT EXISTS pg_trgm;" > /dev/null

# 2. 种子餐厅和菜品：database-init.sql 插入的、带图片的那几家，生成的餐厅都没有图片
BASE_R=$(psql_ -c "SELECT COALESCE(max(id), 0) FROM restaurants WHERE image_url IS NOT NULL")
BASE_M=$(psql_ -c "SELECT COALESCE(max(id), 0) FROM menu_items WHERE restaurant_id <= $BASE_R")
if (( BASE_R == 0 )); then
    echo "还没有种子餐厅，请先启动一次后端" >&2
    exit 1
fi
if (( BASE_R > $(wc -w <<< "$HOT_RANKS") )); then
    echo "原始餐厅有 $BASE_R 家，HOT_RANKS 只给出了 $(wc -w <<< "$HOT_RANKS") 个名次" >&2
    exit 1
fi
if (( RESTAURANTS <= BASE_R || CUSTOMERS < 1 || ORDERS < 1 )); then
    echo "参数不合法：餐厅总数必须大于原始餐厅数 $BASE_R" >&2
    exit 1
fi

# 3. 已有生成数据时必须显式 --reset，避免误删
existing=$(psql_ -c "SELECT (SELECT count(*) FROM orders)
                          + (SELECT count(*) FROM customers WHERE email LIKE '%@$GEN_EMAIL_DOMAIN')
                          + (SELECT count(*) FROM restaurants WHERE id > $BASE_R)")
# 生成的用户 id 接在真实用户后面
CUST_OFFSET=$(psql_ -c "SELECT COALESCE(max(id), 0) FROM customers WHERE email NOT LIKE '%@$GEN_EMAIL_DOMAIN'")
if (( existing > 0 && RESET == 0 )); then
    echo "已有生成的数据，如需重新生成请加 --reset" >&2
    exit 1
fi

psql_ -F ',' -c "SELECT id, restaurant_id, price FROM menu_items
                 WHERE restaurant_id <= $BASE_R AND stock IS NULL ORDER BY id" > "$TMP/base_menu.csv"
if [[ ! -s $TMP/base_menu.csv ]]; then
    echo "没有种子菜品，请先启动一次后端" >&2
    exit 1
fi

# 4. 生成餐厅和菜品
t=$(now)
"$AWK" -v seed="$SEED" -v base_r="$BASE_R" -v base_m="$BASE_M" -v total_r="$RESTAURANTS" \
       -v r_out="$TMP/restaurants.csv" -v m_out="$TMP/menu_items.csv" -v all_out="$TMP/menu_all.csv" '
function addcat(name, words, a, b, c, lo, hi) {
    ncat++; cat_name[ncat] = name; cat_lo[ncat] = lo; cat_hi[ncat] = hi
    cat_words[ncat] = words; cat_a[ncat] = a; cat_b[ncat] = b; cat_c[ncat] = c
}
function pick(s,   arr, n) { n = split(s, arr, "|"); return arr[int(rand() * n) + 1] }
function csv(s) { return "\"" s "\"" }
BEGIN {
    FS = ","; OFS = ","
    # 品类名 | 店名用词 | 菜名三段（第三段可以为空）| 价格区间（美元）
    addcat("Burgers", "Burger Joint|Burger Bar|Grill|Burgers", "Classic|Double|Bacon|Spicy|BBQ|Mushroom Swiss|Jalapeno|Crispy", "Cheeseburger|Chicken Sandwich|Veggie Burger|Beef Burger|Fish Sandwich", "|Combo|Meal", 4, 15)
    addcat("Pizza", "Pizzeria|Pizza Co.|Slice House|Pizza Kitchen", "Margherita|Pepperoni|Spicy Sausage|BBQ Chicken|Hawaiian|Four Cheese|Veggie Supreme|Buffalo", "Pizza|Flatbread|Calzone", "|(Large)|(Personal)", 9, 28)
    addcat("Mexican", "Taqueria|Cantina|Tacos|Burrito Bar", "Carne Asada|Al Pastor|Chipotle Chicken|Carnitas|Spicy Shrimp|Veggie|Barbacoa|Fish", "Taco|Burrito|Quesadilla|Bowl|Nachos|Enchilada", "|Plate", 4, 16)
    addcat("Chinese", "Kitchen|Wok|Garden|Dumpling House|Noodle Bar", "Kung Pao|General Tso|Sichuan Spicy|Orange|Garlic|Black Pepper|Mapo|Honey Walnut|Szechuan Hot", "Chicken|Beef|Shrimp|Tofu|Pork|Fish", "|Fried Rice|Lo Mein|Combo|Noodle Soup", 9, 22)
    addcat("Japanese", "Sushi|Ramen House|Izakaya|Sushi Bar", "Salmon|Tuna|Spicy Tuna|Eel|Teriyaki Chicken|Tonkotsu|Miso|Shrimp Tempura|California", "Roll|Nigiri|Ramen|Don|Udon|Bento", "", 6, 30)
    addcat("Thai", "Thai Kitchen|Thai Cuisine|Bangkok Street", "Pad Thai|Green Curry|Red Curry|Basil|Drunken Noodle|Pineapple Fried Rice|Tom Yum|Panang", "with Chicken|with Beef|with Shrimp|with Tofu", "", 11, 20)
    addcat("Indian", "Curry House|Tandoor|Indian Kitchen|Masala", "Butter|Tikka Masala|Vindaloo|Saag|Korma|Spicy Madras|Tandoori", "Chicken|Lamb|Paneer|Shrimp|Chickpea", "|with Rice|Plate", 12, 24)
    addcat("Italian", "Trattoria|Osteria|Pasta Bar|Italian Kitchen", "Spaghetti|Fettuccine|Penne|Rigatoni|Lasagna|Gnocchi|Risotto", "Bolognese|Alfredo|Carbonara|Arrabbiata|Pesto|Marinara", "", 14, 32)
    addcat("Mediterranean", "Grill|Kebab House|Falafel|Mezze", "Chicken|Lamb|Beef|Falafel|Halloumi|Spicy Chicken", "Shawarma|Gyro|Kebab|Plate|Wrap|Bowl", "", 10, 20)
    addcat("Breakfast", "Cafe|Diner|Brunch House|Bagels", "Classic|Avocado|Bacon|Sausage|Spicy Chorizo|Veggie|Blueberry|Ham", "Omelette|Breakfast Burrito|Bagel|Pancakes|Toast|Waffle", "", 6, 15)
    addcat("Dessert", "Sweets|Bakery|Creamery|Dessert Bar", "Chocolate|Strawberry|Matcha|Mango|Tiramisu|Salted Caramel|Cookies and Cream|Lemon", "Cheesecake|Ice Cream|Cake|Crepe|Tart|Shake", "", 4, 10)
    addcat("Bubble Tea", "Tea House|Boba|Tea Bar", "Brown Sugar|Taro|Mango|Matcha|Classic|Jasmine|Strawberry|Oolong", "Milk Tea|Boba|Smoothie|Fruit Tea|Latte", "|(Large)", 4, 8)
    addcat("BBQ", "BBQ|Smokehouse|Barbecue Co.", "Smoked|Texas|Spicy|Honey|Hickory|Carolina", "Brisket|Pulled Pork|Ribs|Wings|Sausage|Chicken", "|Platter|Sandwich", 12, 35)
    addcat("Sandwiches", "Deli|Sandwich Shop|Subs", "Turkey|Italian|Roast Beef|Chicken Pesto|Spicy Buffalo|Tuna|Veggie|Ham", "Club|Sub|Panini|Wrap", "", 8, 15)
    addcat("Vietnamese", "Pho|Banh Mi|Vietnamese Kitchen", "Beef|Chicken|Lemongrass Pork|Spicy Beef|Shrimp|Tofu|Grilled Pork", "Pho|Banh Mi|Vermicelli Bowl|Spring Rolls|Rice Plate", "", 10, 18)
    addcat("Korean", "Korean BBQ|Tofu House|Korean Kitchen", "Kimchi|Spicy Pork|Bulgogi|Seafood|Beef|Spicy Tofu|Galbi", "Bibimbap|Soondubu|Jjigae|Fried Rice|Rice Bowl", "", 12, 30)
    prefixes = "Golden|Lucky|Uncle Joe'"'"'s|Little|Blue Door|Main Street|Sunset|Red Lantern|Happy|Royal|Urban|Old Town|Green Leaf|Big Mike'"'"'s|Mama'"'"'s|Corner|Silver Spoon|Fire|Ocean|Jade|Maple|Harbor|Northside|Sunny|Lotus|Twin Pines|Hilltop|Bay|Union|Liberty"
    streets = "Main|Oak|Pine|Maple|Cedar|Elm|Washington|Lake|Hill|Park|Market|Mission|Broadway|Valencia|Geary|Clement|Irving|Castro"
    cities = "San Francisco|Oakland|San Jose|Berkeley|Palo Alto|Fremont|Daly City|San Mateo"
}
{ print > all_out }   # 原始菜品原样写入 menu_all.csv
END {
    srand(seed); m = base_m
    for (r = base_r + 1; r <= total_r; r++) {
        c = int(rand() * ncat) + 1
        name = pick(prefixes) " " pick(cat_words[c])
        addr = (int(rand() * 9000) + 100) " " pick(streets) " " pick("St|Ave|Blvd") ", " pick(cities) ", CA"
        phone = sprintf("%03d%03d%04d", 200 + int(rand() * 800), 200 + int(rand() * 800), int(rand() * 10000))
        print r, csv(name), csv(addr), phone, csv(cat_name[c]) > r_out

        delete seen
        items = int(rand() * 51) + 10
        for (j = 1; j <= items; j++) {
            for (try = 0; try < 20; try++) {
                c3 = pick(cat_c[c])
                dish = pick(cat_a[c]) " " pick(cat_b[c]) (c3 == "" ? "" : " " c3)
                if (!(dish in seen)) break
            }
            if (dish in seen) continue   # 组合用完了，这家店的菜少几个
            seen[dish] = 1
            price = int(cat_lo[c] + rand() * (cat_hi[c] - cat_lo[c])) + (rand() < 0.5 ? 0.49 : 0.99)
            print ++m, r, csv(dish), price > m_out
            print m, r, price > all_out
        }
    }
}' "$TMP/base_menu.csv"

# 5. 生成用户：customers + 登录权限 authorities + 空购物车 carts（和注册流程产生的数据一致）
"$AWK" -v n="$CUSTOMERS" -v seed="$SEED" -v offset="$CUST_OFFSET" -v domain="$GEN_EMAIL_DOMAIN" -v hash="$PASSWORD_HASH" \
       -v auth_out="$TMP/authorities.csv" -v carts_out="$TMP/carts.csv" 'BEGIN {
    srand(seed + 1); OFS = ","
    split("James|Mary|Robert|Patricia|John|Jennifer|Michael|Linda|David|Elizabeth|Wei|Mei|Jose|Maria|Kevin|Emily|Daniel|Sarah|Ethan|Olivia|Ryan|Grace|Jason|Chloe", first, "|")
    split("Smith|Johnson|Williams|Brown|Jones|Garcia|Miller|Davis|Chen|Wang|Lee|Nguyen|Martinez|Lopez|Kim|Patel|Wilson|Anderson|Taylor|Thomas", last, "|")
    split("415|510|408|650|628|925", area, "|")
    end = systime()
    for (i = 1; i <= n; i++) {
        email = "user" i "@" domain
        print offset + i, email, hash, first[int(rand() * 24) + 1], last[int(rand() * 20) + 1],
              area[int(rand() * 6) + 1] sprintf("%07d", i),
              strftime("%Y-%m-%d %H:%M:%S+00", end - 365 * 86400 - int(rand() * 365 * 86400), 1)
        print email, "ROLE_USER" > auth_out
        print offset + i, 0 > carts_out
    }
}' > "$TMP/customers.csv"

# 6. 生成订单和明细
"$AWK" -F ',' -v n="$ORDERS" -v customers="$CUSTOMERS" -v offset="$CUST_OFFSET" -v seed="$SEED" -v base_r="$BASE_R" -v hot_ranks="$HOT_RANKS" \
       -v orders_out="$TMP/orders.csv" -v lines_out="$TMP/order_lines.csv" '
# 在累计权重数组里二分查找第一个 >= u 的位置
function bsearch(cum, n, u,   lo, hi, mid) {
    lo = 1; hi = n
    while (lo < hi) { mid = int((lo + hi) / 2); if (cum[mid] < u) lo = mid + 1; else hi = mid }
    return lo
}
function pick_dish(r, u,   lo, hi, mid) {
    lo = 1; hi = cnt[r]
    while (lo < hi) { mid = int((lo + hi) / 2); if (dcum[r, mid] < u) lo = mid + 1; else hi = mid }
    return item[r, lo]
}
function sample_time(   u, x, day, dstart, t) {
    while (1) {
        u = rand(); x = -1 + sqrt(1 + 3 * u)   # 密度 ∝ 1 + x：一年里订单量线性增长到 2 倍
        day = int(x * 365)
        dstart = day0 + day * 86400
        if (((int((dstart + tz) / 86400) + 3) % 7) < 5 && rand() > 1 / 1.2) continue   # 周一到周五少 1/6
        t = dstart + (bsearch(hcum, 24, rand() * htot) - 1) * 3600 + int(rand() * 3600)
        if (t <= end) return t
    }
}
{   # 读 menu_all.csv：id, restaurant_id, price
    r = $2
    if (!(r in cnt)) rids[++nr] = r
    cnt[r]++; item[r, cnt[r]] = $1; price[$1] = $3
}
END {
    srand(seed + 2); OFS = ","
    end = systime(); tz = -8 * 3600                              # 太平洋时间 UTC-8
    day0 = int((end - 364 * 86400 + tz) / 86400) * 86400 - tz    # 窗口第一天的本地零点

    # 小时权重（本地时间 0~23 点）：午餐、晚餐两个高峰
    split("3 2 1 1 1 1 2 4 5 4 6 14 16 8 4 3 4 9 13 10 6 5 4 3", hw, " ")
    for (h = 1; h <= 24; h++) { htot += hw[h]; hcum[h] = htot }

    # 餐厅热度排名：原始餐厅放在 hot_ranks 指定的名次，其余餐厅随机打乱填进剩下的名次
    nh = split(hot_ranks, hr, " ")
    for (i = 1; i <= nr; i++) {
        r = rids[i]
        if (r <= base_r) { rank_of[hr[++used]] = r } else { others[++no] = r }
    }
    for (i = no; i > 1; i--) { j = int(rand() * i) + 1; tmp = others[i]; others[i] = others[j]; others[j] = tmp }
    k = 0
    for (rank = 1; rank <= nr; rank++) {
        if (!(rank in rank_of)) rank_of[rank] = others[++k]
        rtot += rank ^ -0.8; rcum[rank] = rtot
    }

    # 店内菜品热度：菜品顺序随机打乱后按 Zipf(1.0) 分配权重
    for (i = 1; i <= nr; i++) {
        r = rids[i]; tot = 0
        for (j = cnt[r]; j > 1; j--) { x = int(rand() * j) + 1; tmp = item[r, j]; item[r, j] = item[r, x]; item[r, x] = tmp }
        for (j = 1; j <= cnt[r]; j++) { tot += 1 / j; dcum[r, j] = tot }
        dtot[r] = tot
    }

    # 用户活跃度三档
    for (i = 1; i <= customers; i++) {
        u = rand(); ctot += (u < 0.05 ? 100 : u < 0.30 ? 30 : 10); ccum[i] = ctot
    }

    for (i = 1; i <= n; i++) {
        cid = offset + bsearch(ccum, customers, rand() * ctot)
        r = rank_of[bsearch(rcum, nr, rand() * rtot)]
        t = sample_time()
        u = rand()
        if (end - t < 1800) status = u < 0.5 ? "PAID" : u < 0.9 ? "ACCEPTED" : "CANCELLED"
        else                status = u < 0.95 ? "DONE" : "CANCELLED"

        u = rand(); k = u < 0.45 ? 1 : u < 0.80 ? 2 : u < 0.95 ? 3 : 4
        if (k > cnt[r]) k = cnt[r]
        delete picked; total = 0
        for (j = 1; j <= k; j++) {
            for (try = 0; try < 10; try++) {
                mid = pick_dish(r, rand() * dtot[r])
                if (!(mid in picked)) break
            }
            if (mid in picked) continue
            picked[mid] = 1
            u = rand(); qty = u < 0.80 ? 1 : u < 0.97 ? 2 : 3
            total += price[mid] * qty
            print i, mid, price[mid], qty > lines_out
        }
        print i, cid, r, status, sprintf("%.2f", total), strftime("%Y-%m-%d %H:%M:%S+00", t, 1) > orders_out
    }
}' "$TMP/menu_all.csv"
echo "生成 CSV：$(elapsed "$t")"
for f in restaurants menu_items customers authorities carts orders order_lines; do
    printf '  %-12s %9d 行\n' "$f" "$(wc -l < "$TMP/$f.csv")"
done

# 7. 上传到容器，由服务端直接读文件 COPY（不经过 psql 客户端中转）
t=$(now)
docker compose exec -T db sh -c 'rm -rf /tmp/gen && mkdir -p /tmp/gen'
for f in restaurants menu_items customers authorities carts orders order_lines; do
    docker compose exec -T db sh -c "cat > /tmp/gen/$f.csv" < "$TMP/$f.csv"
done
echo "上传到容器：$(elapsed "$t")"

# 8. 一个事务内完成：删外键 → (清理旧数据) → COPY → 加回外键 → 推进序列
reset_sql=""
if (( RESET == 1 )); then
    # 先删权限和购物车再删用户：authorities.email 上没有索引，靠级联删除会对每个用户扫一遍全表
    reset_sql="TRUNCATE order_lines, orders RESTART IDENTITY;
DELETE FROM authorities WHERE email LIKE '%@$GEN_EMAIL_DOMAIN';
DELETE FROM carts WHERE customer_id IN (SELECT id FROM customers WHERE email LIKE '%@$GEN_EMAIL_DOMAIN');
DELETE FROM customers WHERE email LIKE '%@$GEN_EMAIL_DOMAIN';
DELETE FROM restaurants WHERE id > $BASE_R;"
fi
t=$(now)
# 每个 \echo 标签后面可能有多条语句，把它们的耗时累加到该标签下
psql_ -1 -f - <<SQL | awk '
    function flush() { if (label != "") printf "  %-26s %6.1fs\n", label, ms / 1000 }
    /^>> / { flush(); label = substr($0, 4); ms = 0; next }
    /^Time: / { ms += $2 }
    END { flush() }'
\timing on
\echo '>> 删除 order_lines 外键'
ALTER TABLE order_lines DROP CONSTRAINT IF EXISTS fk_line_order, DROP CONSTRAINT IF EXISTS fk_line_menu_item;
\echo '>> 清理旧数据'
$reset_sql
\echo '>> 原始餐厅补品类'
UPDATE restaurants SET category = CASE WHEN name ILIKE '%burger%' THEN 'Burgers'
                                       WHEN name ILIKE '%tofu%'   THEN 'Korean'
                                       WHEN name ILIKE '%wok%'    THEN 'Chinese' END
WHERE id <= $BASE_R;
\echo '>> COPY restaurants'
COPY restaurants (id, name, address, phone, category) FROM '/tmp/gen/restaurants.csv' WITH (FORMAT csv, ENCODING 'UTF8');
\echo '>> COPY menu_items'
COPY menu_items (id, restaurant_id, name, price) FROM '/tmp/gen/menu_items.csv' WITH (FORMAT csv, ENCODING 'UTF8');
\echo '>> COPY customers'
COPY customers (id, email, password, first_name, last_name, phone, created_at) FROM '/tmp/gen/customers.csv' WITH (FORMAT csv, ENCODING 'UTF8');
\echo '>> COPY authorities'
COPY authorities (email, authority) FROM '/tmp/gen/authorities.csv' WITH (FORMAT csv);
\echo '>> COPY carts'
COPY carts (customer_id, total_price) FROM '/tmp/gen/carts.csv' WITH (FORMAT csv);
\echo '>> COPY orders'
COPY orders (id, customer_id, restaurant_id, status, total_price, created_at) FROM '/tmp/gen/orders.csv' WITH (FORMAT csv);
\echo '>> COPY order_lines'
COPY order_lines (order_id, menu_item_id, price, quantity) FROM '/tmp/gen/order_lines.csv' WITH (FORMAT csv);
\echo '>> 加回外键（一次性校验）'
ALTER TABLE order_lines
    ADD CONSTRAINT fk_line_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_line_menu_item FOREIGN KEY (menu_item_id) REFERENCES menu_items (id);
\echo '>> 推进序列'
SELECT setval(pg_get_serial_sequence('restaurants', 'id'), (SELECT max(id) FROM restaurants)),
       setval(pg_get_serial_sequence('menu_items', 'id'), (SELECT max(id) FROM menu_items)),
       setval(pg_get_serial_sequence('customers', 'id'), (SELECT max(id) FROM customers)),
       setval(pg_get_serial_sequence('authorities', 'id'), (SELECT max(id) FROM authorities)),
       setval(pg_get_serial_sequence('carts', 'id'), (SELECT max(id) FROM carts)),
       setval(pg_get_serial_sequence('orders', 'id'), (SELECT max(id) FROM orders)),
       setval(pg_get_serial_sequence('order_lines', 'id'), (SELECT max(id) FROM order_lines));
\echo '>> 生成餐厅的商家账号'
INSERT INTO customers (email, password, enabled, first_name)
SELECT 'merchant' || id || '@$GEN_EMAIL_DOMAIN', '$PASSWORD_HASH', TRUE, name FROM restaurants WHERE id > $BASE_R;
INSERT INTO authorities (email, authority)
SELECT 'merchant' || id || '@$GEN_EMAIL_DOMAIN', 'ROLE_MERCHANT' FROM restaurants WHERE id > $BASE_R;
INSERT INTO restaurant_staff (email, restaurant_id)
SELECT 'merchant' || id || '@$GEN_EMAIL_DOMAIN', id FROM restaurants WHERE id > $BASE_R;
SQL
echo "导入总计（单事务）：$(elapsed "$t")"
docker compose exec -T db rm -rf /tmp/gen
rm -rf "$TMP"
psql_ -c "ANALYZE restaurants, menu_items, customers, authorities, carts, orders, order_lines"

# 9. 校验分布
echo
psql_ -F ' | ' <<SQL
\pset tuples_only off
\pset footer off
\echo == 数据量
SELECT relname AS 表, n_live_tup AS 行数, pg_size_pretty(pg_total_relation_size(relid)) AS 大小
FROM pg_stat_user_tables WHERE relname IN ('restaurants', 'menu_items', 'customers', 'authorities', 'carts', 'orders', 'order_lines') ORDER BY relname;
\echo
\echo == 餐厅热度（订单数排名）
WITH r AS (SELECT restaurant_id, count(*) AS c, rank() OVER (ORDER BY count(*) DESC) AS rk FROM orders GROUP BY 1)
SELECT (SELECT round(100.0 * max(c) / sum(c), 1) FROM r)                                          AS 第1名占比,
       (SELECT round(100.0 * sum(c) FILTER (WHERE rk <= (SELECT count(*) FROM r) / 100) / sum(c), 1) FROM r) AS "前1%占比",
       (SELECT percentile_disc(0.5) WITHIN GROUP (ORDER BY c) FROM r)                            AS 中位数订单,
       (SELECT min(c) FROM r)                                                                    AS 最少订单;
\echo
\echo == 原始餐厅的热度名次
WITH r AS (SELECT restaurant_id, count(*) AS c, rank() OVER (ORDER BY count(*) DESC) AS rk FROM orders GROUP BY 1)
SELECT rs.id, rs.name, r.c AS 订单数, r.rk AS 名次 FROM r JOIN restaurants rs ON rs.id = r.restaurant_id
WHERE rs.id <= $BASE_R ORDER BY r.rk;
\echo
\echo == 店内招牌菜占比（订单数 1000 以上的餐厅的平均值）
WITH d AS (SELECT o.restaurant_id, l.menu_item_id, count(*) AS c FROM order_lines l JOIN orders o ON o.id = l.order_id GROUP BY 1, 2),
     s AS (SELECT restaurant_id, max(c)::numeric / sum(c) AS top_share FROM d GROUP BY 1 HAVING sum(c) >= 1000)
SELECT round(100 * avg(top_share), 1) AS 招牌菜平均占比 FROM s;
\echo
\echo == 下单时段（UTC-8）订单量最高的 5 个小时
SELECT extract(hour FROM created_at AT TIME ZONE INTERVAL '-08:00')::int AS 小时, count(*) AS 订单数 FROM orders GROUP BY 1 ORDER BY 2 DESC LIMIT 5;
\echo
\echo == 订单状态
SELECT status AS 状态, count(*) AS 订单数 FROM orders GROUP BY 1 ORDER BY 2 DESC;
SQL

# 10. 硬性检查：原始餐厅必须全部在热度前 10
bad=$(psql_ -c "WITH r AS (SELECT restaurant_id, rank() OVER (ORDER BY count(*) DESC) AS rk FROM orders GROUP BY 1)
                SELECT count(*) FROM restaurants rs LEFT JOIN r ON r.restaurant_id = rs.id
                WHERE rs.id <= $BASE_R AND (r.rk IS NULL OR r.rk > 10)")
if (( bad > 0 )); then
    echo "校验失败：有 $bad 家原始餐厅不在热度前 10" >&2
    exit 1
fi
echo
echo "校验通过：原始 $BASE_R 家餐厅都在热度前 10"
