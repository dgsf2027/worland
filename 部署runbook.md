# 沃朗租赁 · 部署 Runbook + 上线红线清单

> 状态:**deploy-ready**(镜像可构建、编排就位)。本 runbook 覆盖 本地 / 预发 / 生产 三环境。
> ⚠ 本波**未做真 prod 部署**:无服务器目标、SSO 凭据未就绪 —— 那是人工 / 门户团队的事(见文末「上线红线清单」)。
> 相关文件:`deploy/backend.Dockerfile` · `deploy/frontend.Dockerfile` · `deploy/frontend-nginx.conf` · `deploy/docker-compose.prod.yml` · `deploy/.env.example`。

---

## 0. 架构速览(一句话)

前端 nginx 容器(静态 SPA + `/api` 反代)→ 后端 Undertow:8082(context-path `/api`)→ MySQL8 + Redis7,四容器一套编排,数据存 docker volume。敏感值全由 `.env` 注入,永不入库。

```
[浏览器] → rent-web(nginx:80) ──/api/──→ rent-server(:8082/api) ──→ rent-mysql(:3306)
                                                              └────→ rent-redis(:6379)
```

---

## 1. 三环境

| 环境 | 数据库 | 起法 | 用途 |
|---|---|---|---|
| **本地 dev** | `vend-mysql`:3308/`worland_dev`(已有容器) | 后端 `mvn -s settings.xml spring-boot:run`(8082);前端 `node node_modules/vite/bin/vite.js`(5175,代理→8082) | 开发调试 |
| **预发 staging** | compose 内自带 mysql8(独立卷) | `docker compose -f deploy/docker-compose.prod.yml up -d --build`,`WEB_PORT=8088` | 上线前验收(镜像=生产) |
| **生产 prod** | compose 内自带 mysql8(独立卷)或对接中央 RDS | 同上 + 前置中央网关/HTTPS 终端 | 正式 |

本地 dev 环境变量:后端默认值已写死在 `application.yml`(`DB_URL` 默认 3308/worland_dev),本地无需 `.env`。

---

## 2. 一键起 compose(预发/生产)

```bash
cd deploy
cp .env.example .env          # ① 复制模板
vi .env                       # ② 填真值:DB_PASSWORD(强随机)、WEB_PORT、(可选)LLM_*/SSO_*
chmod 600 .env                # ③ 收权限(仅 owner 可读)
docker compose -f docker-compose.prod.yml up -d --build   # ④ 构建+起全栈
docker compose -f docker-compose.prod.yml ps              # ⑤ 看健康
```

- 首启:`rent-mysql` 健康后 `rent-server` 才起(`depends_on: service_healthy`);Flyway 随 boot 自动 V1→V14 建齐 43 表(空库无需手动 apply,见 §4)。
- 访问:`http://<host>:${WEB_PORT}`(默认 8088)。生产建议前置中央 Caddy/Nginx 终端 HTTPS,反代到 `rent-web:80`。

## 3. 健康检查

```bash
# 后端存活(容器内已 expose 8082,不对外)
docker compose -f deploy/docker-compose.prod.yml exec rent-server \
  sh -c 'wget -qO- http://localhost:8082/api/v1/health || echo DOWN'
# 经前端 nginx 反代(对外)
curl -s http://<host>:8088/api/v1/health          # 期望 200 JSON
# Flyway 是否全绿
docker compose -f deploy/docker-compose.prod.yml exec rent-mysql \
  mysql -uroot -p"$DB_PASSWORD" worland -e \
  "SELECT version,success FROM flyway_schema_history ORDER BY installed_rank;"
```

冒烟(上线后至少打这几枪,全 200):`/api/v1/health` · `/api/rent/quote/calc`(POST) · `/api/rent/suppliers` · `/api/rent/workbench` · `/api/rent/monthly-report` · `/api/rent/bi/occupancy`。

## 4. Flyway migration 上线说明

- **自动 apply**:Flyway 随后端 boot 执行(`spring.flyway.enabled=true`),空库首启自动 V1→V14 建齐 43 表 + 种子(rule_config 等)。**编排内自带 mysql 的首启无需手动 apply**。
- **`baseline-on-migrate=true`**:若接**已有数据**的现存库(非空、无 flyway 历史),Flyway 会先打 baseline 再增量,避免"非空库拒迁移"。
- **`out-of-order=true`**:允许低版本号迁移后到补跑(并行开发防互卡)。生产合库前确认无版本号冲突。
- **🔴 改 schema 的部署要点(对接外部/共享 DB 时)**:若某环境的 DB 是**共享 RDS 且不由本编排托管**,Flyway 自动 apply 仍会跑,但**跨团队库须先评审 migration SQL**;涉及 `ALTER TABLE` 破坏性变更(改列类型/删列/加 NOT NULL 无默认)必须:①先备份 ②灰度窗口 ③回滚 SQL 备好。新增 mysqlEnum 值、加列同理。**严禁**在无备份下对生产库跑破坏性 DDL。

## 5. 回滚

代码级回滚(不涉 schema 破坏性变更时):

```bash
git revert <bad_commit>            # 生成反向 commit(不改写 main 历史)
# 或回退到已知良好 tag/commit(仅在你自己的部署分支)
docker compose -f deploy/docker-compose.prod.yml up -d --build rent-server rent-web
```

- **Flyway 不支持自动 down**:已 apply 的迁移不能靠 Flyway 回退。schema 回滚须**手写反向 SQL** 并人工在库上执行(先备份)。
- 数据卷 `rent_mysql_data` 在 `down` 时**不删**(除非 `down -v`);`down -v` 会清库,生产严禁。

---

## 🔴 上线红线清单(钉死 · 需人工 / 门户团队 · AI 不自动执行)

> 以下每条**未完成前不得对外真上线**。AI 只做到 deploy-ready,红线由人/门户/运维团队落地。

1. **真 SSO 接入(2026-08-19 代码已接好,待凭据激活)**
   - 现状:代码按 `ole-portal-sso` 已实装(`common/sso/*`:`GET /api/v1/sso/callback` → 门户 exchange → RS256 验签 → 按 `portal_uid` 找/建 `yc_rent_auth_user` → 签本系统 token → 302 前端 `/sso/callback`);`SSO_ENABLED=false` 时模块不挂载,账号密码登录不受影响。
   - 待办:**门户团队**在 `yc_portal_system` 注册「曜石租赁」(回调地址 `https://<域名>/api/v1/sso/callback`)→ 下发 `app_id` / `client_secret` → 运维填 `deploy/.env` 的 `SSO_ENABLED=true / SSO_PORTAL_BASE_URL / SSO_APP_ID / SSO_CLIENT_SECRET`(见 `.env.example`)→ 重启后端容器,`V100__auth_user_portal_uid.sql` 由 **Flyway 随 boot 自动跑(幂等·INFORMATION_SCHEMA 守卫),禁止手动 apply**;起来后 `SHOW COLUMNS FROM yc_rent_auth_user LIKE 'portal_uid'` 验证即可。

2. **P0-C 网关剥离并重注入 X-User-* 头**
   - 边界网关(中央 Caddy/Nginx/SSO 层)**必须先剥离**客户端传入的任何 `X-User-Id/X-User-Name/X-User-Roles`,再由**可信** SSO 适配层重注入,严禁把浏览器伪造的 X-User-* 直通后端(否则越权 = 任意角色伪装)。`deploy/frontend-nginx.conf` 已占位置空这三头作提示;真隔离须在**最外层可信网关**做。

3. **rsync / 部署必 exclude `.env` / `.env.*`**
   - 任何 rsync 到 prod 必带 `--exclude='.env' --exclude='.env.*'`。覆盖 prod `.env` = 全站 outage(历史踩过)。`.env` 已在 `.gitignore`(仅 `.env.example` 例外入库)。

4. **prod DB 密码 / 密钥走 env,不入库**
   - `DB_PASSWORD`、`SSO_CLIENT_SECRET`、`LLM_API_KEY`、对象存储签名密钥等一律 `.env`(chmod 600)注入,**永不进 Git、永不进对话、AI 不代填明文**。上线生成强随机 `DB_PASSWORD`。

5. **审计法律级抗抵赖 + 签名密钥托管**
   - `audit_log` 已只追加(DB 触发器拒 UPDATE/DELETE,补 IP/URI/请求号指纹,M5-06)。**上线加固**:哈希链 / WORM 存储(防运维篡改),审计与业务库物理隔离;对象存储签名 HMAC 密钥迁 **KMS**(现为应用侧 rule 密钥,仅够 dev)。

6. **需提供 prod 服务器 / 域名目标**
   - 目标 ECS(IP/规格/磁盘)、对外域名、HTTPS 证书、中央网关反代配置、备份策略(mysql 卷 + 定时 dump)——均需**运维/用户**提供后方可真部署。

---

### 附:本波构建验证 & 未做项(诚实交代)
- docker 后端/前端镜像**本地真构建**结果见 `整合验收报告-全系统.md`「B. deploy-ready」。
- **未做**:真 prod 部署、真 SSO、推 registry、真跑整套 compose(仅验镜像可 build)。这些依赖上文红线 1/2/6 的人工前置。
