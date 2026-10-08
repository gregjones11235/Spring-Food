#!/usr/bin/env bash
# 打包前端 → 构建 Spring Boot 镜像 → 推送到 AWS ECR → 触发 ECS 重新部署
# 用法: ./deploy.sh
set -euo pipefail

REPOSITORY_BASE_URI="965220895098.dkr.ecr.us-east-2.amazonaws.com"
AWS_REGION="us-east-2"
IMAGE_NAME="onlineorder"
IMAGE_TAG="latest"
FULL_IMAGE="${REPOSITORY_BASE_URI}/${IMAGE_NAME}:${IMAGE_TAG}"
ECS_CLUSTER="default"       # Express Mode 未指定集群时用 default
ECS_SERVICE="onlineorder"   # 创建服务时填的 Name

# 切到脚本所在目录（OnlineOrder 根目录），保证从任何位置运行都正确
cd "$(dirname "$0")"
FRONTEND_DIR="../doordash-app"
PUBLIC_DIR="src/main/resources/public"

# 0. 前置检查
command -v npm >/dev/null    || { echo "未找到 npm，请先安装 Node.js"; exit 1; }
command -v docker >/dev/null || { echo "未找到 docker，请先安装并启动 Docker Desktop"; exit 1; }
command -v aws >/dev/null    || { echo "未找到 aws CLI，请先安装并执行 aws configure"; exit 1; }
docker info >/dev/null 2>&1  || { echo "Docker 未运行，请先打开 Docker Desktop"; exit 1; }

# 1. 打包前端，并替换后端 public/ 里的旧版本
echo "==> [1/5] 打包前端 ${FRONTEND_DIR}"
(
  cd "${FRONTEND_DIR}"
  [ -d node_modules ] || npm ci
  npm run build
)
rm -rf "${PUBLIC_DIR}"
cp -r "${FRONTEND_DIR}/build" "${PUBLIC_DIR}"

# 2. 构建镜像（等同于 IDEA 里的 bootBuildImage --imageName=...）
echo "==> [2/5] 构建镜像 ${FULL_IMAGE}"
./gradlew bootBuildImage --imageName="${REPOSITORY_BASE_URI}/${IMAGE_NAME}"

# 3. 登录 ECR
echo "==> [3/5] 登录 ECR (${AWS_REGION})"
aws ecr get-login-password --region "${AWS_REGION}" \
  | docker login --username AWS --password-stdin "${REPOSITORY_BASE_URI}"

# 4. 推送
echo "==> [4/5] 推送镜像"
docker push "${FULL_IMAGE}"

# 5. 让 ECS Express Mode 服务重新拉取 latest 镜像
#    （服务还没创建时这一步会失败，但镜像已经推上去了）
echo "==> [5/5] 触发 ECS 重新部署 ${ECS_CLUSTER}/${ECS_SERVICE}"
aws ecs update-service --region "${AWS_REGION}" \
  --cluster "${ECS_CLUSTER}" --service "${ECS_SERVICE}" \
  --force-new-deployment --query 'service.serviceName' --output text

echo "✅ 完成：${FULL_IMAGE}，新版本约需几分钟完成滚动替换"
