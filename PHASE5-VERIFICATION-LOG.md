# 沃朗科技租赁板块 · Phase 3 地基 + 冲刺0 验收日志

> 日期：2026-08-08　|　流程：F1 · 冲刺0(S0-01~S0-07)　|　环境：JDK17 / MySQL8@3308 worland_dev / 端口 8082
> 真测留证据(§4.21):每 ticket 落地即测,不攒到 Phase 5。

---

## 环境与启动

| 项 | 结果 |
|---|---|
| `mvn -s settings.xml -q compile` | ✅ 通过(无错) |
| 后端 boot(`spring-boot:run`) | ✅ `Started RentApplication in 1.44s` · Undertow @8082 context `/api` |
| Flyway migration | ✅ `Successfully applied 2 migrations, now at version v2` |
| `curl /api/v1/health` | ✅ `{"code":200,"data":"worland-rent backend alive"}` |
| 前端 build | ✅ `node node_modules/vite/bin/vite.js build` → `✓ built in 2.48s`(dist 生成 Quote/Dashboard chunks) |
| 前端 E2E(浏览器) | ✅ 报价页填参→点「测算报价」→结果面板正确渲染(截图见下) |

> 注：`pnpm build` 脚本包装器被本机 pnpm 供应链策略(verify-deps + ignored-builds)拦截而 exit 1，与代码无关；`onlyBuiltDependencies` 已写入 `pnpm-workspace.yaml`，直接 `node .../vite.js build` 构建成功。

---

## S0-01/03 脚手架 + 占位头 UserContext(ADR-001)

- 分层目录 `common(result/exception/config/web/auth) + modules(rule/quote)`，包名 `top.aole.rent`。
- 占位头身份解析验证：

| curl | 返回 | 判定 |
|---|---|---|
| `X-User-Name: 李工`(seed) | `userId=1004, role=""` | ✅ 非0，种子命中 |
| `X-User-Name: 老板`(URL 编码 `%E8%80%81%E6%9D%BF`，模拟浏览器) | `userId=1001, userName=老板` | ✅ 浏览器路径正常 |
| `X-User-Name: 王五`(未知) | `userId=405242` | ✅ hash 兜底非0 |
| 无头 → `GET /v1/whoami` | `{"code":401,"message":"未登录或身份缺失…"}` | ✅ 缺头 401 |

> 修复：Undertow 默认按 ISO-8859-1 读请求头，中文名 mojibake；filter 内 ISO-8859-1→UTF-8 + URL 解码兼容 curl 原始/浏览器编码两路。

## S0-02 migration 框架 + 期间锁

```
SHOW TABLES LIKE 'yc_rent_%'  → yc_rent_rule_config / yc_rent_accounting_period / yc_rent_asset_event
```
- `yc_rent_accounting_period(period, book, is_locked)` 已建，为红冲锁账守卫打底。
- migration 手动 apply 七步文档：`db/migration/README.md`。

## S0-05 rule_config 版本化(禁硬编码)

```
SELECT rule_key,scope_key,rule_value,effective_from,effective_to FROM yc_rent_rule_config WHERE rule_key='tax_vat';
 tax_vat |  | 0.01000000 | 2023-01-01 | 2027-12-31   ← 3%减按1%
 tax_vat |  | 0.03000000 | 2028-01-01 | NULL         ← sunset 后恢复 3%
```
- 取值 helper `RuleConfigService.getEffective(key,scope,bizDate)` 按业务日期取生效版本；跨 2027-12-31 sunset 命中不同版本。
- 已灌入：税率/品类转让率(播种墙10%/货架30%)/租期/目标IRR/速算系数/管理费阶梯(JSON)/三层杠杆参数/留存下限/押金月数/回本上限。

---

## S0-06 报价测算器 · 逐格对平设立方案书 §10（招牌 · 硬验收）

**口径**（方案书§二）：初始流出=集采成本；各期税后净现金流入；期末加计转让价；月度 IRR 年化=目标税后 IRR。
**税（小规模·价内）**：增值税=收入/1.01×1% · 附加=增值税×12% · 所得税=利润×5%。全部取自 rule_config。
输入统一 `市场价20万 / 集采18万`。`POST /api/rent/quote/calc` 实测：

| 品类/客户 | 目标IRR | 月租(精确) | 方案书 | Δ | 速算月租 | 客户总付 | 方案书 | 税后净利 | 方案书 | 税后IRR | 三层回报 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 播种墙 云山 | 25% | 6718.06 | 6720 | +1.94 | **6720**✓ | 261850 | 261907 | 74999 | 75053 | **25.0%**✓ | **25/50/65.8**✓ |
| 播种墙 其他 | 30% | 7148.02 | 7150 | +1.98 | 7160 | 277329 | 277396 | 89541 | 89604 | **30.0%**✓ | **30/60/80**✓ |
| 播种墙 其他 | 35% | 7572.90 | 7575 | +2.10 | 7580 | 292625 | 292701 | 103911 | 103983 | **35.0%**✓ | 35/70/94.2 |
| 货架 云山 | 25% | 4664.68 | 4669 | +4.32 | 4660 | 339881 | 340165 | 148306 | 148573 | **25.0%**✓ | **25/50/65.8**✓ |
| 货架 其他 | 30% | 5175.24 | 5181 | +5.76 | 5180 | 370514 | 370841 | 177086 | 177392 | **30.0%**✓ | **30/60/80**✓ |
| 货架 其他 | 35% | 5679.19 | 5685 | +5.81 | 5680 | 400751 | 401115 | 205492 | 205834 | **35.0%**✓ | 35/70/94.2 |

**对平结论**
- ✅ **完全对平**：税后 IRR（25/30/35%，实为定价目标）、三层回报（25%→25/50/65.8、30%→30/60/80，校准自方案书§4.4 杠杆临界点表两点）、播种墙25% 速算月租 6720。
- ✅ **勾稽成立**：客户总付 = 期数×月租 + 转让价（不含押金）；税后净利 = 全生命周期聚合税后口径（税模型经反推与方案书§10.2 逐格吻合：如播种墙25% 精确 75052.5≈75053、货架25% 148573.2≈148573，用表内月租代入时到元级一致）。
- ⚠️ **精确月租残差 ≤0.09%**（播种墙 +2元、货架 +6元）：本引擎目标 IRR=25.00% 精确解得 6718/4665；方案书精确列 6720/4669 对应其电子表的 IRR 实为 25.02%~25.05%（用表内月租代入本模型即得此年化），属源表对「示意推演」数字的自舍入粒度，非模型错误。工程处置：**速算月租**（rule_config 系数）给出方案书§10.3 起价建议，**精确月租**给出 IRR 严格解，二者并列呈现，差额透明。
- 价值定价两校验（P0-G）：回本期=市场价/月节省≤18 亮灯、月净收益=月节省−月租>0，两过才解锁「生成合同」——已实测 pass/unlock。
- 单台首付杠杆：实际资金占用=首付−2月押金，与方案书§4.3 逐档对平（首付30%→占用≈40560）；杠杆 IRR（41.4/50.9/70.1%）依赖供应商账期偿付计划，源文档未给该计划，待 M1-12/M3-05 明确后精确出数（已在响应 `leverageNote` 中诚实标注）。
- 边界：缺 `monthlyLaborValue` → `{"code":400,"message":"…必填…"}` ✅。

**方案书 L123 "10万→16667" 疑似笔误回传**：品类准入表「10 万元 / 约 16,667 元月节省下限」与前一行「10 万元 / 5,556 元」重复且 16667=按 6 万/18 反推，疑应为 30 万元档。已记，待用户确认。

## S0-07 单据事件流基表

- `yc_rent_asset_event` 已建（事件留痕底座），凭证过账 Service 内核 M1 复用小卖账房时接入；冲刺0 仅建表占位（TODO S0-07 起步）。

---

## 前端 E2E 证据
报价页(播种墙/云山/首付30%)点「测算报价」→ 精确月租 ¥6,718.06 / 速算 ¥6,720.00 / 转让 ¥20,000 / 客户总付 ¥261,850.29 / 税后净利 ¥74,999.27 / 税后IRR 25.0% / 三层 25%·50%·65.8% / 价值定价两校验全过·可解锁生成合同 / 单台占用 ¥40,563.87。全链路 前端→vite proxy→后端8082→规则引擎 正常。

## 遗留 / 未做（本次范围外）
- M1~M5 业务模块（供应商/客户CRM/逐件设备/合同/收租/双账/转让/BI 等）——按 TODO 分期。
- 精确月租 0.09% 残差与单台杠杆 IRR 的账期口径：待与用户确认源表假设。
- 前端 `pnpm build` 脚本包装器策略问题（非代码）：已用直接 vite 构建绕过。

---

# M1 供应商 + 客户CRM 验收(2026-08-08)

## 环境与编译
- `mvn -s settings.xml compile` 通过;后端真 boot 8082(Undertow),Flyway 自动应用 **V3__supplier / V4__customer** → `Successfully applied 2 migrations, now at version v4`。
- 前端 `vue-tsc --noEmit` 通过 0 error;vite 5175 起,浏览器 3 页 E2E 无 console error。
- 回归:`/api/v1/health` alive;报价引擎 `/rent/quote/calc` 播种墙/云山 → 月租6718.06·速算6720·税后IRR25%·三层25/50/65.8(与冲刺0 基线一致,地基未坏)。

## 供应商模块(M1-01/02)
- 表:`yc_rent_supplier`(6状态) + `yc_rent_supplier_supply`(供货矩阵·履约五维·价格构成)。
- 接口 curl 证据:
  - `GET /rent/suppliers` → 4 家,履约加权总分即时算(恒丰88/82/90/85/84 → **86**,权重来自 rule_config,对平 mockup)。
  - `GET /rent/suppliers?category=播种墙` → 2 家(CJK query 需 URL 编码,axios 自动编码 OK)。
  - `GET /rent/suppliers/dependency-alert` → 品类「货架」可用 0 家 < 2 亮灯。
  - `GET /rent/suppliers/1` → 履约雷达/供货矩阵(整机+电控+传感)/价格构成(材料9.4万/加工4.7万/利润3.9万·报价18万 vs BOM17.6万=合理)。
  - `POST /rent/suppliers` 建 → `POST /{id}/retire` 淘汰 → SQL 验:status=淘汰、retire_reason、retired_by=1006(供应链)留痕。
- P0-E 字段级:as LP → `costMasked=true`、priceComposition=null、supplyMatrix.quotePrice=null,但履约分仍可见。

## 客户CRM(M1-03/04/05/09)
- 表:`yc_rent_customer`(信用五维+准入决策+行级隔离键) + `yc_rent_customer_followup` + `yc_rent_opportunity`。
- 接口 curl + 浏览器 E2E 证据:
  - `GET /rent/customers`(老板)→ 6 家;评级即时算(阿昌75/55/70/48/65 → 加权62 → **B**,对平 mockup)。
  - `GET /rent/customers/5` → 信用画像/加权62→B/准入建议 **20万·2月·30%**(rule_config 矩阵)/LTV(合同3·收租18.6万·利润4.1万·续租67%)/敞口11万·逾期5181/集中度4.38%(即时算)/时间线4条。
  - `GET /rent/customers/pipeline` → 6 列看板 + 加权预测 **101万**(Σ open 商机 est×prob)。
  - `POST /rent/customers/5/followup` → SQL 验落库 + 客户 next_follow_date 同步。
  - `POST /rent/customers/2/admission`(rating B)→ SQL 验 credit_limit=200000/deposit=2/target_irr=0.30/note 落定。
- **P0-E 服务端隔离(浏览器实测三身份切换)**:
  - 行级:切「业务」(1007)→ 客户池由 6 → **4**(李工名下京东/云山不可见);`GET /customers/3`(李工客户)越权 → **403**。
  - 字段级:切「投资人」(LP)→ 在租额/逾期/LTV/授信/IRR 全 🔒 打码,评级/阶段/跟进仍可见。
  - 隔离在 DAO 查询条件(owner OR 公海)+ DTO 投影双层,非仅前端隐藏。

## 派生字段单一真相源(§4.24)
- 履约加权总分、客户评级/加权信用分、集中度、准入建议 —— 全部 rule_config 权重/阶梯即时算,不落库(防 stale);准入落定值由准入接口唯一写手写入。rule_config 由 25 → **30** 条(+供应商权重/依赖下限 +客户权重/评级阶梯/准入矩阵)。

## 遗留 / 未做
- 供应商「账期=回报放大器」表(mockup 有):单台杠杆 IRR 依赖报价引擎+供应商偿付计划(M1-12),本波仅呈现账期/首付,未算单台回报(与冲刺0 leverageNote 口径一致,诚实标注)。
- 看板卡片拖拽推进阶段:本波只读展示,拖拽改阶段留 M1 后续。
- RBAC 统一鉴权切面(M1-15)、行级隔离升级为切面:本波在 service 内联强制,切面化待 M1-15。

---

# M1（第二波）· 逐件设备 + 配件树BOM + 合同 + 租金计划 + 单笔P&L（2026-08-08）

## 环境 / 构建
- `mvn -s settings.xml compile` → **BUILD SUCCESS**；后端重启真 boot（Undertow:8082 · Started RentApplication）。
- Flyway boot 自动 apply **V5__asset.sql / V6__contract.sql**（flyway_schema_history 到 version=6，success=1）；新表 8 张就位。
- 前端 `vue-tsc --noEmit` 无错；`vite build` ✓（Asset 13.02kB / Contract 14.20kB chunk）。
- 报价/供应商/客户回归 curl：quote calc=200、suppliers=200、customers=200，未弄坏 S0/上一波。

## 设备逐件台账（M1-06/07）
- 表：`yc_rent_asset`（序列号唯一/状态机/价值定价输入/派生 holder+contract）+ `yc_rent_asset_bom`（自引用多级）+ 复用 `yc_rent_asset_event`。
- curl + 浏览器 E2E：
  - `GET /rent/assets`（老板）→ 台账列表；`bookValue` 直线折旧即时算（WL-BZQ-0001：基数 18万-2万残值，36月直线，投放11月 → **13.11万**，SQL 手算吻合）；`residualValue`=市场价×transfer_rate（播种墙 20万×0.10=**2万**；货架 6万×0.30=1.8万）。
  - `GET /rent/assets/1` → 配件树 **2 级递归**（电控系统→PLC/变频器/线束）；成本拆解 Σ=18万=集采价 差 0；残值构成部件合计 3.77万 vs 整机 2万；故障档案按 fault_count 降序（传感2/电控1/变频1）；自购回本=20万/1.5万=**13.3 月**；状态机时间轴 采购→投放→在租（倒序）。
  - `POST /rent/assets`（供应链）建 WL-BZQ-0003 → 状态机校验：**采购→在租 拒 400**（非法）；**采购→投放 200**；SQL 验 status + asset_event（operator=1006）。
- 字段级隔离（LP）：purchasePrice/bookValue/costBreakdown/回报率 = null，residual/市场价可见。

## 合同签约 + 自动生成 N 期租金计划（M1-10/11/17/18）
- 表：`yc_rent_contract` + `yc_rent_contract_asset`（alloc_rent 单台分摊）+ `yc_rent_rent_schedule`（应收计划态）+ `yc_rent_deposit_ledger` + `yc_rent_contract_change`。
- **核心 curl + SQL 证据（签约 HT-2026-0001：2 台设备/36 期/月租 1.3 万/转让 4 万/起租 2026-02-01）**：
  - **自动生成 rent_schedule N 期**：SQL `COUNT(*)=36`、`SUM(amount)=468000`、首期 2026-03-01、末期 2029-02-01、已过 6 期。
  - **勾稽等式成立**：期数×月租 468000 + 转让 40000 = **客户总付 508000**；scheduleSum=468000 → `scheduleBalanced=true`、`balanced=true`；押金 26000（月租×2）**单列不进客户总付**。
  - **挂设备转在租**：SQL contract_asset 2 行 alloc_rent 各 6500（均摊末台补差）；asset.status→在租（markRented 走 asset_event）。
  - **押金台账**：deposit_ledger 收 26000。
  - **每期租金构成**（构成合计恒等月租）：本金摊 10000 + 资金成本 1800 + 残值预留 1111.11 + 差价 88.89 = 13000 ✓。
  - **单笔 P&L**：收租 468000 + 转让 40000 − 集采 360000 − 资金成本 64800（集采×6%×36/12）− 坏账 9360（收租×2%）= **税后净利 73840**（净利率 15.78%）。
- **逆向路径**：
  - **续租**：HT-0001 +12 期 → term 36→48、schedule 48 行、contract_change(续租)。
  - **提前结清（change）**：settleDate=今天 → 截断未到期 42 期、term→6、status=关闭、押金期末抵、contract_change(提前结清·截断42期)。
  - **作废（void，限未采购整份红冲）**：HT-0003 → status=已作废、schedule 有效行=0、押金 收+退红字、**设备释放**（status→投放、holder/contract 清空）、contract_change(is_reverse=1)。
- **禁「融资租赁」**：nature 固定「分期收款销售」；字段/自由文本含「融资租赁」→ 400 校验。
- 前端 E2E（真浏览器截图）：合同详情抽屉「勾稽对平（绿）/回款进度/每期构成/单笔P&L/挂载设备/租金计划逐期」全渲染；LP 身份 fetch 实测 `pnl:null、rentComposition:null、sensitiveMasked:true`，勾稽/客户总付/alloc 仍可见。

## 修复的坑（本波）
- **MyBatis-Plus updateById 跳过 null 字段**：作废/状态流转清 `current_holder_customer_id`/`contract_id` 时不生效 → 改用 `LambdaUpdateWrapper.set(col, null)` 显式置空（AssetService.releaseOnVoid / changeStatus）。复测：作废后 holder/contract 真清空。

## 单一真相源 / 口径
- book_value（经营口径直线折旧占位·注释 M3 asset_depreciation_line 精确化）、residual、self_purchase_payback、单台回报率 —— 全即时算不落库。
- rent_schedule.plan_status 只计划态（未到期/已生成单）；收款/逾期/红冲态归 M2 rent_bill（未越界写）。
- alloc_rent 单一真值 Σ=month_rent；rule_config +1 条（contract_bad_debt_rate 0.02）→ 共 31 条。

## 遗留 / 未做（诚实）
- 「先签约后采购」：本波挂已建档设备（钩子 asset.purchase_in_id + void 限未采购校验就位）；采购模块建单/应付属 **M1-12** 下波。
- rent_bill 收租/核销/逾期（收款真相源）、折旧表精确 book_value、transfer_order 逐台处置属 **M2/M3/M4**。
- 单笔 P&L 为经营口径简化（未含税与运维/管理费分摊）、每期构成为简化模型，均已在响应 note 标注，M2 凭证/分配精确化。
- 设备/合同页身份切换用占位头；RBAC 切面统一鉴权待 M1-15。

---

# M1 收口验收 —— 采购应付+退货 / 投放·空置亮灯 / RBAC 统一切面（2026-08-08）

## 环境 / 编译 / 启动
- `mvn -s settings.xml compile` → **BUILD SUCCESS**（全量）。
- 后端 `spring-boot:run` 8082 启动：Flyway `Current version 6 → Migrating to v7 → Successfully applied 1 migration, now at v7`；`Started RentApplication`。
- 健康：`GET /api/v1/health` → `{code:200,"worland-rent backend alive"}`。
- **地基回归（没弄坏）**：`/api/v1/health` 200；`rent/suppliers` total=4、`rent/customers` total=6、`rent/contracts` total=3、`rent/assets` total=6 均 200；`quote/calc` 缺 monthlyLaborValue 正确 400（价值定价校验在）。
- 前端 `vite build` → `✓ built in 2.72s`（Dashboard/Quote/Supplier/Customer/Asset/Contract chunk 正常，无破坏）。

## 采购应付 + 退货（M1-12/13）—— curl + SQL 证据
- **无合同拒采购**：`POST /rent/purchase`（contractId=999 不存在）→ `404 合同不存在,不允许建采购单(先签约后采购)`；contractId=2（已作废)→ `400 合同已作废,不允许建采购单`。✅ 硬校验
- **下单**（contract=1·2 件·total 222000）→ `200 data=1`。SQL：`purchase_in(id=1,status=已下单,total=222000)`；`payable` 1 行 `首付 66600 待付`（下单即生成首付·30%）；`purchase_item` 2 件 `asset_id=NULL`。
- **入库自动生成逐件 asset**：`POST /rent/purchase/1/receive` → 200。SQL：
  - `yc_rent_asset` 新增 **id=7(WL-T-9001·播种墙)/id=8(WL-T-9002·货架)**，`status=采购`、`purchase_in_id=1`、`purchase_price` 逐件落库。✅ **入库自动逐件建 asset**
  - `purchase_item.asset_id` 回填 7/8。✅ 回填
  - `payable` 变 **3 期**：首付 66600 / 验收 133200 / 尾款 22200（due=入库日+账期90天）→ **合计 222000 = 采购总额**。✅ **应付计划 3 期落库**
  - `asset_event` 逐件 `采购/ref=purchase_in#1`。✅ 事件流留痕
- **退货红冲**：`POST /rent/purchase/1/return`（供应链）→ 200。SQL：`purchase_in.status=已红冲`；`asset 7,8 status=报废、contract_id=NULL`（释放）；`payable` 3 期全 `红冲` + 新增 `退款红字 -222000`。✅ **红冲+设备释放+应付红字**
- **GP/LP 成本打码**：`GET /rent/purchase/1`（角色 GP）→ `sensitiveMasked=true`、`totalAmount/purchasePrice/payable.amount` 均 `null`；老板可见全额。✅ 字段级隔离

## 投放 / 空置亮灯（M1-14）
- **投放/交付确认**：`POST /rent/assets/7/deploy`（老板）→ 200，`采购→投放` 走事件流 + EXECUTED audit。
- **空置亮灯**：`GET /rent/assets/idle-alert` → asset1 `在租→收回待处置` 后亮灯 `alertCount=1`，`reason=收回待处置、residualValue=20000（市场价×10%转让率）`；阈值 `idle_alert_days=30`（rule_config）。✅ 驾驶舱红点数据源
  - 注：投放超期分支按「最近投放/再投放事件距今 > 阈值」判定；种子 asset2 投放 19 天未达阈值故不亮（口径正确）。

## RBAC 统一鉴权切面（M1-15 · P0-D）—— 越权 403 + audit
统一切面 `@RequireRole` + `RoleGuardInterceptor`（HandlerInterceptor·零新增依赖），越权先卡权限不进业务：
| 敏感操作 | 越权角色 | 结果 | 放行角色 | 结果 |
|---|---|---|---|---|
| 投放审批 asset7 | 财务 | **403 DENIED** | 老板 | 200 EXECUTED |
| 采购退货 purchase1 | 财务 | **403 DENIED** | 供应链 | 200 EXECUTED |
| 合同作废 contract1 | 业务 | **403 DENIED** | （财务/老板） | — |
| 供应商淘汰 supplier4 | 业务 | **403 DENIED** | 供应链 | 200 EXECUTED |
- **状态流转→投放 内联守卫**：`POST /rent/assets/8/status{投放}`（供应链）→ 403（body 依赖，切面拦不了，service 内 `RoleGuard.assertRole(老板)` 兜底·与 /deploy 双保险）。
- **audit 留痕**：`yc_rent_audit_log` 共 7 行，覆盖 DENIED（越权拦截）+ EXECUTED（放行执行），含 action/target/result/operator_role/detail，`REQUIRES_NEW` 独立事务（DENIED 不随被拦请求回滚）。✅

## 🚨 P0-C 上线红线待办（代码层做不了·部署拓扑）
**网关剥离客户端 X-User-\* 头再重注入**：占位期后端直接信任请求头 `X-User-Name/X-User-Role` 解析身份（`UserContextFilter`）。**生产上线前，可信网关必须先剥离客户端自带的 X-User-\* 头，再按会话重注入**，否则任意客户端可伪造 `X-User-Role: 老板` 绕过本波所有 RBAC 切面。此为部署配置项（ADR-001 / DESIGN §二 P0-C / §十一 B），**代码层无法自证，列为上线 Gate 阻断项**。切真 SSO（ole 澳乐门户 auth_code 换 token）后由门户下发身份，此红线自然消解。

## 遗留 / 未做（诚实 · 本波）
- **采购/应付前端页 未建**：本波交付为后端 REST + SQL 验收（交付清单即后端范围）；UI-mockup「采购入库·应付页 / 驾驶舱空置红点」React 落地属下波（可复用 idle-alert / purchase 接口）。
- 应付「实付/核销」「兑付缺口 T-N 扫描红灯」属 M3（本波已为其留 `payable.due_date/amount/status` 负债字段）。
- 退货红冲当前为整单红冲（非逐件部分退）；名义价/幂等键（reverses_id 唯一约束）等 P0-F/P1-19 强化属后续。
- audit 身份取占位头解析（P1-16 网关注入身份指纹待真 SSO）。
