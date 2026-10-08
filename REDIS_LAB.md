# Redis 5 种基本数据类型 实验指南

本项目主业务的缓存仍然是 **Caffeine**（`@Cacheable("restaurants")`、`@Cacheable("cart")`）。
Redis 只在一个独立的练习通道里使用：

- 接口：`/lab/redis/**`（代码在 `src/main/java/com/laioffer/onlineorder/redislab/RedisLabController.java`）
- Key：统一以 `lab:` 开头
- 数据：只读 `menu_items` 表，把菜品 id 翻译成菜名和价格，不写数据库

---

## 0. 准备环境

### 0.1 启动 Postgres 和 Redis

```powershell
cd D:\githubProject\project1\OnlineOrder
docker compose up -d
docker compose ps          # 看到 db 和 redis 都是 Up
```

### 0.2 启动后端

在 IntelliJ 里运行 `OnlineOrderApplication`，或者：

```powershell
.\gradlew bootRun
```

日志里出现 `Started OnlineOrderApplication` 就说明启动好了。

### 0.3 打开两个观察窗口（这一步是实验的关键）

**窗口 A：实时监听 Redis 收到的每一条命令**

```powershell
docker compose exec redis redis-cli MONITOR
```

**窗口 B：交互式 redis-cli，用来查看数据**

```powershell
docker compose exec redis redis-cli
```

### 0.4 发请求的方式（任选一种）

- **推荐**：在 IntelliJ 里打开 `redis-lab.http`，点每个请求左边的 ▶ 运行
- 命令行：注意 PowerShell 里的 `curl` 其实是 `Invoke-WebRequest` 的别名，要写成 **`curl.exe`**：
  ```powershell
  curl.exe -X POST http://localhost:8080/lab/redis/string/menu-item/2/view
  ```

下文中的 `{{host}}` 指 `http://localhost:8080/lab/redis`。

### 0.5 实验中常用的菜品 id

| id | 菜名 | 价格 |
|---|---|---|
| 2 | Whopper Meal | 10.59 |
| 5 | Whopper | 6.39 |
| 11 | Original Soft Tofu | 17.06 |
| 20 | Ham & Cheese Soft Tofu | 17.06 |

---

## 实验 1：String（字符串）

> String 是最基础的类型，一个 key 对应一个值。值可以是文本、数字或序列化后的对象。
> 在它上面还能做**原子计数**（`INCR`）、**设置过期时间**（`EX`）和**只在不存在时写入**（`NX`）。

### 1.1 手写 Cache-Aside 缓存

```
GET {{host}}/string/menu-item/2/name
```

| 第几次 | 预期返回 | 窗口 A（MONITOR）里看到 |
|---|---|---|
| 第 1 次 | `"source":"db"` | `GET` 返回空 → 查数据库 → `SET lab:menu_item:2:name "Whopper Meal" EX 30` |
| 30 秒内第 2 次 | `"source":"redis"` | 只有一条 `GET` |
| 30 秒后 | 又变回 `"db"` | key 已经过期 |

在窗口 B 里观察 key 的倒计时：

```
TTL lab:menu_item:2:name      # 一直执行，看数字变小；过期后变成 -2
```

**思考**：这正是 `@Cacheable` 在幕后做的事。项目用 Caffeine 缓存在 JVM 内存里；用 Redis 的话，多个后端实例可以**共享**同一份缓存。两种方式各有什么优缺点？

### 1.2 原子计数器 INCR

```
POST {{host}}/string/menu-item/2/view     # 多调几次
```

预期 `views` 依次变成 1、2、3……

```
GET lab:menu_item:2:views
INCRBY lab:menu_item:2:views 100          # 手动加 100，再调一次接口看看
```

**思考**：如果写成 Java 里的"先 GET 再 +1 再 SET"，两个请求同时进来会出什么问题？为什么 `INCR` 不会？

### 1.3 用 SET NX EX 防止重复提交

```
POST {{host}}/string/checkout-lock/1      # 连续快速调两次
```

- 第 1 次：`"acquired":true`
- 10 秒内第 2 次：`"acquired":false`
- 10 秒后：又能拿到锁

MONITOR 里能看到 `SET lab:lock:checkout:1 1 EX 10 NX`。

**思考**：为什么一定要加过期时间 `EX`？如果拿到锁的服务在释放锁之前崩溃了会怎样？

---

## 实验 2：Hash（哈希）

> 一个 key 下面存多组 field → value，相当于一个小对象或小 Map。
> 可以**单独修改其中一个字段**，不需要把整个对象读出来再写回去。

### 2.1 Redis 版购物车

```
POST {{host}}/hash/cart/1/items/2      # 调 2 次
POST {{host}}/hash/cart/1/items/11     # 调 1 次
GET  {{host}}/hash/cart/1
```

预期：

```json
{"total_price":38.24,"items":[
  {"menu_item_id":2,"name":"Whopper Meal","price":10.59,"quantity":2},
  {"menu_item_id":11,"name":"Original Soft Tofu","price":17.06,"quantity":1}]}
```

在窗口 B 里看结构：

```
TYPE    lab:cart:1        # hash
HGETALL lab:cart:1        # 2 → 2, 11 → 1
HGET    lab:cart:1 2
HLEN    lab:cart:1
```

清空购物车：

```
DELETE {{host}}/hash/cart/1
```

### 2.2 和项目里现有的购物车对比

主项目的 `CartService.addMenuItemToCart` 每加一个菜，大约要执行：

1. 查 `carts` 表
2. 查 `menu_items` 表
3. 查 `order_items` 表
4. 写 `order_items` 表
5. 更新 `carts.total_price`

Redis 版只需要一条 `HINCRBY lab:cart:1 2 1`。
可以登录前端往真实购物车里加菜，在后端日志里数一数 SQL（`jdbc.core` 已经开了 DEBUG），和 MONITOR 里的命令对比一下。

**思考**：既然 Redis 这么快，为什么真实系统不把订单只存在 Redis 里？（提示：持久化、事务、对账、复杂查询。）

---

## 实验 3：List（列表）

> 有序、可以重复的双端链表。两端都能插入和弹出，还支持**阻塞弹出**。
> 典型用途：最近 N 条记录、简单的消息队列。

### 3.1 最近浏览（只保留最近 5 条）

```
POST {{host}}/list/recent/1/5
POST {{host}}/list/recent/1/7
POST {{host}}/list/recent/1/2
... 连续调用 6 次以上，id 随便换
```

返回值始终是**最新的在前面**，而且最多 5 个。MONITOR 里能看到每次都是 `LPUSH` 后面跟着 `LTRIM ... 0 4`。

```
LRANGE lab:recent:1 0 -1
LLEN   lab:recent:1
```

### 3.2 订单队列（生产者 / 消费者）

```
POST {{host}}/list/orders?order=order-1001
POST {{host}}/list/orders?order=order-1002
POST {{host}}/list/orders/take       # 先拿到 order-1001（先进先出）
POST {{host}}/list/orders/take       # 再拿到 order-1002
POST {{host}}/list/orders/take       # 队列空了：会阻塞 5 秒后返回 timeout
```

**体验"阻塞"**：先调用 `take`，趁它还在等的 5 秒内，到窗口 B 执行：

```
LPUSH lab:orders:queue order-9999
```

刚才等待的 `take` 请求会**立刻**返回 `order-9999`。这就是 `BRPOP` 和"每秒查一次有没有新数据"的区别。

**思考**：用 List 做队列时，如果消费者取出订单后还没处理完就崩溃了，这个订单会怎样？（感兴趣可以继续了解 Redis Stream。）

---

## 实验 4：Set（集合）

> 无序、元素不重复。支持**交集、并集、差集**运算。

### 4.1 收藏：天然去重

```
POST {{host}}/set/fav/1/2      # newly_added: true,  fav_count: 1
POST {{host}}/set/fav/1/2      # newly_added: false, fav_count: 1  ← 重复收藏被忽略
POST {{host}}/set/fav/1/11
POST {{host}}/set/fav/2/11
POST {{host}}/set/fav/2/5
```

### 4.2 共同收藏（交集）

```
GET {{host}}/set/fav/common?a=1&b=2      # ["Original Soft Tofu"]
```

在窗口 B 里试试其他集合运算：

```
SMEMBERS  lab:fav:1
SINTER    lab:fav:1 lab:fav:2      # 交集：两人都收藏了
SUNION    lab:fav:1 lab:fav:2      # 并集：至少一人收藏了
SDIFF     lab:fav:1 lab:fav:2      # 差集：用户 1 收藏了、用户 2 没收藏 → 可以推荐给用户 2
SISMEMBER lab:fav:1 2              # O(1) 判断"是否已收藏"，前端的 ❤ 图标就是靠这个点亮的
```

**思考**：同样的"共同收藏"功能，如果用 SQL 写应该怎么写？数据量很大时哪种更快？

---

## 实验 5：Sorted Set（有序集合，ZSet）

> 和 Set 一样元素不重复，但每个元素带一个分数（score），Redis **始终按分数排好序**。
> 典型用途：排行榜、按时间排序的延时队列。

### 5.1 热销榜

```
POST {{host}}/zset/sold/11?qty=3
POST {{host}}/zset/sold/2?qty=5
POST {{host}}/zset/sold/20?qty=1
GET  {{host}}/zset/top?n=5
```

预期：

```json
[{"rank":1,"name":"Whopper Meal","sold":5.0},
 {"rank":2,"name":"Original Soft Tofu","sold":3.0},
 {"rank":3,"name":"Ham & Cheese Soft Tofu","sold":1.0}]
```

然后再调一次 `POST {{host}}/zset/sold/20?qty=10`，重新看 `top`，id 20 会直接排到第一。

在窗口 B 里：

```
ZREVRANGE lab:hot:menu_items 0 -1 WITHSCORES   # 从高到低
ZSCORE    lab:hot:menu_items 2                 # 某个菜卖了多少
ZREVRANK  lab:hot:menu_items 2                 # 排第几（从 0 开始）
ZRANGEBYSCORE lab:hot:menu_items 3 +inf        # 销量 ≥ 3 的菜
```

**思考**：
- 用 SQL 实现是 `SELECT ... GROUP BY ... ORDER BY SUM(quantity) DESC LIMIT 5`，每次查询都要重新计算一遍；ZSet 在写入时就已经排好序了。
- 如果要做"**今日**热销榜"，key 应该怎么设计？（提示：`lab:hot:2026-09-24` 加上 `EXPIRE`。）

---

## 实验 6：确认主业务仍然使用 Caffeine

```
DELETE {{host}}/reset                                 # 先清空练习数据
GET    http://localhost:8080/restaurants/menu         # 主业务接口，带 @Cacheable
```

然后在窗口 B 里执行：

```
KEYS *
```

结果应该是空的，不会出现 `restaurants::SimpleKey []` 之类的 key。说明主业务的缓存确实还在 JVM 里的 Caffeine 中。

原因在 `application.yml` 的 `spring.cache.type: caffeine`。**可以试着把这一行注释掉，重启后再做一次这个实验**，你会看到 Spring 自动改用 Redis 做缓存，并且会因为 DTO 没有实现 `Serializable` 而报错。这个坑值得亲眼看一次，看完记得改回来。

---

## 清理

```
DELETE {{host}}/reset            # 删除所有 lab:* key
```

```powershell
docker compose stop              # 停止容器（数据保留）
docker compose down              # 删除容器（Postgres 数据保存在 volume 里，仍然保留）
```

> `reset` 接口内部用的是 `KEYS lab:*`。`KEYS` 会遍历整个库并阻塞 Redis，只适合练习环境；生产环境应该用 `SCAN`。

---

## 总结：什么时候用哪种类型

| 类型 | 一句话 | 本实验里的用法 | 其他常见用法 |
|---|---|---|---|
| String | 一个 key 对应一个值，还能计数、设置过期、做互斥 | 缓存、浏览量、结账防重锁 | 分布式锁、限流、验证码 |
| Hash | 一个 key 对应一个小对象 | 购物车 | 用户资料、Spring Session |
| List | 有序、可重复，两端操作，可阻塞 | 最近浏览、订单队列 | 时间线、简单的任务队列 |
| Set | 无序、不重复，支持集合运算 | 收藏、共同收藏 | 标签、抽奖、去重、好友关系 |
| Sorted Set | 带分数、自动排序 | 热销榜 | 排行榜、延时队列、按时间排序 |

## 进阶练习（自己动手改代码）

1. **把两个通道串起来**：在 `RedisLabController` 里加一个"lab 结账"接口：读出 `lab:cart:{id}` 这个 Hash，对每个菜 `ZINCRBY` 热销榜，`LPUSH` 订单队列，最后 `DEL` 购物车。
2. **限流**：给 `view` 接口加上"同一个用户每分钟最多 10 次"（`INCR` + 第一次时 `EXPIRE 60`）。
3. **原子性**：上面第 1 题中间某一步失败时会怎样？试试用 `MULTI/EXEC` 或 Lua 脚本把几步变成原子操作。
4. **今日热销榜**：key 按日期拆分，并设置 2 天过期。
