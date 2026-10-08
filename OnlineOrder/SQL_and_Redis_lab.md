# OnlineOrder：SQL 调优与 Redis 实践（按面试题组织）

## 这份文档怎么用

每个实验都挂在一个**业务功能**上，顺序是：先做功能 → 数据量或并发上来后出现问题 → 定位 → 优化 → 用数字验证。
面试时讲的是"做某个功能时遇到了什么问题、怎么解决的"，而不是"我做了一个 Redis 实验"。

目录按**美团真实面经里的提问**组织。每一节固定分成这几块：

| 小标题 | 内容 |
|---|---|
| 面试原问 | 面经里出现过的问法 |
| 业务场景 | 故事的起点：做哪个功能时碰到的 |
| 实操步骤 | 要真正动手做的事 |
| 新增 / 复用 | 要新写的代码，以及能直接拿来用的现有代码 |
| 留存证据 | 要保存下来的 EXPLAIN、耗时、截图，面试时能说出具体数字 |
| 理论对照 | PostgreSQL 和 MySQL 在这个点上的异同，理论部分直接背 |

> **讲述边界**：数据量要照实说，比如"压测环境造了 200 万订单"，不要说"线上用户"。面试官一定会追问数据从哪来，照实说反而能顺势讲造数脚本（第五章）。

---

## 目录

- [项目主线：从 Demo 到订单中心](#项目主线从-demo-到订单中心)
- [第一章 SQL 与索引](#第一章-sql-与索引)
  - [Q1 你项目里的慢 SQL 是怎么发现和定位的？](#q1-你项目里的慢-sql-是怎么发现和定位的)
  - [Q2 索引原理？联合索引、最左前缀、覆盖索引、回表？](#q2-索引原理联合索引最左前缀覆盖索引回表)
  - [Q3 索引失效有哪些情况？你遇到过吗？](#q3-索引失效有哪些情况你遇到过吗)
  - [Q4 深分页怎么优化？](#q4-深分页怎么优化)
  - [Q5 ORM 的 N+1 问题 / 子查询和 JOIN 怎么优化？](#q5-orm-的-n1-问题--子查询和-join-怎么优化)
  - [Q6 手写 SQL：每月消费 Top3 用户、累计金额](#q6-手写-sql每月消费-top3-用户累计金额)
  - [Q7 大批量写入怎么做？](#q7-大批量写入怎么做)
- [第二章 事务与锁](#第二章-事务与锁)
  - [Q8 悲观锁和乐观锁？项目里怎么用的？](#q8-悲观锁和乐观锁项目里怎么用的)
  - [Q9 隔离级别怎么实现的？MVCC？](#q9-隔离级别怎么实现的mvcc)
  - [Q10 遇到过死锁吗？怎么排查？](#q10-遇到过死锁吗怎么排查)
  - [Q11 用户下单、商家接单、骑手派单大致是个什么过程？](#q11-用户下单商家接单骑手派单大致是个什么过程)
  - [Q12 连接池被打满 / 长事务怎么排查？](#q12-连接池被打满--长事务怎么排查)
- [第三章 Redis 数据结构](#第三章-redis-数据结构)
  - [Q13 Redis 的主要数据结构？项目里分别用在哪？](#q13-redis-的主要数据结构项目里分别用在哪)
  - [Q14 Redis 分布式锁怎么实现？SETNX 有哪些参数？](#q14-redis-分布式锁怎么实现setnx-有哪些参数)
  - [Q15 基于 Redis 用 token + Lua 实现幂等](#q15-基于-redis-用-token--lua-实现幂等)
  - [Q16 消息队列的作用？顺序消费要注意什么？](#q16-消息队列的作用顺序消费要注意什么)
  - [Q17 令牌桶限流怎么实现？](#q17-令牌桶限流怎么实现)
- [第四章 缓存与数据库配合](#第四章-缓存与数据库配合)
  - [Q18 Redis 和数据库的双写一致性怎么解决？](#q18-redis-和数据库的双写一致性怎么解决)
  - [Q19 缓存穿透是什么？布隆过滤器的原理和使用场景？](#q19-缓存穿透是什么布隆过滤器的原理和使用场景)
  - [Q20 高频计数怎么写数据库？](#q20-高频计数怎么写数据库)
  - [Q21 系统设计：1000 份额度，晚上 8 点开抢](#q21-系统设计1000-份额度晚上-8-点开抢)
- [第五章 Shell 与线上排查](#第五章-shell-与线上排查)
  - [Q22 常用 Linux 命令？统计日志里访问最多的 10 个 IP / URL](#q22-常用-linux-命令统计日志里访问最多的-10-个-ip--url)
  - [Q23 线上 CPU 飙到 100% 怎么排查？](#q23-线上-cpu-飙到-100-怎么排查)
  - [Q24 优化效果你是怎么量化的？](#q24-优化效果你是怎么量化的)
  - [Q25 部署脚本 / 自动化做过什么？](#q25-部署脚本--自动化做过什么)
- [附录 A 实施顺序](#附录-a-实施顺序)
- [附录 B 环境改动](#附录-b-环境改动)

---

## 项目主线：从 Demo 到订单中心

对面试官讲的主线是：

> OnlineOrder 最初是一个外卖点餐 Demo，只有菜单、购物车和结账，而且结账只是清空购物车，订单根本没有落库。
> 我在它的基础上补齐了**订单中心**：订单落库、我的订单、商家后台、热销榜、限时特价（目前只做订餐，不含配送）。
> 用造数脚本把订单量压到百万级之后，陆续碰到了慢查询、并发写错、死锁、缓存一致性等问题，然后逐一解决。

这条主线里的问题都能在现有代码里找到依据：

| 现状（可以在代码里指出来） | 位置 | 引出的故事 |
|---|---|---|
| 结账只清空购物车，订单不落库 | `CartController.checkout` → `cartService.clearCart` | 为什么要做订单中心 |
| 购物车查询时每个菜品单独查一次 | `CartService.getOrderItemDtos` 在循环里调 `findById` | Q5 N+1 |
| 加菜是"读出总价 → 加上单价 → 写回" | `CartService.addMenuItemToCart` | Q8 并发下总价丢更新 |
| 除了主键和 UNIQUE 没有任何索引，外键列也没有 | `database-init.sql` | Q1–Q3 |
| "我的订单"查询没有分页，且 `orders.customer_id`、`order_lines.order_id` 都没有索引 | `LabDatabase.findOrders` | Q1、Q2、Q4 |

### 功能与技术点的对应关系

| 功能（要讲的业务） | 自然用到的技术 | 状态 |
|---|---|---|
| 菜品详情缓存 | String cache-aside、空值缓存 | ✅ 已有 |
| 菜品浏览量 | String INCR、批量回写、upsert | ✅ 已有 |
| 防重复提交结账 | SET NX EX | ✅ 已有，需补安全解锁 |
| Redis 购物车 | Hash | ✅ 已有 |
| 最近浏览 | List LPUSH + LTRIM | ✅ 已有 |
| 收藏、共同收藏 | Set、SINTER | ✅ 已有 |
| 热销榜 | ZSet ZINCRBY / ZREVRANGE | ✅ 已有，需补 SQL 对账 |
| 商家接单通知 | Stream 消费者组 | ✅ 已有 |
| 订单落库 | 事务、`INSERT ... RETURNING` | ✅ 已有（`LabDatabase.createOrder`） |
| 我的订单（分页） | 联合索引、覆盖索引、keyset 分页 | 🆕 新增 |
| 商家后台：订单搜索、待接单列表 | 索引失效、表达式索引、部分索引、pg_trgm | 🆕 新增 |
| 商家经营报表 | 窗口函数、物化视图 | 🆕 新增 |
| 商家多店员并发接单 | `FOR UPDATE SKIP LOCKED`、状态机条件更新 | 🆕 新增 |
| 限时特价（秒杀） | Lua 预扣、条件 UPDATE、死锁、限流 | 🆕 新增 |
| 下单幂等 | token + Lua | 🆕 新增 |
| 运维脚本 | 造数、压测、日志分析、排查、部署 | 🆕 新增 |

### 数据模型扩展 ✅ 已完成

订单中心和主业务**共用一个数据库、一套表**（`onlineorder`），全部写在 `src/main/resources/database-init.sql` 里。这个脚本是幂等的：只建不存在的表、只加不存在的列，种子数据只在表为空时插入。后端每次启动都会执行它，`gen_data.sh` 也会执行，**不会删任何数据**。以前的版本每次启动都会删表重建，而且订单中心单独放在一个 `redislab` 库里，现在都已合并。

| 改动 | 用途 |
|---|---|
| `customers` 加 `phone`（TEXT）、`created_at` | 报表、订单搜索；phone 故意用 TEXT（Q3）。生成的 10 万用户带邮箱、密码、权限和购物车，和真实注册的用户一样可以登录 |
| `restaurants` 加 `category` | 16 个品类，报表按品类统计 |
| `orders` 加 `restaurant_id`、`status`（默认 `PAID`）、`version` | 商家后台、状态流转 PAID → ACCEPTED → DONE / CANCELLED、乐观锁 |
| 删掉 `orders.rider_id` | 只订餐，暂不做配送 |
| `menu_items` 加 `stock` | 仅限时特价菜品有库存，普通菜品为 NULL（Q10、Q21） |
| 扩展 `pg_stat_statements`、`pg_trgm` | 慢 SQL 统计（Q1）、模糊搜索（Q3）。由 `gen_data.sh` 在本地创建，不放进启动脚本 |
| `LabDatabase.createOrder` 写入 `restaurant_id` | Redis 购物车结账产生的订单也归属到餐厅 |

**订单中心的表刻意没有建任何二级索引**，索引在 Q1–Q4 里按步骤添加，这样才有"优化前"的数据可以对比。

**合并时顺带解决的两个主业务问题**（都是真实发生的，可以当故事讲）：
- **首页接口**：`/restaurants/menu` 原来是 `findAll` 全量返回所有餐厅和菜品。餐厅扩到 5000 家、菜品 16 万个之后，返回会变成约 20MB。现在改为只返回近 30 天订单量最高的 20 家餐厅（`RestaurantRepository.findHot`，数量可以通过 `app.home.hot-restaurants` 配置），接口格式不变，前端不用改。实测缓存未命中 0.30s、命中 10ms，返回 41KB。
- **登录**：权限表 `authorities` 的 `email` 列上没有索引，10 万用户时每次登录查权限都要全表扫描（1667 个数据页，约 3ms），批量删除生成的用户时级联检查也要逐个扫全表（95 秒）。加上 `idx_authorities_email` 后，查权限降到 0.04ms，批量删除降到 21 秒。

**当前数据**（`scripts/gen_data.sh --reset` 生成，SEED=42）

| 表 | 行数 | 大小 |
|---|---|---|
| restaurants | 5000（种子 3 家 + 生成 4997 家） | 672 kB |
| menu_items | 约 16 万 | 14 MB |
| customers / authorities / carts | 各约 10 万（1 个真实用户 + 10 万生成用户） | 24 / 9 / 9 MB |
| orders | 200 万 | 190 MB |
| order_lines | 约 360 万 | 256 MB |

餐厅、菜品都用英文名和美元价格，地址在旧金山湾区，和原始 3 家店以及前端保持一致。

| 分布特征 | 设计 | 实测 |
|---|---|---|
| 餐厅热度 | Zipf(s=0.8)，排名和 id 随机打乱 | 第 1 名占 4.4%（约 8.8 万单），前 1%（50 家）占 28.3%，中位数 167 单，最少 71 单 |
| 原始 3 家店 | 固定排第 2、4、7 名 | Burger King 第 2、SGD Tofu House 第 4、Fashion Wok 第 7；脚本在最后会硬性校验 |
| 店内菜品热度 | Zipf(s=1.0) | 招牌菜平均占全店 22.3% |
| 用户活跃度 | 5% 重度、25% 普通、70% 轻度 | — |
| 下单时段（UTC-8） | 午餐和晚餐高峰，周末多 20%，全年逐月增长到约 2 倍 | 最高的 5 个小时依次是 12、11、18、19、17 点 |
| 状态 | 30 分钟内 PAID / ACCEPTED，更早的 95% DONE、5% CANCELLED | DONE 约 190 万、CANCELLED 约 10 万、PAID 219、ACCEPTED 184 |

**原始 3 家店为什么一定在前 10**：热度不是随机抽出来的名次，而是先指定名次、再按名次给权重。原始 3 家被直接放在第 2、4、7 名，其余餐厅随机填进剩下的名次，然后每一单按这些权重抽样选店。抽样的随机误差很小：第 7 名的期望约 1.8 万单，第 11 名约 1.3 万单，相差约 5000 单，而 200 万次抽样的随机波动只有一两百单，所以名次基本不会变。为了保险，脚本最后还会用 SQL 按真实订单数排名，只要有一家不在前 10 就报错退出。

---

# 第一章 SQL 与索引

## Q1 你项目里的慢 SQL 是怎么发现和定位的？

**面试原问**："线上慢 SQL 怎么定位"，"你是怎么发现这个 SQL 慢的"。

**业务场景**：订单中心上线后，用造数脚本（Q24）把订单压到 200 万。压测"我的订单"和商家后台时，接口 P99 明显变差。

**实操步骤**
1. 打开慢日志：compose 里给 postgres 加 `-c log_min_duration_statement=100`，超过 100ms 的 SQL 都会写进容器日志。
2. 打开统计视图：`-c shared_preload_libraries=pg_stat_statements`，再执行 `CREATE EXTENSION pg_stat_statements;`。
3. 压测结束后找出总耗时 Top5：
   ```sql
   SELECT calls, round(total_exec_time) total_ms, round(mean_exec_time,1) mean_ms, rows, query
   FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 5;
   ```
4. 对 Top SQL 执行 `EXPLAIN (ANALYZE, BUFFERS)`，看是不是 Seq Scan、实际扫描行数、`shared read` 有多少页。
5. 可选：打开 `auto_explain`（`auto_explain.log_min_duration=200ms`），慢 SQL 的执行计划会自动写进日志。
6. 用 shell 做慢日志日报（Q22 的 awk 脚本加 cron）。

**新增**：compose 参数、`scripts/slow_sql_report.sh`。
**复用**：`LabDatabase.findOrders` 就是第一个被抓出来的慢 SQL。

**留存证据**：优化前后各一份 `pg_stat_statements` Top5 截图，以及一份 EXPLAIN 原文。

**理论对照**：MySQL 对应的是 `slow_query_log` + `long_query_time`，分析工具是 `mysqldumpslow` / `pt-query-digest`，还有 `performance_schema`。美团技术博客的步骤是：确认问题 → 单表看区分度 → EXPLAIN → 结合业务 → 建索引 → 验证。你的故事按同样的顺序讲即可。

---

## Q2 索引原理？联合索引、最左前缀、覆盖索引、回表？

**面试原问**："MySQL 索引介绍一下，为什么是 B+ 树，每个节点存什么，一张表有几个聚簇索引"，"B+ 树和 B 树的区别"。

**业务场景**："我的订单"页：按用户查、按状态筛选、按时间倒序、每页 20 条。

```sql
SELECT id, status, total_price, created_at FROM orders
WHERE customer_id = ? AND status = ? ORDER BY created_at DESC, id DESC LIMIT 20;
```

排序里加上 `id DESC`，是为了在 `created_at` 相同时顺序也是确定的，Q4 的游标分页要依赖这一点。

**实操步骤**：每一步都记录 `EXPLAIN (ANALYZE, BUFFERS)` 的输出。

| 阶段 | 做法 | 预期执行计划 |
|---|---|---|
| 0 | 不加索引 | Seq Scan + Sort |
| 1 | `(customer_id)` | Index/Bitmap Scan，但还有 Sort 节点 |
| 2 | `(customer_id, status, created_at DESC, id DESC)` | Index Scan，Sort 节点消失（排序由索引完成） |
| 3 | 在阶段 2 基础上加 `INCLUDE (total_price)` | **Index Only Scan**，`Heap Fetches` 接近 0（不再回表） |

阶段 2 必须把 `id` 放进索引：PG 的索引叶子节点只存 TID，不存主键，缺了 `id` 的话阶段 3 仍然要回表取 `id`，不会出现 Index Only Scan。`id` 放在排序列而不是 `INCLUDE` 里，这样 Q4 的游标分页 `(created_at, id) < (?, ?)` 能直接复用这个索引。
| 4 | 只按 `WHERE status = ?` 查 | 用不上阶段 2 的索引，用来验证最左前缀 |

还有一个 PG 特有的亮点：刚大批量插入后，Index Only Scan 的 `Heap Fetches` 仍然很高。执行 `VACUUM orders` 后降到 0，原因是 visibility map 更新了。能讲清楚这一点，说明你真的动手做过。

**新增**：`GET /orders?status=&cursor=` 接口，以及对应的索引迁移脚本。
**复用**：`orders` 表、`createOrder` 的写入逻辑。

**留存证据**：四个阶段的耗时、rows、buffers 汇成一张表。

**理论对照**
- MySQL InnoDB：数据按主键组织成聚簇索引（一张表只有 1 个），二级索引的叶子节点存的是主键值，查其他列要回表。
- PG：表是 heap（堆表），**没有聚簇索引**（`CLUSTER` 命令只是一次性按某个索引重排物理顺序），所有索引的叶子节点存的都是 TID（行的物理地址），相当于所有索引都是"二级索引"。PG 的回表就是按 TID 直接去 heap 取行（执行计划里的 Index Scan、Bitmap Heap Scan），不用像 InnoDB 那样再走一遍主键 B+ 树。另外 PG 的 heap 里还存着 MVCC 可见性信息，所以即使索引覆盖了所有列，也可能要回表确认可见性，这就是 `Heap Fetches` 的来源。
- 两者共通的部分：B+ 树结构、最左前缀、覆盖索引。
- B+ 树相对 B 树：非叶子节点只存键，一个节点能放更多键，所以树更矮、IO 更少；叶子节点之间有链表，适合范围查询。

---

## Q3 索引失效有哪些情况？你遇到过吗？

**面试原问**："MySQL 索引失效？怎么解决"（美团高频题）。

**业务场景**：商家后台的订单搜索，这里的每个查询都是商家的真实需求。

| 商家需求 | 第一版写法（索引失效） | 原因 | 改法 |
|---|---|---|---|
| 查某一天的订单 | `WHERE created_at::date = '2026-09-01'` | 对索引列做了函数运算 | 改成范围条件 `created_at >= '2026-09-01 00:00-08' AND created_at < '2026-09-02 00:00-08'`；或建表达式索引。注意 `created_at` 是 timestamptz，转 date 依赖会话时区（不是 IMMUTABLE），直接 `CREATE INDEX ON orders ((created_at::date))` 会报错 `functions in index expression must be marked IMMUTABLE`，要写成 `((created_at AT TIME ZONE 'America/Los_Angeles')::date)`，查询里的表达式也必须一字不差 |
| 按手机号查 | `WHERE phone = 4150001234`（参数是数字；phone 是 10 位纯数字的 TEXT） | 隐式类型转换：PG 直接报错 `operator does not exist: text = bigint`；MySQL 不报错，悄悄对列做转换，导致索引失效。PG 里真正会"悄悄失效"的是为了消掉报错改成 `phone::bigint = ?`（Seq Scan），以及整数列和小数比较（`id = 3.0`，Java 传 BigDecimal 时会出现），列被转成 numeric | 参数统一传字符串，参数类型和列类型保持一致 |
| 按手机号前缀查 | `WHERE phone LIKE '415%'` | PG 特有：库的排序规则是 `en_US.utf8`，普通 B-tree 不支持 LIKE 前缀匹配（MySQL 可以） | 建 `text_pattern_ops` 索引，或列用 `COLLATE "C"` |
| 按手机尾号查 | `WHERE phone LIKE '%1111'` | 前导通配符 | 用 `pg_trgm` 建 GIN 索引，或存一列反转的手机号 |
| 按菜名模糊搜 | `WHERE name ILIKE '%chicken%'`（菜名是英文） | 前导通配符 | `pg_trgm` GIN 索引（`gin_trgm_ops`）。trigram 至少要 3 个字符才起作用，搜单个汉字（如 `'%辣%'`）帮不上忙，中文要用分词或 `pg_bigm` |
| 待接单或已取消 | `status = 'PAID' OR customer_id = ?` | OR 两侧不能用同一个索引，任一侧没有索引就只能全表扫描 | 两侧的列各自有索引时，PG 走 BitmapOr；MySQL 有 index_merge（union），但优化器经常不选，稳妥做法是改写成 `UNION ALL` |
| "待接单"列表 | `WHERE status = 'PAID'` | status 区分度低，优化器选择全表扫描 | **部分索引** `CREATE INDEX ... WHERE status = 'PAID'`（待接单的订单只占极少数） |
| 头部商家的订单 | 同一条"查商家订单"的 SQL，查头部商家（约 8.8 万单）和尾部商家（约 100 单） | 数据倾斜：优化器根据统计信息，对头部商家改走全表扫描或位图扫描，对尾部商家走索引扫描 | 用 `pg_stats` 查 `most_common_vals` / `most_common_freqs`，解释优化器为什么这样选 |

**新增**：商家后台（商家账号、待处理订单、订单搜索），见下方"商家后台"。造数时餐厅热度已经是长尾分布（前 1% 的商家占 28% 的订单），待接单的 `PAID` 订单只有几百条。
**复用**：`menu_items.name`。

### 商家后台 ✅ 已实现（索引和改写还没做）

**商家账号命名规范**：每家餐厅一个商家账号，密码统一是 `123456`。

| 餐厅 | 账号 | 由谁创建 |
|---|---|---|
| 原始 3 家（带图片的种子餐厅，id 1–3） | `merchant<餐厅id>@mail.com` | `database-init.sql`，本地和 AWS 都有 |
| 生成的餐厅（id 4–5000） | `merchant<餐厅id>@gen.example` | `gen_data.sh`，`--reset` 时跟生成的用户一起删除重建 |

常用账号：

| 用途 | 账号 | 餐厅 | 订单数 |
|---|---|---|---|
| 日常演示 | `merchant1@mail.com` | Burger King | 约 5 万 |
| 数据倾斜：头部 | 查询 `SELECT restaurant_id, count(*) FROM orders GROUP BY 1 ORDER BY 2 DESC LIMIT 1` 得到 id | Bay Mezze | 约 8.8 万 |
| 数据倾斜：尾部 | 同上，改成 `ORDER BY 2 LIMIT 1` | Royal Vietnamese Kitchen | 81 |

- 商家账号就是 `customers` 里的一行（同一套登录），权限是 `ROLE_MERCHANT`，没有购物车。账号属于哪家店记录在 `restaurant_staff(email, restaurant_id)` 表里。
- 登录后前端调用 `GET /me`，根据角色决定进入顾客页面还是商家后台。
- `/merchant/**` 要求 `ROLE_MERCHANT`，`/cart/**` 要求 `ROLE_USER`：没登录返回 401，角色不对返回 403。
- 商家接口的路径里不带餐厅 id，一律按登录身份查出所属餐厅，不能通过改 URL 去看别家店的订单。操作别家店的订单一律返回 404，不暴露那一单是否存在。

**接口**

| 接口 | 说明 |
|---|---|
| `GET /merchant/orders/active` | 待处理订单：`PAID` 和 `ACCEPTED`，先下单的排前面，最多 100 条 |
| `GET /merchant/orders/search` | 参数：`date`（太平洋时间的日期）、`phone` + `phone_match`（`exact` / `prefix` / `suffix`）、`dish`、`status`（可以传多个）、`page`、`size`。按下单时间倒序；返回 `has_more`，不返回总数 |
| `POST /merchant/orders/{id}/accept`、`reject`、`complete` | 状态机的条件更新：`PAID → ACCEPTED`、`PAID → CANCELLED`、`ACCEPTED → DONE`。当前状态不对返回 409 |

顾客结账 `POST /cart/checkout` 以前只清空购物车、不落库，现在会生成正式订单：每家餐厅一单，状态是 `PAID`，出现在商家的待处理列表里（Q11）。

**搜索条件和 Q3 的对应关系**：SQL 在 `OrderRepository.search`，都按业务上最自然的写法实现，没有刻意写错。

| 页面上的条件 | 第一版 SQL | 对应上面表格的哪一行 |
|---|---|---|
| Date | `(o.created_at AT TIME ZONE 'America/Los_Angeles')::date = :date` | 对索引列做函数运算 |
| Phone · Exact | `c.phone = :phone`（参数是字符串） | 写法本身没问题，只缺索引；隐式类型转换那一行要手动在 psql 里复现 |
| Phone · Starts with | `c.phone LIKE '415%'` | 非 C 排序规则下 LIKE 前缀 |
| Phone · Ends with | `c.phone LIKE '%0552'` | 前导通配符 |
| Dish | `EXISTS (... m.name ILIKE '%whopper%')` | 菜名模糊搜索，`pg_trgm` |
| Status 选 `PAID` / 待处理列表 | `o.status IN ('PAID', 'ACCEPTED')` | 区分度低，部分索引 |
| 换头部、尾部商家登录，执行同一个搜索 | `o.restaurant_id = :restaurantId` | 数据倾斜 |

OR 条件那一行在页面上没有对应的功能，还是在 psql 里手动演示。

**优化前的基线**（2026-09-28，`merchant1@mail.com`，所有订单表都没有二级索引，curl 单次请求的端到端耗时，只作参考，正式对比要用 `EXPLAIN (ANALYZE, BUFFERS)` 和 `bench.sh`）：

| 查询 | 耗时 |
|---|---|
| 不带条件 | 0.19s |
| `date=2026-09-01` | 0.20s |
| `phone=415&phone_match=prefix` | 0.19s |
| `phone=0552&phone_match=suffix` | 0.15s |
| `dish=whopper` | 0.83s |
| 待处理列表 | 0.19s |

每次查询都还要按 `order_id IN (...)` 取这一页的订单明细，而 `order_lines` 上没有 `order_id` 索引，这一步本身就是 360 万行的全表扫描，很可能占了上面耗时的大头。这是 Q1 用 `pg_stat_statements` 最先抓出来的慢 SQL，先把它和订单主查询的耗时分开，再做 Q3。

订单搜索页面每次查询都会显示前端测到的耗时，加索引前后可以直接在页面上对比。

**留存证据**：每个坑一组"改前计划 → 改后计划"的对比，整理成"失效原因 → 改法"表。

**理论对照**：MySQL 失效清单要背，包括：最左前缀不满足、范围条件右边的列用不上索引、`!=`、`IS NOT NULL`（视数据分布而定），以及优化器认为回表成本太高而放弃索引。对应的 MySQL 工具是 `optimizer_trace`，PG 里则是看 `EXPLAIN` 中估算行数和实际行数的差距。

- **两类失效**："不能用"（写法和索引对不上：函数、类型转换、前导 `%`）和"不愿用"（基于成本放弃：区分度低、数据倾斜）。区分方法：`SET enable_seqscan = off` 后再 EXPLAIN，还是 Seq Scan 就是不能用，换成索引但 cost 更高就是不愿用。
- **PG 特有的点**：表达式索引要求 IMMUTABLE；类型不匹配直接报错而不是悄悄失效；非 C 排序规则下 LIKE 前缀需要 `text_pattern_ops`；有部分索引（MySQL 没有）；预编译语句执行 5 次后可能切换为 generic plan，数据倾斜时同一条 SQL 在 psql 里快、在应用里慢（MySQL 每次执行都重新优化）；PG 15 没有 skip scan（PG 18 才有，MySQL 8.0.13 起就有）。
- **排查工具对照**：MySQL 用 `EXPLAIN` / `EXPLAIN ANALYZE`、`optimizer_trace`、`FORCE INDEX`、`SHOW INDEX`、`sys.schema_unused_indexes`；PG 用 `EXPLAIN (ANALYZE, BUFFERS)`、`enable_*` 开关、`pg_stats`、`pg_stat_user_indexes.idx_scan`，强制索引需要 `pg_hint_plan` 扩展。

---

## Q4 深分页怎么优化？

**面试原问**：深分页优化。

**业务场景**：商家后台的订单列表翻到第 5 万页，以及"导出本月全部订单"对账。

**实操步骤**
1. 对比 `OFFSET 1000000 LIMIT 20` 和 `OFFSET 0` 的耗时：OFFSET 会把前面的行全部读出来再丢掉。
2. 延迟关联：先用覆盖索引只查出 id，再 JOIN 回表取其他列。
3. **Keyset（游标）分页**：`WHERE (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT 20`，接口返回 `next_cursor`。
4. 导出场景：服务端游标 `fetchSize` 流式读取，或者 `COPY (SELECT ...) TO STDOUT`，避免一次性把全部数据读进 JVM 内存。

**新增**：Q2 的接口改成游标分页；`GET /merchant/{rid}/orders/export`。
**复用**：Q2 的联合索引。

**留存证据**：三种写法在不同页码下的耗时曲线，以及导出时 JVM 堆内存的对比。

**理论对照**：MySQL 完全一样，用 LIMIT offset 或延迟关联。游标分页的代价是不能跳到指定页，所以产品上要改成"加载更多"的交互。

---

## Q5 ORM 的 N+1 问题 / 子查询和 JOIN 怎么优化？

**面试原问**：项目追问"一个接口发了几条 SQL"。美团技术博客里有一个案例：复杂子查询改写后提速 200 倍。

**业务场景**：这是现有代码里真实存在的问题。`CartService.getOrderItemDtos` 在循环里对每个菜品调用一次 `menuItemRepository.findById`，购物车有 20 个菜品就发 21 条 SQL。

**实操步骤**
1. 打开 `jdbc.core` DEBUG 日志（`application.yml` 已经开了），数一下一次 `GET /cart` 发了多少条 SQL。在 `pg_stat_statements` 里也能看到 `calls` 次数暴涨。
2. 修复：改成 `findAllById(ids)`（一条 `IN` 查询），或写一条 `JOIN` 的 `@Query`。
3. 延伸：商家报表里的"每个菜品最近一次被下单的时间"，第一版用关联子查询写，改成 `JOIN + GROUP BY` 或 `DISTINCT ON` 后再对比执行计划。

**新增**：无，直接修改现有代码。
**复用**：`CartService`、`MenuItemRepository`。

**留存证据**：修复前后的 SQL 条数和接口耗时。

**✅ 已完成**：`CartService.getOrderItemDtos` 改为 `findAllById` 一次 `IN` 查询，按 id 建 Map 组装。购物车 5 种菜时，`GET /cart` 从 8 条 SQL（3 + N）降到固定 4 条，DB 耗时约 27ms → 20ms。延伸题的关联子查询 → `LEFT JOIN + GROUP BY` 改写已做，估算 cost 约 214 万 → 7.3 万。

---

## Q6 手写 SQL：每月消费 Top3 用户、累计金额

**面试原问**：美团 SQL 题"统计每个月支付金额排名前 3 的用户，以及每个用户截止到该月的累计支付金额"。

**业务场景**：商家经营报表页。

```sql
-- 每个商家每月消费 Top3 用户
SELECT * FROM (
  SELECT restaurant_id, date_trunc('month', created_at) AS m, customer_id, sum(total_price) AS amt,
         dense_rank() OVER (PARTITION BY restaurant_id, date_trunc('month', created_at)
                            ORDER BY sum(total_price) DESC) AS rk
  FROM orders WHERE status <> 'CANCELLED'
  GROUP BY 1, 2, 3) t
WHERE rk <= 3;

-- 用户截止到每个月的累计金额
SELECT customer_id, m, amt, sum(amt) OVER (PARTITION BY customer_id ORDER BY m) AS cum_amt
FROM (SELECT customer_id, date_trunc('month', created_at) m, sum(total_price) amt
      FROM orders GROUP BY 1, 2) t;
```

另外再写两条：复购率（下过 2 单以上的用户占比），以及近 30 天各菜品销量 TopN（基于 `order_lines`）。

**性能延伸**：报表每次都扫描 200 万行，耗时秒级 → 改成**物化视图**加定时 `REFRESH MATERIALIZED VIEW CONCURRENTLY`（需要唯一索引），或按天预聚合的汇总表。

**和 Redis 热销榜的关系**（能体现系统设计能力）：ZSet 热销榜是实时的，但可能丢数据，比如 Redis 重启、INCR 之后订单又被取消。SQL 报表是准确的，但不实时。所以每天凌晨用 SQL 结果**校准**一次 ZSet（`DEL` 之后批量 `ZADD`）。

**新增**：`GET /merchant/{rid}/report/*`、物化视图、校准任务。
**复用**：`/lab/redis/zset/*` 热销榜的逻辑。

**理论对照**：MySQL 8 也支持窗口函数，写法完全相同。`rank` / `dense_rank` / `row_number` 的区别要背。

---

## Q7 大批量写入怎么做？

**业务场景**：两处。一是历史订单导入和造数；二是浏览量从 Redis 回写数据库（现有的 `flushViews` 就是逐条 upsert）。

**实操步骤**
1. 回写 1 万个菜品的浏览量，对比四种方式：逐条 `update` → `batchUpdate` → JDBC URL 加 `reWriteBatchedInserts=true` → `INSERT ... SELECT unnest(?::int[], ?::bigint[])` 一条 SQL 完成。
2. 造数：`COPY` 对比 JDBC 批量插入（Q24）。
3. 大表 `count(*)` 很慢：PG 由于 MVCC 必须扫表确认行可见性。改法是用 `reltuples` 取估算值，或者由业务侧自己计数。
4. **外键导致导入慢 30 倍（已实测）**。造数时 order_lines（400 万行）COPY 用了 176s，orders（200 万行）只用了 5.8s。
   - **控制变量对比**：同一份 400 万行数据，从服务端文件 COPY 进约束不同的表。

     | 约束 | 耗时 |
     |---|---|
     | 无约束 | 2.5s |
     | 仅主键 | 4.5s |
     | 主键 + 外键 → menu_items | 78.7s |
     | 主键 + 外键 → orders | 83.1s |
     | 主键 + 两个外键 | **160.3s** |

   - **直接证据**：对 20 万行执行 `EXPLAIN ANALYZE INSERT ... SELECT`。插入本身 334ms，两个外键触发器 `Trigger for constraint ...` 各约 3.5s、各被调用 20 万次，外键检查占总耗时 95%。
   - **原因**：PG 的外键是用**逐行触发器**实现的，每插入一行就执行一次 `SELECT ... FOR KEY SHARE` 去检查并锁住被引用的行。成本在于每行都要调一次触发器，和被引用表的大小无关：menu_items 只有 30 行，这个外键和指向 200 万行 orders 的外键一样慢，就是证明。
   - **优化**：导入时先不建外键，导完再 `ALTER TABLE ... ADD CONSTRAINT`。这样校验只需一次性的连接扫描，两个外键加起来 1.6s（1.36s + 0.26s），总耗时从 160s 降到约 6s。DDL 在 PG 里可以放进事务，可以把"删外键 → COPY → 重建外键"包在同一个事务里，失败时整体回滚，不会留下没有外键的表。
   - **理论对照**：MySQL 批量导入时常用 `SET foreign_key_checks = 0`，但它导完后**不会补做校验**，脏数据会直接留在表里。PG 重建外键时会对全表校验一遍，更安全。

**复用**：`LabDatabase` 里的 upsert，以及 `/string/views/flush`。

---

# 第二章 事务与锁

## Q8 悲观锁和乐观锁？项目里怎么用的？ ✅ 已完成

> **完成情况**：用原子更新修复（`CartRepository.addTotalPrice`、`OrderItemRepository.addOne`、唯一索引 `uq_order_items_cart_menu`），复现脚本 `scripts/concurrent_add.sh`。悲观锁、乐观锁两种写法和 QPS 对比只做了理论分析，没有实现。
>
> | | HTTP 状态码 | total_price | item_rows | total_qty |
> |---|---|---|---|---|
> | 期望 | 200×50 | 244.5 | 1 | 50 |
> | 修复前（3 次） | 200×10、500×40 | 4.89 | 9~10 | 10 |
> | 修复后 | 200×50 | 244.5 | 1 | 50 |
>
> 复现中发现的三个问题，面试时都可以讲：
> - **两类并发问题**：总价、数量是"丢失更新"，靠原子更新 `SET x = x + ?` 修复；购物车为空时多个请求都查到"没有这个菜"、各自 INSERT，这是"重复插入"，此时没有行可锁，`FOR UPDATE` 也拦不住，只能靠唯一约束 + `INSERT ... ON CONFLICT DO UPDATE`。重复行还会引发连锁错误：之后 `findByCartIdAndMenuItemId` 查出多行，抛异常，返回 500。
> - **压测工具本身不并发**：最初用 `xargs -P 20 curl`，Git Bash 下每启动一个 curl 进程要约 30ms，比一次加菜请求（约 8ms）还慢，请求实际是串行的。只有后端刚启动、JVM 还冷的第一轮能复现，之后几轮"全部正确"。改成单进程 `curl -Z --parallel-max 20` 后，50 个请求 0.12s 发完，每次都能稳定复现。结论：测不出并发 bug 不代表没有，先确认压测的实际并发度。
> - **加唯一索引前要先清理重复行**：否则 `CREATE UNIQUE INDEX` 失败，启动脚本报错，应用起不来。
>
> 修复后仍存在的缓存问题：`TwoLevelCache` 不感知事务，`@CacheEvict` 删缓存有可能发生在事务提交之前，这时并发读会把旧值写回缓存（尚未验证）。这属于缓存一致性问题（Q18），不是 Q8 的范围。

**面试原问**："悲观锁和乐观锁概念"，"你之前用锁解决了什么场景问题"。

**业务场景**：这是现有代码里真实存在的并发 bug。`addMenuItemToCart` 的流程是"读出 cart.totalPrice → 加上菜品单价 → 写回"。用户快速连点两次"加菜"（或者两个设备同时加），两次都读到旧值，最后**总价少算一次**；同时 `quantity + 1` 也存在同样的问题。

**实操步骤**
1. 复现：`seq 50 | xargs -P 20 -I{} curl -s -X POST .../cart ...` 并发加同一个菜 50 次，再查总价和数量，确认对不上。
2. 三种修法，都要做一遍并对比：
   - 原子更新：`UPDATE carts SET total_price = total_price + ? WHERE id = ?`（最简单，不需要显式加锁）
   - 悲观锁：`SELECT ... FROM carts WHERE id = ? FOR UPDATE`，之后再读改写
   - 乐观锁：加 `version` 列，`UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?`，影响行数为 0 就重试
3. 对照 Redis 版购物车：`HINCRBY` 天然是原子操作，所以根本不存在这个问题。这正好解释了"为什么购物车要迁到 Redis Hash"。

**新增**：`carts.version` 列、并发复现脚本 `scripts/concurrent_add.sh`。
**复用**：`CartService`、Redis 版购物车 `/lab/redis/hash/cart/*`。

**留存证据**：修复前后 50 次并发的最终总价和数量，以及三种方案的 QPS。

**理论对照**：选型原则是冲突多用悲观锁，冲突少用乐观锁。乐观锁在高冲突下重试会雪崩，Q21 秒杀里会再遇到这个问题。

---

## Q9 隔离级别怎么实现的？MVCC？

**面试原问**："MySQL 的隔离级别有哪些，怎么实现的"，以及 redo log / undo log。

**业务场景 1**：商家对账报表先查汇总、再查明细，两条 SQL 之间有新订单写入，导致汇总和明细对不上。
- 开两个 psql 窗口演示：Read Committed 下两次读的结果不一致；把报表事务改成 `REPEATABLE READ` 后两次读一致（同一个快照）。

**业务场景 2**：浏览量每分钟回写一次（`ON CONFLICT DO UPDATE`），`menu_item_views` 表越来越大。
- 查 `SELECT n_live_tup, n_dead_tup, last_autovacuum FROM pg_stat_user_tables`：dead tuples 很多。
- 解释原因：PG 的 UPDATE 实际上是插入新版本、旧版本标记为 dead，靠 VACUUM 回收空间。
- 修法：调低 `fillfactor`，让更新走 HOT update（`n_tup_hot_upd` 会上升），并调整 autovacuum 参数。
- 用 `SELECT xmin, xmax, * FROM menu_item_views` 直接看到行版本信息。

**复用**：`/string/views/flush`，以及 Q6 的报表。

**理论对照**（要背）
- MySQL：undo log 版本链 + ReadView 实现 MVCC。RC 每条语句生成一次 ReadView，RR 在事务的第一次读时生成。RR 下用**间隙锁 / next-key lock** 防幻读。redo log 保证持久性（WAL）。
- PG：旧版本直接留在表里（xmin/xmax），所以需要 VACUUM。RR 实际上是快照隔离，Serializable 用 SSI（谓词锁），**没有间隙锁**。
- 面试时可以这样说："我在 PG 上观察到的是 X，MySQL 用 undo log 实现同样的效果，区别是 Y。"对比着讲更加分。

---

## Q10 遇到过死锁吗？怎么排查？

**业务场景**：限时特价菜品有库存（`menu_items.stock`）。一个订单里有多个特价菜时，结账事务会逐个扣减库存。订单 A 的菜品顺序是 [1, 2]，订单 B 是 [2, 1]，并发时相互等待，PG 报 `deadlock detected`。

**实操步骤**
1. 复现：两个 psql 窗口手动交叉执行，或者用脚本并发下单。
2. 看日志：PG 会把双方的 SQL 和锁信息写进日志；`log_lock_waits=on` 还能记录超过 `deadlock_timeout` 的锁等待。
3. 实时查看：`pg_locks` JOIN `pg_stat_activity`，或者 `SELECT pid, pg_blocking_pids(pid), query FROM pg_stat_activity WHERE wait_event_type = 'Lock'`。
4. 修复：扣库存前**按 menu_item_id 排序**，保证所有事务按相同顺序加锁；或者改成一条 `UPDATE ... FROM (VALUES ...)` 批量扣减。
5. 应用层对死锁异常（SQLState `40P01`）做有限次数的重试。

**新增**：`stock` 列、特价下单逻辑（也是 Q21 的一部分）。
**复用**：`LabDatabase.createOrder` 的事务框架。

**理论对照**：MySQL 用 `SHOW ENGINE INNODB STATUS` 查看 `LATEST DETECTED DEADLOCK`，`innodb_lock_wait_timeout` 控制等待超时。另外 MySQL 的间隙锁会带来 PG 没有的死锁场景：两个事务都在同一个间隙上 `SELECT ... FOR UPDATE` 空行，然后各自插入。这个要背。

---

## Q11 用户下单、商家接单、骑手派单大致是个什么过程？

**面试原问**：美团校招一面原题。

> **范围**：项目目前只做订餐，不含配送。所以"下单 → 商家接单"这一段要真正实现；骑手派单部分只讲设计，理论对照里给了要点。

**业务场景**：把"下单 → 接单"这条链路串起来讲。

```
用户结账 ──(事务写 orders/order_lines, status=PAID)──> XADD 订单事件到 Stream
                                                           │
                    商家消费者组：通知商家有新订单（崩溃后 XCLAIM 接管）
                                                           │
   商家后台多台平板（多个店员）并发从"待接单池"里取单：
        SELECT id FROM orders WHERE restaurant_id = ? AND status = 'PAID'
        ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED;
        UPDATE orders SET status = 'ACCEPTED', version = version + 1 WHERE id = ?;
```

**实操步骤**
1. 接单防重：`UPDATE orders SET status = 'ACCEPTED', version = version + 1 WHERE id = ? AND status = 'PAID'`。用状态机加条件更新，影响行数为 0 说明已经被别人接走了。
2. 多店员并发取单：给头部商家造一批 PAID 订单，10 个并发"店员"同时取单。对比普通 `FOR UPDATE`（大家排队等同一行）和 `SKIP LOCKED`（各自拿到不同的单），记录吞吐量。
3. 状态机约束：用 CHECK 约束或在应用层校验，只允许合法的状态流转：PAID → ACCEPTED → DONE，PAID / ACCEPTED → CANCELLED。

**新增**：`POST /merchant/orders/{id}/accept`、`POST /merchant/{rid}/orders/take`。

> **部分完成**：结账落库（`OrderService.checkout`）、接单 / 拒单 / 完成的条件更新（`OrderRepository.transition`，状态不对返回 409）已经实现，见 Q3 的"商家后台"。多店员并发取单（`SKIP LOCKED`）、Stream 通知、CHECK 约束还没做。
**复用**：Stream 消费者组（`/lab/redis/stream/*` 实验 10，包括崩溃后的 XCLAIM）、`createOrder`。

**理论对照**
- MySQL 8 同样支持 `SKIP LOCKED`。
- 骑手派单（只讲设计）：商家接单后，订单进入派单系统。派单系统按距离、骑手负载、预计送达时间做匹配，是"系统指派"和"骑手抢单"相结合的方式；骑手抢单同样可以用 `SKIP LOCKED` 或 Redis 原子操作来保证一单只被一个骑手拿到。美团公开过真实的派单数据集（INFORMS TSL Challenge），面试时可以提一句。

---

## Q12 连接池被打满 / 长事务怎么排查？

**业务场景**：结账事务里调用了"支付"（用 sleep 2s 模拟外部支付接口）。压测时 Hikari 报 `Connection is not available, request timed out`，连带菜单等无关接口也一起超时。

**实操步骤**
1. `SELECT state, count(*) FROM pg_stat_activity GROUP BY state`：出现大量 `idle in transaction`。
2. Hikari 日志和指标：active 连接数一直等于 maximumPoolSize，pending 线程不断增加。
3. 修复：把外部调用移到事务之外（先调支付，再开短事务写库）；设置 `statement_timeout` 和 `idle_in_transaction_session_timeout` 兜底；按 `连接数 ≈ CPU 核数 × 2 + 有效磁盘数` 的思路调整池大小，并解释为什么池子不是越大越好。
4. 降级：支付超时后，订单先记为"待确认"，由后台任务补偿。对应 JD 里的"容灾、降级"。

**复用**：`createOrder`、`LabDatabase` 的 Hikari 池（池大小只有 3，更容易复现）。

**留存证据**：修复前后压测的错误率和 P99。

---

# 第三章 Redis 数据结构

> 这一章的功能基本都已实现（见 `REDIS_LAB.md`）。要做的只是**把讲法改成业务驱动**，再补上几个缺口。

## Q13 Redis 的主要数据结构？项目里分别用在哪？

面试时按功能讲，不要按类型讲：

| 功能 | 类型 | 为什么选它 | 现有接口 |
|---|---|---|---|
| 菜品详情缓存 | String | 整个对象序列化后存取 | `GET /lab/redis/string/menu-item/{id}` |
| 浏览量 | String INCR | 单线程执行，天然原子 | `POST /string/menu-item/{id}/view` |
| 购物车 | Hash | 按字段增减某个菜品的数量，`HINCRBY` 原子，不用整体读写 | `/hash/cart/*` |
| 最近浏览 | List | `LPUSH` + `LTRIM` 保留最近 N 条 | `/list/recent/*` |
| 收藏、共同收藏 | Set | 自动去重，`SINTER` 求交集 | `/set/fav/*` |
| 热销榜 | ZSet | 按分数排序，`ZINCRBY` 更新 | `/zset/*` |
| 商家接单通知 | Stream | 消费者组、ACK、崩溃后可以 XCLAIM 接管 | `/stream/orders/*` |

**要补的追问点**
- ZSet 底层是 listpack 加跳表：元素少时用 listpack，超过阈值转为跳表 + dict。用 `OBJECT ENCODING` 可以亲眼看到编码的切换。
- 购物车 Hash 什么时候落库：结账时（`/hash/cart/{id}/checkout` 已实现），或者设置过期时间。

---

## Q14 Redis 分布式锁怎么实现？SETNX 有哪些参数？

**面试原问**："Redis 实现分布式锁"，"setnx 命令和 set 命令的区别"，"除了 Redis 还有什么分布式锁"。

**业务场景**：防止用户重复点击结账（现有的 `/string/checkout-lock/{customerId}`）。

**现有实现的缺口**（补上后就能讲出一个完整的故事）
1. 现在的 value 固定是 `"1"`，解锁时可能**删掉别人的锁**：A 的锁过期后 B 拿到了锁，A 执行完又 DEL 掉了 B 的锁。
   → 改成 value 存 UUID，解锁用 Lua 脚本："GET 出来的值等于自己的 UUID 才 DEL"。
2. 业务执行时间超过 TTL：锁提前过期 → 用看门狗线程定期续期（Redisson 就是这样做的），或者把 TTL 设得明显大于 P99 执行时间。
3. 复现：两个线程加 `sleep` 构造出"锁过期、被别人拿到、再被误删"的时序。

**理论对照**：`SET key value NX EX 10` 是一条原子命令；`SETNX` 加 `EXPIRE` 分两步执行，中间崩溃会留下永不过期的锁。其他方案包括 Redisson、ZooKeeper 临时顺序节点、数据库唯一索引或 `SELECT FOR UPDATE`。

---

## Q15 基于 Redis 用 token + Lua 实现幂等

**面试原问**：美团二面原题。

**业务场景**：用户在结账页点"提交订单"，网络超时后客户端自动重试，结果生成了两笔订单。

**实操步骤**
1. 进入结账页时调用 `GET /orders/token`，服务端生成 token 并 `SET idem:{token} 1 EX 600`。
2. 提交订单时带上 token，服务端用一段 Lua 脚本原子地执行"存在则删除并返回 1，否则返回 0"。返回 0 就直接拒绝，说明是重复提交。
3. 兜底：数据库层给 `orders` 加 `request_id UNIQUE`。即使 Redis 出问题，也不会产生重复订单。
4. 复现：用同一个 token 并发提交 10 次，确认只生成 1 笔订单。

**新增**：token 接口、Lua 脚本、`request_id` 唯一约束。
**复用**：结账逻辑 `/hash/cart/{id}/checkout`。

**和 Q14 的区别**：分布式锁控制的是"同一时刻只有一个人在执行"，幂等控制的是"同一个请求只生效一次"。

---

## Q16 消息队列的作用？顺序消费要注意什么？

**面试原问**："消息队列的作用"，"RocketMQ 和 Kafka 的区别"，"顺序消费要注意什么"。

**业务场景**：下单后，通知商家接单、更新热销榜、刷新浏览量都不应该阻塞用户的结账请求。

**现有功能**：Stream 消费者组（实验 10）已经包含 XADD、XREADGROUP、XACK、pending 列表、崩溃后 XCLAIM、多消费者组广播。

**要补的**
- 解耦：结账只做 XADD；"商家通知"和"热销榜更新"是两个独立的消费者组，同一条消息各消费一次，对应的是广播。
- 顺序：同一个订单的状态事件（PAID → ACCEPTED → CANCELLED）必须按顺序处理。可以按 `orderId % N` 分到 N 个 Stream（类比 Kafka 按 key 分区），每个 Stream 只由一个消费者处理；消费端再用状态机校验，拒绝"从 CANCELLED 变回 ACCEPTED"这类非法流转。
- 重复消费：XCLAIM 之后可能重复处理，所以消费端要做幂等（复用 Q11 的条件更新）。

**理论对照**：Kafka、RocketMQ 的区别直接背。Kafka 是分区日志，靠顺序写和零拷贝获得高吞吐；RocketMQ 的 CommitLog 是所有 topic 共用的一个文件，另外支持事务消息和延时消息。

---

## Q17 令牌桶限流怎么实现？

**面试原问**：美团实习一面原题。

**业务场景**：限时特价开抢时，下单接口瞬间涌入大量请求，要保护数据库。

**实操步骤**
1. Redis 加 Lua 实现令牌桶：Hash 里存 `tokens` 和 `last_refill_ts` 两个字段。每次请求先按经过的时间补充令牌（不超过桶容量），再尝试扣 1 个令牌。
2. 在 Spring 里用拦截器或注解接入 `POST /flash-sale/{id}/order`，超出限流的请求直接返回 429。
3. 压测：用 k6 的 `constant-arrival-rate` 固定请求速率，验证放行的 QPS 稳定在设定值附近。

**理论对照**：令牌桶允许一定程度的突发流量，漏桶则以恒定速率输出。固定窗口有临界突刺问题，滑动窗口可以用 ZSet 实现。

---

# 第四章 缓存与数据库配合

## Q18 Redis 和数据库的双写一致性怎么解决？

**面试原问**：美团二面原题。

**业务场景**：商家修改菜品价格后，用户看到的仍然是旧价格。

**现有功能**：`GET /string/menu-item/{id}`（cache-aside）加 `PUT /db/menu-item/{id}/price`，也就是 REDIS_LAB 实验 6。

**要补的**
1. 对比"先删缓存再更新 DB"和"先更新 DB 再删缓存"两种顺序，用两个请求加 `sleep` 构造出脏数据的时序：读请求在写请求的两步之间把旧值回填进了缓存。
2. 延时双删：更新后先删一次，等 500ms 再删一次。
3. 删除缓存失败怎么办：把删除动作发到 Stream 里重试（复用 Q16）。进阶方案是订阅 binlog（MySQL 用 Canal，PG 用逻辑复制或 Debezium），这里只讲理论。
4. 主应用里的 Caffeine 本地缓存（`@Cacheable("restaurants")`）也有同样的问题，而且有多个实例时每台机器各自持有一份。这就是"为什么要从本地缓存演进到 Redis"。

**✅ 已实现：Caffeine（L1）+ Redis（L2）两级缓存**（`cache/` 包，线上 L2 是 ElastiCache Serverless for Valkey）

| 设计点 | 做法 | 验证方式（本地两个实例 8080 / 8081） |
|---|---|---|
| 读路径 | L1 → L2（命中则回填 L1）→ 数据库（写入 L2 和 L1）。L1 60s，L2 10 分钟 | A 查库后，B 从 L2 读到的餐厅列表与 A 的响应逐字节一致 |
| 跨实例 L1 失效 | 写入或删除后，通过 Redis Pub/Sub 广播"缓存名 + key"，其他实例删掉自己的 L1；消息里带实例 ID，忽略自己发的 | A 加菜后立即在 B 读购物车，读到的是新数据，B 的日志显示收到了广播 |
| Redis 故障降级 | 超时设为 500ms；一次连接失败或超时就熔断 30 秒，期间跳过 Redis，只用 L1 + 数据库 | 停掉 Redis 后接口照常返回 200，只有第一个请求多等了一次超时 |
| 故障期间的删除 | 删不掉的 L2 key 先记下来，熔断结束后补删 | 暂停 Redis 期间加菜，恢复后首次读取触发补删，两个实例都读到新值 |
| 序列化 | 按缓存名配置具体类型的 JSON 序列化器，JSON 里不存类名；反序列化失败（DTO 改了字段）当作未命中并删掉坏值 | — |
| 集群兼容 | 清空缓存用 SCAN 不用 KEYS（Serverless 禁用了 KEYS） | 本地 Redis 同样以单节点集群模式运行 |

**实测中的两个细节**（面试时可以讲）：
- **超时不等于没执行**：暂停 Redis 时，删除命令已经发进了网络缓冲区，客户端 500ms 就报了超时，但 Redis 恢复后照样把这条命令执行了。所以补删必须是幂等操作，而"超时"只能理解为"结果未知"，不能当成"失败"。
- **广播的局限**：广播只清 L1。Redis 故障期间广播发不出去，其他实例的 L1 最多保留 60 秒旧值，这是 L1 TTL 设得短的原因。

**已知取舍**：先删 L2 再删 L1 的顺序，仍然存在"删除和回填交错导致脏数据"的经典竞态，没有做延时双删（见上文第 2 条）。

---

## Q19 缓存穿透是什么？布隆过滤器的原理和使用场景？

**面试原问**："缓存穿透是什么，如何解决"，"布隆过滤器使用场景及原理"。

**业务场景**：有爬虫用不存在的菜品 id 大量请求详情接口，每次都穿过缓存直接打到数据库。

**现有功能**：空值缓存，即 null 标记加短 TTL（实验 7）。

**要补的**
1. 空值缓存的缺点：如果攻击者用随机 id，缓存里会塞满大量空值 key。用 `INFO memory` 或 `DBSIZE` 可以直接观察到。
2. 布隆过滤器：用 `SETBIT`/`GETBIT` 自己实现，k 个哈希、m 位数组。启动时把所有菜品 id 加进去，请求 id 不在过滤器里就直接返回 404。
3. 验证误判率：插入 n 个 id，再用 10 万个不存在的 id 去测，实测误判率和公式 `(1 - e^(-kn/m))^k` 对比。
4. 缺点：不支持删除。菜品下架后仍然会被判断为"可能存在"，可以改用 Counting Bloom Filter 或定期重建。
5. 顺带讲缓存击穿（热点 key 过期时加互斥锁重建，复用 Q14 的锁）和缓存雪崩（TTL 加随机值）。

---

## Q20 高频计数怎么写数据库？

**业务场景**：菜品浏览量。每次浏览都 `UPDATE` 数据库的话，热点菜品会有严重的行锁竞争，还会让表膨胀（Q9）。

**现有功能**：`INCR` 加 `/string/views/flush` 批量 upsert 回写（实验 8）。

**要补的**
- 用 `@Scheduled` 定时回写。回写时用 `GETDEL` 或 `RENAME` 取走当前计数，避免"读完到删除之间"新增的计数丢失。
- 用压测对比直接 UPDATE 数据库和 Redis 缓冲这两种方式的 QPS。
- 这一节可以和 Q7（批量写入）、Q9（dead tuples）串成一个完整的故事。

---

## Q21 系统设计：1000 份额度，晚上 8 点开抢

**面试原问**：美团原题"一台手机 1000 个额度 晚上八点开抢 设计秒杀系统"。

**业务场景**：限时特价菜，每天 20:00 放出 1000 份。

**分层设计**（每一层都要真正实现并压测）

| 层 | 做法 | 复用 / 新增 |
|---|---|---|
| 入口 | 令牌桶限流 | Q17 |
| 防重 | 一个用户限购一份：`SADD flash:{id}:buyers uid`，返回 0 说明已经买过 | 复用 Set |
| 扣减 | Lua 脚本原子执行"检查库存 > 0 → DECR → SADD 购买用户" | 新增 |
| 异步落库 | 扣减成功后 XADD，消费者写 `orders` | 复用 Stream |
| DB 兜底 | `UPDATE menu_items SET stock = stock - 1 WHERE id = ? AND stock > 0` | 新增 |
| 死锁 | 多个菜品一起扣库存时按 id 排序 | Q10 |

**必做的对比压测**（结论才可信）：纯数据库 `FOR UPDATE`、纯数据库条件 UPDATE、乐观锁（会大量重试）、Redis Lua 预扣，四种方案在 2000 并发下对比两项：**是否超卖**、QPS。

**留存证据**：四种方案的对比表，以及"库存 1000、最终订单 1000、零超卖"的核对 SQL。

### 口头示范回答（约 3 分钟）

> 方括号 `[ ]` 里的数字要替换成你自己压测的实测值。回答顺序是：**先澄清需求 → 点出核心矛盾 → 按请求链路分层讲 → 讲异常兜底 → 用项目数据收尾**。面试官中途打断追问是好事，说明他在跟着你的思路。

**① 先澄清需求（15 秒，别跳过）**

> "我先确认几个点：一共 1000 份，一人限购一份对吧？抢到之后是否需要在一定时间内支付，超时释放库存？预估参与人数大概是什么量级？我先按 100 万人同时抢、一人一份、15 分钟内支付来设计。"

**② 核心矛盾（15 秒）**

> "这个场景的特点是**瞬时读写极度集中，但最终只有 1000 个请求是有效的**。100 万个请求里 99.9% 注定失败，所以设计的核心就是：**让失败的请求尽早失败，越靠近用户越好，真正打到数据库的只有那 1000 个；同时绝对不能超卖**。整体是一个漏斗，我按请求链路从外往里讲。"

**③ 分层漏斗（1 分半，主体）**

> "**第一层，前端和接入层**。按钮在 8 点前置灰，倒计时以服务端时间为准；点一次就禁用，防止连点。商品详情页是静态的，放 CDN，不打到后端。秒杀下单的地址不写死，8 点才下发一个带签名的 token，防止脚本提前刷接口。
>
> **第二层，网关限流**。用令牌桶按接口做总量限流，再按用户 ID 做限频，超出的直接返回'活动火爆请重试'。1000 份库存，放进来的流量是它的几倍就足够了，没必要让 100 万请求都进业务层。
>
> **第三层，服务内存**。每个服务实例在本地维护一个'已售罄'标记，库存一旦被抢光就置为 true，后续请求在内存里直接返回，连 Redis 都不访问。
>
> **第四层，Redis 原子扣减，这是核心**。活动开始前把库存预热到 Redis。扣减用一个 Lua 脚本，原子地做三件事：先用 `SISMEMBER` 判断这个用户是否已经抢过，再判断库存是否大于 0，满足条件就 `DECR` 库存并把用户 `SADD` 进已购集合。因为 Redis 单线程执行 Lua，整个判断和扣减中间不会被别的请求插进来，所以不会超卖，也保证了一人一单。
>
> **第五层，异步下单**。Lua 返回成功后，我不直接写数据库，而是发一条消息到队列，项目里用的是 Redis Stream 消费者组，然后立刻返回'排队中'，前端轮询下单结果。消费者按自己的节奏写订单表，数据库的写入压力被削平了。
>
> **第六层，数据库兜底**。消费者写库时，扣库存用 `UPDATE ... SET stock = stock - 1 WHERE id = ? AND stock > 0`，影响行数为 0 就说明没库存了；订单表对（用户 ID，活动 ID）建唯一索引。这样即使上面某一层出了 bug，数据库这一层也保证不会超卖、不会重复下单。"

**④ 异常和一致性（45 秒）**

> "还有几个异常情况要处理：
> - **超时未支付**：用延时任务，15 分钟后检查订单状态，未支付就取消订单，同时把库存加回 Redis 和数据库，并从已购集合里移除用户。
> - **消息丢失或重复**：Stream 有 pending 列表，消费者崩溃后可以用 XCLAIM 把消息接管过来重新处理；因为可能重复消费，消费端靠唯一索引做幂等。
> - **Redis 和数据库库存不一致**：原则是**宁可少卖不能超卖**。Redis 扣了但数据库没写成功，最多少卖几份，可以通过对账任务比对 Redis 的已购集合和订单表后补偿；超卖则由数据库的条件更新兜住。
> - **热点 key**：1000 份库存都在一个 key 上，单个 Redis 节点可能扛不住。可以把库存拆成 10 个 key，每个 100 份，按用户 ID 哈希到其中一个。
> - **隔离**：秒杀服务独立部署，挂了不影响主站的正常下单。"

**⑤ 用项目收尾（20 秒，加分项）**

> "这套方案我在自己的外卖项目里做过'限时特价菜'，对比压测了四种扣库存方式，[2000] 并发下：数据库 `FOR UPDATE` 所有请求排队等同一行锁，QPS 只有 [x]；条件 UPDATE 能到 [x]；乐观锁因为冲突太多，大量重试失败，[1000] 份最后只卖出去 [x] 份，出现了少卖；Redis Lua 预扣 QPS 到了 [x]，最终 1000 份、1000 个订单、零超卖。"

### 常见追问

| 追问 | 回答要点 |
|---|---|
| 为什么不直接用数据库扣库存？ | 所有请求更新同一行，行锁把并发变成串行；连接池很快被占满，还会拖垮其他业务（Q12） |
| 为什么用 Lua，`DECR` 之后判断是否小于 0 不行吗？ | 只扣库存的话 `DECR` 后判断再回补也可以；但还要加上"一人一单"判断，涉及多个命令，只有 Lua 能保证整体原子 |
| 乐观锁为什么不适合？ | 冲突率极高时大部分请求更新失败要重试，吞吐量低，还会少卖；乐观锁适合冲突少的场景（Q8） |
| Redis 挂了怎么办？ | 主从加哨兵自动切换；切换时可能丢失少量扣减记录，但数据库条件更新保证不会超卖，最多少卖，靠对账补偿 |
| 怎么防黄牛和脚本？ | 动态秒杀地址加签名、验证码或答题（同时能把峰值打散到几秒内）、按用户和设备限频、风控黑名单 |
| 消息队列积压了怎么办？ | 用户看到的是"排队中"，不影响抢购结果的判定；消费端可以横向扩展消费者；超时未落库的由对账任务补偿 |
| 库存预热怎么做？ | 活动开始前用定时任务把数据库库存写进 Redis，并初始化售罄标记；活动结束后清理相关 key |

---

# 第五章 Shell 与线上排查

> 全部用 bash 写在 `scripts/` 下，在 WSL Ubuntu 里运行。

## Q22 常用 Linux 命令？统计日志里访问最多的 10 个 IP / URL

**业务场景**：打开 Tomcat access log（`server.tomcat.accesslog.enabled=true`，已在 `application.yml` 打开，末列是 `%{ms}T` 毫秒耗时；Tomcat 10 的 `%D` 是微秒，要注意），压测后分析接口的表现。

`scripts/log_report.sh` 要能输出下面这些统计：

```bash
LOG=${1:-logs/access_log.log}
# Top10 IP
awk '{print $1}' "$LOG" | sort | uniq -c | sort -rn | head -10
# Top10 URL（去掉 query 参数）
awk '{split($7,a,"?"); print a[1]}' "$LOG" | sort | uniq -c | sort -rn | head -10
# 状态码分布
awk '{c[$9]++} END{for(k in c) print k, c[k]}' "$LOG"
# 每秒 QPS 峰值（时间字段截到秒）
awk '{print substr($4,2,20)}' "$LOG" | uniq -c | sort -rn | head -5
# 每个接口的 P99 耗时（最后一列是毫秒）
awk '{split($7,a,"?"); print a[1], $NF}' "$LOG" | sort -k1,1 -k2,2n |
  awk '{u[$1][++n[$1]]=$2} END{for(k in u){i=int(n[k]*0.99); if(i<1)i=1; print k, "p99="u[k][i]"ms", "n="n[k]}}'
# 5xx 最多的分钟
awk '$9 ~ /^5/ {print substr($4,2,17)}' "$LOG" | uniq -c | sort -rn | head
```

同样的思路可以用来分析 PG 慢日志：`grep 'duration:' | awk` 按 SQL 指纹聚合，然后用 cron 每天跑一次生成日报（Q1）。

> 注意：`u[$1][...]` 这种数组的数组是 gawk 的扩展语法，WSL 里默认是 mawk，需要先 `sudo apt install gawk`。

---

## Q23 线上 CPU 飙到 100% 怎么排查？

**面试原问**：通用的必问题。

**业务场景**：这里要讲两个真实能复现的故事。

1. **数据库侧**：商家报表（Q6）在建物化视图之前，被多个商家同时打开，postgres 进程的 CPU 飙满。
   `top` 找到 postgres 进程 → `pg_stat_activity` 按 pid 查到对应的 SQL → `pg_cancel_backend(pid)` 止血 → 用物化视图根治。
2. **应用侧**：订单导出（Q4）第一版在循环里用 `String +=` 拼接 CSV，导出大量数据时 Java 进程 CPU 飙高、GC 频繁。
   排查步骤写成 `scripts/diagnose.sh <pid>` 固化下来：
   ```bash
   top -Hp $PID -b -n1 | head -20            # 找到 CPU 最高的线程 id
   printf '%x\n' $TID                          # 转成 16 进制
   jstack $PID | grep -A 20 "nid=0x$HEX"      # 定位代码行
   jstat -gcutil $PID 1000 5                   # 看 GC 是否频繁
   jmap -histo:live $PID | head -20            # 看哪些对象占用最多
   ss -ant | awk '{print $1}' | sort | uniq -c # 连接状态分布（TIME_WAIT、CLOSE_WAIT）
   df -h; du -sh logs/*                        # 磁盘是否写满
   ```
   修复：改用 `StringBuilder` 或流式写出（Q4 的方案），修复后再跑一次脚本做对比。

**留存证据**：修复前后的 jstack 片段、top 截图、GC 次数。

---

## Q24 优化效果你是怎么量化的？

这是所有 SQL 故事都会被追问的一点，需要两个脚本支撑。

**`scripts/gen_data.sh`：造数** ✅ 已完成
```bash
scripts/gen_data.sh [--reset] [订单数=2000000] [用户数=100000] [餐厅总数=5000]    # SEED 环境变量控制随机种子
```
- 用 gawk 一次遍历同时生成 orders 和 order_lines 两个 CSV，保证订单总价等于明细之和、明细菜品都属于订单所在的餐厅；然后用 `COPY FROM STDIN` 导入，并对每张表分别计时。
- 已有数据时不加 `--reset` 会拒绝执行；导入后执行 `setval` 推进序列，再 `ANALYZE`。
- 分布设计和实测结果见"项目主线 → 数据模型扩展"。
- 导入方式：先删掉 order_lines 的外键，COPY 完再加回。清理旧数据、COPY 五张表、重建外键、推进序列，全部在同一个事务里完成，中途失败会整体回滚。
- 实测耗时（Git Bash）：生成 CSV 77s，上传到容器 15s，**单事务导入 13.9s**，其中 order_lines 360 万行的 COPY 用了 4.5s，重建外键 1.7s。第一版逐行检查外键时，同样的导入要 3 分钟左右，原因分析见 Q7 第 4 条。

**`scripts/bench.sh`：压测** ✅ 已完成
```bash
scripts/bench.sh <路径> [并发=50] [时长=30s] [备注]
scripts/bench.sh /restaurants/menu 50 30s "N+1 修复前"
METHOD=POST BODY='...' scripts/bench.sh /xxx 20 10s
```
- 底层用 k6。每次运行向 `bench/results.md` 追加一行：时间、commit（有未提交改动时带 `+dirty`）、接口、并发、时长、QPS、P50、P95、P99、错误率、备注。
- 每个优化点都执行一次"优化前跑 → 改代码或加索引 → 优化后跑"，结果表格直接用于简历和面试。

---

## Q25 部署脚本 / 自动化做过什么？

**业务场景**：现有的 `deploy.sh` 按顺序执行"构建前端 → bootBuildImage → 推 ECR → ECS 滚动发布"。

**要补的**
1. 加上 `set -euo pipefail`，任何一步失败都立即中止，避免把半成品推上去。
2. 镜像 tag 用 `git rev-parse --short HEAD`，不要只用 `latest`，这样回滚时才有具体版本可用。
3. 发布后执行 `aws ecs wait services-stable`，再对 `/restaurants/menu` 做健康检查；失败时自动把 task definition 切回上一个版本。
4. `trap` 捕获错误并打印失败的步骤。

---

## 附录 A 实施顺序

第 1 周优先做面经里的高频题，而且大部分只需改现有代码：

| 顺序 | 内容 | 工作量 |
|---|---|---|
| 1 | ✅ 环境改动（附录 B）、数据模型扩展、`gen_data.sh`、`bench.sh` | 已完成 |
| 2 | ✅ Q5 N+1；Q8 加菜并发 bug（直接修现有代码） | 已完成 |
| 3 | Q1 → Q2 → Q4：我的订单（慢 SQL 定位 → 索引 → 游标分页） | 中 |
| 4 | Q3 商家订单搜索、Q6 报表 + 热销榜校准 | 中 |
| 5 | Q14 锁补齐、Q15 幂等、Q18/Q19 一致性和布隆过滤器 | 中 |
| 6 | Q11 商家接单、Q10 死锁、Q21 秒杀（含 Q17 限流） | 大 |
| 7 | Q22/Q23 日志分析和排查脚本、Q9 MVCC、Q12 连接池、Q25 部署 | 中 |

## 附录 B 环境改动

| 改动 | 状态 |
|---|---|
| `docker-compose.yml` db 服务加 `command`：`pg_stat_statements` 预加载、`log_min_duration_statement=100`、`log_lock_waits=on`、`track_io_timing=on` | ✅ 已完成，容器已重建（数据卷保留） |
| `CREATE EXTENSION pg_stat_statements / pg_trgm` | ✅ 已完成，写在 schema 文件里 |
| `application.yml` 打开 Tomcat access log，输出到 `OnlineOrder/logs/access_log.log`，格式为 `%h %l %u %t "%r" %s %b %{ms}T` | ✅ 已完成 |
| `.gitignore` 忽略 `logs/`、`bench/tmp/` | ✅ 已完成 |
| JDBC URL 加 `reWriteBatchedInserts=true` | ⏳ Q7 做对比时再加 |
| 新接口去掉 `/lab/` 前缀，用 `/orders`、`/merchant`、`/flash-sale`，并在 `AppConfig` 放行或鉴权 | `/merchant/**` ✅ 已完成（要求 `ROLE_MERCHANT`）；其余 ⏳ 写对应功能时再做 |

**工具**
- Git Bash 自带 gawk，Windows 上已装 k6，两个脚本在 Git Bash 里可以直接运行。
- WSL 里需要 `sudo apt install gawk`，k6 另外安装；Docker Desktop 要开启 WSL 集成。
- `jstack`/`jstat`/`jmap` 由 JDK 自带。

**常用观测命令**
```bash
docker compose logs db --since 5m | grep duration:          # 慢 SQL 日志
docker compose exec db psql -U postgres -d onlineorder -c \
  "SELECT calls, round(mean_exec_time,1) ms, query FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 5"
docker compose exec db psql -U postgres -d onlineorder -c "SELECT pg_stat_statements_reset()"   # 每轮压测前清零
```

---

## 参考

- [美团技术团队：MySQL 索引原理及慢查询优化](https://tech.meituan.com/2014/06/30/mysql-index.html)
- [美团技术团队：基于代价的慢查询优化建议](https://tech.meituan.com/2022/04/21/slow-query-optimized-advice-driven-by-cost-model.html)
- [JavaGuide：深度分页优化](https://javaguide.cn/high-performance/deep-pagination-optimization.html)
- 面经：[美团后端校招一面](https://github.com/liqiangcc/interview-lab/issues/2321) · [美团后端二面](https://github.com/liqiangcc/interview-lab/issues/2157) · [美团后端实习一面](https://github.com/liqiangcc/interview-lab/issues/2376) · [美团后端（秒杀 / AI coding）](https://github.com/liqiangcc/interview-lab/issues/2313)
