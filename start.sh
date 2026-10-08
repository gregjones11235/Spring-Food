#!/usr/bin/env bash
# 一键启动本地开发环境：Docker（PostgreSQL + Redis）→ 后端（:8080）→ 前端（:3000，自动打开浏览器）。
# 用法（Windows 用 Git Bash，macOS / Linux 用终端）：./start.sh
# 按 Ctrl+C 同时停止前端和后端；Docker 容器保留在后台（数据不丢），停止用 cd OnlineOrder && docker compose stop
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$ROOT/OnlineOrder"
FRONTEND_DIR="$ROOT/doordash-app"
BACKEND_LOG="$BACKEND_DIR/logs/backend.log"

command -v docker >/dev/null || { echo "未找到 docker，请先安装 Docker Desktop"; exit 1; }
command -v java >/dev/null   || { echo "未找到 java，请先安装 JDK 21"; exit 1; }
command -v npm >/dev/null    || { echo "未找到 npm，请先安装 Node.js"; exit 1; }
docker info >/dev/null 2>&1  || { echo "Docker 未运行，请先打开 Docker Desktop"; exit 1; }

echo "==> [1/4] 启动 PostgreSQL + Redis"
cd "$BACKEND_DIR"
docker compose up -d
# 后端启动时要执行建表脚本，数据库必须先就绪；Redis 挂了后端会自动降级，不用等
until docker compose exec -T db pg_isready -U postgres -q; do sleep 1; done

echo "==> [2/4] 编译后端"
./gradlew bootJar -q

echo "==> [3/4] 启动后端，日志写到 OnlineOrder/logs/backend.log"
mkdir -p "$(dirname "$BACKEND_LOG")"
JAR=$(ls build/libs/*.jar | grep -v -- '-plain.jar' | head -1)
java -jar "$JAR" > "$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!
trap 'echo "==> 停止后端"; kill $BACKEND_PID 2>/dev/null || true' EXIT

until curl -s -o /dev/null localhost:8080/restaurants; do
    if ! kill -0 $BACKEND_PID 2>/dev/null; then
        echo "后端启动失败，最后 30 行日志："
        tail -30 "$BACKEND_LOG"
        exit 1
    fi
    sleep 1
done
echo "    后端已就绪：http://localhost:8080"

echo "==> [4/4] 启动前端（第一次会先安装依赖）"
cd "$FRONTEND_DIR"
[ -d node_modules ] || npm ci
npm start
