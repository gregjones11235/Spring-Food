# Spring Food · Online Order

## QUICK START

```bash
./start.sh
```

> Windows 请在 **Git Bash** 里执行；运行前先打开 Docker Desktop。
> 脚本会依次启动 Docker（PostgreSQL + Redis）、后端（:8080）和前端（:3000），前端就绪后会自动打开浏览器。按 `Ctrl+C` 同时停止前后端。

默认账号（密码都是 `123456`）：

| 角色 | 账号 |
|---|---|
| 顾客 | `foo@mail.com` |
| 商家（种子餐厅） | `merchant1@mail.com`、`merchant2@mail.com`、`merchant3@mail.com` |

---

外卖订餐网站：顾客浏览餐厅、加购物车、下单；商家在后台接单、拒单、搜索订单。在此基础上做了一系列 SQL 优化和 Redis 实验。

## 项目结构

| 目录 | 说明 |
|---|---|
| `OnlineOrder/` | 后端：Spring Boot 3.5、Java 21、PostgreSQL、Redis（集群模式） |
| `doordash-app/` | 前端：React 18 + Ant Design 4，开发时通过 `proxy` 把请求转发到 `:8080` |
| `start.sh` | 一键启动本地开发环境 |

## 环境要求

- Docker Desktop
- JDK 21
- Node.js（已在 22 上测试）
- Windows 需要 Git Bash（安装 Git for Windows 时自带）

## 常用操作

以下命令都在 `OnlineOrder/` 目录下执行。

```bash
# 造数：5000 家餐厅、10 万用户、200 万订单（需要先启动过一次后端，让它建好表）
scripts/gen_data.sh

# 用数据库里的历史订单校准各店热销榜（只能在本机调用）
curl -X POST localhost:8080/ops/hot-items/rebuild

# 查看后端日志
tail -f logs/backend.log

# 进入 Redis / 数据库命令行
docker compose exec redis redis-cli --raw
docker compose exec db psql -U postgres -d onlineorder

# 停止容器（数据保留）
docker compose stop
```

## 部署

`OnlineOrder/deploy.sh`：打包前端、构建镜像并部署到 AWS ECS
