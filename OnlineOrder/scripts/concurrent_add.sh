#!/usr/bin/env bash
# Q8 并发加菜复现：清空购物车 → 并发 POST /cart 加同一个菜 N 次 → 直接查库核对总价和数量。
#
# 用法（在 OnlineOrder 目录下，后端已启动）：
#   scripts/concurrent_add.sh [次数=50] [并发=20]
#   MENU_ID=3 scripts/concurrent_add.sh 100 30
#
# 环境变量：BASE_URL（默认 http://localhost:8080）、EMAIL、PASSWORD（默认生成用户 user1@gen.example / 123456）、MENU_ID（默认 1）
set -euo pipefail
cd "$(dirname "$0")/.."

N=${1:-50}
P=${2:-20}
BASE_URL=${BASE_URL:-http://localhost:8080}
EMAIL=${EMAIL:-user1@gen.example}
PASSWORD=${PASSWORD:-123456}
MENU_ID=${MENU_ID:-1}
COOKIE=bench/tmp/cookie.txt
mkdir -p bench/tmp

psql_() { docker compose exec -T db psql -U postgres -d onlineorder -tA "$@"; }

# 1. 登录拿 session cookie
code=$(curl -s -c "$COOKIE" -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/login" \
    --data-urlencode "username=$EMAIL" --data-urlencode "password=$PASSWORD") || true
if [[ $code == 000 ]]; then
    echo "连不上 $BASE_URL，后端启动了吗？" >&2
    exit 1
elif [[ $code != 200 ]]; then
    echo "登录失败（HTTP $code）：$EMAIL" >&2
    exit 1
fi

# 2. 清空购物车
curl -s -f -b "$COOKIE" -X POST "$BASE_URL/cart/checkout" -o /dev/null

# 3. 并发加菜，统计每个请求的状态码
PRICE=$(psql_ -c "SELECT price FROM menu_items WHERE id = $MENU_ID")
start=$(date +%s%N)
# 不用 xargs -P 起 N 个 curl 进程：Git Bash 下起一个进程要 30ms 左右，比一次加菜请求（约 8ms）还慢，
# 请求实际上是一个接一个发的，几乎碰不到并发。改用一个 curl 进程内并行发送（-Z），?n=[1-N] 只是让 URL 重复 N 次
codes=$(curl -s -Z --parallel-immediate --parallel-max "$P" -o /dev/null -w "%{http_code}\n" -b "$COOKIE" \
    -H "Content-Type: application/json" -d "{\"menu_id\":$MENU_ID}" "$BASE_URL/cart?n=[1-$N]")
elapsed=$(awk -v s="$start" -v e="$(date +%s%N)" 'BEGIN { printf "%.2f", (e - s) / 1e9 }')

# 4. 直接查库核对（不走 GET /cart，避免缓存干扰）
echo "菜品 $MENU_ID 单价 $PRICE，并发 $P 加 $N 次，耗时 ${elapsed}s"
echo "HTTP 状态码：$(echo "$codes" | tr -d '\r' | sort | uniq -c | awk '{printf "%s×%s ", $2, $1}')"
echo "期望：total_price=$(awk -v n="$N" -v p="$PRICE" 'BEGIN { print n * p }')  item_rows=1  total_qty=$N"
psql_ -F ' ' -c "
SELECT 'actual：total_price=' || c.total_price,
       'item_rows=' || (SELECT count(*) FROM order_items oi WHERE oi.cart_id = c.id),
       'total_qty=' || (SELECT COALESCE(sum(quantity), 0) FROM order_items oi WHERE oi.cart_id = c.id)
FROM carts c JOIN customers cu ON cu.id = c.customer_id
WHERE cu.email = '$EMAIL'"
