# OA Prompt Cheat Sheet

> 配套文档：[Amazon_OA_AI题目准备.md](Amazon_OA_AI题目准备.md)（知识点）、
> [Amazon_OA本项目示范例题.md](Amazon_OA本项目示范例题.md)（练习题）
>
> 用途：Amazon SDE OA "Code Repository + AI Assistant" 题型的现场提问手册。
> 所有 prompt 均为英文，可直接复制。编号用于快速索引。
>
> 整理日期：2026-08-27

---

## 0. 五条使用原则

1. **AI 能读整个 workspace** —— 不需要你先点开文件，也不需要贴代码就能问结构。
   但它按"与本次请求的相关性"检索文件，**问题里带具体类名/文件名/报错关键词，命中率高得多**。
2. **一次只问一件事** —— 问 5 件事会漏答，且你分不清哪条对应哪问。
3. **加约束句** —— `Don't suggest a fix yet` / `One line each, no explanation` /
   `File paths only` / `Keep it under 20 lines`。不加它会倒一大堆你读不完。
4. **Guarded Mode 不会给完整解法** —— 别问 "fix all the bugs"，会得到一堆废话。
   改问"对比 / 列差异 / 解释"，判断留给自己。
5. **对话会被 recruiter 逐字看到**（Transcript 默认开启）——
   问题体现判断力比问题数量重要。

### 语言选择

中文可用，官方无限制。但**技术 prompt 建议用英文**（术语本就是英文，且 transcript 谁都能读），
描述不清时切中文或中英夹杂即可。**不要浪费一轮去问 "Can you answer me with Chinese?"**。

---

## 1. 考场速查：按时间轴

| 时段 | 做什么 | 用哪些 prompt |
|---|---|---|
| **0-5 min** | 跑 test，看红的是哪些 | 不用 AI |
| **5-10 min** | 摸清仓库结构与约定 | **A1 A2 A5 A6** |
| **10-20 min** | 读懂 test 与 contract | **B1 B2 B3** |
| **20-25 min** | 定位改动点 | **C1 C2 C3** |
| **25-50 min** | 改代码 | **D**（语法）+ **E**（排错）+ **H**（纠正 AI）|
| **50-57 min** | 补隐藏 test 的覆盖 | **F1 F2 F3 F4** |
| **57-60 min** | 提交前自查 | **G1 G2** |

**最高价值的三条**：**B3**（对比 test 期望 vs 实现）、**C3**（对比好/坏路径）、**F1**（contract 差距扫描）。
时间紧只用这三条。

---

## 2. A 组 — 开场：摸清环境与仓库

### A1. 架构地图 ⭐

```
Give me a high-level architecture map of this repository.

First: what layering convention does it ACTUALLY use? Don't assume MVC —
tell me what you see (e.g. controller/service/repository, hexagonal,
feature-based packages, or something else).

Then for each layer: the package or directory path, and one example file.

Finally: trace how a typical HTTP request flows through those layers.

File paths only, one line each. Keep it under 20 lines.
```

> **`Don't assume MVC` 这句是关键。** 不同项目分层差别很大：
> 经典三层（`controller`/`service`/`repository`）、六边形（`domain`/`application`/`infrastructure`）、
> 按功能垂直切分（`watchlist/`/`movie/`/`user/`）、两层（`web`/`dao`）。
> 包名也可能叫 `web`、`api`、`rest`、`resource`、`persistence`、`dao`。
> 不加这句，AI 会往你给的模板里硬套，给你一张错的地图。

### A2. 错误处理约定 ⭐

```
Is there a global exception handler in this project (@ControllerAdvice /
@RestControllerAdvice, or an equivalent error middleware)?

If yes: show me which exception class maps to which HTTP status code, as a table,
and what the error response body looks like.

If no: tell me how errors are currently turned into HTTP responses.
```

> 这条直接给你"异常 → 状态码"映射表。有它的话，后面你**只需要在 service 里
> `throw` 正确的异常，不用碰 controller**。

### A3. 技术栈与版本

```
What framework and version does this project use? Check the build file
(pom.xml / build.gradle / package.json) and tell me:
- framework + major version
- language version
- ORM / data access library
- test framework
- whether a validation library is on the classpath
One line each.
```

> 最后一条很重要：Spring Boot 3 里 `spring-boot-starter-validation`
> **不再随 web starter 传递引入**，缺了的话 `@NotNull` 等注解会静默失效。

### A4. 怎么跑测试

```
How do I run the tests in this project? Give me:
- the command to run all tests
- the command to run a single test class
- the command to run a single test method
- where the test report is written
Commands only.
```

### A5. 隐藏文件说明 ⭐

```
Is there a PROJECT_FILES_INSTRUCTIONS.md, or any file that lists files which are
excluded from the project during the attempt and added back at scoring time?
If so, show me its contents.
```

> **完全合规** —— 这是平台主动放进 workspace 给候选人看的说明文件，
> 里面只有文件路径，没有隐藏 test 的内容。
> 但**文件名本身信息量很大**：`RepayLoanTest.java` 这种名字直接暴露考点。

### A6. README 里的 API contract

```
Show me the API contract section of the README: every endpoint, its HTTP method,
request body shape, response body shape, and every status code it can return.
Present as a table. Don't summarize — I need the exact field names.
```

> **README contract 是唯一权威**，压过题目描述。
> 而且**隐藏 test 极可能就是照着它写的** —— 见 F 组。

---

## 3. B 组 — 读懂 test 与 contract

### B1. 汇总所有 test 的期望

```
Read all test files in this repository. For each test method, give me a table:

test method name | endpoint it calls | HTTP method | expected status code |
expected response fields

Don't suggest any fixes.
```

### B2. 只看失败的那几个 ⭐

```
The following tests are failing:
<paste the failing test names from the test output>

For each one, tell me exactly what the API must return: the HTTP status code,
the response body shape, and the EXACT field names (watch for snake_case vs
camelCase). Also tell me what request it sends.

Don't suggest a fix yet.
```

> **`Don't suggest a fix yet` 必加。** 先让它把需求说清楚，你再自己判断改哪里。
> 不加这句它会直接跳到方案，方向一旦偏了要花更多时间拉回来。

### B3. 对比 test 期望 vs 当前实现 ⭐⭐⭐

```
Compare what <XxxControllerTest> expects against what <XxxController> and
<XxxService> actually do. List every mismatch: status codes, field names,
missing validation, missing persistence.

Present as a table: what the test expects | what the code does | the gap.
Don't write code.
```

> **这是全表最值钱的一条。** 同时用上"能读 test"和"能读源码"两个能力，
> 直接把差异摆出来，而且完全绕开"不给完整解法"的限制 ——
> 你只是让它做对比，判断和改动是你自己的。

### B4. 判断 test 在哪一层

```
Look at <XxxTest.java>. What kind of test is it — a pure unit test with mocks,
a web-layer slice test, or a full integration test?

Based on that, tell me whether framework features like transactions, caching,
bean validation, and exception handlers are active in this test or bypassed.
```

> **决定你往哪层找 bug**：
> - 纯 Mockito 单测（手动 `new` 被测对象）→ AOP 代理不生效，
>   `@Transactional`/`@Cacheable`/`@Valid` **全部失效**，bug 只可能在业务逻辑里
> - `@WebMvcTest` → 只起 Web 层，Service 是 mock 的，**别去改 Service**
> - `@SpringBootTest` → 全链路，事务/缓存/校验/数据库都在范围内

### B5. 字段命名约定

```
Does this project use snake_case or camelCase in its JSON API? Check for a
Jackson property-naming-strategy setting in application.yml/properties, and any
@JsonProperty annotations. Show me where it's configured.
```

> `menuId` 在配置了 `SNAKE_CASE` 后对外是 `menu_id`。
> test 断言 `jsonPath("$.movie_id")` 而你返回 `movieId` 就是红的。
> **改全局配置比改 20 个 DTO 快，但会影响所有端点 —— 可能修好一个弄坏五个。**
> 只有个别字段对不上时，用 `@JsonProperty` 单点修复更安全。

---

## 4. C 组 — 定位改动点

### C1. 哪些文件要改

```
Which files would I need to change to fix "<paste the failing test name or the
bug description from the problem statement>"?

List the files and one line on why each is involved. Don't write any code yet.
```

### C2. 追一条完整调用链

```
Trace what happens when a client calls <METHOD> <path>.
Name every file and method involved, in order, from the entry point down to the
database. Include any filters, interceptors, or middleware in the path.
Don't suggest a fix.
```

### C3. 对比好的路径 vs 坏的路径 ⭐⭐⭐

```
Compare how the "<feature that works>" flow and the "<feature that's broken>"
flow are implemented.

Where do they differ in: input validation, existence checks, error handling,
status codes returned, and whether they persist to the database?
```

> **杀手锏。** OA repo 里通常有一部分功能是好的。
> 让 AI 把"好的路径"和"坏的路径"并排摆出来，缺什么一目了然 ——
> 缺了存在性检查、缺了 `save()`、状态码不一样。
> **比让它直接找 bug 有效得多**，因为它有正确样本可参照。

### C4. 找某个符号的所有引用

```
List every file in this repository that references <ClassName / method name /
table name>, grouped by layer, with one line on what each does with it.
```

### C5. 数据模型与关联

```
Show me the entity/model classes and how they relate to each other
(foreign keys, one-to-many, many-to-one). Which side owns each relationship?
Present as a short list.
```

> **JPA 双向关联的经典坑**：维护关系的是**有外键的那一端**（`@ManyToOne` 那侧）。
> 只往 `issue.getComments().add(c)` 里加，`comment.issue_id` 仍然是 null ——
> 这就是"create 之后不显示 / 没有和 issue link 起来"的直接原因。

---

## 5. D 组 — 写代码：语法与骨架

> D 组是四段式：**[环境] + [现状] + [目标] + [约束]**。
> 虽然 AI 能读仓库，**贴出具体片段仍然值得** —— 不是因为它看不到，而是为了**聚焦**。

### D0. 通用四段式模板

```
I'm working in a <Spring Boot 3.5 / Java 21 / Spring Data JPA> project.

Here is the method I'm changing (in <File.java>):
```java
<paste 5-15 lines — not the whole file>
```

I need it to <exact desired behavior, including the exact status code and
response body>.

Show me only the minimal change, including any imports and annotations needed.
Don't rewrite the whole class.
```

### D1. 状态码速查

```
In Spring Boot 3, give me the ResponseEntity call for each of these.
One line each, no explanation:
200 with body, 201 with body, 201 with a Location header, 204 No Content,
400 with an error body, 403, 404, 409.
```

**期望答案（背下来，用于校验 AI）**：

| 场景 | 码 | 写法 |
|---|---|---|
| 查询成功 | 200 | `ResponseEntity.ok(dto)` |
| 创建成功 | **201** | `ResponseEntity.status(HttpStatus.CREATED).body(dto)` |
| 创建 + Location | 201 | `ResponseEntity.created(URI.create("/x/" + id)).body(dto)` |
| 删除成功 | **204** | `ResponseEntity.noContent().build()` |
| 参数非法 / 业务规则拒绝 | **400** | `ResponseEntity.badRequest().body(err)` |
| 未登录 | 401 | — |
| 已登录但越权 | **403** | — |
| 资源不存在 | **404** | `ResponseEntity.notFound().build()` |
| 重复创建 | **409** | `ResponseEntity.status(HttpStatus.CONFLICT).body(err)` |

> **401 vs 403**：401 = 没表明身份；403 = 表明了身份但没权限。
> **400 vs 404**：`GET /movies/99999`（ID 合法但不存在）是 **404**；
> `GET /movies/abc`（无法解析成 Long）才是 400。

### D2. 全局异常处理器

```
Write a @RestControllerAdvice for Spring Boot 3 that maps:
- ResourceNotFoundException -> 404
- DuplicateResourceException -> 409
- ForbiddenOperationException -> 403
- MethodArgumentNotValidException -> 400, with all field errors joined into one
  message string

The response body should be a record: ErrorResponse(String code, String message).
Include all imports.
```

### D3. Bean Validation

```
In Spring Boot 3, show me how to validate a request body DTO that is a Java
record. I need: a required non-blank string, a valid email, a positive BigDecimal,
and a string with min length 6.

Include the imports, the annotation on the controller parameter, and tell me
which Gradle/Maven dependency is required.
```

**三处都对了才生效，缺任何一处都是静默失效、无报错**：

1. 依赖 `spring-boot-starter-validation`（Boot 2.3 起已从 web starter 移除）
2. DTO 字段上的约束注解（`jakarta.validation.constraints.*`，**不是 `javax`**）
3. Controller 参数上的 **`@Valid`**

> 两条校验链路是分开的：
> `@RequestBody` 靠参数上的 `@Valid`，抛 `MethodArgumentNotValidException`；
> `@PathVariable`/`@RequestParam` 靠**类上**的 `@Validated`，抛 `ConstraintViolationException`。
> advice 里要分别处理。

### D4. 存在性 / 查重

```
Spring Data JPA. Write repository method signatures using derived query naming
(no @Query) for:
- find a movie by id AND watchlist id
- check whether a movie with this movieId already exists in this watchlistId
- delete by watchlist id and movie id
```

> 期望：`existsByWatchlistIdAndMovieId(...)` 返回 `boolean` —— **查重神器**。

### D5. 404 + 403 的标准三步

```
Spring Boot 3, Spring Data JPA. Write a service method that:
1. loads a Comment by id, throws ResourceNotFoundException (-> 404) if absent
2. throws ForbiddenOperationException (-> 403) if the comment's author is not
   the currently authenticated user
3. otherwise updates the content and persists it
Keep it under 15 lines.
```

**模板（背下来）**：
```java
X x = repo.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("X", id));   // → 404
if (!x.getOwner().equals(currentUser)) {
    throw new ForbiddenOperationException("...");                      // → 403
}
repo.save(...);
```

### D6. 拿当前登录用户

```
Spring Boot 3 with Spring Security 6. Inside a @RestController method, show me
two ways to get the currently authenticated user's username. Include imports.
```

### D7. Optional 正确用法

```
In Java, list the Optional methods I should use instead of .get(), and when to
use each: orElseThrow with a supplier, orElse, orElseGet, ifPresent, map.
One line each.
```

> **`.orElseThrow()` 是直接取代 `.get()`，不是组合** —— 两者都返回 `T`，赋值语句不用改。
> **在 OA repo 里看到 `.get()` 就该条件反射地改**：
> `.get()` 空时抛 `NoSuchElementException` → **500**，而正确语义是 **404**。

### D8. 时间与过期

```
In Java 21 using java.time, I store a token with a field `Instant generatedAt`.
Show me two ways to check whether it expired more than 30 seconds ago, and tell
me which one is less error-prone.
```

> 注意 `Duration.between(a, b)` 的方向，写反了逻辑就是反的。

### D9. 金额

```
In Java, why should I use BigDecimal instead of double for money, and why
compareTo instead of equals when comparing two BigDecimal values?
Show the correct way to check "balance is less than amount".
```

> `0.1 + 0.2 == 0.30000000000000004`。
> `BigDecimal.equals` 比较标度（`2.0 != 2.00`），必须用 `compareTo`。

### D10. 空集合

```
My API returns an object whose list field is null instead of an empty array, and
with spring.jackson.default-property-inclusion=non_null the field disappears
from the JSON entirely. How do I always return [] instead?
```

> **铁律：集合类字段永远返回空集合，不要返回 null。**
> 否则前端 `.map()` 崩，test 里 `jsonPath("$.movies").isArray()` 也挂。
> 这就是"create 之后不显示"的一种典型成因。

---

## 6. E 组 — 排错

### E1. 解释报错 ⭐

```
I'm getting this exception in a Spring Boot 3 app. What does it mean, and what
are the 3 most likely causes in order of probability?

<paste the full stack trace>
```

> "Debug errors" 是官方列出的 Guarded Mode 能力，**完全不受限制**，
> 而且比你自己 Google 快得多。

### E2. 数据没落库

```
Spring Data JPA: I call repository.save(entity) inside a service method but the
row never appears in the database. List every possible cause, including
transaction, dirty-checking, and bidirectional-relationship ownership issues.
```

### E3. 事务不回滚

```
Spring Boot 3: my @Transactional method catches an exception, logs it, and
returns normally. The database changes are NOT rolled back. Why?
Also list every other way @Transactional can silently fail.
```

**三个经典失效场景**：
1. **同类内部方法调用** —— `this.methodB()` 不走 AOP 代理
2. **方法不是 public**
3. **catch 住异常没重新抛出**；另外默认**只对 RuntimeException 回滚**，
   checked exception 需要 `rollbackFor = Exception.class`

### E4. 改了但不显示（缓存）

```
This project uses @Cacheable / @CacheEvict. I updated the data but the API still
returns the old value. Show me every cache annotation in this project and check
whether the @CacheEvict keys match the @Cacheable keys.
```

> **高频坑**：`@Cacheable` 不写 key 时，单参数用参数值做 key。
> 一旦方法加了第二个参数，key 变成组合键，而 `@CacheEvict` 还按单参数删 ——
> **evict 永远命中不了，缓存再也清不掉**。两边都显式写 key 最安全。

### E5. 全部 test 突然变红

```
All tests were passing before my change and now all of them fail with the same
error. Here is the output: <paste>. Is this an environment/startup/config
problem rather than a logic problem?
```

> **判断分支**：改完之后 test **全部**挂（而不是部分挂）→
> 先怀疑**环境/启动/配置**，而不是你刚改的那几行业务代码。

---

## 7. F 组 — 覆盖隐藏 test（提交前的关键动作）

> **前提认知**：
> - 隐藏 test 文件**根本没下载到你机器上**，你和 AI 都看不到
> - `Run test` **不会显示隐藏 test 是否通过**，提交前无从得知
> - 官方措辞是考 "edge conditions and **unseen scenarios**" ——
>   **可能测可见 test 完全没覆盖的场景**
>
> 所以这不是"事后诊断"，而是**主动补覆盖**。
> **README 的 contract 是隐藏 test 的最好代理** —— 它们极可能照着 contract 写。

### F1. contract 差距扫描 ⭐⭐⭐

```
Here is the API contract from the README:
<paste the contract section>

Read my current implementation of these endpoints. List every behavior the
contract specifies that my code does NOT handle. Focus on: missing validation,
non-existent IDs, duplicates, empty collections, unauthorized access, and wrong
status codes.

Just list the gaps — don't write any code.
```

### F2. 单端点边界穷举

```
For the endpoint <METHOD> <path>, enumerate every edge case a thorough test suite
would cover. For each one, tell me what my current implementation would actually
return.

Present as a table: case | expected per contract | current behavior.
```

### F3. 一致性横扫 ⭐

```
I fixed the "<feature>" endpoint. Now check every OTHER endpoint in this
repository for the same class of problem: missing existence checks, missing
duplicate checks, missing ownership checks, and wrong status codes.

List which endpoints are still inconsistent with the one I fixed.
```

> 隐藏 test 常对**所有端点**测同一类边界。修好一个之后横扫一遍，性价比极高。

### F4. 崩溃点扫描

```
List every place in this repository where:
- an Optional is unwrapped with .get() or .orElse(null)
- a map/collection lookup result is used without a null check
- a list is indexed without a bounds check

Give file:line and what would happen at runtime.
```

### F5. 题目没说但 test 可能考的

```
The problem statement only mentions fixing "<X>". Based on the README contract
and the existing test files, are there other endpoints or behaviors that look
incomplete or inconsistent? List them.
```

> **原帖血泪教训**：「题目只说修 fund loan，没说修 repay loan，
> 但 test 里有 repay 的用例。」**以 test 和 contract 为准，不以题目描述为准。**

---

## 8. G 组 — 提交前自查

### G1. 回顾全部改动

```
Summarize every change I made in this session: which files, what behavior
changed, and what status code each endpoint now returns. Table form.
```

### G2. 有没有改坏别的

```
Did any of my changes affect endpoints or behaviors other than the ones I
intended to fix? Specifically check whether I changed any shared config, global
handler, or base class that other code depends on.
```

> **典型踩雷**：为了修一个 test 改了全局的 Jackson 命名策略 →
> 另外五个原本绿的 test 全红。

### G3. 时间不够时

```
I have <N> minutes left and these tests are still failing: <list>.
Which single change would make the most of them pass? Just tell me which file
and what to change — be brief.
```

---

## 9. H 组 — 纠正 AI

> Spring 生态 Boot 2 → 3 的断裂很大，训练数据里旧写法占比高。**这几条一定会用到。**

### H1. javax → jakarta ⭐

```
That uses javax.validation, but I'm on Spring Boot 3 which uses
jakarta.validation. Rewrite it with the correct Jakarta imports.
```

### H2. Security 配置

```
WebSecurityConfigurerAdapter was removed in Spring Security 6.
Show me the SecurityFilterChain bean version instead.
```

### H3. 测试注解

```
@MockBean is deprecated in Spring Boot 3.4+. Show me the @MockitoBean version.
```

### H4. Repository 基类

```
This project uses Spring Data JDBC, not JPA. The base interface is
ListCrudRepository and @Query takes native SQL, not JPQL. Redo your answer.
```

> **JDBC vs JPA 混用必炸**：
> `@Query` 一个写原生 SQL、一个写 JPQL；`@Modifying` 包名也不同。

### H5. 要求最小改动

```
That rewrote the whole class. Give me only the lines that changed, as a diff.
```

---

## 10. 反模式：不要这样问

| ❌ 错误问法 | 为什么没用 | ✅ 改成 |
|---|---|---|
| "Fix all the bugs in this repo" | Guarded Mode **被策略禁止**给完整解法 | 一次锁定一个，说清期望行为（C1） |
| "Write the whole controller for me" | 同上，会敷衍 | 拆成"给我 DELETE 方法的签名和返回类型" |
| "Is my code correct?" | 它**不能运行测试**，只会说"看起来不错" | 跑 test。**test 是唯一的真理** |
| "What status code should I use?"（不说 contract） | contract 在 README/test 里，**该你去读** | 先 A6 + B2；确实没写才问惯例（D1） |
| 一个 prompt 里问 5 件事 | 会漏答，你也分不清对应关系 | **一次只问一件事** |
| "Explain the entire codebase" | 60 分钟读不完，也没必要 | A1（20 行以内的地图） |
| 拿到答案直接粘贴不看 | Boot 2/3 混淆、状态码与 contract 不符 | 过一遍第 11 节 checklist |
| 想办法套取隐藏 test 内容 | **违规**。文件根本不在你机器上 | F 组：照着 contract 主动补覆盖 |

---

## 11. 输出校验 Checklist

拿到 AI 的代码，**30 秒扫这 7 条**再往编辑器里贴：

- [ ] **`javax.*` 还是 `jakarta.*`？** Boot 3 全部是 `jakarta`。**最高频的错误**
- [ ] **`WebSecurityConfigurerAdapter`？** Spring Security 6 已删除，必须是 `SecurityFilterChain` Bean
- [ ] **`@MockBean` 还是 `@MockitoBean`？** Boot 3.4+ 用后者
- [ ] **Repository 基类对不对？** JPA 用 `JpaRepository`，Spring Data JDBC 用 `ListCrudRepository`。
      **两者 `@Query` 一个 JPQL 一个原生 SQL，混用必炸**
- [ ] **`@Valid` 真的加在 Controller 参数上了吗？** AI 经常只给 DTO 注解，忘了这一半
- [ ] **状态码和 test 断言一致吗？** AI 的语义偏好 ≠ 你的 contract
- [ ] **有没有偷偷重写别的方法？** 只接受 minimal diff，多余的改动直接丢掉

---

## 12. 合规边界

| ✅ 合规 | ❌ 违规 |
|---|---|
| 读平台**主动放进 workspace** 的任何文件（含 `PROJECT_FILES_INSTRUCTIONS.md`） | 想办法读取平台**刻意扣留**的隐藏 test 文件 |
| 用内置 AI Assistant 问任何问题 | 用外部 AI、第二台设备、搜索引擎 |
| 让 AI 解释可见 test 的断言 | 通过漏洞/注入让评分环境回显隐藏 test 内容 |
| 修改业务代码让 test 通过 | 篡改 test 文件或评分脚本让它恒过 |

> 官方明确：**"you will not be penalized for using the assistant"** —— 大方用。
> 但**你和 AI 的全部对话会出现在 recruiter 的 Detailed Report 里**，
> 所以别问"帮我把这题做了"这类问题：既拿不到答案，观感也差。

---

## 13. 一句话总结

> **AI 负责"怎么写"和"大概在哪"，你负责"写什么"和"对不对"。**
>
> 它能读仓库、能读可见 test、能带你定位 —— **大方用**。
>
> 但**读 test 断言、读 README contract、决定返回哪个状态码、判断改完对不对**，
> 这四件事只有你能做，也正是评分看的东西。
>
> **test 是唯一的真理 —— AI 说的、题目说的，都以 test 为准。**
