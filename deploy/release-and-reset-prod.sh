#!/usr/bin/env bash
# =========================================================================
# 曜石租赁（Worland）生产一键上线 + 可选历史业务清零脚本
#
# 用法（在生产服务器 worland 仓库根目录执行）：
#   1) 仅更新上线（不删任何数据）：
#        bash deploy/release-and-reset-prod.sh
#   2) 备份 + 更新上线 + 清空除「客户CRM / 供应商档案 / 供应商考察 / 账号规则」外的其余历史数据：
#        bash deploy/release-and-reset-prod.sh --with-reset
# =========================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

WITH_RESET=false
if [[ "${1:-}" == "--with-reset" ]]; then
  WITH_RESET=true
fi

if [[ ! -f deploy/.env ]]; then
  echo "❌ 缺少 deploy/.env，请先确认生产环境变量文件存在。"
  exit 1
fi

set -a
# shellcheck disable=SC1091
source deploy/.env
set +a

DB_NAME="${MYSQL_DATABASE:-worland_prod}"
WEB_PORT="${WEB_PORT:-8088}"
BACKUP_DIR="deploy/backups"
mkdir -p "$BACKUP_DIR"
TS="$(date +%Y%m%d_%H%M%S)"
BACKUP_FILE="${BACKUP_DIR}/worland_pre_release_${TS}.sql.gz"

echo "=== [1/5] 拉取最新 main 代码 ==="
git pull --ff-only origin main

echo "=== [2/5] 全库备份（强制执行，失败自动中止） ==="
docker exec worland-mysql sh -c "mysqldump -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --single-transaction --routines --triggers \"${DB_NAME}\"" | gzip > "${BACKUP_FILE}"
if [[ ! -s "${BACKUP_FILE}" ]]; then
  echo "❌ 数据库备份文件为空，已中止上线！"
  exit 1
fi
echo "✅ 备份完成：${BACKUP_FILE} ($(du -h "${BACKUP_FILE}" | cut -f1))"

echo "=== [3/5] 构建并重启前后端容器（含 V129~V132 迁移与最新前端） ==="
export GIT_COMMIT="$(git rev-parse HEAD)"
export GIT_COMMIT_TIME="$(git log -1 --format=%cI)"
export GIT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build

echo "=== [4/5] 等待后端健康检查与 Flyway V132 迁移就绪 ==="
for i in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:${WEB_PORT}/api/v1/health" | grep -q "worland-rent backend alive"; then
    echo "✅ 后端健康检查通过（版本 ${GIT_COMMIT:0:7}）"
    break
  fi
  if [[ "$i" -eq 40 ]]; then
    echo "❌ 后端启动超时，请检查 docker logs worland-server"
    exit 1
  fi
  sleep 3
done

if [[ "$WITH_RESET" == "true" ]]; then
  echo "=== [5/5] 执行安全清理（保留客户CRM / 供应商档案 / 供应商考察 / 账号规则，清空其余历史数据） ==="
  docker exec -i worland-mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --default-character-set=utf8mb4 \"${DB_NAME}\"" < deploy/reset-business-keep-crm-supplier.sql
  echo "✅ 安全清理已完成！如需回滚可使用备份文件：${BACKUP_FILE}"
else
  echo "=== [5/5] 未传 --with-reset，跳过历史业务清零（全部旧数据已原样保留） ==="
fi

echo "🎉 上线流程全部完成！"
