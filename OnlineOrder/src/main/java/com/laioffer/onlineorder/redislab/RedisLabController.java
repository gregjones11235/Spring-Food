package com.laioffer.onlineorder.redislab;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


/**
 * Redis 5 种基本类型的练习通道。
 * 和主业务共用同一个数据库（菜品、订单是同一套表，见 LabDatabase）和同一个 Redis；
 * 这里的 Redis key 统一以 "lab:" 开头，和主业务两级缓存的 "cache:" 前缀区分开。
 * 建议边调用边在 redis-cli 里执行 MONITOR 观察实际发出的命令。
 */
@RestController
@RequestMapping("/lab/redis")
public class RedisLabController {


    private static final String PREFIX = "lab:";
    private static final String NULL_MARKER = "__NULL__";
    private static final String HOT_KEY = PREFIX + "hot:menu_items";
    private static final String ORDER_QUEUE_KEY = PREFIX + "orders:queue";
    private static final String STREAM_KEY = PREFIX + "stream:orders";
    private static final String STREAM_GROUP = "kitchen";


    private final StringRedisTemplate redis;
    private final LabDatabase db;
    private final ObjectMapper objectMapper;


    public RedisLabController(StringRedisTemplate redis, LabDatabase db, ObjectMapper objectMapper) {
        this.redis = redis;
        this.db = db;
        this.objectMapper = objectMapper;
    }


    // ---------------- String ----------------


    // 手写 cache-aside：GET 命中直接返回；未命中查库，把整个菜品序列化成 JSON 后 SET key value EX 60。
    // 数据库里没有的 id：cacheNull=true 时缓存一个空值标记 10 秒，防止缓存穿透
    @GetMapping("/string/menu-item/{id}")
    public Map<String, Object> cachedMenuItem(
            @PathVariable long id,
            @RequestParam(defaultValue = "true") boolean cacheNull) throws JsonProcessingException {
        String key = menuItemKey(id);
        Map<String, Object> result = new LinkedHashMap<>();
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            boolean isNull = NULL_MARKER.equals(cached);
            result.put("source", isNull ? "redis (空值标记)" : "redis");
            result.put("item", isNull ? "不存在" : objectMapper.readValue(cached, LabMenuItem.class));
        } else {
            Optional<LabMenuItem> item = db.findMenuItem(id);
            if (item.isPresent()) {
                redis.opsForValue().set(key, objectMapper.writeValueAsString(item.get()), Duration.ofSeconds(60));
            } else if (cacheNull) {
                redis.opsForValue().set(key, NULL_MARKER, Duration.ofSeconds(10));
            }
            result.put("source", "db");
            result.put("item", item.isPresent() ? item.get() : "不存在");
        }
        result.put("ttl_seconds", redis.getExpire(key));
        return result;
    }


    // 修改数据库里的价格。evict=true 时改完顺手 DEL 缓存，evict=false 用来观察缓存和数据库不一致
    @PutMapping("/db/menu-item/{id}/price")
    public ResponseEntity<Map<String, Object>> updatePrice(
            @PathVariable long id,
            @RequestParam double price,
            @RequestParam(defaultValue = "true") boolean evict) {
        if (db.updatePrice(id, price) == 0) {
            return ResponseEntity.status(404).body(Map.of("error", "menu item not found: " + id));
        }
        boolean evicted = evict && Boolean.TRUE.equals(redis.delete(menuItemKey(id)));
        return ResponseEntity.ok(Map.of("menu_item_id", id, "db_price", price, "cache_evicted", evicted));
    }


    // INCR 原子计数：菜品浏览量，先只记在 Redis
    @PostMapping("/string/menu-item/{id}/view")
    public Map<String, Object> viewMenuItem(@PathVariable long id) {
        Long views = redis.opsForValue().increment(viewsKey(id));
        return Map.of("menu_item_id", id, "views_in_redis", views);
    }


    // 把 Redis 里累积的浏览量批量回写数据库：SCAN 找 key，GETDEL 原子地"取出并清零"，再累加进表
    @PostMapping("/string/views/flush")
    public Map<String, Object> flushViews() {
        Map<Long, Long> flushed = new LinkedHashMap<>();
        for (String key : scanKeys(PREFIX + "views:*")) {
            String value = redis.opsForValue().getAndDelete(key);
            if (value != null) {
                long menuItemId = Long.parseLong(key.substring(key.lastIndexOf(':') + 1));
                db.addViews(menuItemId, Long.parseLong(value));
                flushed.put(menuItemId, Long.parseLong(value));
            }
        }
        return Map.of("flushed", flushed, "db_views", db.findViews());
    }


    @GetMapping("/db/views")
    public List<Map<String, Object>> dbViews() {
        return db.findViews();
    }


    // SET key value NX EX 10：10 秒内重复结账会被拒绝（和下面的结账接口共用同一个 key）
    @PostMapping("/string/checkout-lock/{customerId}")
    public Map<String, Object> checkoutLock(@PathVariable long customerId) {
        String key = checkoutLockKey(customerId);
        Boolean acquired = redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(10));
        return Map.of("acquired", Boolean.TRUE.equals(acquired), "ttl_seconds", redis.getExpire(key));
    }


    // ---------------- Hash ----------------


    // HINCRBY lab:cart:{customerId} {menuItemId} 1
    @PostMapping("/hash/cart/{customerId}/items/{menuItemId}")
    public ResponseEntity<Map<String, Object>> addToCart(@PathVariable long customerId, @PathVariable long menuItemId) {
        if (db.findMenuItem(menuItemId).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "menu item not found: " + menuItemId));
        }
        Long quantity = redis.opsForHash().increment(cartKey(customerId), String.valueOf(menuItemId), 1);
        return ResponseEntity.ok(Map.of("menu_item_id", menuItemId, "quantity", quantity));
    }


    // HGETALL，再用数据库里的价格算总价
    @GetMapping("/hash/cart/{customerId}")
    public Map<String, Object> getCart(@PathVariable long customerId) {
        List<Map<String, Object>> items = new ArrayList<>();
        double total = 0;
        for (Map.Entry<Long, Integer> entry : readCart(customerId).entrySet()) {
            LabMenuItem menuItem = db.findMenuItem(entry.getKey()).orElse(null);
            double price = menuItem == null ? 0 : menuItem.price();
            total += price * entry.getValue();
            items.add(Map.of(
                    "menu_item_id", entry.getKey(),
                    "name", menuItem == null ? "N/A" : menuItem.name(),
                    "price", price,
                    "quantity", entry.getValue()));
        }
        return Map.of("items", items, "total_price", Math.round(total * 100) / 100.0);
    }


    // DEL：清空购物车只需一条命令
    @DeleteMapping("/hash/cart/{customerId}")
    public Map<String, Object> clearCart(@PathVariable long customerId) {
        return Map.of("deleted", Boolean.TRUE.equals(redis.delete(cartKey(customerId))));
    }


    // 结账：把几种类型串起来。
    // 1. SET NX EX 防重复提交  2. 读 Hash 购物车  3. 事务写入数据库 orders/order_lines
    // 4. ZINCRBY 热销榜  5. LPUSH 通知后厨  6. DEL 购物车
    @PostMapping("/hash/cart/{customerId}/checkout")
    public Map<String, Object> checkout(@PathVariable long customerId) {
        String lockKey = checkoutLockKey(customerId);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(10)))) {
            return Map.of("ok", false, "reason", "10 秒内重复结账，被锁拦截", "lock_ttl_seconds", redis.getExpire(lockKey));
        }
        Map<Long, Integer> cart = readCart(customerId);
        if (cart.isEmpty()) {
            redis.delete(lockKey);
            return Map.of("ok", false, "reason", "购物车是空的");
        }
        long orderId = db.createOrder(customerId, cart);
        for (Map.Entry<Long, Integer> entry : cart.entrySet()) {
            redis.opsForZSet().incrementScore(HOT_KEY, String.valueOf(entry.getKey()), entry.getValue());
        }
        redis.opsForList().leftPush(ORDER_QUEUE_KEY, "order-" + orderId);
        redis.delete(cartKey(customerId));
        return Map.of("ok", true, "order_id", orderId);
    }


    @GetMapping("/db/orders/{customerId}")
    public List<Map<String, Object>> dbOrders(@PathVariable long customerId) {
        return db.findOrders(customerId);
    }


    // ---------------- List ----------------


    // LPUSH + LTRIM 0 4：只保留最近浏览的 5 个菜品（允许重复）
    @PostMapping("/list/recent/{customerId}/{menuItemId}")
    public List<String> pushRecent(@PathVariable long customerId, @PathVariable long menuItemId) {
        String key = PREFIX + "recent:" + customerId;
        redis.opsForList().leftPush(key, String.valueOf(menuItemId));
        redis.opsForList().trim(key, 0, 4);
        return toMenuItemNames(redis.opsForList().range(key, 0, -1));
    }


    // LPUSH 订单进队列（生产者）
    @PostMapping("/list/orders")
    public Map<String, Object> enqueueOrder(@RequestParam String order) {
        Long size = redis.opsForList().leftPush(ORDER_QUEUE_KEY, order);
        return Map.of("queue_size", size);
    }


    // 取一单（消费者/后厨），LPUSH + RPOP = 先进先出。
    // timeout > 0：BRPOP 最多阻塞 timeout 秒；timeout = 0：非阻塞的 RPOP，队列空时立刻返回。
    // 注意 Redis 原生的 BRPOP key 0 表示"永远等待"，这里为了避免请求一直挂住，把 0 映射成了 RPOP
    @PostMapping("/list/orders/take")
    public Map<String, Object> takeOrder(@RequestParam(defaultValue = "5") long timeout) {
        String order = timeout > 0
                ? redis.opsForList().rightPop(ORDER_QUEUE_KEY, Duration.ofSeconds(timeout))
                : redis.opsForList().rightPop(ORDER_QUEUE_KEY);
        return order == null ? Map.of("order", "queue empty") : Map.of("order", order);
    }


    // ---------------- Set ----------------


    // SADD：重复收藏不会产生重复元素
    @PostMapping("/set/fav/{customerId}/{menuItemId}")
    public Map<String, Object> favorite(@PathVariable long customerId, @PathVariable long menuItemId) {
        Long added = redis.opsForSet().add(favKey(customerId), String.valueOf(menuItemId));
        return Map.of("newly_added", added != null && added > 0, "fav_count", redis.opsForSet().size(favKey(customerId)));
    }


    // SINTER：两个用户共同收藏的菜品。
    // 集群模式下多 key 命令要求所有 key 在同一个 slot，否则报 CROSSSLOT；
    // 所以收藏的 key 里带了 hash tag {fav}，只有花括号里的部分参与 slot 计算，所有用户的收藏都落在同一个 slot
    @GetMapping("/set/fav/common")
    public List<String> commonFavorites(@RequestParam long a, @RequestParam long b) {
        Set<String> ids = redis.opsForSet().intersect(favKey(a), favKey(b));
        return toMenuItemNames(ids == null ? List.of() : new ArrayList<>(ids));
    }


    // ---------------- Sorted Set ----------------


    // ZINCRBY lab:hot:menu_items {qty} {menuItemId}（结账接口也会自动调用）
    @PostMapping("/zset/sold/{menuItemId}")
    public Map<String, Object> sold(@PathVariable long menuItemId, @RequestParam(defaultValue = "1") int qty) {
        Double score = redis.opsForZSet().incrementScore(HOT_KEY, String.valueOf(menuItemId), qty);
        return Map.of("menu_item_id", menuItemId, "total_sold", score);
    }


    // ZREVRANGE lab:hot:menu_items 0 n-1 WITHSCORES：热销榜
    @GetMapping("/zset/top")
    public List<Map<String, Object>> top(@RequestParam(defaultValue = "5") int n) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeWithScores(HOT_KEY, 0, n - 1);
        List<Map<String, Object>> result = new ArrayList<>();
        if (tuples == null) {
            return result;
        }
        int rank = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", rank++);
            row.put("name", toMenuItemNames(List.of(tuple.getValue())).get(0));
            row.put("sold", tuple.getScore());
            result.add(row);
        }
        return result;
    }


    // ---------------- Stream（消费者组） ----------------


    // XADD lab:stream:orders * order {order}：消息追加到 Stream，不会因为被读取而消失
    @PostMapping("/stream/orders")
    public Map<String, Object> streamAdd(@RequestParam String order) {
        RecordId id = redis.opsForStream().add(STREAM_KEY, Map.of("order", order));
        ensureStreamGroup();
        return Map.of("id", id.getValue(), "stream_length", redis.opsForStream().size(STREAM_KEY));
    }


    // XREADGROUP GROUP kitchen {consumer} COUNT {count} BLOCK 2000 STREAMS lab:stream:orders >
    // ">" 表示只读从未分配给本组任何消费者的新消息；读到的消息进入该消费者的 pending 列表，直到 XACK
    @PostMapping("/stream/orders/read")
    public List<Map<String, Object>> streamRead(
            @RequestParam String consumer,
            @RequestParam(defaultValue = "1") long count) {
        if (!Boolean.TRUE.equals(redis.hasKey(STREAM_KEY))) {
            return List.of();
        }
        ensureStreamGroup();
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(STREAM_GROUP, consumer),
                StreamReadOptions.empty().count(count).block(Duration.ofSeconds(2)),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
        return toStreamMessages(records, consumer);
    }


    // XACK lab:stream:orders kitchen {id}：确认处理完成，消息从 pending 列表移除
    @PostMapping("/stream/orders/ack/{id}")
    public Map<String, Object> streamAck(@PathVariable String id) {
        Long acked = redis.opsForStream().acknowledge(STREAM_KEY, STREAM_GROUP, id);
        return Map.of("id", id, "acked", acked != null && acked > 0);
    }


    // XPENDING：已经分给消费者、但还没 XACK 的消息，以及它们闲置了多久、被投递过几次
    @GetMapping("/stream/orders/pending")
    public Map<String, Object> streamPending() {
        if (!Boolean.TRUE.equals(redis.hasKey(STREAM_KEY))) {
            return Map.of("total", 0);
        }
        ensureStreamGroup();
        PendingMessagesSummary summary = redis.opsForStream().pending(STREAM_KEY, STREAM_GROUP);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (PendingMessage message : redis.opsForStream().pending(STREAM_KEY, STREAM_GROUP, Range.unbounded(), 100)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", message.getIdAsString());
            row.put("consumer", message.getConsumerName());
            row.put("idle_seconds", message.getElapsedTimeSinceLastDelivery().toSeconds());
            row.put("delivery_count", message.getTotalDeliveryCount());
            messages.add(row);
        }
        return Map.of(
                "total", summary.getTotalPendingMessages(),
                "per_consumer", summary.getPendingMessagesPerConsumer(),
                "messages", messages);
    }


    // XCLAIM：把闲置超过 minIdleSeconds 的 pending 消息转给 {consumer}，用来接管崩溃消费者手里的消息
    @PostMapping("/stream/orders/claim")
    public List<Map<String, Object>> streamClaim(
            @RequestParam String consumer,
            @RequestParam(defaultValue = "10") long minIdleSeconds) {
        if (!Boolean.TRUE.equals(redis.hasKey(STREAM_KEY))) {
            return List.of();
        }
        Duration minIdle = Duration.ofSeconds(minIdleSeconds);
        List<RecordId> ids = new ArrayList<>();
        for (PendingMessage message : redis.opsForStream().pending(STREAM_KEY, STREAM_GROUP, Range.unbounded(), 100)) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0) {
                ids.add(message.getId());
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                .claim(STREAM_KEY, STREAM_GROUP, consumer, minIdle, ids.toArray(new RecordId[0]));
        return toStreamMessages(records, consumer);
    }


    // XGROUP CREATE lab:stream:orders kitchen 0：从头开始消费，组已存在时 Redis 返回 BUSYGROUP，忽略即可
    private void ensureStreamGroup() {
        try {
            redis.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), STREAM_GROUP);
        } catch (RuntimeException e) {
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")
                    && (e.getCause() == null || !String.valueOf(e.getCause().getMessage()).contains("BUSYGROUP"))) {
                throw e;
            }
        }
    }


    private List<Map<String, Object>> toStreamMessages(List<MapRecord<String, Object, Object>> records, String consumer) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (records == null) {
            return result;
        }
        for (MapRecord<String, Object, Object> record : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", record.getId().getValue());
            row.put("order", record.getValue().get("order"));
            row.put("consumer", consumer);
            result.add(row);
        }
        return result;
    }


    // ---------------- 工具 ----------------


    // 清理所有 lab:* key；db=true 时顺便清空练习库的订单和浏览量。
    // 用 SCAN 而不是 KEYS：KEYS 会阻塞 Redis，ElastiCache Serverless 也禁用了它。
    // 逐个 DEL：一次 DEL 多个 key 时，key 不在同一个 slot 会报 CROSSSLOT
    @DeleteMapping("/reset")
    public Map<String, Object> reset(@RequestParam(defaultValue = "false") boolean db) {
        long deleted = 0;
        for (String key : scanKeys(PREFIX + "*")) {
            if (Boolean.TRUE.equals(redis.delete(key))) {
                deleted++;
            }
        }
        if (db) {
            this.db.resetOrdersAndViews();
        }
        return Map.of("deleted_keys", deleted, "db_reset", db);
    }


    private Map<Long, Integer> readCart(long customerId) {
        Map<Long, Integer> cart = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : redis.opsForHash().entries(cartKey(customerId)).entrySet()) {
            cart.put(Long.parseLong((String) entry.getKey()), Integer.parseInt((String) entry.getValue()));
        }
        return cart;
    }


    private String menuItemKey(long id) {
        return PREFIX + "menu_item:" + id;
    }


    private String viewsKey(long id) {
        return PREFIX + "views:" + id;
    }


    private String checkoutLockKey(long customerId) {
        return PREFIX + "lock:checkout:" + customerId;
    }


    private String cartKey(long customerId) {
        return PREFIX + "cart:" + customerId;
    }


    private String favKey(long customerId) {
        return PREFIX + "fav:{fav}:" + customerId;
    }


    // 集群模式下 SCAN 只能在单个节点上执行，所以逐个主节点扫描再合并（ElastiCache Serverless 对外只有一个虚拟节点）
    private List<String> scanKeys(String pattern) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
        return redis.execute((RedisCallback<List<String>>) connection -> {
            List<String> keys = new ArrayList<>();
            if (connection instanceof RedisClusterConnection cluster) {
                for (RedisClusterNode node : cluster.clusterGetNodes()) {
                    if (node.isMaster()) {
                        try (Cursor<byte[]> cursor = cluster.scan(node, options)) {
                            cursor.forEachRemaining(key -> keys.add(new String(key, StandardCharsets.UTF_8)));
                        }
                    }
                }
            } else {
                try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                    cursor.forEachRemaining(key -> keys.add(new String(key, StandardCharsets.UTF_8)));
                }
            }
            return keys;
        });
    }


    private List<String> toMenuItemNames(List<String> ids) {
        List<String> names = new ArrayList<>();
        for (String id : ids) {
            names.add(db.findMenuItem(Long.parseLong(id)).map(LabMenuItem::name).orElse("N/A"));
        }
        return names;
    }
}
