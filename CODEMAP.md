# CODEMAP · 代码导航图

> 「**功能 → 文件**」的速查地图：想改某个功能，先在这里定位到要动哪几个文件，再进代码。
>
> 与其他文档的分工：`DESIGN_DOC.md` 讲**业务设计与数据模型**，`TODO.md` 讲**进度与验收**，本文只讲**代码在哪、怎么串**。
>
> 基准 commit：`f618720`（main）。结构性改动（新增模块 / 新增路由 / 新增迁移）后请同步更新本文。

---

## 1. 一句话概览

设备租赁公司的经营管理系统：**设备逐件建档 → 采购入库 → 签合同挂设备 → 按期收租 → 逾期催收 → 到期转让/处置 → 财务双账 + 折旧 + 分配 + 月报**。

定位是「智慧园区平台」下挂的可插拔板块，账号体系可走门户 SSO（默认关闭，走本地邀请码注册登录）。

单体仓库、**模块化单体**（不是微服务）：

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 2.7.18（JDK17 编译，字节码 release=8）· Undertow（已排除 Tomcat）· MyBatis-Plus 3.5.5 · Druid · MySQL 8 · Flyway · Knife4j · Hutool · POI |
| 前端 | Vue 3.4 + Vite 5 + TypeScript + Element Plus + Pinia + UnoCSS（pnpm） |
| 部署 | 四容器 compose：nginx 前端 → Undertow 后端 → MySQL 8 + Redis 7 |

关键坐标：groupId `top.aole`，包名 `top.aole.rent`，端口 **8082**，context-path **`/api`**，前端 dev 端口 **5175**，库表前缀 `yc_rent_`。

---

## 2. 目录结构速查

```
worland/
├── backend/                              Spring Boot 后端（Java 238 个文件）
│   ├── pom.xml                           依赖与编译配置
│   ├── settings.xml                      Maven 镜像源（本地构建用 mvn -s settings.xml）
│   └── src/main/
│       ├── java/top/aole/rent/
│       │   ├── common/                   横切能力（见 §3.2）
│       │   └── modules/                  25 个业务模块（见 §3.1）
│       └── resources/
│           ├── application.yml           全部配置与默认值（含上线红线开关）
│           └── db/migration/             Flyway 迁移 SQL（见 §5）
├── frontend/                             Vue 3 前端
│   ├── vite.config.ts                    别名 @ / dev 代理 /api→8082 / 构建版本号注入
│   └── src/
│       ├── router/index.ts               22 条路由 + 未登录拦截（见 §4）
│       ├── api/                          20 个后端接口封装
│       ├── views/                        21 个页面组件
│       └── utils/{request,session}.ts    axios 实例与本地会话
├── deploy/                               Docker 与上线产物（见 §6）
├── DESIGN_DOC.md  TODO.md  ADR-00{1,4}   设计 / 进度 / 架构决策（见 §8）
└── 部署runbook.md  上线前置清单.md        运维实操
```

---

## 3. 后端地图

### 3.1 业务模块（`modules/*`，25 个）

每个模块严格同构，找文件按这个套路走：

```
modules/<module>/
├── domain/       实体（MyBatis-Plus，@TableName("yc_rent_xxx")）
├── dto/          请求 / 响应 DTO
├── mapper/       MyBatis Mapper 接口
├── service/      业务逻辑，以及定时任务 *Scheduler.java
└── interfaces/   REST Controller
```

下表路径均省略 context-path，实际请求要加 `/api` 前缀（例如 `/api/rent/assets`）。

| 模块 | 核心实体 | Controller 路径 | 定时任务 |
|---|---|---|---|
| `asset` | Asset / AssetBom / AssetEvent / AssetDepreciationLine | `/rent/assets` | — |
| `supplier` | Supplier / SupplierSupply | `/rent/suppliers` | — |
| `customer` | Customer / Opportunity / CustomerFollowup | `/rent/customers` | — |
| `contract` | Contract / ContractAsset / ContractChange / RentSchedule / DepositLedger | `/rent/contracts` | — |
| `purchase` | PurchaseIn / PurchaseItem / Payable | `/rent/purchase` | — |
| `billing` | RentBill / OverdueCase / RepossessOrder / AccountingPeriod | `/rent/bills`、`/rent/overdue` | 收租单生成 01:00；逾期扫描 02:00 |
| `finance` | Voucher / VoucherLine / LedgerBook | `/rent/vouchers`、`/rent/depreciation/run`、`/rent/tax/threshold` | 折旧计提 每月 1 日 03:00 |
| `distribution` | Distribution / Investor | `/rent/distribution`、`/rent/investors` | 结账分配 每月 5 日 04:00 |
| `analytics` | 无实体（只读聚合） | `/rent/cashflow`、`/rent/analytics/return-attribution` | 兑付缺口扫描 05:00 |
| `monthly` | MonthlyReport | `/rent/monthly-report` | 报表包生成 每月 2 日 02:00 |
| `transfer` | TransferOrder / TransferOrderLine | `/rent/transfer` | — |
| `maintenance` | Maintenance | `/rent/maintenance` | — |
| `stocktake` | Stocktake / StockDiff | `/rent/stocktake` | — |
| `reminder` | Reminder | `/rent/reminders` | 合同到期提醒 06:00；跟进到期提醒 07:00 |
| `task` | Task | `/rent/tasks` | 任务派发 03:00 |
| `approval` | Approval | `/rent/approvals` | — |
| `roster` | UserRoleExt / Commission | `/rent/roster`、`/rent/commission` | — |
| `pdca` | ActionItem | `/rent/pdca` | 到期复查 04:00 |
| `bi` | 无实体（多维聚合） | `/rent/bi` | — |
| `workbench` | 无实体（首页聚合） | `/rent/workbench` | — |
| `quote` | 无实体（纯测算） | `/rent/quote` | — |
| `imports` | ImportJob | `/rent/imports` | — |
| `file` | FileObject | `/rent/files` | — |
| `ai` | LlmReply / AiScenes / ILlmService | `/rent/ai` | — |
| `rule` | RuleConfig | **无 REST** | — |

`rule` 是版本化的规则常量表，被各模块直接读取（费率、限额、账期口径等），不对外暴露接口。

**定时任务共 9 个 Scheduler**，登记表在 `common/web/CronRegistryController.java`（`GET /api/rent/crons`，登记了 8 条；`TaskDispatchScheduler` 未进登记表）。每个 Scheduler 都是「`@Scheduled` 入口 + `run*Cron` 执行体」双出口，执行体可由对应模块的手动触发接口调用，写单测不必等到点。

### 3.2 横切能力（`common/*`）

| 包 | 内容 |
|---|---|
| `auth` | 身份与权限全在这里：`AuthController`（登录/注册/签发 token）、`AuthTokenService`、`UserContextFilter`（解析身份写入 `UserContext`）、`RequireRole` + `RoleGuardInterceptor`（方法级角色守卫，越权 403 并留审计）、`DataScope`（行级 + 字段级可见域）、`PasswordHasher` |
| `sso` | 门户 SSO 适配层，`SSO_ENABLED=false` 时整个模块不挂载：`SsoController` `/v1/sso`、`PortalJwtVerifier`、`PortalPublicKeyCache`、`SsoClient` |
| `audit` | `AuditLog` / `AuditLogService`，敏感操作留痕 |
| `result` | 统一响应体 `R{code,message,data}` 与 `PageResult` |
| `exception` | `GlobalExceptionHandler` 全局异常转 `R` |
| `config` | CORS、MyBatis-Plus（逻辑删除 `isDeleted`）、SpringFox 兼容 |
| `web` | `HealthController` `/v1/health`、`CronRegistryController` `/rent/crons` |

---

## 4. 前端页面 ↔ 后端模块 对照

改一个功能时按这张表顺链路找齐三端文件：**页面 → API 封装 → 后端模块**。

| 路由 | 页面组件 | API 封装 | 后端模块 |
|---|---|---|---|
| `/`（重定向 → `/workbench`） | — | — | — |
| `/login` | `Login.vue` | 直接调 `/auth/*` | `common/auth` |
| `/sso/callback` | `SsoCallback.vue` | — | `common/sso` |
| `/workbench` | `Workbench.vue` | `workbench.ts` | `workbench` |
| `/purchase` | `Purchase.vue` | `purchase.ts` | `purchase` |
| `/task` | `Task.vue` | `task.ts` + `approval.ts` | `task` + `approval` |
| `/roster` | `Roster.vue` | `roster.ts` | `roster` |
| `/dashboard` | `Dashboard.vue` | 无（地基自检页） | — |
| `/quote` | `Quote.vue` | `quote.ts` | `quote` |
| `/supplier` | `Supplier.vue` | `supplier.ts` | `supplier` |
| `/customer` | `Customer.vue` | `customer.ts` | `customer` |
| `/asset` | `Asset.vue` | `asset.ts` + `supplier.ts` | `asset` |
| `/contract` | `Contract.vue` | `contract.ts` | `contract` |
| `/rent` | `Rent.vue` | `rent.ts` | `billing` |
| `/transfer` | `Transfer.vue` | `transfer.ts` | `transfer` |
| `/maintenance` | `Maintenance.vue` | `maintenance.ts` | `maintenance` |
| `/stocktake` | `Stocktake.vue` | `stocktake.ts` | `stocktake` |
| `/voucher` | `Voucher.vue` | `voucher.ts` | `finance` |
| `/cashflow` | `Cashflow.vue` | `distribution.ts` | `analytics` + `distribution` |
| `/monthly` | `Monthly.vue` | `monthly.ts` | `monthly` |
| `/bi-pdca` | `BiPdca.vue` | `bi.ts` + `pdca.ts` | `bi` + `pdca` |
| `/import` | `ImportCenter.vue` | `imports.ts` | `imports` |

注意 API 封装名与后端模块名并非一一对应：`rent.ts` 对 `billing`，`voucher.ts` 对 `finance`。`file` 模块无独立页面，由各页的上传控件调用。

**前端两个基础设施文件**：

- `utils/request.ts`：axios 实例，`baseURL=/api`；请求拦截注入 `Authorization: Bearer <token>`（`/v1/sso/*`、`/auth/login`、`/auth/register` 在免登白名单里）；响应拦截只在 `code===200` 时 resolve 出 `data`，并单独处理 blob 下载。
- `router/index.ts`：`beforeEach` 未登录一律跳 `/login`（`meta.public` 除外），`afterEach` 写页面标题。

---

## 5. 数据库与迁移

迁移文件在 `backend/src/main/resources/db/migration/`，Flyway 随 boot 启动执行（`baseline-on-migrate: true`，`out-of-order: true`）。

| 版本 | 内容 |
|---|---|
| `V1__init.sql` | 基础表与租户/用户骨架 |
| `V2__seed_rule_config.sql` | 规则常量种子数据 |
| `V3__supplier.sql` | 供应商 |
| `V4__customer.sql` | 客户 / 商机 / 跟进 |
| `V5__asset.sql` | 设备台账 / BOM / 事件流 |
| `V6__contract.sql` | 合同 / 挂设备 / 租金计划 / 押金 |
| `V7__purchase.sql` | 采购入库 / 应付 |
| `V8__rent_bill.sql` | 收租单 / 逾期 |
| `V9__voucher_double_book.sql` | 双账凭证 / 账套 |
| `V10__distribution_investor.sql` | 分配 / 投资人 |
| `V11__monthly_report_llm.sql` | 月报与 LLM 产出 |
| `V12__transfer_maintenance_stocktake.sql` | 转让 / 维保 / 盘点 |
| `V13__task_approval_roster_commission.sql` | 任务 / 审批 / 花名册 / 提成 |
| `V14__pdca_import_file_audit_append.sql` | PDCA / 导入 / 文件 / 审计 |
| `V99__auth_user.sql` | 账号体系（邀请码注册） |
| `V100__auth_user_portal_uid.sql` | 账号绑定门户 uid |
| `V101__supplier_bank_details.sql` | 供应商银行账户信息 |

`V99+` 是与业务表并行的账号体系版本号段，刻意留出间隔避免并行开发撞号。

**字段约定**：表前缀 `yc_rent_`；逻辑删除字段 `isDeleted`（1 删 0 在），**不做物理删除**；金额单位统一为**元**；时区 `Asia/Shanghai`。

---

## 6. 鉴权与部署要点

### 6.1 身份链路（改权限前必读）

现行链路：前端登录拿 `/api/auth` 签发的 **Bearer token** → `UserContextFilter` 按 token 派生身份写入 `UserContext` → `RoleGuardInterceptor` 按 `@RequireRole` 卡权限 → service 层用 `DataScope` 做行级 / 字段级过滤。

`X-User-*` 占位头是 ADR-001 遗留的开发期通道，由 `rent.auth.placeholder-headers-enabled` 控制，**默认 false**。若为兼容旧网关而打开，则**网关必须先剥离客户端自带的这些头再重新注入**，否则任何人可伪造身份（`TODO.md` 记为 `S0-04 [P0·上线红线]`）。

### 6.2 上线红线（`application.yml` 里必须改的默认值）

| 配置 | 默认值 | 生产要求 |
|---|---|---|
| `rent.auth.invite-code` | `dgsd1985`（硬编码） | 必须改，首个注册账号自动成为「老板」 |
| `rent.auth.secret` | `rent-dev-secret-change-me` | 必须配 `RENT_AUTH_SECRET` |
| `rent.auth.placeholder-headers-enabled` | `false` | 保持 false |
| `spring.datasource.password` | `vend123` | 必须配 `DB_PASSWORD` |

其余红线见 `上线前置清单.md`。

### 6.3 其他约定

- **Flyway 已入库的迁移文件禁止修改**，加字段只能新增版本号。
- **派生字段必须有唯一写手**：`asset.book_value` 由折旧计提定时任务回填，禁止业务代码直写；设备状态由 `asset_event` 事件流驱动。
- **LLM key 只从 `.env` 注入不入库**；`llm.base-url` / `api-key` / `model` 三项全非空才真调外部模型，否则自动回退 mock，零外呼。

### 6.4 部署产物

| 文件 | 作用 |
|---|---|
| `deploy/backend.Dockerfile` | 后端镜像 |
| `deploy/frontend.Dockerfile` | 前端镜像（build args 透传 `GIT_COMMIT` 等版本信息） |
| `deploy/frontend-nginx.conf` | 静态 SPA + `/api` 反代 |
| `deploy/docker-compose.prod.yml` | 四容器编排（web / server / mysql / redis） |
| `deploy/.env.example` | 环境变量模板，复制为 `.env` 填真值 |

健康检查：`GET /api/v1/health`。接口文档：`http://127.0.0.1:8082/api/doc.html`（Knife4j）。

---

## 7. 本地跑起来

**以 `部署runbook.md` 为准**，下面是最短路径摘录。

后端（默认连本地 MySQL `127.0.0.1:3308/worland_dev`，本地无需 `.env`）：

```bash
cd backend
mvn -s settings.xml spring-boot:run
```

前端（dev 端口 5175，`/api` 代理到 8082）：

```bash
cd frontend
pnpm install
pnpm dev
```

预发 / 生产用 compose 一键起：

```bash
cd deploy
cp .env.example .env && vi .env && chmod 600 .env
docker compose -f docker-compose.prod.yml up -d --build
```

---

## 8. 仓库其他文档

| 文件 | 作用 |
|---|---|
| `DESIGN_DOC.md` | 业务架构与数据模型设计，字段级说明 |
| `TODO.md` | Phase 2 功能拆解，含 P0 上线红线清单 |
| `PHASE5-VERIFICATION-LOG.md` | 每个 ticket 的测试留证 |
| `ADR-001-SSO占位头适配层.md` | 身份适配层的架构决策 |
| `ADR-004-折旧与账套口径.md` | 折旧与双账口径的架构决策 |
| `部署runbook.md` | 三环境部署实操 |
| `上线前置清单.md` | 上线前检查项 |
| `整合验收报告-全系统.md`、`交付报告-地基+冲刺0+M1.md` | 阶段验收报告 |
| `系统方案/`、`运营手册/`、`新租赁科技有限公司/` | 需求、运营与公司层面文档 |

---

## 9. 改代码时的建议路径

**加一个接口**
1. `modules/<m>/dto/` 加请求 / 响应 DTO
2. `modules/<m>/service/` 写业务逻辑，敏感字段过 `DataScope`
3. `modules/<m>/interfaces/<X>Controller.java` 挂 `@GetMapping` / `@PostMapping`，返回 `R` 或 `PageResult`，敏感操作加 `@RequireRole`
4. `frontend/src/api/<m>.ts` 加封装，页面里调

**加一个字段**
1. `db/migration/` **新增**一个版本号的 SQL（绝不改历史文件）
2. `modules/<m>/domain/` 实体加字段
3. DTO、service、前端页面按需透出
4. 若是派生字段，明确唯一写手（定时任务或事件流），并在注释里写清楚

**改权限**
- 角色能不能调某个接口 → `common/auth/RequireRole` 注解 + `RoleGuardInterceptor`
- 角色能看到哪些行 / 哪些字段 → `common/auth/DataScope`
- 别在 service 里散写角色字符串硬编码，一律收敛到上面两处

**改定时任务**
1. `modules/<m>/service/<X>Scheduler.java` 改 cron 或执行体
2. 同步 `common/web/CronRegistryController.java` 的登记表
3. 保持「`@Scheduled` 入口 + `run*Cron` 执行体」双出口，方便手动触发和单测

**加一个页面**
1. `frontend/src/views/` 加组件
2. `frontend/src/router/index.ts` 加路由（`meta.title` 会写进页面标题）
3. `frontend/src/api/` 加对应封装
4. 回来更新本文 §4 的对照表
