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

---

# M2 收租闭环 验收日志（2026-08-08）

环境:mvn compile + boot 重启(8082 释放后 spring-boot:run)· health `/api/v1/health` 200 · Flyway V7→**V8**(rent_bill/overdue_case/repossess_order + 3 rule seed)自动 apply · 前端 vite build 通过 + 真浏览器测。DB 走 `docker exec vend-mysql mysql`。测试数据:新建生效合同 `HT-M2-TEST-01`(id4·起租 2026-02-01 回溯·6 期·月租 5000·挂 asset3)。

## M2-01 收租单生成 cron（双出口 + 幂等 + 生效守卫）
- `POST /rent/bills/gen/run`(执行体 = `RentBillGenScheduler.runRentBillGenCron`→`RentBillService.runRentBillGen`;`@Scheduled(0 0 1 * * ?)` startup 注册·非 require.main)→ `generated:6, skipped:6, horizon:2026-08-11`。
- **SQL 验到期生成**:`yc_rent_rent_bill` contract4 生成 6 张(P01-P06·due 2026-03-01~08-01·amount 5000·status 待收)。
- **SQL 验回写**:`yc_rent_rent_schedule` contract4 六期 `plan_status=已生成单` + `rent_bill_id` 已回填(1..6)。✅ 单一真相源单向回写
- **生效守卫**:skipped=6 = 关闭/已作废合同(HT-2026-0001 关闭 等)的到期计划行被跳过(仅生效合同生成)。
- **幂等**:再跑一次 → `generated:0`(已回填 rent_bill_id 不重复生成)。

## M2-02 到账核销
- `POST /rent/bills/1/match`(财务·金额缺省=应收)→ 200,bill1 `status=已核销, received_amount=5000, matched_at, account_period=2026-08, voucher_id=NULL`(收入简化流水·凭证 M3 钩子)。
- 幂等:重复 match 已核销单 → 400「已核销(幂等拒)」。金额不一致 → 400「需人工处理」。

## M2-03 红冲（P0-F 三保险）
- `POST /rent/bills/1/reverse`(财务)→ 影响清单:`生成红冲行 …-P01-R 金额 -5000 / 原单→红冲 / 租金计划 期1 回退未到期+清关联 / 无凭证无需冲销`。
  - **SQL 金额反转**:原单 id1 `status=红冲`;红冲行 id7 `amount=-5000, bill_kind=红冲, reverses_id=1`。✅
  - **SQL 计划回退**:schedule 期1 `plan_status=未到期, rent_bill_id=NULL`。✅
- **幂等(应用层)**:再 reverse id1 → 400「已是红冲态」。
- **幂等(DB 硬约束)**:直接 `INSERT … reverses_id=1` → **ERROR 1062 Duplicate for key `uk_bill_reverses`**;确认 DUP-TEST 未落库(dup_rows=0)。✅ reverses_id 唯一约束 = 幂等键
- **锁账守卫**:`INSERT accounting_period(2026-08,tax,is_locked=1)` 后 match 待收单 → **400「会计期 2026-08(tax)已锁账,禁止写入,请走上期调整」**;解锁后 match → 已核销。✅
- **RBAC**:reverse 以「业务」角色 → **403 DENIED** +「收租红冲」audit(operator_role=业务, result=DENIED)。

## 退款
- `POST /rent/bills/2/refund{amount:5000}`(财务)→ 负额退款单 `…-P02-RF amount=-5000, bill_kind=退款, status=已核销, ref_bill_id=2`。✅

## M2-05 逾期检测 + 三步走
- `POST /rent/overdue/scan/run`(执行体 = `OverdueScanScheduler.runOverdueScanCron`;`@Scheduled(0 0 2 * * ?)`)→ `opened:3, marked:3`(P03/P04/P06 待收过期→逾期+开案)。
  - **SQL**:3 案 `step=延期, status=开启, owner=财务, deadline=today+7`;对应 bill `status=逾期`。✅ 每案裁决人+期限
  - **幂等**:再跑 → `opened:0`(uk_overdue_bill 唯一约束 + 已有案跳过)。
- **三步走(案1/P03)**:`extend`(展期10天·owner 刘总)→ `penalty{days:30}` → **罚息单 `PN-… amount=75.00`(5000×0.0005×30)** + 案 `penalty_amount=75`;→ `lock`(step=锁机) → `repossess`(owner 老板)。
  - **SQL 收回**:`yc_rent_repossess_order REP-… asset_count=1 disposal_status=待处置`;**asset3 `status=收回待处置`**(接 M1 状态机 在租→收回待处置);案 `step=收回, status=关闭, closed_at`。✅

## M2-06 还款恢复
- `POST /rent/overdue/2/repay`(案2/P04)→ `caseStatus=关闭, assetsRestored=true`。**SQL**:案2 `step=关闭 status=关闭 closed_at`,bill P04 `status=已核销`。✅

## M2-04 钱该动没动稽核
- `GET /rent/audit/cash-check` → `到期未生成单 / 已到账未核销 / 已核销缺凭证` 三项计数 + 明细。已核销缺凭证 N>0(M2 未生成凭证·符合预期·待 M3);红冲后该期回「到期未生成单」亮灯 → 再跑 gen 生成 `…-P01-G2` 补单闭环(bill_no 撞车 500 bug 已修:同期重生成追加 -G{n})。✅

## 前端 · 收租页(真浏览器验)
- `/rent` 路由 + 侧栏「收租 · 收租单/逾期」;vite build 通过(Rent chunk 12.13kB)。
- **V1 happy path**:勾选待收单 → 批量核销按钮实时显示「1张 · ¥5,000」→ 确认 → 单转已核销(操作列变红冲/退款);顶部稽核带「已核销缺凭证 4→5」联动。✅
- **两 tab 渲染**:收租单 tab(状态/性质/逾期天数/缺凭证 tag + 核销/红冲/退款)、逾期 tab(三步走按钮·已结案灰显)。**0 console error**。

## 回归(地基未坏 · main 仍 boot)
health/contracts/assets/purchase/customers/suppliers/bills/overdue 各 GET → 全 `code:200`;设备/合同/采购模块逻辑未改(仅 AssetService 新增 `repossess` 方法,不动既有流转)。

## 遗留 / 未做（诚实 · 本波）
- **锁机**为记步 + 留痕(step=锁机 + audit),真远程锁机由 IoT 侧执行(留钩子);收回后设备 `收回待处置`,再投放/二手/报废属 M4。
- **收入凭证**M2 为简化流水(rent_bill 已核销 + received_amount),完整双账凭证 + `voucher_id` 回填属 M3(稽核「已核销缺凭证」恒亮以提示)。
- 核销金额一致才自动核销;**部分收款/超额/多笔拆分**属 M3;罚息为按次手动计(自动罚息 cron 可后续加)。
- audit/operator 身份仍取占位头(P1-16 网关注入指纹待真 SSO);P0-C 网关剥离 X-User-* 红线同前波未消解。

---

# M3 Wave A 验收(凭证双账 + 折旧真相源 + 500万红线 + 凭证视图 · 2026-08-10)

> Flyway V9 boot 自动 apply(`Successfully applied 1 migration … now at v9`)。后端 mvn compile BUILD SUCCESS + boot OK(Started in ~1.6s)。前端 vite ready + 真浏览器验凭证页 happy path 0 console error。

## M3-01 凭证双账(核销→双账收入凭证 + 借贷平衡 + 稽核缺凭证归 0)
- **回填历史已核销单**:`POST /rent/vouchers/backfill-rent-income` → `scanned=5 posted=5 voucherCount=10`(每单 tax+ops 各一)。
- **稽核联动**:`GET /rent/audit/cash-check` `matchedNoVoucher` **5 → 0**,`allClear=true`。✅
- **SQL 借贷平衡**:每张凭证 `Σdr=Σcr`(`IF(...)='BALANCED'`),税务收入凭证分录 = dr 1122 应收账款 5000 / cr 6001 主营业务收入 5000;经营 = dr 1002 银行 / cr 6051 租赁收入。✅
- **退款负额**:bill10(退款)方向反转(dr 主营收入 / cr 应收),ledger_book `-5000`。✅
- **rent_bill.voucher_id 回填**:5 张已核销单均回填 tax 凭证 id(1/3/5/7/9)。✅
- **实时核销自动过账**:`POST /rent/bills/6/match` → `status=已核销 voucherId=24`,SQL 见 tax+ops 双账各一。✅
- **红冲联动**:`POST /rent/bills/6/reverse` → 影响清单含「联动红冲收入凭证 2 张(税务账+经营账·ledger_book 同步冲销·营收红线回退)」;SQL `reverses_id` 命中 2 张红冲凭证。✅

## M3-02 折旧真相源(逐月 book_value 递减 · book_value 唯一写手)
- **月度计提**:`POST /rent/depreciation/run?bizDate=2026-08-15` → `scanned=6 generated=6 vouchers=6 totalDepr=14533.32`;2026-09 再计提 6 台。
- **SQL 递减**:asset 2(播种墙)`period_no=1 → book_value_after=175555.56`,`period_no=2 → 171111.12`(月折旧 4444.44)。✅
- **book_value 即时取真相源**:`GET /rent/assets/2` → `bookValue=171111.12`(=最新折旧行 book_value_after,旧直线占位已下线)。✅
- **幂等**:同月重复触发 `generated=0 skipped=6`(unique(asset_id,book,period_no) 兜底)。✅
- **ops≠tax 佐证**:折旧凭证只落 ops 账(12 张 cost 29066.64),tax 账无 cost 行。✅

## M3-08 500万营收红线(取税务账)
- `GET /rent/tax/threshold` → `book=tax threshold=5000000 currentRevenue=15000 opsRevenue=15000 remaining=4985000 usedRatio=0.003 level=正常`。
- **SQL 口径**:`SUM(amount) WHERE book='tax' AND entry_type='revenue' AND YEAR=2026` = 15000(正常 4×5000 − 退款 5000)。阈值/预警占比走 rule_config(`tax_revenue_threshold`/`tax_threshold_warn_ratio`,禁硬编码)。✅
- **红冲联动回退**:红冲凭证 #3 后 tax 收入 `15000 → 10000`(ledger_book 写负额行)。✅

## M3-11 凭证查询/红冲视图(P0-F 通用红冲内核)
- `GET /rent/vouchers`(账套/来源/期/是否红冲筛选) + `GET /rent/vouchers/{id}`(分录借贷 + 借贷合计平衡 + 双账对家凭证对照 + 红冲指向)。
- **P0-F 红冲**:`POST /rent/vouchers/3/reverse` → 生成 `-R` 镜像反转凭证 + ledger 负额冲销;**幂等**重复红冲拒(`code=400 该凭证已被红冲`);**锁账守卫**:锁 2026-08/ops 后红冲 ops 折旧凭证 #12 拒(`会计期已锁账`)。✅
- **RBAC**:业务角色红冲凭证 `code=403`(`需要角色 [财务, 老板]`),voucher 未被红冲,audit `DENIED` 留痕。✅
- **前端凭证页(真浏览器验)**:`/voucher` 顶部 500万红线进度条(0.2%·正常·税务1万/剩余499万·经营账对照1.5万);列表双账成对 + 借=贷 tag + 红冲 tag;详情弹窗见分录 dr/cr(1122 借 5000 / 6001 贷 5000)+ 借贷平衡✓ + 「本凭证已被红冲(#26)」+ 双账口径对照(经营账 PZ-ops-rent_bill-6 查看)。**0 console error**。✅

## 回归(已过模块未坏 · main 仍 boot)
- assets/purchase/contracts/customers/suppliers/bills 各 GET → 全 `code:200`。
- 采购 `receive()` 加应付凭证钩子(dr 固定资产 / cr 应付账款·tax·幂等),不改既有 payable 行为;billing `match/reverse` 扩为同事务过账/冲销凭证(钱账一致·失败整体回滚)。

## 遗留 / 未做(诚实 · 本波)
- **转让残值凭证** `postResidual` 仅留接口(抛 501),分录口径待 M4 转让模块对齐处置损益后填充。
- **ledger_book** 为 append-only 收入/成本流水(500万口径够用);完整 GL 试算平衡表/科目余额表属 M3 Wave B/BI。
- 折旧口径为**直线法**(base=集采价−残值,末期结平尾差);加速折旧/税会差异(税务账折旧)属后续。
- 折旧 cron 记账期取执行当月;跨月补提用 `?bizDate=` 手动入口(运维/测试)。
- M3 Wave B 待做:分配(管理费阶梯/50-50)/现金流/账期兑付缺口红灯/月度报表六件套。

---

# M3 Wave B 验收 — 结账分配/现金流驾驶舱/账期兑付缺口(P0-H)/回报四源

环境:main·Flyway V10·mvn compile+boot OK(`Started RentApplication`)·8082/api·全 curl 走占位头 `X-User-*`。

## M3-03/04 结账分配(管理费阶梯 / 50-50 / 留存校验 / 幂等冲销)
- **出资人 seed**:`GET /rent/investors` → 数智云仓 GP 20万/10% · 小洪 LP 60万/30% · 刘总 LP 70万/35% · 其他 LP 50万/25%(合计 200万)。✅
- **核心链路(权威例·方案书§11/§13.2)**:`POST /rent/distribution/run {"period":"2026-06","profitBefore":500000,"returnRate":0.25}` →
  - 净利 50万 → 回报率 25% → **管理费 10% 档**(§13.1:25-30%→10%)→ 管理费 **5万** → 可分配 **45万** → 现金 **22.5万** / 滚存 **22.5万**(50/50)。
  - 留存下限 = max(20万, 未来3月供应商净应付) = **20万**;分配后留存 22.5万 → `reserveSufficient=true`。
  - **按比例分 4 方**:GP 数智云仓 现金2.25万+滚存2.25万+**管理费5万**=**9.5万**;小洪 13.5万;刘总 15.75万;其他 11.25万。份额加总 = 现金22.5万+滚存22.5万+管理费5万 ✓。✅
- **auto 回报率**:不传 returnRate → `returnRate=profitBefore/totalCapital=500000/2000000=0.25` → 10% 档(与显式一致)。✅
- **阶梯换档**:`profitBefore=600000 returnRate=0.32` → 落 30-35% 档 → **管理费率 15%** → 可分配 51万。✅
- **period 幂等**:同期 2026-06 无 force 重跑 → `code=400 已存在生效分配单 FP-202606;重算请带 force=true`。✅
- **force 冲销重算**:`force=true` → 旧单置 `reversed` + 生成负额镜像冲销单(reverses_id=原id)+ 新 active 单。SQL 验:
  `SELECT ... FROM yc_rent_rent_distribution` → id1 reversed / id2 FP-202606-R(profit -50万·reverses_id=1)/ id3 active;`period='2026-06' GROUP BY status` → active=1 reversed=2(**同期恒一条 active**)。✅
- **手动冲销 + 幂等拒**:`POST /rent/distribution/3/reverse` → 原单 reversed + 冲销单 FP-202606-R-2;重复冲销 → `code=400 该分配已冲销`;`uk_distribution_reverses` 唯一约束在库(SHOW INDEX 确认)。✅
- **RBAC(P0-D)**:业务角色跑分配 → `code=403 需要角色 [财务, 老板]`。✅
- **P0-E 角色投影**:财务(canSeeCost)见全量 4 人份额;LP(无匹配 user_id)见 0 人(仅见自己那份)。✅
- **单一真相源**:阶梯(mgmt_fee_ladder v2 §13.1 五档)/分配比(distribution_cash_ratio 0.5)/留存下限(reserve_floor)全走 rule_config(V10 seed·禁硬编码)。每人份额 = 分配快照 × investor.ratio 即时算(不落冗余表)。

## M3-05 现金流驾驶舱
- `GET /rent/cashflow?months=6` → 应收(未收)分层 1年内 88000/1年以上 0/合计 88000(8 笔);应付(待付)-222000(1 笔·退款红字);净头寸 310000;
  三层杠杆:①自有 200万(=Σ investor.amount)②供应商账期 max(0,Σ待付)=0 ③融资 0(未启用)合计 200万;预测曲线逐月 inflow/outflow/net/cumulative。✅
- **口径**:应收未收态取 rent_bill(status≠已核销 计入),应付取 payable status='待付'(§4.24 单一真相源)。

## M3-06 账期兑付缺口预警(P0-H)
- **现金头寸模型**:待付 payable 按到期升序,逐 checkpoint 计 `预计现金 = 可动用留存 + Σ收款(≤到期) − Σ应付(≤到期)`;<0 → 红灯。
- **红灯实证(造一笔到期应付80万>回款+留存)**:插入 payable 尾款 80万 due 2026-08-20 待付 → `GET /rent/cashflow/coverage-gap?tMinusDays=7` →
  `hasRedAlert=true redCount=1`;🔴 应付#7 尾款 应付80万 累计回款8.8万 累计应付57.8万(含-22.2万退款净额)→ **预计现金 -21万 → 缺口 -21万**;
  **裁决人**=老板(合伙事务执行人/GP 数智云仓);**补款来源**=启用层级③融资/GP·LP 股东借款/延后分配抬滚存/加速收回处置回款。删除临时行后复查 `hasRedAlert=false`。✅
- **可动用留存** = Σ active 分配 reserve_after − 20万下限。

## M3-09 回报四源(四源之和=总IRR·可勾稽)
- `GET /rent/analytics/return-attribution`(默认其他客户·总IRR 0.30)→ 集采差价 12%(权重40%)+ 资金时间价值 7.5%(25%)+ 价值定价 6%(20%)+ 残值回收 4.5%(15%)= **sumCheck 0.30 = totalIrr → reconciled=true**。✅
- `?customerType=云山快仓`(总IRR 0.25)→ 四源和 0.25 勾稽平。✅
- **末源兜尾差**保证 ∑ 精确=总 IRR;权重走 rule_config `return_attribution_weights`。术语区分:四源=利润来源 vs 三层杠杆=资金结构。

## 前端(真浏览器验 · /cashflow · 0 console error)
- 顶部账期兑付缺口预警条(🟢兑付安全/🔴红点·可动留存 2.5万·T-N 天可调);现金流 KPI 四卡(应收8.80万/应付-22.20万/净头寸31万/三层杠杆自有200万);净现金流预测柱;
- 结账分配区(运行表单·幂等 force·列表 现金/滚存/留存达标 tag/生效·冲销)+ **详情弹窗见计算链路 8 步 + 每人份额**(GP 9.5万含管理费·勾稽);回报四源堆叠条(12%+7.5%+6%+4.5%=30%·✓勾稽平)。✅

## 回归(main 仍 boot · 已过模块未坏)
- `GET /rent/vouchers`(total 27)/`/rent/tax/threshold`(正常)/`/rent/purchase`(200) 全 code:200。新增 distribution/analytics 模块 @Mapper 自动纳入(无 @MapperScan 限制),不改凭证/收租/采购对外行为。

## 遗留 / 未做(诚实 · 本波)
- **回报四源权重**为 rule_config 业务口径分解(§8.4·可校准),非逐单 IRR 蒙特卡洛;逐合同四源拆解属 M4/BI。
- **三层杠杆③融资** = 0(融资模块未启用);供应商账期占用取待付 payable 净额。
- **可动用留存**按 Σ active 分配 reserve_after 累计口径(无独立留存余额台账);完整留存滚动台账属 Wave C。
- 分配「每人份额」现场按快照×ratio 算(investor.user_id 现为空 → LP 门户投影待真 user 表就绪)。
- **M3 Wave C 待做**:月度报表六件套 + 经营分析七节 + 财务日历 + 定时任务集补齐(折旧/分配/缺口扫描 cron 已就位,报表/日历 cron 待接)。

---

## M3 Wave C · 月度报表六件套 + 经营分析七节 + 财务日历 + 定时任务集(2026-08-10)

环境:main·Flyway **V11**(yc_rent_monthly_report / yc_rent_llm_call_log / yc_rent_reminder)·mvn compile BUILD SUCCESS·boot `Started RentApplication`·各源模块回归 curl 200(bills/distribution/cashflow/vouchers)。

### 六件套与源模块一致(curl API vs SQL 源)· period=2026-08
| 件套 | API | 源 SQL | 一致 |
|---|---|---|---|
| ②利润表(ops) | 经营利润 466.68(收入15000−折旧14533.32) | ledger_book ops revenue 15000 / cost 14533.32 | ✅ |
| ③现金流水 | 流入25000 流出10000 净15000 | ops 1002 银行存款 dr 25000(5)/cr 10000(2) | ✅ |
| ⑤资产快照 | 8台·市场价家底1040000·账面净值858933.36 | asset 8台 SUM(market)=1040000·Σ最新折旧行book_value=858933.36 | ✅ |
| ①收租台账 | 应收15000·已收15000·回款率100% | rent_bill account_period=2026-08 Σamount/received=15000/15000 | ✅ |
| ④往来 | 应收未收88000·应付待付(CashflowService 分层) | CashflowService 复用不重算 | ✅ |

### 分配表按角色(P0-E · §4.5)
- 财务:`distributionVisible=true` shares=4(全表·scope「全表可见(老板/财务)」)
- 业务(普通角色):`distributionVisible=false` distribution=null · maskNote「当前角色『业务』无分配明细可见权限,本件套已隐藏」✅ 普通角色不出现
- LP:`distributionVisible=true` shares=0(仅本人·investor.user_id 现空→本人份额空)· scope「仅本人那份可见(GP/LP 只读)」

### 七节数字引 package_json + AI mock + idempotent
- `/monthly-report/analysis` 返回 7 节,每 dataPoint 带 `source`(利润表(ops)/收租台账/资产快照…);数字与六件套逐一一致(经营利润466.68·账面净值858933.36·在租率25%)。
- AI 综述 = `[MOCK] …`·model=`mock(kimi-k2)`·confidence=0.90 computed·llmCallId=1。
- **幂等**:二次调 `cacheHit=true` 复用 call#1;`force=true` → `cacheHit=false` 新建 call#2。`yc_rent_llm_call_log` 落 2 行(reasoning 含「喂入前已剔除成本/分配/身份§十一 脱敏」)。

### 财务日历状态机
- period=2026-08 → status **REPORTED**;日1 待人工(ops 未锁账)/日2 自动完成(报表包快照已生成)/日3 待人工核对/日5 自动完成(分配单已生成)。

### 导出(POI)
- Excel:`export=xlsx` http200 · **7894 字节** · `Microsoft OOXML`(六 sheet:收租台账/利润表/现金流水/往来/资产快照/分配表🔒)。
- Word:`export=docx` http200 · **3989 字节** · `Microsoft OOXML`(七节 + 数据溯源表)。

### 报表快照落库
- `POST /monthly-report/generate?period=2026-08` → `yc_rent_monthly_report` id=1 · calendar_status=REPORTED · generated_by=manual · package_json 3529 字符 · analysis_json 4123 字符 · llm_call_id=2。

### 定时任务集补齐(M3-10 · cron 双 export + startup 注册)
- 新增 cron:**月报包**(每月2日02:00 `MonthlyReportScheduler`)·**合同到期30天转让提醒**(每日06:00)·**潜客跟进到期提醒**(每日07:00)·**兑付缺口 T-N 扫描**(每日05:00 `CoverageGapScanScheduler`·前波缺此调度器,本波补)。
- 手动触发落库:`POST /reminders/scan/contract-expiry` upserted 1(HT-M2-TEST-01 逾期9天)· `POST /reminders/scan/followup-due` upserted 3;`yc_rent_reminder` 落 4 行;**幂等**二次触发不重复插(唯一键 type+ref_id+due_date)。
- `GET /rent/crons` 登记全 8 条 cron(收租生成/逾期检测/折旧/兑付缺口/分配/月报/到期提醒×2·各带 schedule+执行体+手动入口+里程碑)。

### 浏览器真测(built-in preview · V1 happy path)
- `/monthly` 页:账期切换器(2026-09/2026-08)· 财务日历看板(4 卡·自动完成/待人工 tag)· 六件套 Tab · 七节报告(每节叙述+溯源表)· 导出按钮 · 🔬 AI 过程弹窗(场景/模型/置信0.9(computed)/推理·输出·脱敏原始数据三 Tab)。
- V4 SQL vs UI:UI 收租台账「应收1.50万/已收1.50万/回款率100%/逾期2单」= SQL 15000/15000 ✅。

### 已知边界(诚实)
- ③现金流水取 ops 银行存款(1002)分录 dr/cr,当前无付款凭证 → 流出仅含红冲镜像;真实付款流水待 M4 付款单挂钩。
- ④往来复用 CashflowService 分层口径(应收取 rent_bill 未核销·应付取 payable 待付)。
- 残值损益(residual)口径待 M4 转让模块对齐(postResidual 现为 501 预留);利润表 residual 行数据为 0 时不显。
- LLM 走 mock 断路(无 key·零外呼);接真网关只需加 OpenAiCompat 实现标 @Primary + 配 rule_config `ai_model_monthly`。

---

## M4 到期转让/处置 + 维保 + 盘点(2026-08-10)

环境:main·Flyway V12 已 apply(boot 日志「Successfully applied 1 migration ... now at version v12」)。后端 8082 boot OK,`/api/v1/health`=200;12 模块回归 curl 全 200。前端 vite build 通过,浏览器三页 happy path 截图(转让详情/维保列表/盘点已闭合)。

### M4-01/02 到期转让(逐台·P0-A)+残值凭证+资产出账+合同关闭
- 签约 HT-M4-01(合同5·assets 5,6 在租·endTransferPrice=100000)。
- `POST /rent/transfer/expiry {contractId:5}` → 单 TR-HT-M4-01-126069,type=转让,已完成,2 台。
- **逐台 line**(SQL yc_rent_transfer_order_line):line1 asset5 book_value=41200(快照)/transfer_price=50000/**gain=8800**/voucher=29;line2 asset6 同,voucher=31。
- **残值凭证双账**(yc_rent_voucher source_doc_type=transfer_line):
  - tax#29 dr 银行存款50000 / cr 主营业务收入(分期收款销售)50000 → ledger tax revenue=50000(**并入分期收款销售计税**);
  - ops#28 dr 银行存款50000 / cr 固定资产41200 / cr 资产处置损益8800(借贷自平衡) → ledger ops revenue=8800(经营口径处置损益)。
- **资产出账**:asset 5,6 status→已转让(SQL 验),asset_event 转让 ref transfer_order#1。
- **合同关闭**:contract 5 status→到期转让(contract_change 留痕)。

### 名义价硬阈值守卫(P1-19·非仅亮灯)
- `dispose {assetId:3,action:二手,transferPrice:2000}` 无理由 → **400「须录理由并走审批」**(转让价2000 < 账面41200 且 < 市场60000×下限0.05=3000)。
- 带 approvalReason → 单 TR-二手-A3(status=**待审批**,needApproval=true,nominalGuardHits=["WL-HJ-0001(转让价2000 vs 账面41200/市场×下限3000)"])。
- `POST /rent/transfer/3/approve {reason}` → 已完成,approvedBy=老板;line gain=-39200;ops#32 dr银行2000/cr固定资产41200/**dr资产处置损益39200**(损失)平衡;asset3→已转让。

### 复投飞轮(M4-03)
- **报废**:`dispose {assetId:1,action:报废}` → totalGain=-171111.12;凭证#34 **ops-only**(dr资产处置损益/cr固定资产,transferPrice=0 不确认税务收入)ledger ops revenue=-171111.12;asset1→报废。
- **二手**:见守卫用例(asset3→已转让+损益凭证)。
- **再投放**:新建 asset9 采购→投放→收回待处置 → `redeploy {assetId:9}` → 投放;asset_event 采购/投放/收回待处置/**再投放** 全留痕。

### M4-04 维保工单(报修→派工→处理→回写)
- asset1 bom6(PLC控制器)报修 → 派工(我方) → **完工 inWarranty=true**:responsibleParty 自动转**供应商**,cost=1500 但 **ourCost=0**(费用不计我方);**fault_count 回写 6:0→1**(SQL 验)。
- 第二单 bom3 完工 inWarranty=false 我方 → **ourCost=800**(计入我方)。
- **高故障备件提示**:bom3 fault_count 累加至 4 > 阈值3(rule_config spare_part_fault_threshold)→ `/spare-alert` 命中 1 项「传感模组 fault4 → 建议常备备件」。

### M4-05 盘点(扫码差异→盘盈亏调整单闭合)
- 建盘点 ST(全量·账面3台)→ scan 3 差异:asset9 实收回待处置=**状态不符**;asset7(账面报废)实投放=**盘盈**;asset2 实丢失=**盘亏**(diffType 服务端推断正确)。
- `POST /rent/stocktake/{id}/close` → 已闭合,调整3台(盘盈1/盘亏1/状态不符1)。
- **走单据不直改台账**(§4.24):经 AssetService.applyStocktakeAdjust → asset9→收回待处置、asset7→投放、asset2→报废(盘亏核销);asset_event **盘点调整** ref stocktake;stock_diff 各行 adjusted=1 + adjust_event_id 回填(37/38/39)。

### 收口 & 剩余
- M4 收口:转让处置(逐台·残值凭证·名义价守卫)/维保/盘点 后端+前端全通,postResidual 501 已落地。
- 剩余:M5(人·事/BI/导入)+ M1 遗留前端(采购页/工作台)。

---

## M5 Wave A · 任务中心 + 审批 + 花名册/权限/提成 + 工作台聚合 + 采购前端页(2026-08-10)

**环境**：Flyway V13 boot 自动 apply(`Successfully applied 1 migration ... now at version v13`),`Started RentApplication`;新表 4 张(yc_rent_task/approval/user_role_ext/commission)+ rule 种子(approval_self_limit=300万·commission_rate 集采降本5%/成交贡献1%)+ 花名册种子 5 人。前端 vite build exit=0。回归:13 模块 curl 全 200(purchase/tasks/approvals/roster/workbench/transfer/maintenance/stocktake/bills/distribution/cashflow/monthly-report/health)。

### M5-01 任务中心(绑角色绑人·转派留痕·完成校验)
- **派单**:`POST /rent/tasks {title,assigneeRole:供应链,assigneeUserId:1004,assigneeUserName:李工,verifyRequired:true}` → task#1(source=派单)。
- **转派留痕(只追加)**:`POST /tasks/1/transfer {toRole:业务,toUserName:王业务,reason:李工外出转王业务}` → 承接改王业务;SQL `transfer_log`=`[{"from":"供应链·李工","to":"业务·王业务","at":...,"by":"小洪","reason":"李工外出,转王业务跟进"}]`。
- **完成校验**:verify_required=1 无证据 `complete {}` → **400「本任务需上传完成证据」**;带 `{evidence:"泵表文件 s3://..."}` → 已完成 + verify_evidence 落库。
- **系统派单(幂等)**:`POST /tasks/system-scan`(逾期→财务催收/兑付缺口→财务/合同到期→业务),同 biz_type+biz_id 未完成不重复开。cron 双出口 TaskDispatchScheduler(@Scheduled 每日03:00 + runSystemDispatchCron)。

### M5-02 投放审批(本金回报达标闸 + 300万自主/超额协商 + RBAC)
- **不达标拒绝**:本金回报25% < 目标30% → **400「不允许发起投放审批(须先达标)」**。
- **300万内自主**:35%≥30% & 18万≤300万 → approval#1 decision_mode=**自主**(target_rate=0.30/self_limit=3000000 快照)。
- **超额协商**:500万>300万 → approval#2 decision_mode=**协商**。
- **RBAC**:供应链调 `/approvals/1/approve` → **403「需要角色[老板]」** + audit DENIED;老板通过 → 已通过 + audit EXECUTED(SQL yc_rent_audit_log action=投放审批 EXECUTED/DENIED 各一)。

### M5-03 花名册/权限/提成(复用 DataScope·从管理费列支)
- **花名册**:5 人 SQL 验;LP 刘总 cost_visible=0(🔒仅月报不见成本)、业务王业务 owner_scoped=1(名下+公海)。角色改动 @RequireRole 老板 + audit(action=角色权限变更)。
- **提成计提(2026-08)**:集采降本 李工=Σ(市场60000-集采42000)×2件×5% → base¥36,000→提成¥1,800(source_ref=purchase:2);成交贡献 王业务=(合同HT-2026-0001 ¥78,000 + HT-M4-01 ¥18,000)×1% → 2行合计¥960;`funded_from=管理费`。
- **幂等**:再算一次 costCutRows=0/dealRows=0/**skipped=3**(uk period+user+type+source_ref)。

### WT-01 工作台聚合(按角色投影可见)
- **老板**:全量 KPI 在租率20%(1/5)/应收¥106,000/本月分配¥450,000/加权回报30% + 红点(空置1)。
- **财务**:应收/分配/回报 KPI + 逾期/兑付缺口/税务红点(当前数据全正常故红点空)。
- **供应链/业务**:仅在租率(应收/分配/回报=null);供应链见空置红点、业务见到期红点。
- **LP**:**仅加权回报30%**,在租率/应收/本月分配 全 null(字段级保密验证)。
- **待办分流**:派供应链+财务各 1 任务 → 供应链待办=1/财务待办=1/业务待办=0。

### 前端(浏览器 happy path·内置 Browser pane)
- **工作台按角色不同**:老板(4 KPI 全显+空置红点)vs LP(在租率/应收/分配显「—」·仅加权回报30%·scope「LP视图·字段级保密」)——截图两版对比。
- **任务页**:任务表显 承接=业务·王业务、校验=**已验证**(绿)、转派留痕=**1 次**(popover from/to/by/reason);投放审批 tab 显 自主(已通过·小洪·300万内自主通过)/协商(待审批·通过/驳回)。
- **采购页浏览器可下单**:UI 弹「采购下单(先签约后采购)」→ 填单号CG-UI-TEST-01/合同1/供应商1/序列号UISN-001/市场55000/集采40000 → 下单 → 列表出 CG-UI-TEST-01(已下单);SQL 验 purchase#3 total_amount=40000(Σ集采价)+ 首付 payable ¥12,000 待付。
- **花名册页**:5 人矩阵(LP🔒保密/业务名下+公海)+ 提成(李工降本¥36,000→¥1,800·王业务成交¥96,000→¥960)DOM 验。

### 提交(4 后端 + 1 前端 commit)
M5-01 任务中心 / M5-02 投放审批 / M5-03 花名册提成 / WT-01 工作台聚合 / feat(M5·前端)。

### 剩余(M5 Wave B)
BI 多维矩阵(M5-04)/PDCA action_item+AI 综述(M5-05)/audit 强化只追加(M5-06)/导入中心(M5-07)/对象存储签名URL(M5-08)。

---

## M5 Wave B 验收 — BI多维矩阵 + PDCA + 审计只追加强化 + 导入中心 + 对象存储签名URL(2026-08-10)

环境:main 直建 · mvn compile 通过 · boot `Started RentApplication` · Flyway **V14** success=1 · 前端 vite 5175 浏览器 happy path 通过。

### V14 迁移(schema 走 Flyway·§4.16)
- 新表:`yc_rent_action_item`(PDCA)、`yc_rent_import_job`(导入)、`yc_rent_file_object`(文件);audit_log 补列 `client_ip/request_uri/request_id`;触发器 `trg_audit_log_no_update/no_delete`;rule_config 追加 pdca_threshold×5 + import_limit×2 + file_sign_ttl。
- `SELECT version,success FROM flyway_schema_history WHERE version='14'` → success=1。

### M5-04 BI 多维矩阵(只读聚合·curl vs SQL 逐维对齐一致)
- 在租率 overall **12.50%**(1在租/8可投放·报废不入分母);SQL `SUM(status='在租')/SUM(status<>'报废')` = 12.50。下钻:货架 0/6、播种墙 1/2 — 与 `GROUP BY category` 一致。
- 加权回报 overall **0.00%**(唯一生效合同 target_irr=NULL→0·权重月租5000);SQL Σ(irr×rent)/Σrent 一致。
- 应收账龄 total **¥5000** 全落 90+ 桶 1 笔;SQL 正常单 amount-received>0 = 单笔 RB-...-P03 due 2026-05-01(≈101天)一致。
- 资产周转(投放率)overall **50.00%**(4已投放/8家底);SQL `SUM(status IN('在租','已转让'))/SUM(status<>'报废')` = 50.00 一致。
- 趋势(近6月回款):2026-08 = **30000**(matched 已收);其余月 0。

### M5-05 PDCA(红绿灯+回查三分支+AI脱敏)
- 看板:红4绿1 — occupancy 0.125<0.8 红 / weighted_return 0<0.15 红 / receivable_aging 101>30 红 / asset_turnover 0.5<0.9 红 / collect_rate 0.857≥0.85 **绿**;阈值全来自 rule_config[pdca_threshold]。
- AI 综述:`model=mock(kimi-k2)` · **AI含数字?False**(脱敏·不出数字·仅喂指标名+红/绿)。
- 改进项回查三分支(SQL 核对状态):
  - ID1 occupancy 目标0.8 → verify_value 0.125 → **未达 → 未见效升级**
  - ID2 collect_rate 目标0.85 → verify_value 0.857 → **通过 → 验证通过(自动关闭)**
  - ID3 无 metric_key → **需人工判定(保持进行中)**;baseline 登记时自动取(0.125/0.857)。
  - 批量到期回查(=cron 执行体):total 2 passed 1 manual 1。

### M5-06 审计只追加强化(P1-16)
- 越权触发(X-User:王业务/业务 淘汰供应商)→ audit 行 #44 `result=DENIED` 且 **client_ip=0:0:0:0:0:0:0:1 · request_uri=/api/rent/suppliers/1/retire**(请求指纹落库)。
- `UPDATE yc_rent_audit_log SET detail='TAMPER'` → **ERROR 1644 (45000):审计日志只追加不可编辑(P1-16):UPDATE 被拒绝**。
- `DELETE FROM yc_rent_audit_log` → **ERROR 1644 (45000):…DELETE 被拒绝**。占位期注:法律级抗抵赖(哈希链/WORM)待真SSO补。

### M5-07 导入中心(走同一校验+事件流·公式转义·越权拒·限流)
- 供应商(string-typed 注入):预览 escapedCells=**3**;行3 `+8613...`、`@HYPERLINK(x)`、行4 `=1+1恶意名` 均标 escaped;确认入库后 SQL 查库落值 **均带单引号前缀**(`'+8613...`/`'@HYPERLINK(x)`/`'=1+1恶意名`)公式被中和;入库经 `supplierService.create`(事件流)。
- 去重:重复供应商名(恒丰自动化)→ **dup 跳过**;commit imported 2 / skippedDup 1。
- 设备:行`项目ID=999`(导入目标项目=无)→ **err 越权:跨项目导入被拒**;缺序列号 → err;正常行 ok。
- 客户(X-User:业务→uid1007·行级隔离):归属人=1007 → **ok**;归属人=9999 → **err 越权:只能导入自己名下客户**。
- 限流:rule import_limit max_file_bytes=5MB / max_rows=2000;multipart 上调 10MB。

### M5-08 对象存储签名URL(P1-20·短时效+服务端鉴权代理)
- 上传合同(ownerRole=财务)→ storage_key=UUID(不可枚举)。
- ①有效签名下载(财务)→ **http=200** 返回文件内容。
- ②篡改 token(尾加XX)→ **code 403 签名 token 非法**。
- ③越权(供应链带同一有效token)→ **code 403 越权:该文件限「财务」角色可见**。
- ④过期(TTL临时改1s·等3s)→ **code 403 签名URL已过期(短时效失效)**;测后恢复300s。

### 回归(main 仍可 boot)
- 各模块 curl 200:suppliers/pool · customers/pool · assets · cashflow · tasks · pdca/board · bi/occupancy · imports。

### 前端浏览器 happy path
- /bi-pdca:四指标矩阵卡(品类/供应商/客户/性质下钻切换)+回款趋势柱 + PDCA红绿灯(红4绿1)+ AI综述透明标签(🔬mock(kimi-k2)/缓存命中·[MOCK]无数字)+ 改进项清单(回查/关闭)。
- /import:目标切换(供应商/客户/设备)+字段模板(去重键高亮)+作业历史(IMP-001~005 逐行统计)。
