#!/usr/bin/env bash
# 在已部署新版本的主机/平台终端运行；密码通过 stdin 传入容器，不落盘。
set -euo pipefail
set +x
task_deploy_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
task_username="${1:-test}"
read -r -s -p "请输入 ${task_username} 的临时密码（至少 6 位）: " task_temporary_password
printf '\n'
trap 'unset task_temporary_password' EXIT
printf '%s\n' "$task_temporary_password" | docker compose -f "$task_deploy_dir/docker-compose.prod.yml" exec -T worland-server \
  java -Dfile.encoding=UTF-8 -Dloader.main=top.aole.rent.common.auth.AdminRecoveryTool \
  -cp /app/app.jar org.springframework.boot.loader.PropertiesLauncher "$task_username"
