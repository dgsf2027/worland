# 沃朗租赁板块 · 部署 Runbook

> 状态：deploy-ready（生产镜像可构建）。**真 prod 上线仍需下方"上线红线清单"里的人工/门户团队动作**——AI 不能代做。

## 一、本地一键起（开发/验收）
```bash
# 后端(需 JDK17)
cd backend && export JAVA_HOME=$(/usr/libexec/java_home -v 17) && mvn -s settings.xml spring-boot:run
# 前端
cd frontend && node node_modules/vite/bin/vite.js --host
# 浏览器 http://localhost:5175  · 后端 8082 · DB=docker vend-mysql:3308/worland_dev
```

## 二、生产编排（自带 MySQL·数据物理隔离）
```bash
cp deploy/.env.example deploy/.env      # 填真值,chmod 600
docker network create worland-edge      # 首次:供中央网关反代
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build
# 健康:docker exec worland-server curl -s localhost:8082/api/v1/health  → code:200
```
- 后端镜像：多阶段（maven:3.9-eclipse-temurin-17 构建 → eclipse-temurin:8-jre 运行，字节码 target=8）。
- 前端镜像：node:22-alpine 构建 → nginx:alpine 托管，`/api` 反代 worland-server:8082。
- **Flyway 随 boot 自动 apply V1→V14**（out-of-order 已开）。

## 三、Flyway / schema 上线要点
- 首次上线：空库 boot 即自动建全部 43 张表；`baseline-on-migrate=true`。
- 改 schema 的迭代：新增 `V15__xxx.sql` 递增版本；**严禁改已发布的迁移文件**。
- 参照 vending `flyway-prod-checklist.md`：升级前备份 prod 库、灰度、失败回滚。

## 四、回滚
```bash
git revert <bad_commit> && docker compose -f deploy/docker-compose.prod.yml up -d --build
```
不可逆 schema 变更需先 restore DB 备份。**main 永不 force-push/reset。**

---

## 🚨 五、上线红线清单（钉死 · 需你 / 门户团队 · AI 不能代做）
| # | 红线 | 谁做 | 说明 |
|---|---|---|---|
| 1 | **prod 服务器 / 域名目标** | 你 | 至今本地(docker)跑，无线上 ECS/域名。给目标才能真推 |
| 2 | **真 SSO 凭据** | 门户团队 | 代码已按 `ole-portal-sso` 接好(2026-08-19,`SSO_ENABLED=false` 默认不挂载)。门户团队在 `yc_portal_system` 注册「曜石租赁」(callback_url=`https://<域名>/api/v1/sso/callback`)+ 下发 `app_id`/`client_secret`；运维填 `.env` 的 `SSO_ENABLED/SSO_PORTAL_BASE_URL/SSO_APP_ID/SSO_CLIENT_SECRET` 即生效。**凭据/密钥类 AI 不能代申请、代输入** |
| 3 | **P0-C 网关剥离 X-User-\*** | 你/运维 | 已在 `frontend-nginx.conf` 清空 `X-User-*`；上游中央网关也须剥离，否则可伪造 `X-User-Role:老板` 绕 RBAC |
| 4 | **rsync/部署 exclude .env** | 运维 | 覆盖 prod `.env` = 全站 outage；`.env` 已入 .gitignore |
| 5 | **密钥走 env/KMS** | 你 | DB 密码、`FILE_SIGN_SECRET`、LLM key 全走 `.env`/KMS，不入库 |
| 6 | **审计法律级抗抵赖** | 后续 | 当前 audit_log 只追加(触发器拒改)+请求指纹；哈希链/WORM 为占位待办 |
| 7 | **真 LLM key（可选）** | 你 | 现全 mock 不外呼；接真模型填 `LLM_*`，注意成本护栏 |

**部署授权**：真 prod 部署属对外不可逆动作，需你明确说"部署"并提供目标后执行。
