# ADR-001：租赁板块 SSO 采用占位头 UserContext 适配层

- 状态：已接受（2026-08-08）
- 关联：DESIGN_DOC.md 第四/六节；Phase 0 需求摘要第八节

## 背景
用户要求"现在就对接平台 SSO"。经代码调研（园区小卖账房 vending-erp）：
- 平台**无真 SSO**——纯占位头：`X-User-Name`（经手人）、`X-User-Role`（角色，仅"老板 vs 非老板"一道 `assertBossRole` 守卫）、`user_id=0L` 硬编码占位；`sso` 模块是空壳，`application.yml sso.enabled=false`。
- 平台**有权威 SSO 契约**：澳乐门户（aole-portal），固化为 skill `ole-portal-sso`（端点 `/v1/sso/auth-url|exchange|public-key`，JWT RS256，共库消费 `yc_portal_member/tenant/system`）。
- **硬阻塞**：真接需门户团队在 `yc_portal_system` 注册子系统并下发 `app_id`/`client_secret`——属红线，AI 不能代申请；且此待办平台侧尚未列。

## 决策
租赁板块**现在不单独真接 SSO**，采用占位头适配层：
1. 复用小卖账房现成占位机制（`Operators.resolve(X-User-Name)` + `X-User-Role` 守卫 + `yc_vend_user_role` 角色表 + `user_id=0` 占位），两板块共用同一身份口径。
2. 把身份获取收敛到**一个 `UserContext`/`CurrentUser` provider**（内部现读头、将来读 JWT），业务代码只依赖此抽象。
3. 前端沿用 `request.ts` 已预留的 `Authorization: Bearer` 挂载点。
4. SSO 就绪时走 `ole-portal-sso` skill（场景 1 共库），**全平台（售卖机+租赁）一起切、只改适配层一处**。

## 理由
- 平台整体未接 SSO，租赁单独真接会造成两套身份口径不一致。
- 真接被凭据申请硬阻塞（红线，非 AI 可完成）。
- 适配层让"未来一处替换"成本最低，与平台 `assertBossRole` 注释承诺一致。

## 后果
- ✅ 不阻塞开发；与小卖账房一致；未来切换局部化。
- ⚠️ 上线前必须完成红线待办：**门户团队注册子系统 + 下发 app_id/client_secret + 提供门户 base_url 与测试账号**（补进《需要老板做的事》）。
- ⚠️ 占位期权限较粗，需在板块内补数据隔离（行级 owner/project + 字段级成本价隔离）。

## 备选（已放弃）
- 现在就真接 SSO：被凭据申请阻塞、不经济、两板块口径不一致。
