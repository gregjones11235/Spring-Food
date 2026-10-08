#!/usr/bin/env bash
# 接口压测：用 k6 压一个接口，结果追加一行到 bench/results.md（带时间和 git commit），用于优化前后对比。
#
# 用法（在 OnlineOrder 目录下，后端已启动）：
#   scripts/bench.sh <路径> [并发=50] [时长=30s] [备注]
#   scripts/bench.sh /restaurants/menu 50 30s "N+1 修复前"
#   METHOD=POST BODY='{"a":1}' scripts/bench.sh /xxx 20 10s
#
# 环境变量：BASE_URL（默认 http://localhost:8080）、METHOD、BODY、K6（k6 可执行文件路径）
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ $# -lt 1 ]]; then
    sed -n '2,10p' "$0"
    exit 1
fi
URL_PATH=$1
VUS=${2:-50}
DURATION=${3:-30s}
NOTE=${4:-}
BASE_URL=${BASE_URL:-http://localhost:8080}
K6=${K6:-$(command -v k6 || true)}
if [[ -z $K6 ]]; then
    echo "需要 k6：https://grafana.com/docs/k6/latest/set-up/install-k6/ （WSL：sudo snap install k6）" >&2
    exit 1
fi

RESULTS=bench/results.md
SUMMARY=bench/tmp/last_summary.txt
mkdir -p bench/tmp
rm -f "$SUMMARY"

# handleSummary 只输出一行 markdown 表格单元，由 shell 拼上时间和 commit
"$K6" run --quiet --vus "$VUS" --duration "$DURATION" \
    --summary-trend-stats "avg,p(50),p(95),p(99),max" \
    -e URL="$BASE_URL$URL_PATH" -e METHOD="${METHOD:-GET}" -e BODY="${BODY:-}" -e OUT="$SUMMARY" - <<'EOF'
import http from 'k6/http';

const params = { headers: { 'Content-Type': 'application/json' } };

export default function () {
    http.request(__ENV.METHOD, __ENV.URL, __ENV.BODY || null, params);
}

export function handleSummary(data) {
    const m = data.metrics;
    const d = m.http_req_duration.values;
    const cells = [
        m.http_reqs.values.rate.toFixed(1),
        d['p(50)'].toFixed(1),
        d['p(95)'].toFixed(1),
        d['p(99)'].toFixed(1),
        (m.http_req_failed.values.rate * 100).toFixed(2) + '%',
    ];
    return { [__ENV.OUT]: cells.join(' | ') };
}
EOF

if [[ ! -s $SUMMARY ]]; then
    echo "k6 没有产出结果" >&2
    exit 1
fi
if [[ ! -f $RESULTS ]]; then
    printf '| 时间 | commit | 接口 | 并发 | 时长 | QPS | P50 ms | P95 ms | P99 ms | 错误率 | 备注 |\n' > "$RESULTS"
    printf '|---|---|---|---|---|---|---|---|---|---|---|\n' >> "$RESULTS"
fi
commit=$(git rev-parse --short HEAD 2>/dev/null || echo "-")
if ! git diff --quiet HEAD 2>/dev/null; then
    commit="$commit+dirty"
fi
line="| $(date '+%F %T') | $commit | ${METHOD:-GET} $URL_PATH | $VUS | $DURATION | $(cat "$SUMMARY") | $NOTE |"
echo "$line" >> "$RESULTS"
echo "$line"
