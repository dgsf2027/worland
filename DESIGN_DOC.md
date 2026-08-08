# 沃朗科技租赁板块 · DESIGN_DOC

> 版本：v0.2（已过 Phase 1.5 设计评审，按 8 P0 修订）　|　日期：2026-08-08
> 上游：Phase 0 需求摘要（已确认）+《业务流与操作流》+《开发方案》+《设立方案书 V14.0》
> 流程：F1 · Phase 1。本文过设计评审委员会 + 用户确认后进 Phase 2 拆解。

---

## 一、定位与目标
智慧园区平台内的「租赁板块」，承接沃朗科技业/财/人/事/物 + 时/钱/风/关系/保密。与园区小卖账房同平台、同栈、同设计七律。库表前缀 `yc_rent_`，先独立运行、后嵌入主框架。

## 二、系统架构

```
┌───────────────────────────── 智慧园区平台 ─────────────────────────────┐
│  账号 / 组织 / 角色权限 / SSO（复用；租赁板块先占位头适配层，就绪换真SSO） │
├──────────────────────────────────────────────────────────────────────┤
│  租赁板块前端 Vue3 + Vite                                                │
│   路由：工作台/报价/合同/供应商/客户CRM/设备/收租/采购/转让/维保/凭证/     │
│         现金流/月度报表/任务/花名册/BI  （17 视图，见 UI mockup V5）      │
├──────────────────────────────────────────────────────────────────────┤
│  租赁板块后端 Spring Boot 2.7（JDK8 target）                             │
│   Controller → Service → Mapper(MyBatis/JPA) → MySQL8                   │
│   核心域：Asset/Contract/RentSchedule/Voucher/Distribution/CRM/Risk     │
│   横切：鉴权过滤器(UserContext) · 数据隔离 · 单据事件流 · 规则引擎 · AI网关 │
├──────────────────────────────────────────────────────────────────────┤
│  MySQL8 (yc_rent_*)  ·  Redis(缓存/幂等)  ·  对象存储(合同/现场照)         │
│  AI 网关(可换模型·透明四件套·idempotent)  ·  导入中心(映射/预览/去重)      │
└──────────────────────────────────────────────────────────────────────┘
```

分层：表现层(Vue) / 接口层(REST) / 领域服务层 / 数据访问层 / 横切(鉴权·隔离·事件·规则·AI)。

## 三、技术栈选型与理由
| 选型 | 理由 | 放弃的备选 |
|---|---|---|
| Spring Boot 2.7 + Vue3 + MySQL8 | 与园区小卖账房同栈，组件/经验/团队复用，同平台嵌入无缝 | Odoo/ERPNext（与同栈/嵌入冲突，本土化成本高）；图南 TS 栈（与小卖账房不同栈） |
| MyBatis（或 JPA）+ 手写 migration | 复杂账务/双账 SQL 可控；migration 手动 apply（§4.16 教训） | 纯 JPA 自动 ddl（账务表不可控） |
| Redis | LLM 幂等、报表缓存、数据新鲜度 | 无 |
| 占位头 UserContext 适配层 | 平台 SSO 未就绪，先 X-User 占位、就绪换真 SSO，不阻塞 | 现在硬接 SSO（无可接对象） |

## 四、核心数据模型（重点：逐件建档 · 合同挂 N 台）

> 前缀 `yc_rent_`；金额单位=元（schema 注释标注）；派生数据只由事件流/单据算，禁直写。

### 4.1 物（设备与配件）
- **`asset` 设备（逐件·一台一条）**：`id, serial_no, category, model, market_price(市场价/元), purchase_price(集采价/元), supplier_id, status, project_id, monthly_labor_value(月替代人工价值/元·价值定价核心输入), replace_headcount(替代人数), self_purchase_payback(=market_price/monthly_labor_value·月), purchase_in_id, created_at`
  - `@owner=资产事件流；@writes=asset_event 驱动 status`
  - `current_holder_customer_id`：派生，由合同生效/关闭事件唯一维护（或即时算，勿冗余 drift）
  - `book_value`：**经营口径 ops 账**，由 `asset_depreciation_line` 即时算/回填（税务账走应收，不在此）
  - `residual_value`：**不落列**，由 `_helpers` 按部件残值×折旧曲线即时算（防 stale·§4.17）
- **`asset_bom` 配件树（自引用多级）**：`id, asset_id, parent_id, name, qty, unit_cost, supplier_id, life_years, warranty_until, repairable, fault_count`
- **`asset_event` 资产事件流（留痕）**：`id, asset_id, event_type(采购/投放/在租/维修/待转让/转让/收回待处置/再投放/二手/报废), ref_doc, biz_time`
- 状态机：采购→投放→在租→待转让→已转让｜(收回待处置→再投放/二手/报废)。

### 4.2 业（供应商/客户CRM/合同）
- **`supplier` 供应商**：`id, name, contact, status(接触/试样/入库/主供/备供/淘汰)`
- **`supplier_supply` 供货矩阵**：`id, supplier_id, item_type(整机/配件), item_name, category, quote_price, first_pay_ratio, account_days, no_interest, score_quality, score_delivery, score_service, score_price, score_term, score_total`
- **`customer` 客户（CRM）**：`id, name, contact, phase(线索/跟进/商机/成交/在租/流失), value_tier(战略/普通/观察), rating(A/B/C), owner_user(负责业务/公海=null), target_irr, credit_limit, deposit_months, industry`
- **`customer_followup` 跟进时间线**：`id, customer_id, user, method(电话/拜访/微信), content, result, next_follow_date`
- **`opportunity` 商机**：`id, customer_id, category, est_amount, win_prob, est_close_month`
- **`contract` 合同**：`id, no, customer_id, term_months, month_rent(元), deposit(元), end_transfer_price(元), target_irr, nature='分期收款销售', status(草稿/生效/到期转让/关闭)`
- **`contract_asset` 合同↔设备（N 台）**：`id, contract_id, asset_id, alloc_rent(单台月租分摊·单一真值)` （一份合同挂 N 台；`contract.month_rent`=合同合计；单台 P&L 按 alloc_rent 摊）

### 4.3 财 · 钱（租金/收付/凭证/分配）
- **`rent_schedule` 租金计划（应收/收款计划，逐期明细行）**：`id, contract_id, period_no, due_date, amount(元), rent_bill_id, plan_status(未到期/已生成单)`
  - ⚠️ 修正：这是**应收计划**≠折旧计划；收款/逾期/红冲态归 `rent_bill`（收款唯一真相源），本表只留计划态。Odoo `account.asset.line` 对应的是下面的**折旧表**，勿混。
- **`asset_depreciation_line` 折旧计划（经营口径·补 book_value 写手）**：`id, asset_id, book(ops), period_no, depr_amount, book_value_after, voucher_id`（`asset.book_value` 由本表即时算/回填，解决"派生无写手"stale 风险）
- **`rent_bill` 收租单**：`id, contract_id, period_no, amount, received_amount, matched_at, status(待收/已核销/逾期/红冲), reversal_of`
- **`overdue_case` 逾期案**：`id, rent_bill_id, step(延期/罚息/锁机/收回/关闭), penalty_amount, next_action, deadline, owner`
- **`purchase_in` 采购入库 / `purchase_item`（逐件）**：采购单绑 `contract_id`（先签约后采购校验）；item 生成逐件 `asset`
- **`payable` 应付计划**：`id, purchase_in_id, stage(首付/验收/尾款), due_date, amount, status`
- **`transfer_order` 转让/处置单（头）**：`id, contract_id, type(转让/收回/二手/报废), total_price, total_gain, status`
- **`transfer_order_line` 逐台处置行**：`id, transfer_order_id, asset_id, book_value(快照·不回写), transfer_price, gain, voucher_id`（拆行支撑逐台损益/部分处置，与 `contract_asset` 同构）
- **`voucher` 凭证 / `voucher_line`**：`id, source_doc_type, source_doc_id, book(税务口径/经营口径), amount, is_reversal, reverse_of, locked_period`
- **`ledger_book` 账套（双账）**：`tax(分期收款销售) / ops(三层回报)`
- **`distribution` 分配**：`id, period(月), profit_before, mgmt_fee_rate(阶梯), mgmt_fee, distributable, cash_50, roll_50, reserve_after`
- **`investor` 出资人**：`id, name, role(GP/LP), amount, ratio`（仅供分配计算，无员工可见门户）
- **`monthly_report` 月度报表**：`id, period, package_json(六件套), analysis_json(七节), calendar_status`

### 4.4 人 · 事（角色/任务/审计）
- 角色复用平台；`user_role_ext`（业务BD/供应链/财务/GP/LP 的板块内数据权限）
- **`task` 任务**：`id, type, assignee_role, assignee_user, source(系统/派单), status, verify_evidence, transfer_log`
- **`approval` 审批**：投放审批（本金回报≥目标 & 300万内自主）
- **`action_item` PDCA 改进**：`id, issue, action, metric, recheck_date, status`
- **`audit_log` 操作留痕**（红冲/作废/淘汰/拒绝等逆向动作全留痕）

### 4.5 保密隔离（横切）
- 行级：`owner_user` / `project_id`（多项目独立核算，业务只见自己+公海）
- 字段级：成本价/账期/分配明细按角色可见（LP 不可见成本；月度报表分配表按角色发放）

## 五、关键 API（REST · 按模块）
- 报价：`POST /api/rent/quote/calc`（市场价+客户+首付→月租+三层回报+达标校验）
- 合同：`POST /api/rent/contracts`（签约→自动生成 rent_schedule N 期）；`POST /contracts/{id}/void|change|renew`（逆向）
- 收租：`GET /api/rent/bills?period=` `POST /bills/{id}/match|reverse|refund`
- 逾期：`POST /api/rent/overdue/{id}/延期|罚息|锁机|收回|还款恢复`
- 采购：`POST /api/rent/purchase`（校验绑合同）`POST /purchase/{id}/return`（退货红冲）
- 转让：`POST /api/rent/transfer`（转让/收回/二手/报废分支）
- 设备：`GET /assets/{id}`（配件树+成本+残值+故障+事件流）
- 供应商：`GET /suppliers?filter` `GET /suppliers/{id}`（供货矩阵/价格构成/履约）
- 客户CRM：`GET /customers?phase=&owner=` `POST /customers/{id}/followup` `GET /pipeline`
- 凭证：`GET /vouchers` `POST /vouchers/{id}/reverse`（红冲连锁+影响清单）
- 分配/报表：`POST /distribution/run` `GET /monthly-report?period=&export=xlsx|docx`
- 横切：所有写单据→事件流→凭证；`GET /audit/钱该动没动`（稽核）

## 六、技术风险与缓解
| 风险 | 缓解 |
|---|---|
| 平台 SSO 未就绪（已证实=纯占位头 X-User-*，user_id=0，sso.enabled=false） | **ADR-001 已定**：复用小卖账房占位头 + 收 `UserContext` 适配层，业务只依赖抽象；平台整体切 SSO（走 `ole-portal-sso` skill·澳乐门户 auth_code 换 token）时一处替换。**红线待办：门户团队注册子系统+下发 app_id/client_secret（补进"需要老板做的事"）** |
| 双账/折旧/租金计划地基复杂 | 数据模型照抄 Odoo `account.asset.line` + ERPNext 多 Finance Book，不从零发明 |
| 逆向红冲连锁易漏 | 单据事件流 + 影响清单确认 + 锁账"上期调整"，红冲必过确认页 |
| 口径歧义（设备价/合同台数） | 已锁：逐件建档 18万/台、合同挂N台；schema 注释标单位；冲刺0 与设立方案书对平 |
| schema 上 prod 不自动 | migration 手动 apply（§4.16 七步） |
| 税务合规 | 合同禁"融资租赁"字段校验；500万营收红线亮灯 |
| LLM 烧钱/乱算 | AI 只起草，规则引擎出数字；idempotent 24h 缓存 |

## 七、里程碑映射（全量规划·分期交付）
冲刺0（报价测算器对平设立方案书 + 平台账号打通方式定）→ M1业务地基(供应商/客户CRM/逐件设备/合同+租金计划) → M2收租闭环 → M3财务分配(双账/管理费/分配/现金流/月报) → M4转让资产(状态机/维保/盘点) → M5事人BI(任务审批/花名册/BI-PDCA-AI)。上线开关分批启用、并行对账 1 月。

## 八、复用园区小卖账房清单
导入中心内核 · 业财一体单据链内核 · AI 透明四件套 · idempotent helper · 报表 BI 框架 · 移动端响应式框架 · 设计七律 · UserContext 占位头模式。

## 九、待评审（Phase 1.5 设计委员会）
工程卓越 / 安全 / 数据审计 三底线 always；本项目加 CIO(定价方法论)、成本官(LLM)、体验官(UI 已 V5)。重点审：逆向红冲连锁完整性、双账口径、逐件建档与合同N台关联正确性、保密隔离、SSO 适配方案。

## 十、ADR 清单（另出）
- ADR-001：SSO **已定=占位头 UserContext 适配层**（见 `ADR-001-SSO占位头适配层.md`）
- ADR-002：逐件建档 + contract_asset 关联（已定）
- ADR-003：双账采用多 ledger_book（抄 ERPNext Finance Book）
- ADR-004：折旧计划表 + 账套口径下的 book_value（见 `ADR-004-折旧与账套口径.md`）

---

## 十一、v0.2 评审修订（Phase 1.5 · 8 P0 + 关键 P1 落地）

> 详见《12_Phase1.5_设计评审纪要》。以下为已并入本设计的硬约束。

**A. 数据模型**
- 已修：`transfer_order` 拆头+行；`rent_schedule`＝应收计划、补 `asset_depreciation_line` 折旧真相源；`contract_asset` 加 `alloc_rent`；`asset` 加价值定价字段、`book_value` 定 ops 口径、`residual_value` 改即时算。
- 补表：`maintenance`(维修工单)、`stocktake`+`stock_diff`(盘点)、`contract_change`(变更/作废/续租·含 reverse)、`accounting_period`(period,book,is_locked·期间锁)、`voucher_line`(account,direction dr/cr,amount)。合同 `status` 补"已作废"。
- 隔离列：`contract/asset/purchase_in/voucher/distribution` 统一加 `project_id`(+必要 owner_user)。
- 收款单一真相源：`rent_bill` 为收款态 owner，`rent_schedule.plan_status` 单向回写。
- 命名统一 `reverses_id`+`is_reversal`；`customer` 拆 `crm_stage`(人工)+运营态(派生自合同)；押金建 `deposit_ledger`(收/退/期末抵)。
- 规则常量入 `rule_config`(版本化·带生效期)：管理费阶梯 / 品类转让率(10%/30%) / 税率(3%减按1%·2027-12-31 sunset / 小微5%)。

**B. 服务端安全（钉死"在服务端强制"）**
- 网关**剥离客户端 X-User-\* 再重注入**（部署拓扑硬前提 + ADR-001 上线红线）。
- 「敏感操作 × 角色」RBAC 矩阵挂**统一鉴权切面**（红冲/作废→财务+老板；分配→财务；投放审批→老板；淘汰→供应链主管+老板）。
- 行级+字段级隔离在 **DAO/DTO 层强制**（成本价/账期/分配/gain 按角色投影）；月报按角色分别落库/渲染，禁单 blob 下发；占位期用 X-User-Name 解析真实 user 主键做隔离键（否则不开放多用户）。
- 红冲连锁**单事务原子性 + 幂等键（reverses_id 唯一约束）+ 锁账期写守卫**（落 locked_period 一律拒绝，只走上期调整单）。
- 导入走同一校验+事件流通道（禁直写派生/隔离字段、公式前缀转义、owner/project 校验）；禁"融资租赁"扩到自由文本；名义价转让设**硬阈值守卫**（低于 book_value 或市场价×下限强制升级审批）；对象存储走短时效签名 URL+鉴权代理。

**C. 定价方法论**
- 价值定价必填 `monthly_labor_value`，两道校验：品类准入 `payback ≤ 18月`、客户 `月节省 − 月租 > 0`（删掉自相矛盾的"≤市场价"）。
- 速算系数仅作**起价建议**；层级①IRR 必用**真实集采成本/残值/账期重算**、**税后口径**（1%增值税+12%附加+5%所得税），冲刺0 逐格对平定价表。
- 客户总付勾稽 `期数×月租 + 转让价`（不含押金）。
- 回报归因补**残值第四源**（四源之和=总 IRR）；术语区分"三层杠杆"vs"四源利润"。

**D. 现金流/杠杆风控（新增预警）**
- **账期兑付缺口红灯**：`payable` 到期 T-N 比对该账期对应设备回款/空置+可动留存，缺口<0 亮灯+裁决人+补款来源。
- 留存下限 `≥ max(20万, 未来3月供应商净应付)`。
- 层级③投放前校验 `资产回报 − 融资成本 ≥ 安全边际` + 负债率/融资额度上限。
- `distribution/run` 按 period 幂等 + 冲销/重算 API。

**E. 8 条 P0 同列 Phase 2 强制 ticket + 上线红线**：网关剥离头 · RBAC 矩阵 · 服务端隔离 · 红冲原子性 · 价值定价输入 · 账期兑付缺口预警 · transfer 拆行 · 折旧真相源。
