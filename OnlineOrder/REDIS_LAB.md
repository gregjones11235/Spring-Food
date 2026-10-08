# Redis 5 种基本数据类型 实验指南

## 这套实验和主项目是什么关系

|  | 主项目（外卖 App） | Redis 练习通道 |
|---|---|---|
| 接口 | `/restaurants/menu`、`/cart` …… | `/lab/redis/**` |
| 缓存 | **两级缓存**：Caffeine（JVM 内存）+ Redis，key 以 `cache:` 开头（见 `cache/` 包） | **Redis**，key 统一以 `lab:` 开头 |
| 数据库 | `onlineorder` 库 | **同一个库、同一套表**（实验里的菜品、订单就是主业务的菜品、订单） |
| 代码 | `controller/`、`service/` …… | `redislab/` 包 |

需要注意的几点：
- 所有表都在 `database-init.sql` 里，启动时执行的是**幂等**脚本：只建不存在的表，种子数据只在表为空时插入，**重启不会丢数据**。练习中下的订单、改过的价格都会保留下来。
- 实验和主业务**共用数据**：实验 6 改的菜品价格，前端和 `/restaurants/menu` 也会看到（主业务的两级缓存最多 10 分钟后才刷新，这本身就是缓存一致性问题）；Redis 购物车结账产生的订单，和造数脚本生成的 200 万订单在同一张 `orders` 表里。
- 两边共用同一个 Redis，但 key 前缀不同（`cache:` / `lab:`），`reset` 接口只删 `lab:` 开头的 key。

> **本地 Redis 是单节点集群模式**，和线上的 ElastiCache Serverless 保持一致。对实验的影响只有一点：**多 key 命令要求所有 key 在同一个 slot**，否则报 `CROSSSLOT` 错误。所以收藏的 key 带了 hash tag：`lab:fav:{fav}:1`，只有花括号里的 `fav` 参与 slot 计算，所有用户的收藏都落在同一个 slot，`SINTER` 才能执行。实验 4 里可以亲手试一下不带 hash tag 会怎样。

实验会用到这些表：

| 表 | 用途 |
|---|---|
| `restaurants`、`menu_items` | 种子数据里的 3 家餐厅、30 个菜品（id 1~3 / 1~30），以及造数脚本生成的餐厅和菜品 |
| `menu_item_views` | 浏览量（从 Redis 回写过来） |
| `orders`、`order_lines` | 正式订单（购物车结账后写进来，造数脚本也会写入 200 万单） |

---

## 0. 准备环境

### 0.1 启动 Postgres 和 Redis

```powershell
cd D:\githubProject\project1\OnlineOrder
docker compose up -d
docker compose ps          # db 和 redis 都显示 Up
```

### 0.2 初始化数据库

不需要单独初始化：后端启动时会自动建表、插入种子数据（见 0.3）。

如果还想要订单中心的大数据量（5000 家餐厅、200 万订单，SQL 调优实验要用），在后端至少启动过一次之后，在 Git Bash 里执行：

```bash
scripts/gen_data.sh
```

### 0.3 启动后端

在 IntelliJ 里运行 `OnlineOrderApplication`，或者：

```powershell
.\gradlew bootRun
```

日志里出现 `redis-lab - Start completed.` 和 `Started OnlineOrderApplication` 就说明启动好了。

### 0.4 打开 4 个窗口，分清每个窗口里输入什么

| 窗口 | 怎么打开 | 在里面输入什么 |
|---|---|---|
| **① 发请求** | 普通 PowerShell | `curl.exe ...`，调用练习接口 |
| **② 监听 Redis** | `docker compose exec redis redis-cli MONITOR` | 什么都不用输入，只看输出 |
| **③ Redis 命令行** | `docker compose exec redis redis-cli --raw` | Redis 命令，比如 `GET`、`HGETALL` |
| **④ 数据库命令行** | `docker compose exec db psql -U postgres -d onlineorder` | SQL，比如 `SELECT * FROM menu_items WHERE id = 2;`（输入 `\q` 退出） |

**窗口 ① 要先执行这两行**（每次新开窗口都要执行）：

```powershell
chcp 65001                                  # 让中文返回结果正常显示
$H = "http://localhost:8080/lab/redis"      # 下文所有请求都会用到这个变量
```

然后就可以这样发请求：

```powershell
curl.exe "$H/string/menu-item/2"                       # GET 请求
curl.exe -X POST "$H/string/menu-item/2/view"          # POST 请求
```

注意：
- 一定要写 `curl.exe`，不能写 `curl`。PowerShell 里的 `curl` 其实是 `Invoke-WebRequest` 的别名，用法完全不同。
- 地址要放在**双引号**里。一是地址里的 `&` 在 PowerShell 里有特殊含义；二是只有双引号里的 `$H` 才会被替换成真实地址。
- 如果你用的是 IntelliJ IDEA **Ultimate**，也可以打开 `redis-lab.http`，点每条请求左边的 ▶ 运行，内容和本文一一对应。

> **最容易犯的错：输错窗口。** 下文每个代码块前面都标了 **▶ 窗口 ①/③/④**，请照着标签输入。
>
> 比如把 `curl.exe "$H/string/menu-item/2"` 或者 `.http` 文件里的 `GET {{host}}/string/menu-item/2` 粘进了 redis-cli（窗口 ③），并不会报错，而是一直返回 `(nil)`。
> 这是因为 Redis 自己也有一个 `GET` 命令，它会把 `{{host}}/string/menu-item/2` 整个当成 key 去查，而 Redis 里并没有这个 key。
> HTTP 请求（`curl.exe`）只能在窗口 ① 里执行；redis-cli 里只能输入 `GET lab:menu_item:2` 这种 **`lab:` 开头的 key**。

### 0.5 实验中常用的菜品 id

| id | 菜名 | 价格 |
|---|---|---|
| 2 | Whopper Meal | 10.59 |
| 3 | Impossible™ Whopper | 7.99 |
| 5 | Whopper | 6.39 |
| 11 | Original Soft Tofu | 17.06 |
| 20 | Ham & Cheese Soft Tofu | 17.06 |

---

# 第一部分：5 种基本类型

## 实验 1：String（字符串）

> String 是最基础的类型，一个 key 对应一个值。值可以是文本、数字，也可以是序列化后的对象（比如 JSON）。
> 在它上面还能做**原子计数**（`INCR`）、**设置过期时间**（`EX`）和**只在不存在时写入**（`NX`）。

### 1.1 手写 Cache-Aside 缓存

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe "$H/string/menu-item/2"
```

| 第几次 | 预期返回 | 窗口 ② 里看到 |
|---|---|---|
| 第 1 次 | `"source":"db"` | `GET` 没命中 → 后端查数据库 → `SET lab:menu_item:2 "{...JSON...}" EX 60` |
| 60 秒内第 2 次 | `"source":"redis"` | 只有一条 `GET` |
| 60 秒后 | 又变回 `"db"` | key 已经过期 |

也可以看后端日志：只有 `source=db` 的那次才会打印 `SELECT ... FROM menu_items`。

**▶ 窗口 ③ redis-cli**（逐行输入，每行回车）：

```text
GET lab:menu_item:2
TTL lab:menu_item:2
```

- `GET lab:menu_item:2`：看到的是一段 JSON 字符串
- `TTL lab:menu_item:2`：多执行几次，看数字变小；过期后变成 -2

**思考**：这正是 `@Cacheable` 在幕后做的事。主项目用 Caffeine 把缓存放在 JVM 内存里；换成 Redis 的话，多个后端实例可以**共享**同一份缓存。两种方式各有什么优缺点？

### 1.2 原子计数器 INCR

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe -X POST "$H/string/menu-item/2/view"     # 多调几次
```

预期 `views_in_redis` 依次变成 1、2、3……

**▶ 窗口 ③ redis-cli**（逐行输入，每行回车）：

```text
GET lab:views:2
INCRBY lab:views:2 100
```

- `INCRBY lab:views:2 100`：手动加 100，再调一次接口看看

**思考**：如果写成 Java 里的"先 GET、再 +1、再 SET"，两个请求同时进来会出什么问题？为什么 `INCR` 不会？

（这些浏览量目前只在 Redis 里。实验 8 会把它们写回数据库。）

### 1.3 用 SET NX EX 防止重复提交

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe -X POST "$H/string/checkout-lock/1"      # 连续快速调两次
```

- 第 1 次：`"acquired":true`
- 10 秒内第 2 次：`"acquired":false`
- 10 秒后：又能拿到锁

窗口 ② 里能看到 `SET lab:lock:checkout:1 1 EX 10 NX`。

> 实验 9 的结账接口用的也是这个 key。做完这一步后要等 10 秒再去结账，否则会被拦截。

**思考**：为什么一定要加过期时间 `EX`？如果拿到锁的服务还没来得及释放就崩溃了，会发生什么？

---

## 实验 2：Hash（哈希）

> 一个 key 下面存多组 field → value，相当于一个小对象或者小 Map。
> 可以**单独修改其中一个字段**，不需要把整个对象读出来再写回去。

### 2.1 Redis 版购物车

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe -X POST "$H/hash/cart/1/items/2"      # 调 2 次
curl.exe -X POST "$H/hash/cart/1/items/11"     # 调 1 次
curl.exe "$H/hash/cart/1"
```

预期：

```json
{"total_price":38.24,"items":[
  {"menu_item_id":2,"name":"Whopper Meal","price":10.59,"quantity":2},
  {"menu_item_id":11,"name":"Original Soft Tofu","price":17.06,"quantity":1}]}
```

购物车里只存了"菜品 id → 数量"，**价格是查询时从数据库读的**。这样改价以后，购物车里显示的总是最新价格。

加一个不存在的菜（`items/9999`）会返回 `menu item not found`。

**▶ 窗口 ③ redis-cli**（逐行输入，每行回车）：

```text
TYPE lab:cart:1
HGETALL lab:cart:1
HGET lab:cart:1 2
HLEN lab:cart:1
```

- `TYPE lab:cart:1`：hash
- `HGETALL lab:cart:1`：2 → 2, 11 → 1

**先不要清空**，实验 9 结账时要用到这个购物车。

### 2.2 和主项目现有的购物车对比

主项目的 `CartService.addMenuItemToCart` 每加一个菜，大约要执行：
查 `carts` → 查 `menu_items` → 查 `order_items` → 写 `order_items` → 更新 `carts.total_price`。

Redis 版只需要一条 `HINCRBY lab:cart:1 2 1`。

**思考**：既然 Redis 这么快，为什么真实系统不把订单也只存在 Redis 里？（实验 9 会回答这个问题。）

---

## 实验 3：List（列表）

> 有序、允许重复的双端链表。两端都能插入和弹出，还支持**阻塞弹出**。
> 典型用途：最近 N 条记录、简单的消息队列。

### 3.1 最近浏览（只保留最近 5 条）

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe -X POST "$H/list/recent/1/5"
curl.exe -X POST "$H/list/recent/1/3"
curl.exe -X POST "$H/list/recent/1/2"
# ……换不同的 id，连续调用 6 次以上
```

返回值始终是**最新的排在最前面**，而且最多 5 个。窗口 ② 里能看到每次都是先 `LPUSH`，再 `LTRIM ... 0 4`。

**▶ 窗口 ③ redis-cli**（逐行输入，每行回车）：

```text
LRANGE lab:recent:1 0 -1
LLEN lab:recent:1
```

### 3.2 订单队列（生产者 / 消费者）

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe -X POST "$H/list/orders?order=order-1001"
curl.exe -X POST "$H/list/orders?order=order-1002"
curl.exe -X POST "$H/list/orders/take"       # 先拿到 order-1001（先进先出）
curl.exe -X POST "$H/list/orders/take"       # 再拿到 order-1002
curl.exe -X POST "$H/list/orders/take"       # 队列空了：BRPOP 等 5 秒后返回 queue empty
curl.exe -X POST "$H/list/orders/take?timeout=0"   # 非阻塞的 RPOP：队列空了立刻返回 queue empty
```

`take` 默认执行的是 `BRPOP lab:orders:queue 5`（最多等 5 秒）；加上 `?timeout=0` 就改成普通的 `RPOP`，不会等待。窗口 ② 里能看到两种命令的区别。

**体验"阻塞"**：在窗口 ① 调用 `take`，趁它还在等的 5 秒内，到窗口 ③ 执行：

**▶ 窗口 ③ redis-cli**（逐行输入，每行回车）：

```text
LPUSH lab:orders:queue order-9999
```

窗口 ① 里等待的请求会**立刻**返回 `order-9999`。这就是 `BRPOP` 和"每秒去查一次有没有新数据"的区别。

### 3.3 LPOP 和 RPOP：从哪一头取

**▶ 窗口 ③ redis-cli**（逐行输入，每行回车）：

```text
RPUSH lab:demo:list a b c
LRANGE lab:demo:list 0 -1
LPOP lab:demo:list
RPOP lab:demo:list
LRANGE lab:demo:list 0 -1
DEL lab:demo:list
```

- `RPUSH ... a b c` 之后，列表从左到右是 `a b c`
- `LPOP` 从左边取出 `a`，`RPOP` 从右边取出 `c`，最后只剩 `b`

所以同一个 List 放入和取出的方向怎么搭配，就决定了它的行为：

| 放入 | 取出 | 效果 |
|---|---|---|
| `LPUSH` | `RPOP` / `BRPOP` | **队列**（先进先出），本项目的订单队列就是这样 |
| `LPUSH` | `LPOP` / `BLPOP` | **栈**（后进先出） |

### 3.4 多个消费者抢同一个队列：每条消息只会被一个人拿到

这一步需要**再开一个 redis-cli 窗口**（和窗口 ③ 一样用 `docker compose exec redis redis-cli --raw` 打开），下面叫它窗口 ③'。

**▶ 窗口 ③ 和窗口 ③' 都输入**（模拟两个后厨同时在等单）：

```text
BRPOP lab:orders:queue 0
```

最后的 `0` 表示**一直等下去**，两个窗口都会卡住不动，这是正常的。

**▶ 窗口 ① PowerShell**（一条一条执行，每执行一条就看一眼两个 redis-cli 窗口）：

```powershell
curl.exe -X POST "$H/list/orders?order=order-A"
curl.exe -X POST "$H/list/orders?order=order-B"
```

- 第一条订单只会出现在**其中一个**窗口里，另一个窗口继续等待
- 第二条订单交给另一个还在等的窗口

一条消息不会被两个消费者同时拿到，因为 `BRPOP` 本身是原子操作：弹出和返回是一步完成的。这就是面试题"用哪个命令实现只消费一条"的答案之一。

> 窗口拿到消息后就会退出等待状态，可以继续输入其他命令。如果不想等了，按 `Ctrl+C` 会直接退出 redis-cli，重新执行 `docker compose exec redis redis-cli --raw` 进去就行。

**思考**：用 List 做队列时，如果消费者取出订单后还没处理完就崩溃了，这个订单会怎样？（答案在实验 10。）

---

## 实验 4：Set（集合）

> 无序、元素不重复。支持**交集、并集、差集**运算。

### 4.1 收藏：天然去重

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe -X POST "$H/set/fav/1/2"      # newly_added: true,  fav_count: 1
curl.exe -X POST "$H/set/fav/1/2"      # newly_added: false, fav_count: 1  ← 重复收藏被忽略
curl.exe -X POST "$H/set/fav/1/11"
curl.exe -X POST "$H/set/fav/2/11"
curl.exe -X POST "$H/set/fav/2/5"
```

### 4.2 共同收藏（交集）

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe "$H/set/fav/common?a=1&b=2"      # ["Original Soft Tofu"]
```

试试其他集合运算：

**▶ 窗口 ③ redis-cli**（逐行输入，每行回车）：

```text
SMEMBERS lab:fav:{fav}:1
SINTER lab:fav:{fav}:1 lab:fav:{fav}:2
SUNION lab:fav:{fav}:1 lab:fav:{fav}:2
SDIFF lab:fav:{fav}:1 lab:fav:{fav}:2
SISMEMBER lab:fav:{fav}:1 2
```

- `SINTER lab:fav:{fav}:1 lab:fav:{fav}:2`：交集：两个人都收藏了的
- `SUNION lab:fav:{fav}:1 lab:fav:{fav}:2`：并集：至少一个人收藏了的
- `SDIFF lab:fav:{fav}:1 lab:fav:{fav}:2`：差集：用户 1 收藏了、用户 2 没收藏 → 可以推荐给用户 2
- `SISMEMBER lab:fav:{fav}:1 2`：O(1) 判断"是否已收藏"，前端的 ❤ 图标就是靠这个点亮的

再试一下**不带 hash tag** 的 key：

```text
SADD lab:fav:x 1
SINTER lab:fav:x lab:fav:{fav}:1
DEL lab:fav:x
```

会报 `CROSSSLOT Keys in request don't hash to the same slot`：两个 key 被分到了不同的 slot，集群模式下不允许在一条命令里同时操作。代价是：hash tag 让所有用户的收藏都挤在同一个 slot，也就是同一个分片上，数据量很大时会形成热点。真实系统里常见的做法是只给需要一起运算的 key 用相同的 tag，或者把交集放到应用层来算。

**思考**：同样的"共同收藏"，用 SQL 应该怎么写？数据量很大时哪种更快？

---

## 实验 5：Sorted Set（有序集合，ZSet）

> 和 Set 一样元素不重复，但每个元素都带一个分数（score），Redis **始终按分数排好序**。
> 典型用途：排行榜、按时间排序的延时队列。

### 5.1 热销榜

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe -X POST "$H/zset/sold/11?qty=3"
curl.exe -X POST "$H/zset/sold/2?qty=5"
curl.exe -X POST "$H/zset/sold/20?qty=1"
curl.exe "$H/zset/top?n=5"
```

预期：

```json
[{"rank":1,"name":"Whopper Meal","sold":5.0},
 {"rank":2,"name":"Original Soft Tofu","sold":3.0},
 {"rank":3,"name":"Ham & Cheese Soft Tofu","sold":1.0}]
```

再执行一次 `curl.exe -X POST "$H/zset/sold/20?qty=10"`，然后重新看 `top`：id 20 会直接排到第一。

**▶ 窗口 ③ redis-cli**（逐行输入，每行回车）：

```text
ZREVRANGE lab:hot:menu_items 0 -1 WITHSCORES
ZSCORE lab:hot:menu_items 2
ZREVRANK lab:hot:menu_items 2
ZRANGEBYSCORE lab:hot:menu_items 3 +inf
```

- `ZREVRANGE lab:hot:menu_items 0 -1 WITHSCORES`：从高到低
- `ZSCORE lab:hot:menu_items 2`：某个菜卖了多少
- `ZREVRANK lab:hot:menu_items 2`：排第几（从 0 开始）
- `ZRANGEBYSCORE lab:hot:menu_items 3 +inf`：销量 ≥ 3 的菜

**思考**：
- 用 SQL 实现是 `SELECT ... GROUP BY ... ORDER BY SUM(quantity) DESC LIMIT 5`，每次查询都要重新算一遍；ZSet 在写入时就已经排好序了。
- 如果要做"**今日**热销榜"，key 应该怎么设计？（提示：`lab:hot:2026-09-24`，再加 `EXPIRE`。）

### 5.2 ZADD 和 ZINCRBY：往 ZSet 里写数据的两种方式

5.1 的接口在窗口 ② 里显示的是 `ZINCRBY lab:hot:menu_items 3.0 11`，而不是 `ZADD`。这两条命令都能往 ZSet 里写数据，区别在于：

| 命令 | 成员已经存在时 | 成员不存在时 | 适合的场景 |
|---|---|---|---|
| `ZADD key 分数 成员` | 用新分数**覆盖**旧分数 | 新增，分数就是给定的值 | 分数是一个"绝对值"：比如按评分排序、按时间戳排序的延时队列 |
| `ZINCRBY key 增量 成员` | 在旧分数上**累加** | 新增，分数就是这个增量（相当于从 0 开始加） | 分数是"累计值"：比如销量、点赞数 |

热销榜的销量需要一直累加，所以用 `ZINCRBY`。如果用 `ZADD`，每卖一次就会把之前的销量覆盖掉。

**▶ 窗口 ③ redis-cli**（逐行输入，每行回车）：

```text
ZADD lab:demo:z 5 dish-2
ZADD lab:demo:z 3 dish-2
ZSCORE lab:demo:z dish-2
ZINCRBY lab:demo:z 3 dish-2
ZADD lab:demo:z INCR 3 dish-2
ZINCRBY lab:demo:z 4 dish-9
ZREVRANGE lab:demo:z 0 -1 WITHSCORES
```

- 第 1 条 `ZADD` 返回 `1`，表示**新增**了 1 个成员
- 第 2 条 `ZADD` 返回 `0`，因为 dish-2 已经存在，这次只是把分数改掉，没有新增成员；`ZSCORE` 返回 `3`，说明 5 被**覆盖**了
- `ZINCRBY` 返回 `6`，在 3 的基础上**累加**了 3
- `ZADD ... INCR` 返回 `9`，效果和 `ZINCRBY` 完全一样。在 Redis 源码里，`ZINCRBY` 就是调用 `ZADD` 的同一套实现，只是打开了 INCR 选项
- `ZINCRBY lab:demo:z 4 dish-9`：dish-9 原本不存在，直接新增，分数是 4

`ZADD` 还有几个选项，可以控制"什么情况下才写入"：

```text
ZADD lab:demo:z NX 100 dish-2
ZADD lab:demo:z XX 1 dish-new
ZADD lab:demo:z GT 1 dish-2
ZADD lab:demo:z GT 50 dish-2
ZADD lab:demo:z 8 dish-11 1 dish-20
ZREVRANGE lab:demo:z 0 -1 WITHSCORES
DEL lab:demo:z
```

- `NX`：成员**不存在**才添加。dish-2 已经存在，所以分数保持 9 不变
- `XX`：成员**已存在**才更新。dish-new 不存在，所以不会被添加
- `GT`：新分数**比旧分数大**才更新。1 比 9 小，不更新；50 比 9 大，更新成 50。适合"只保留最高分"，比如游戏的历史最高分榜（`LT` 正好相反）
- 一条 `ZADD` 可以一次写入**多个**"分数 成员"对

**思考**："用户最近一次登录时间"排行应该用 `ZADD` 还是 `ZINCRBY`？"用户累计登录天数"呢？

---

# 第二部分：Redis 和数据库配合

真实项目里，**数据库保存正式数据，Redis 负责加速和处理临时数据**。下面 4 个实验都会写数据库，建议打开窗口 ④ 对照着看。

## 实验 6：缓存一致性（改了数据库，缓存怎么办？）

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe "$H/string/menu-item/2"                                  # ① 放进缓存，price 10.59
curl.exe -X PUT "$H/db/menu-item/2/price?price=99.99&evict=false" # ② 只改数据库，不动缓存
curl.exe "$H/string/menu-item/2"                                  # ③ 还是 10.59！source=redis
```

确认数据库确实已经改了：

**▶ 窗口 ④ psql**：

```sql
SELECT id, name, price FROM menu_items WHERE id = 2;     -- 99.99
```

这时**缓存和数据库不一致**，最长会持续 60 秒（等到 TTL 过期）。

现在改用"更新数据库之后删除缓存"的方式，顺便把价格改回去：

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe -X PUT "$H/db/menu-item/2/price?price=10.59&evict=true"  # cache_evicted: true
curl.exe "$H/string/menu-item/2"                                  # source=db，拿到最新价格
```

窗口 ② 里能看到 `DEL lab:menu_item:2`。

> 主项目里 `@CacheEvict(cacheNames = "cart", key = "#customerId")` 做的是同一件事，只不过是由注解自动完成的。

**思考**：
- 为什么通常是"**先更新数据库，再删除缓存**"，而不是"先删缓存、再更新数据库"？（提示：删完缓存、还没改完数据库的这段时间里，如果另一个请求把旧值又读回缓存，会怎样？）
- 为什么是"删除缓存"而不是"更新缓存"？
- 就算一直用 `evict=false`，TTL 也能保证数据**最终**会一致。这就是缓存一定要设过期时间的原因之一。

> **做完一定要把价格改回 10.59**，否则后面算出的总价会和本文不一样。

## 实验 7：缓存穿透（查一个根本不存在的东西）

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe "$H/string/menu-item/9999?cacheNull=false"     # 多调几次
```

每次都是 `"source":"db"`，后端日志里每次都有一条 SQL。如果有人拿大量不存在的 id 来刷接口，所有请求都会**穿过缓存直接打到数据库**上。

改成把"不存在"这个结果也缓存起来（默认就是这样）：

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe "$H/string/menu-item/9999"      # 第 1 次：source=db，同时缓存一个空值标记，只保留 10 秒
curl.exe "$H/string/menu-item/9999"      # 第 2 次：source=redis (空值标记)，不再查数据库
```

**▶ 窗口 ③ redis-cli**（逐行输入，每行回车）：

```text
GET lab:menu_item:9999
TTL lab:menu_item:9999
```

- `GET lab:menu_item:9999`：__NULL__

**思考**：空值标记的过期时间为什么要设得比正常缓存短？如果后来真的新增了 id 为 9999 的菜，会发生什么？（进阶：布隆过滤器。）

## 实验 8：计数回写（让 Redis 替数据库扛住写请求）

每次有人浏览菜品就 `UPDATE` 一次数据库，并发一高数据库就扛不住。常见做法是：**先在 Redis 里 `INCR`，再定时批量写回数据库**。

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe -X POST "$H/string/menu-item/2/view"     # 调 3 次
curl.exe -X POST "$H/string/menu-item/11/view"    # 调 1 次
curl.exe "$H/db/views"                            # 数据库里还没有数据：[]
curl.exe -X POST "$H/string/views/flush"          # 回写
```

预期返回：

```json
{"flushed":{"2":3,"11":1},
 "db_views":[{"menu_item_id":2,"name":"Whopper Meal","views":3},
             {"menu_item_id":11,"name":"Original Soft Tofu","views":1}]}
```

再浏览一次 id 2，然后再回写：数据库里 id 2 的浏览量变成 **4**（是累加，不是覆盖）。

窗口 ② 里能看到回写的过程：
- `SCAN 0 MATCH lab:views:* COUNT 100`：逐批找出所有浏览量 key（不用 `KEYS`，不会阻塞 Redis）
- `GETDEL lab:views:2`：**原子地**读出并删除。读和删之间不会插进别人的 `INCR`，所以一次浏览都不会丢

**▶ 窗口 ④ psql**：

```sql
SELECT * FROM menu_item_views;
```

**思考**：
- 如果用 `GET` 再 `DEL` 两条命令代替 `GETDEL`，可能会丢掉哪些浏览量？
- 如果 `GETDEL` 执行成功了，但紧接着写数据库失败了，会怎样？这种"丢一点"对浏览量来说能接受吗？对订单金额呢？
- 真实项目里，`flush` 通常由 `@Scheduled` 定时任务每隔几十秒自动调用一次。

## 实验 9：结账落库（把几种类型串起来）

购物车是**临时数据**，放在 Redis 里；订单是**正式数据**，必须写进数据库。结账时两者会交接：

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe "$H/hash/cart/1"                        # 确认购物车里有东西（实验 2 加的）
curl.exe -X POST "$H/hash/cart/1/checkout"       # {"ok":true,"order_id":1}
curl.exe -X POST "$H/hash/cart/1/checkout"       # 马上再点一次：被锁拦截
```

窗口 ② 里能按顺序看到结账用到的每一种类型：

| 步骤 | 命令 | 类型 |
|---|---|---|
| 1. 防重复提交 | `SET lab:lock:checkout:1 1 EX 10 NX` | String |
| 2. 读购物车 | `HGETALL lab:cart:1` | Hash |
| 3. 写订单 | （这一步是数据库事务，窗口 ② 里看不到，后端日志里能看到 `INSERT INTO orders`） | — |
| 4. 更新热销榜 | `ZINCRBY lab:hot:menu_items 2.0 2` ……（每个菜一条，`2.0` 是数量） | Sorted Set |
| 5. 通知后厨 | `LPUSH lab:orders:queue order-1` | List |
| 6. 清空购物车 | `DEL lab:cart:1` | — |

然后检查结果：

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe "$H/db/orders/1"                # 数据库里的订单明细
curl.exe "$H/hash/cart/1"                # 购物车已清空
curl.exe "$H/zset/top"                   # 热销榜已更新
curl.exe -X POST "$H/list/orders/take"   # 后厨取到 order-1
```

**▶ 窗口 ④ psql**：

```sql
-- orders 里可能有造数脚本生成的 200 万单，只看用户 1 最近的几单
SELECT * FROM orders WHERE customer_id = 1 ORDER BY id DESC LIMIT 5;
SELECT * FROM order_lines WHERE order_id = (SELECT max(id) FROM orders WHERE customer_id = 1);
```

**重启后端之后**再查 `/db/orders/1`，订单还在，因为建表脚本不会删数据。

**思考**：
- 为什么要**先**写数据库（第 3 步），再更新 Redis（第 4–6 步）？如果顺序反过来，数据库写入失败时会留下什么问题？
- 第 3 步之后、第 6 步之前如果后端崩溃了，用户下次结账会不会重复下单？该怎么防？（提示：进阶练习第 2 题）
- `order_lines` 里为什么要单独记一份 `price`，而不是需要时再去查 `menu_items`？（提示：结合实验 6 想想。）

---

# 第三部分：进阶类型 Stream（可靠的消息队列）

Stream 不属于"5 种基本类型"，是 Redis 5.0 新增的类型，但它正好弥补了 List 做队列的缺陷，也就是实验 3 最后那道思考题：

| | List（`LPUSH` + `BRPOP`） | Stream（`XADD` + `XREADGROUP`） |
|---|---|---|
| 消息被取走后 | **从队列里删除**。消费者如果还没处理完就崩溃，这条消息就**丢了** | **还留在 Stream 里**，同时记在这个消费者的"待确认列表"（pending）里 |
| 确认处理完成 | 没有这个机制 | 处理完后调用 `XACK`，才会从 pending 列表里移除 |
| 消费者崩溃了 | 没办法补救 | 其他消费者可以用 `XCLAIM` **接管**崩溃消费者手里没确认的消息 |
| 多个消费者 | 只能竞争同一个队列 | 同一个**消费者组**内部竞争（分摊工作）；**不同消费者组**各自都能收到全部消息（广播） |
| 查看历史消息 | 取走就没了 | 可以随时用 `XRANGE` 查看 |

本实验用到的名字：
- Stream 的 key：`lab:stream:orders`
- 消费者组：`kitchen`（后厨）。第一次发消息时会自动创建，相当于执行了 `XGROUP CREATE lab:stream:orders kitchen 0`，最后的 `0` 表示从第一条消息开始消费
- 消费者：`chef-a`、`chef-b`（两个厨师），名字随便起，第一次读消息时会自动注册

## 实验 10：Stream 消费者组

### 10.1 发消息：XADD

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe -X POST "$H/stream/orders?order=order-1"
curl.exe -X POST "$H/stream/orders?order=order-2"
curl.exe -X POST "$H/stream/orders?order=order-3"
```

每条返回一个 `id`，比如 `"1790310646937-0"`。格式是"**毫秒时间戳-序号**"，由 Redis 自动生成，并且保证递增。

**▶ 窗口 ③ redis-cli**（逐行输入，每行回车）：

```text
XLEN lab:stream:orders
XRANGE lab:stream:orders - +
```

- `XLEN`：Stream 里有几条消息
- `XRANGE ... - +`：从最早（`-`）到最新（`+`）列出所有消息

### 10.2 两个厨师分摊订单：XREADGROUP

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe -X POST "$H/stream/orders/read?consumer=chef-a"
curl.exe -X POST "$H/stream/orders/read?consumer=chef-b"
```

chef-a 拿到 `order-1`，chef-b 拿到 `order-2`。**同一个组里，每条消息只会分给一个消费者。**

窗口 ② 里能看到实际发出的命令：

```text
XREADGROUP GROUP kitchen chef-a BLOCK 2000 COUNT 1 STREAMS lab:stream:orders >
```

- `BLOCK 2000`：没有新消息时最多等 2 秒（和 `BRPOP` 的阻塞是一个意思）
- `COUNT 1`：一次最多取 1 条
- `>`：只要**还没分给本组任何人**的新消息

现在再到窗口 ③ 里看一眼，**消息并没有被删除**，还是 3 条：

**▶ 窗口 ③ redis-cli**：

```text
XLEN lab:stream:orders
```

### 10.3 确认处理完成：XACK 和 pending 列表

先看看哪些消息"已经分出去了、但还没确认"：

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe "$H/stream/orders/pending"
```

预期（id 以你自己的为准）：

```json
{"total":2,
 "per_consumer":{"chef-a":1,"chef-b":1},
 "messages":[
   {"id":"1790310646937-0","consumer":"chef-a","idle_seconds":5,"delivery_count":1},
   {"id":"1790310646984-0","consumer":"chef-b","idle_seconds":5,"delivery_count":1}]}
```

- `idle_seconds`：分出去以后过了多久还没确认
- `delivery_count`：这条消息一共被投递过几次

假设 chef-b 做完了 order-2，确认一下。**把下面的 id 换成上一步里 chef-b 那条消息的 id**：

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe -X POST "$H/stream/orders/ack/1790310646984-0"     # acked: true
curl.exe -X POST "$H/stream/orders/ack/1790310646984-0"     # 再确认一次：acked: false，已经确认过了
curl.exe "$H/stream/orders/pending"                         # 只剩 chef-a 的 1 条
```

窗口 ② 里能看到 `XACK lab:stream:orders kitchen 1790310646984-0`。

### 10.4 模拟崩溃：chef-a 拿了订单却一直没确认

假设 chef-a 拿到 order-1 之后**崩溃了**，永远不会再发 `XACK`。如果用的是 List，这个订单已经丢了；用 Stream 的话，它还在 pending 列表里，可以交给别人接手。

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe -X POST "$H/stream/orders/claim?consumer=chef-b&minIdleSeconds=10"
```

- 如果距离 chef-a 拿到消息**还不到 10 秒**，返回 `[]`。接口只会接管闲置了 10 秒以上的消息，因为 chef-a 可能只是处理得慢，并没有崩溃
- **等 10 秒以上再执行一次**：返回 `order-1`，现在它归 chef-b 了

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe "$H/stream/orders/pending"
```

order-1 现在显示 `"consumer":"chef-b"`，`delivery_count` 变成了 **2**（被投递过两次）。chef-b 处理完后，用它的 id 执行 `ack`，pending 列表就清空了。

窗口 ② 里能看到接管用的是 `XCLAIM lab:stream:orders kitchen chef-b 10000 1790310646937-0`（`10000` 是最短闲置毫秒数）。

**思考**：
- 如果某条消息每次处理都会出错，它就会被一直接管、一直失败。`delivery_count` 能帮你发现这种情况吗？（提示：超过 N 次就转到"死信队列"，交给人工处理。）
- 接管以后，order-1 可能已经被 chef-a 做了一半，chef-b 又从头做一遍。所以消费逻辑必须**幂等**，也就是重复处理也不会出错。（提示：回想实验 1.3 的 `SET NX`，或者数据库唯一索引。）

### 10.5 消费者重启后，先处理自己的历史消息

如果 chef-a 不是彻底挂了，而是**重启**了，它可以先把自己名下还没确认的消息重新拿回来处理。先让 chef-a 拿一条新消息，并且不确认：

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe -X POST "$H/stream/orders/read?consumer=chef-a"     # 拿到 order-3
```

**▶ 窗口 ③ redis-cli**（逐行输入，每行回车）：

```text
XREADGROUP GROUP kitchen chef-a STREAMS lab:stream:orders 0
XREADGROUP GROUP kitchen chef-a STREAMS lab:stream:orders >
```

- 最后一个参数写 `0`：读 **chef-a 自己名下还没确认**的消息，返回 order-3
- 写 `>`：读**新消息**。现在没有新消息，返回空

所以消费者的标准启动流程是：先用 `0` 把自己没处理完的消息做完，再用 `>` 读新消息。

处理完后确认 order-3（换成你自己的 id）：

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe -X POST "$H/stream/orders/ack/1790310647031-0"
```

### 10.6 多个消费者组 = 广播

假设除了后厨，还有一个"统计"服务也想收到**所有**订单。给它单独建一个消费者组：

**▶ 窗口 ③ redis-cli**（逐行输入，每行回车）：

```text
XGROUP CREATE lab:stream:orders stats 0
XREADGROUP GROUP stats s1 STREAMS lab:stream:orders >
XINFO GROUPS lab:stream:orders
```

- `stats` 组能从头读到**全部 3 条**订单，虽然这些订单在 `kitchen` 组里早就处理完了
- `XINFO GROUPS` 会列出两个组各自的消费进度（`last-delivered-id`）和待确认的消息数（`pending`）

总结一下：**组内竞争**，一条消息只分给组里的一个消费者；**组间广播**，每个组都能收到全部消息。

### 10.7 用 Stream 回答那道面试题

> 问：怎么保证一条消息只被消费一次，并且不会因为消费者崩溃而丢失？
>
> 答：用 Stream 的消费者组。`XREADGROUP` 保证同一个组里一条消息只分给一个消费者；消息被分出去后会进入 pending 列表，处理完调用 `XACK` 才会移除；消费者崩溃时，可以用 `XPENDING` 找出闲置太久的消息，再用 `XCLAIM`（或者 Redis 6.2 新增的 `XAUTOCLAIM`）转给其他消费者。由于消息可能被重复投递，消费逻辑本身还要做到幂等。

---

## 清理

**▶ 窗口 ① PowerShell**：

```powershell
curl.exe -X DELETE "$H/reset"             # 只删除 Redis 里的 lab:* key（包括 Stream 和它的消费者组）
curl.exe -X DELETE "$H/reset?db=true"     # 同时清空所有订单和浏览量（菜品价格不会恢复）
                                          # 注意：会连造数脚本生成的 200 万订单一起清掉，之后要重新执行 scripts/gen_data.sh --reset
```

**▶ 窗口 ① PowerShell**：

```powershell
docker compose stop              # 停止容器，数据保留
docker compose down              # 删除容器，数据卷还在，两个库的数据都保留
docker compose down -v           # 连数据卷一起删除。之后要重新执行 0.2 的初始化脚本
```

> `reset` 接口内部用 `SCAN` 找出 `lab:*` 的 key 再逐个 `DEL`，没有用 `KEYS`：`KEYS` 会遍历整个库并阻塞 Redis，ElastiCache Serverless 直接禁用了它。逐个删除则是因为一次 `DEL` 多个 key 时，key 不在同一个 slot 会报 `CROSSSLOT`。

---

## 常见问题

| 现象 | 原因 | 解决办法 |
|---|---|---|
| redis-cli 里一直返回 `(nil)` | 把 HTTP 请求输进了 redis-cli；或者 key 已经过期（缓存只保留 60 秒） | HTTP 请求去窗口 ① 用 `curl.exe` 执行；redis-cli 里只查 `lab:` 开头的 key。可以先执行 `SCAN 0 MATCH lab:* COUNT 100`，看看现在有哪些 key |
| redis-cli 里报 `ERR wrong number of arguments` | 把注释（`#` 后面的内容）也一起粘进去了，redis-cli 不支持注释 | 只输入命令本身 |
| redis-cli 里报 `ERR unknown command 'curl.exe'` | 同样是输错了窗口 | 去窗口 ① 执行 |
| PowerShell 里报 `Invoke-WebRequest ...` 相关错误 | 写成了 `curl`，少了 `.exe` | 改成 `curl.exe` |
| `curl.exe` 报 `Failed to connect to localhost port 8080` | 后端没启动，或者还没启动完成 | 等日志里出现 `Started OnlineOrderApplication` |
| 中文显示成乱码 | 窗口 ① 没有执行 `chcp 65001` | 执行一次 `chcp 65001` |
| 结账返回"被锁拦截" | 10 秒内结过账，或者刚做过实验 1.3 | 等 10 秒再试 |
| redis-cli 里执行 `BRPOP ... 0` 后一直卡住 | `0` 表示一直等到有消息为止，这是正常的 | 去窗口 ① 发一条订单；或者按 `Ctrl+C` 退出，再重新进入 redis-cli |
| `stream/orders/read` 返回 `[]` | 没有**新**消息了。`>` 只会读还没分给本组任何人的消息，分给过别人的不会再分给你 | 先用 `stream/orders?order=...` 发新消息 |
| `stream/orders/claim` 返回 `[]` | pending 里的消息闲置还不到 `minIdleSeconds` 秒，或者 pending 列表已经空了 | 用 `stream/orders/pending` 看看 `idle_seconds`，等够时间再试 |
| `stream/orders/ack/...` 返回 `"acked":false` | id 抄错了，或者这条消息已经确认过了 | 用 `stream/orders/pending` 查看正确的 id |
| redis-cli 里 `XGROUP CREATE` 报 `BUSYGROUP` | 这个消费者组已经存在了 | 不用管，直接用就行 |
| 后端启动时日志里有大量 `Executing prepared SQL` | 正常现象。启动时会先执行建表脚本，然后 `DevRunner` 会自动注册一个测试用户 `foo@mail.com` | 只要能看到 `Started OnlineOrderApplication` 就说明已经启动好了 |

---

## 总结：什么时候用哪种类型

| 类型 | 一句话 | 本实验里的用法 | 其他常见用法 |
|---|---|---|---|
| String | 一个 key 对应一个值，还能计数、设置过期、做互斥 | 菜品缓存、空值标记、浏览量、结账锁 | 分布式锁、限流、验证码 |
| Hash | 一个 key 对应一个小对象 | 购物车 | 用户资料、Spring Session |
| List | 有序、可重复，两端操作，可以阻塞 | 最近浏览、订单队列 | 时间线、简单的任务队列 |
| Set | 无序、不重复，支持集合运算 | 收藏、共同收藏 | 标签、抽奖、去重、好友关系 |
| Sorted Set | 带分数、自动排序；`ZADD` 覆盖分数，`ZINCRBY` 累加分数 | 热销榜 | 排行榜、延时队列、按时间排序 |
| Stream（进阶） | 只追加的消息日志，支持消费者组和确认机制 | 可靠的订单队列 | 消息队列、事件流、操作日志 |

以及 Redis 和数据库的分工：

| 数据 | 放在哪里 | 原因 |
|---|---|---|
| 菜品信息 | 数据库为准，Redis 缓存 | 读多写少，缓存可以随时重建 |
| 浏览量 | Redis 累积，定时回写数据库 | 写得非常频繁，丢一点也能接受 |
| 购物车 | 只放 Redis | 临时数据，丢了影响不大 |
| 订单 | 只放数据库 | 正式数据，需要事务和持久化 |

## 进阶练习（自己动手改代码）

代码都在 `src/main/java/com/laioffer/onlineorder/redislab/` 下。

1. **定时回写**：用 `@Scheduled` 每 30 秒自动调用一次实验 8 的 flush 逻辑（需要在启动类上加 `@EnableScheduling`）。
2. **结账的原子性**：实验 9 的第 4–6 步是 3 条独立的命令，中途失败会留下"订单已落库但购物车没清空"的状态。试着用 `MULTI/EXEC` 或者 Lua 脚本，把它们变成一个原子操作。
3. **限流**：给 `view` 接口加上"同一个用户每分钟最多 10 次"（`INCR`，第一次时再加 `EXPIRE 60`）。
4. **今日热销榜**：key 按日期拆分，并设置 2 天后过期。
5. **后台消费者**：给 `orders` 表加一列 `status`。实验 9 结账时改用 `XADD` 把订单写进 Stream；再写一个后台消费者（可以用 Spring 的 `StreamMessageListenerContainer`），读到订单后把状态更新为"已接单"并 `XACK`。
6. **死信队列**：定时检查 pending 列表，把 `delivery_count` 超过 3 次的消息转存到 `lab:stream:orders:dead`，再 `XACK` 掉原来那条。
