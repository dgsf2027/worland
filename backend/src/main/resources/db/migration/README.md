# Flyway Migration 手动 apply 七步(§4.16)

本项目 schema 变更走手写 SQL migration，**禁 JPA 自动 ddl**。开发期 Flyway 在应用启动时自动跑（`spring.flyway.enabled=true`）；**生产（prod）必须手动 apply**，drizzle/flyway 不会替你把 migration 推上去。

## 命名
`V{n}__{描述}.sql`（两个下划线）。版本号段各票分配，避免并行互卡；`out-of-order=true` 允许低版本后到补跑。

## 生产手动 apply 七步
1. 写 migration SQL（本目录），本地库先验证。
2. commit / 推送部署包。
3. rebuild 后端镜像（不带 `.env` 覆盖 prod）。
4. **SSH 到 prod，手动跑 `ALTER TABLE` / `CREATE TABLE`**（或 `flyway migrate` 指向 prod 库）。
5. `SHOW COLUMNS FROM yc_rent_xxx` 验证字段就位。
6. 真测 1 个完整业务流程（如报价接口逐格对平）。
7. 记录到 PHASE5-VERIFICATION-LOG.md。

缺任一步 = `SELECT *` 可能全挂（P39.0「数据没了」事故教训）。

## 部署红线（S0-04 / §2.9）
- 任何 rsync 到 prod 必须 `exclude .env / .env.*`（覆盖 prod .env = 全站 outage）。
- 可信网关必须**先剥离**客户端自带 `X-User-*` 头，再由网关按会话**重注入**；后端只信任网关注入身份。

## 当前 migration
- `V1__init.sql`：`yc_rent_rule_config`（版本化规则源）、`yc_rent_accounting_period`（期间锁）、`yc_rent_asset_event`（事件流基表）。
- `V2__seed_rule_config.sql`：灌入税率/转让率/租期/目标IRR/速算系数/管理费阶梯/杠杆参数等口径常量。
