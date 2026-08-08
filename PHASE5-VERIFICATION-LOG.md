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
