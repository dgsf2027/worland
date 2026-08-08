# 沃朗科技租赁板块 · Phase 2 功能拆解 TODO

> 版本：v1.0　|　日期：2026-08-08　|　流程：v2.0 F1 · Phase 2
> 上游真相源：`DESIGN_DOC.md`(v0.2) + `系统方案/03_业务流与操作流.md` + `系统方案/12_Phase1.5_设计评审纪要.md`
> 每完成 1 ticket → git micro-commit + 就地真测留证据到 `PHASE5-VERIFICATION-LOG.md`（§4.21，禁攒到 Phase 5）。
> 覆盖功能点 ID 约定：`流程N`=业务流 12 条；`8.x/9.x`=业务流深度拆解/CRM；`4.x`=DESIGN 数据模型；`API-五`=DESIGN 第五节 API；`P0-x / P1-x`=评审纪要清单。

---

## 一、里程碑概览 + Ticket 计数

| 里程碑 | 主题 | Ticket 数 | 含 P0 |
|---|---|---|---|
| **冲刺0** | 地基·脚手架·对平·占位头·配置源 | 7 | P0-C |
| **M1** | 业务地基（供应商/客户CRM/逐件设备/报价器/合同+租金计划/采购/隔离） | 18 | P0-D · P0-E · P0-G |
| **M2** | 收租闭环（收租单/核销/红冲/逾期/亮灯） | 6 | P0-F |
| **M3** | 财务分配（双账/折旧/管理费/分配/现金流/缺口预警/月报） | 9 | P0-B · P0-H |
| **M4** | 转让资产（转让拆行/状态机/维保/盘点） | 5 | P0-A |
| **M5** | 事·人·BI（任务/审批/花名册/BI/PDCA/审计/导入/存储） | 8 | — |
| **合计** | — | **53** | **8/8 全落位** |

**8 条 P0 上线红线落位**：①transfer 拆行 → `M4-01`；②折旧真相源 → `M3-02`；③网关剥离 X-User 头 → `S0-04`；④RBAC 矩阵 → `M1-15`；⑤行/字段级隔离服务端强制 → `M1-16`；⑥红冲原子性+幂等+锁账 → `M2-03`；⑦价值定价月节省输入+两校验 → `M1-08`；⑧账期兑付缺口预警 → `M3-06`。全部标 `[P0·上线红线]`，未过不上线。

**逆向路径落位**：合同作废/变更/续租/提前结清 → `M1-11`；采购退货红冲 → `M1-13`；收租红冲/退款 → `M2-03`；逾期还款恢复/收回待处置 → `M2-06`；转让·收回·二手·报废 → `M4-02`；盘盈亏调整 → `M4-05`。

---

## 冲刺0 · 地基

### S0-01　脚手架与仓库初始化
- **描述**：建前后端双仓 + DB。Spring Boot 2.7(JDK8 target) + Vue3+Vite + MySQL8；库表统一前缀 `yc_rent_`；金额单位=元（schema 注释标注）。CI 骨架 + 分层目录（Controller→Service→Mapper→MySQL；横切包 auth/isolation/event/rule/ai）。
- **覆盖功能点**：DESIGN 二/三（架构+技术栈）
- **依赖**：无
- **验收标准**：`git log` 有初始 commit；`mvn -q compile` 成功；前端 `pnpm build` 成功；连库跑一条 `SHOW TABLES LIKE 'yc_rent_%'`（此时为空但连接通）。
- **测试策略**：Bash 跑编译/build；SQL 连库验证前缀约定文档存在。

### S0-02　migration 手动 apply 框架 + accounting_period 期间锁
- **描述**：建 migration 目录（手写 SQL·§4.16 七步流程文档化，禁 JPA 自动 ddl）。首批建 `accounting_period(period, book, is_locked)` 期间锁表，为后续红冲/凭证锁账守卫打底。
- **覆盖功能点**：4.3(accounting_period)、P1-3、DESIGN 六(schema 手动 apply 风险)
- **依赖**：S0-01
- **验收标准**：`ALTER/CREATE` SQL 文件入库；prod-like 库执行后 `SHOW COLUMNS FROM yc_rent_accounting_period` 见 period/book/is_locked；migration README 含七步。
- **测试策略**：schema → ALTER + SHOW COLUMNS + 插一条 period 业务 query。

### S0-03　平台占位头 UserContext 适配层
- **描述**：实现 `UserContext` 适配层：从 X-User-* 占位头解析 user 主键/角色/项目，业务代码只依赖该抽象；平台切真 SSO（走 `ole-portal-sso`）时一处替换。占位期用 `X-User-Name` 解析真实 user 主键作隔离键。
- **覆盖功能点**：ADR-001、DESIGN 六(SSO 风险)、P0-E 前置
- **依赖**：S0-01
- **验收标准**：curl 带 `X-User-Name: 李工` → 接口内 `UserContext.getUserId()` 返真实主键（非 0）；缺头 → 401；单测覆盖解析。
- **测试策略**：新 API → curl 带/不带头两组；SQL 查解析出的 user 主键对得上。

### S0-04　网关剥离 X-User-* 头再重注入　`[P0·上线红线]`
- **描述**：部署拓扑硬前提：网关（Nginx/Gateway）**先剥离客户端自带 X-User-\* 头，再由可信网关重注入**；后端只信任网关注入的身份。写进部署文档 + ADR-001 上线红线。
- **覆盖功能点**：P0-C、DESIGN 十一-B、ADR-001
- **依赖**：S0-03
- **验收标准**：curl 从"客户端侧"伪造 `X-User-Name: 老板` → 经网关后被剥离，后端拿到的是网关按会话注入的真实身份（伪造无效）；部署 checklist 含该规则。
- **测试策略**：新 API/网关配置 → curl 伪造头穿透测试（伪造必失败）；对照网关 access log。

### S0-05　rule_config 版本化配置源
- **描述**：建 `rule_config`（key, value, version, effective_from, effective_to）承载所有口径常量：管理费阶梯、品类转让率(10%/30%/35%)、税率(3%减按1%·2027-12-31 sunset / 小微5%所得税)、目标 IRR、速算系数、留存下限、融资成本、坏账拨备(参照16.2万/10年)。禁硬编码。
- **覆盖功能点**：P2(rule_config)、P1-12、DESIGN 十一-A(规则常量)
- **依赖**：S0-02
- **验收标准**：`SELECT * FROM yc_rent_rule_config WHERE key='tax_vat'` 返带 effective_from/to 的多版本行；取值 helper 按业务日期取生效版本；税率 2027-12-31 sunset 命中不同版本。
- **测试策略**：schema → SHOW COLUMNS；跨 sunset 日期取值 SQL 两组对比。

### S0-06　报价测算器与设立方案书定价表逐格对平（税后口径）
- **描述**：实现报价核算规则引擎首版，与《设立方案书 V14.0》定价表**逐格对平**：层级①IRR 用真实集采成本/残值/账期重算、**税后口径**（1%增值税+12%附加+5%所得税）；客户总付勾稽 `期数×月租 + 转让价`（不含押金）。回传方案书 L123"10万/16667"疑似笔误。
- **覆盖功能点**：流程2、P1-11/12/13、DESIGN 十一-C、评审纪要五
- **依赖**：S0-05
- **验收标准**：对方案书每一档品类，`POST /api/rent/quote/calc` 返回的月租/三层回报/税后IRR 与定价表逐格一致（差异=0，留对平表证据）；勾稽等式成立。
- **测试策略**：新 API → curl 每档一组，输出 vs 方案书对平表（截图/表格证据入 log）。

### S0-07　单据事件流 + 凭证过账 Service 内核
- **描述**：复用小卖账房业财一体单据链内核：所有写单据 → 事件流 → 凭证。明确复用凭证过账 Service + 板块专属 `yc_rent_` 表（非重造）。派生数据只由事件流/单据算，禁直写。
- **覆盖功能点**：P1-8、DESIGN 八(复用清单)、流程12、第五部分单据流
- **依赖**：S0-02
- **验收标准**：写一条测试单据 → 自动落 1 条事件 + 1 张凭证；直写派生字段被 Service 拦截报错。
- **测试策略**：新 API → curl 建单；SQL 查事件流表 + 凭证表各 1 行。

---

## M1 · 业务地基

### M1-01　供应商池列表
- **描述**：`supplier` 池列表：搜索 + 按 品类/评分/账期/是否主供 多条件筛选、排序、分页；pipeline 状态（接触/试样/入库/主供/备供/淘汰）。支撑 N 家比价管理。
- **覆盖功能点**：流程1、9.4、8.2(关系生命周期)、4.2(supplier)
- **依赖**：S0-03、S0-07
- **验收标准**：`GET /api/rent/suppliers?filter=` 返分页；淘汰置"停用"留痕；每品类<2 家亮"单一依赖"灯。
- **测试策略**：新 API+UI → curl 筛选组合 + 浏览器截图列表/筛选/分页。

### M1-02　供应商详情（供货矩阵/价格构成/履约雷达/账期返利）
- **描述**：详情页含 `supplier_supply` 供货矩阵（整机/配件，配件级可挂多供应商比价）+ 价格构成拆解(材料+加工+利润 vs BOM/市场价)+ 履约雷达(品质/交期/服务/价格/账期加权总分自动排序)+ 账期与返利(首付/验收/尾款分层·账期↔回报联动)。
- **覆盖功能点**：8.2、4.2(supplier_supply)、API-五(suppliers/{id})
- **依赖**：M1-01
- **验收标准**：`GET /suppliers/{id}` 返矩阵+评分+账期；雷达五维加权总分与手算一致；主供/备供按总分排序。
- **测试策略**：新 API+UI → curl 校验评分字段；浏览器截图雷达+矩阵。

### M1-03　客户池列表（100+ 规模化）
- **描述**：`customer` 池全量列表：搜索 + 按 阶段/评级/行业/负责业务/价值分层/应收账龄 多条件筛选、排序、分页；保存的分群；负责人绑定 + 公海认领（业务只看自己+公海，保密隔离）；批量改阶段/派跟进/发提醒。
- **覆盖功能点**：9.3、4.2(customer)、API-五(customers)
- **依赖**：S0-03、M1-16(隔离)
- **验收标准**：`GET /customers?phase=&owner=` 分页；业务 A 登录只见自己+公海（SQL 验隔离）；分群可存取；批量动作生效。
- **测试策略**：新 API+UI → 两个 user 隔离对比 SQL；浏览器截图列表/筛选/分群/批量。

### M1-04　销售管道看板（潜客跟进）
- **描述**：Kanban（线索/跟进中/商机/已成交/流失）卡片可拖动推进阶段；`customer_followup` 跟进时间线（时间/方式/内容/结果/下一步）；`opportunity` 商机卡(品类/金额/成交概率/预计成交月)+管道加权预测；下次跟进日到期进待办、逾期未跟进亮灯；阶段对齐动作建议；商机一键转报价。
- **覆盖功能点**：9.1、9.2、4.2(customer_followup/opportunity)、API-五(followup/pipeline)
- **依赖**：M1-03
- **验收标准**：拖卡 → `crm_stage` 更新（SQL 验）；`POST /customers/{id}/followup` 落时间线；逾期未跟进客户亮灯；`GET /pipeline` 返加权预测。
- **测试策略**：新 API+UI → curl followup；浏览器截图看板拖动 + 待办亮灯 + 一键转报价。

### M1-05　客户详情（信用画像/LTV/风险敞口/下钻）
- **描述**：信用画像雷达(盈利/现金流/稳定/履约/行业→评级 A/B/C→授信/押金/目标IRR)+ 价值 LTV(累计合同/收租/利润/在租/续租率→战略/普通/观察)+ 风险敞口(单客户在租总额/应收/逾期→集中度预警)+ 跟进时间线 + 名下合同→租金计划→设备层层下钻。`crm_stage`(人工) 与运营态(派生自合同) 分离。
- **覆盖功能点**：8.3、9.2、9.5、4.2(customer 派生态)
- **依赖**：M1-04、M1-10(合同下钻)
- **验收标准**：`GET /customers/{id}` 返画像+LTV+敞口；运营态由合同事件派生（无直写）；下钻可达单台设备；集中度超阈亮灯。
- **测试策略**：新 API+UI → SQL 验派生态；浏览器截图雷达+下钻链路。

### M1-06　逐件设备建档（asset + BOM + 事件流 + 状态机）
- **描述**：`asset` 一台一条(serial_no/category/model/market_price/purchase_price/supplier_id/status/project_id/monthly_labor_value/replace_headcount/self_purchase_payback/purchase_in_id)；`asset_bom` 自引用多级配件树；`asset_event` 事件流驱动 status。`current_holder_customer_id` 由合同生效/关闭事件唯一维护（@writes 标注，防 stale·P1-9）；`residual_value` 不落列改 `_helpers` 即时算（P1-5）。
- **覆盖功能点**：4.1、流程6、8.1(配件树)、P1-5、P1-9
- **依赖**：S0-02、S0-07、M1-16(project_id 隔离)
- **验收标准**：`SHOW COLUMNS FROM yc_rent_asset` 含价值定价字段；建一台 → asset_event 落 1 行；status 只被事件流改（直写拒绝）；残值由 helper 即时算非读列。
- **测试策略**：schema → ALTER+SHOW COLUMNS+业务 query；curl 建档 → SQL 查事件流。

### M1-07　设备详情拆解（成本/残值/故障/单台收益）
- **描述**：设备详情页：配件树 BOM 逐级展开（配件亦是"物"可下钻挂供应商）+ 成本拆解环形/瀑布(采购+运输+安装 vs 集采价识别虚高)+ 残值构成(Σ 部件残值×折旧曲线)→转让定价参考 + 故障档案(按配件故障率/备件清单/质保倒计时)+ 单台收益(累计收租/月租分摊/在租天数/空置天数/回报率，空置亮灯)。
- **覆盖功能点**：8.1、4.1、API-五(assets/{id})
- **依赖**：M1-06
- **验收标准**：`GET /assets/{id}` 返 BOM 树+成本+残值+故障+事件流；单台回报率与手算一致；空置天数超阈亮灯。
- **测试策略**：新 API+UI → curl 校验树结构；浏览器截图成本瀑布+残值+单台收益卡。

### M1-08　报价测算器完整（价值定价必填+两校验+达标闸）　`[P0·上线红线]`
- **描述**：`POST /api/rent/quote/calc`：`monthly_labor_value`(月替代人工价值/月节省) **必填**；两道校验【①品类准入 `market_price/monthly_labor_value ≤ 18 月`，超阈亮"吸引力不足"；②客户净收益 `月节省 − 月租 > 0`（删旧"≤市场价"矛盾口径）】；速算系数仅作起价建议，层级①IRR 用真实集采成本/残值/账期税后重算；本金回报 ≥ 目标 IRR 才解锁"生成合同"，不达标锁按钮。回报四源(集采差价+资金时间价值+价值定价+残值W%，之和=总IRR·P1-6)。
- **覆盖功能点**：P0-G、流程2、P1-6/10/11、DESIGN 十一-C、8.4(回报四源)
- **依赖**：S0-06、S0-05
- **验收标准**：缺 monthly_labor_value → 422；payback>18 → 亮灯且不给达标；月节省≤月租 → 校验②失败；本金回报<目标IRR → "生成合同"按钮锁定；四源之和=总IRR（勾稽）。
- **测试策略**：新 API+UI → curl 边界四组（缺输入/超18月/净收益负/达标）；浏览器截图达标解锁 vs 锁定。

### M1-09　客户准入与风控
- **描述**：`customer` 风控档：评估盈利/现金流/稳定 → 品类限定 → 授信额度/押金月数/风控评级；不过则拒绝留痕、评级下调触发存量合同预警。**先签约后采购**前置（无准入无合同）。
- **覆盖功能点**：流程3、4.2(customer credit_limit/deposit_months/rating)
- **依赖**：M1-05
- **验收标准**：`POST` 准入 → 评级+授信落库；拒绝写 audit_log；评级下调 → 存量合同亮预警；无准入不能进签约。
- **测试策略**：新 API → curl 准入/拒绝两组 + SQL 查 audit；UI 截图预警。

### M1-10　合同签约 + 租金计划自动生成
- **描述**：`contract`(no/customer_id/term_months/month_rent/deposit/end_transfer_price/target_irr/nature='分期收款销售'/status/project_id) + `contract_asset`(N 台·`alloc_rent` 单台月租分摊单一真值·P1-2) + 签约自动生成 `rent_schedule` N 期(due_date/amount/plan_status)。勾稽 `期数×月租+转让价=客户总付`(不含押金)。禁"融资租赁"：枚举校验 + 自由文本/附件关键词拦截+人工复核(P1-18)。
- **覆盖功能点**：流程4、4.2(contract/contract_asset)、4.3(rent_schedule)、P1-2/13/18、API-五(contracts)
- **依赖**：M1-08(达标)、M1-09(准入)、M1-06(设备)
- **验收标准**：`POST /api/rent/contracts` → `rent_schedule` 生成 N 行（SQL count=term_months）；`SUM(alloc_rent)=month_rent`；勾稽等式成立；写入含"融资租赁"文本被拦。
- **测试策略**：新 API → curl 签约 → SQL 查 rent_schedule 行数+alloc 合计；UI 截图计划表；负例 curl 融资租赁文本。

### M1-11　合同作废 / 变更 / 续租 / 提前结清（逆向）
- **描述**：`contract_change`(含 reverse)：作废→整份红冲(限未采购)；变更(提前结清→剩余期一次性计 / 续租→重算剩余期)；合同 status 补"已作废"。走单据+影响清单确认，不直改。
- **覆盖功能点**：流程4逆向、流程7逆向(提前结清)、P1-3(contract_change)、状态机(合同)
- **依赖**：M1-10、M2-02(提前结清依赖收租核销)
- **验收标准**：`POST /contracts/{id}/void|change|renew` → 生成 contract_change 逆向单；作废后 status='已作废' 且 rent_schedule 未来期红冲；已采购的合同禁作废（报错）。
- **测试策略**：新 API → curl 三分支 + SQL 查逆向单/status；UI 截图影响清单确认页。

### M1-12　采购集采 + 应付计划（先签约后采购）
- **描述**：`purchase_in` 绑 `contract_id`（**无合同不允许生成采购单**gate·防空置）+ `purchase_item` 逐件生成 `asset`；`payable` 应付计划(首付/验收/尾款·按谈成账期)→应付凭证→计入负债率、进现金流驾驶舱。
- **覆盖功能点**：流程5、4.3(purchase_in/purchase_item/payable)、API-五(purchase)
- **依赖**：M1-10、S0-07
- **验收标准**：无合同 `POST /api/rent/purchase` → 拒绝；有合同 → 生成 payable 三阶段 + 应付凭证 + 逐件 asset；SQL 查 asset 数=采购件数。
- **测试策略**：新 API → curl 无合同(负例)/有合同两组 → SQL 查 payable+asset+凭证。

### M1-13　采购退货红冲（逆向）
- **描述**：`POST /purchase/{id}/return`：采购红冲 + 应付红字；到货不符→拒收留痕。走事件流+影响清单。
- **覆盖功能点**：流程5逆向、状态机(purchase_in 退货红冲/拒收)
- **依赖**：M1-12、S0-07
- **验收标准**：退货 → 生成红冲凭证(is_reversal=1, reverses_id 指向原单) + 应付红字；对应 asset 状态回退/作废；影响清单弹确认。
- **测试策略**：新 API → curl 退货 → SQL 查红冲凭证+应付红字+asset 状态。

### M1-14　入库投放交付
- **描述**：采购到货 → 逐件编号建档(序列号/条码) → 现场交付拍照留痕 → 起租 → 押金入账。状态机 采购→投放→在租；起租日=租金计划首期起算日；未起租设备亮"空置"灯。
- **覆盖功能点**：流程6、状态机(asset)、4.1(asset_event)
- **依赖**：M1-12、M1-17(押金)
- **验收标准**：交付 → asset_event 记 投放→在租；`起租日 = rent_schedule 首期 due 起算`；未起租设备列表亮空置灯；现场照存对象存储(签名URL·M5-08)。
- **测试策略**：新 API+UI → SQL 查状态机事件；浏览器截图交付页+空置亮灯。

### M1-15　敏感操作 RBAC 矩阵 + 统一鉴权切面　`[P0·上线红线]`
- **描述**：「敏感操作 × 角色」权限矩阵挂**统一鉴权切面**（非散在 Controller）：红冲/作废→财务+老板；分配→财务；投放审批→老板；淘汰→供应链主管+老板。留痕≠管控，先卡权限再执行。
- **覆盖功能点**：P0-D、DESIGN 十一-B、4.4(角色)
- **依赖**：S0-03
- **验收标准**：以"供应链"身份 curl 红冲接口 → 403（切面拦截，未进业务）；以"财务"→放行；矩阵配置化可查；越权尝试入 audit。
- **测试策略**：新 API/切面 → curl 每类敏感操作 × 有权/无权角色两组；SQL 查越权 audit。

### M1-16　行级+字段级隔离服务端 DAO/DTO 强制 + project_id　`[P0·上线红线]`
- **描述**：隔离在**服务端 DAO/DTO 层强制**：行级 `owner_user`/`project_id`（业务只见自己+公海，多项目独立核算）；字段级成本价/账期/分配/gain 按角色 DTO 投影（LP 不见成本）。`contract/asset/purchase_in/voucher/distribution` 统一加 `project_id`(+必要 owner_user·P1-4)。月报按角色分别落库/渲染，禁单 blob 下发。
- **覆盖功能点**：P0-E、4.5、P1-4、DESIGN 十一-B
- **依赖**：S0-03
- **验收标准**：`SHOW COLUMNS` 五张表均含 project_id；LP 角色 curl 返回 DTO 无 cost/gain 字段（非前端隐藏，服务端投影）；跨 project 查询被 DAO 过滤（SQL 对比）。
- **测试策略**：schema → SHOW COLUMNS 五表；curl 多角色/多项目返回体字段差异对比 + SQL 验行过滤。

### M1-17　押金全生命周期台账 deposit_ledger
- **描述**：`deposit_ledger`(收/退/期末抵转让价)：押金单独走保证金科目，不进客户总付净额；期末可抵转让价；押金覆盖率监控。
- **覆盖功能点**：P2(deposit_ledger)、流程4(押金)、P1-13
- **依赖**：S0-02、M1-10
- **验收标准**：`SHOW COLUMNS FROM yc_rent_deposit_ledger`；收押金→保证金科目凭证；期末抵转让 → 台账记抵扣；勾稽等式不含押金（对 M1-10 复核）。
- **测试策略**：schema → SHOW COLUMNS；curl 收/退/抵三动作 → SQL 查台账+凭证科目。

### M1-18　每期租金构成拆解
- **描述**：合同页「租金构成」拆解条：每期租金 = 本金摊 + 资金成本 + 差价分摊 + 残值预留（"这份合同每期赚在哪"）；单笔 P&L(收租−集采−资金成本−运维−坏账拨备−管理费分摊)。
- **覆盖功能点**：8.4(每期租金构成/单笔盈亏)
- **依赖**：M1-10、S0-05(坏账拨备参数)
- **验收标准**：合同页返每期四段构成，四段之和=月租；单笔 P&L 各减项来源可溯。
- **测试策略**：新 API+UI → curl 校验四段合计；浏览器截图构成条。

---

## M2 · 收租闭环

### M2-01　收租单自动生成 cron
- **描述**：租金计划每期到期 T-3 自动生成 `rent_bill`（cron 双 export runXxxCron+startXxxScheduler，禁 require.main·§4.14）；`rent_bill` 为收款态**单一真相源**，`rent_schedule.plan_status` 单向回写(P1-1)。
- **覆盖功能点**：流程7、4.3(rent_bill)、P1-1、状态机(rent_bill 待生成→待收)
- **依赖**：M1-10、S0-07
- **验收标准**：手动触发 cron → 到期期次生成 rent_bill（SQL count 对）；rent_schedule.plan_status='已生成单' 单向回写；幂等不重复生成。
- **测试策略**：cron → 手动触发 + SQL 查 rent_bill/plan_status；重复触发验幂等。

### M2-02　到账匹配核销 + 收入凭证
- **描述**：客户付款到账(微信/银行流水)自动匹配 → 核销 → 收入凭证；已核销期回填凭证号。`POST /bills/{id}/match`。
- **覆盖功能点**：流程7、API-五(bills/match)、状态机(rent_bill 已核销)
- **依赖**：M2-01、S0-07
- **验收标准**：`POST /bills/{id}/match` → status='已核销' + 收入凭证生成 + 凭证号回填；received_amount/matched_at 落库。
- **测试策略**：新 API → curl 核销 → SQL 查 rent_bill 状态+凭证号+voucher。

### M2-03　收租红冲 / 退款（原子性+幂等+锁账）　`[P0·上线红线]`
- **描述**：`POST /bills/{id}/reverse|refund`：红冲连锁在**单事务**内完成，失败整体回滚；带**幂等键**（`reverses_id` 唯一约束 + 幂等 token）；服务端硬守卫：落 `locked_period` 的凭证写一律拒绝，只走显式"上期调整"单。命名统一 `reverses_id`+`is_reversal`。必过"影响清单"确认页。
- **覆盖功能点**：P0-F、流程7逆向、流程12(红冲连锁)、P2(命名统一)、状态机(rent_bill 红冲)
- **依赖**：M2-02、S0-02(accounting_period)、M1-15(RBAC)
- **验收标准**：并发/重复调 reverse 同一单 → 仅 1 条红冲（唯一约束挡重）；红冲中途抛异常 → 全回滚(应收/凭证无残留)；对锁定期写 → 拒绝并提示走上期调整；影响清单先弹确认。
- **测试策略**：新 API → curl 重复调验幂等 + SQL 查唯一约束；模拟异常验事务回滚；锁定期负例；UI 截图影响清单。

### M2-04　"钱该动没动"稽核亮灯
- **描述**：`GET /audit/钱该动没动`：到期未生成收租单 / 已收未核销 / 该核销未回凭证 全部亮灯。钱只被单据改的稽核出口。
- **覆盖功能点**：流程7(亮灯)、第五部分单据流(铁律)、API-五(audit)
- **依赖**：M2-02
- **验收标准**：造一条"到期未生成"数据 → 稽核 API 返该项亮灯；修复后消灯；对账口径与凭证同源。
- **测试策略**：新 API → 造异常数据 curl 稽核 → 亮灯；修复后复查消灯。

### M2-05　逾期案状态机（延期→罚息→锁机/收回）
- **描述**：`overdue_case` 三步走（延期→罚息→锁机/收回，内外一视同仁）：自动短信/升级人工/生成罚息单/收回单；每案必有"裁决人+下一步+期限"。`POST /overdue/{id}/延期|罚息|锁机|收回`。
- **覆盖功能点**：流程8、4.3(overdue_case)、状态机(overdue_case)、API-五(overdue)
- **依赖**：M2-01
- **验收标准**：逾期 rent_bill → 自动开 overdue_case；每步流转 SQL 验状态+罚息单/收回单；缺裁决人/期限的案亮灯。
- **测试策略**：新 API+UI → curl 逐步流转 + SQL 查状态；截图裁决人/期限必填。

### M2-06　逾期还款恢复 + 收回待处置流转（逆向）
- **描述**：还款→案关闭、合同恢复；收回设备→asset 状态转"收回待处置"、评估残值二次流转(再投放/二手/报废)进空置/再投放闭环。`POST /overdue/{id}/还款恢复`。
- **覆盖功能点**：流程8逆向、状态机(overdue 还款恢复 / asset 收回待处置)
- **依赖**：M2-05、M1-06(asset 状态机)
- **验收标准**：还款恢复 → overdue_case='关闭' + 合同恢复收租；收回 → asset.status='收回待处置' 进待处置池（SQL 验）。
- **测试策略**：新 API → curl 两分支 → SQL 查 overdue/asset 状态；UI 截图待处置池。

---

## M3 · 财务分配

### M3-01　凭证 + 双套账引擎
- **描述**：`voucher` + `voucher_line`(account, direction dr/cr, amount·P2) + `ledger_book` 双账(tax'分期收款销售' / ops'三层回报')。复用凭证过账 Service。任何业务单据确认→自动凭证→两套账各记。
- **覆盖功能点**：流程12、4.3(voucher/voucher_line/ledger_book)、P2(voucher_line 借贷)、ADR-003
- **依赖**：S0-07、S0-02
- **验收标准**：`SHOW COLUMNS FROM yc_rent_voucher_line` 含 direction/amount；一张凭证借贷平衡(SUM dr=SUM cr)；同一单据 tax/ops 双账各生成。
- **测试策略**：schema → SHOW COLUMNS；curl 建单 → SQL 查双账各一套 + 借贷平衡。

### M3-02　折旧真相源 asset_depreciation_line + book_value 回填　`[P0·上线红线]`
- **描述**：`asset_depreciation_line`(asset_id, book(ops), period_no, depr_amount, book_value_after, voucher_id) 作折旧真相源（补 book_value 唯一写手，解决"派生无写手"stale·P0-B）；`asset.book_value` 仅经营口径 ops、由本表即时算/回填；税务账走应收不在此。抄 Odoo `account.asset.line`。→ ADR-004。
- **覆盖功能点**：P0-B、4.1(book_value)、4.3(asset_depreciation_line)、ADR-004、DESIGN 十一-A
- **依赖**：M3-01、M1-06
- **验收标准**：`SHOW COLUMNS FROM yc_rent_asset_depreciation_line`；跑折旧 → 逐期 book_value_after 递减且=手算；`asset.book_value` 读值=折旧表最新 book_value_after（无第二写手）。
- **测试策略**：schema → ALTER+SHOW COLUMNS；curl/cron 跑折旧 → SQL 查逐期 book_value vs 手算曲线。

### M3-03　管理费阶梯计提
- **描述**：月度按 `rule_config` 管理费阶梯(带生效期)计提 `distribution.mgmt_fee`；口径与凭证同源。
- **覆盖功能点**：流程11、4.3(distribution mgmt_fee_rate/mgmt_fee)、S0-05
- **依赖**：M3-01、S0-05
- **验收标准**：给定利润 → 命中正确阶梯档、mgmt_fee 与手算一致；阶梯从 rule_config 取生效版本（非硬编码）。
- **测试策略**：新 API → curl 跨阶梯档三组 → 对手算表。

### M3-04　分配 distribution/run（幂等+冲销/重算）
- **描述**：`POST /distribution/run`：profit_before→管理费→distributable→50%现金/50%滚存，`留存 ≥ max(20万, 未来3月供应商净应付)`(P1-15)；当期无可分配→结转、年终清算多退少补。**按 period 幂等 + 冲销/重算 API**(P1-7)。`investor`(GP/LP) 仅供计算无门户。分配表按角色可见(隔离)。
- **覆盖功能点**：流程11、4.3(distribution/investor)、P1-7、P1-15、API-五(distribution/run)
- **依赖**：M3-03、M1-16(隔离)
- **验收标准**：同 period 重复 run → 幂等不重复分配；留存<下限则不足额分配并亮灯；冲销 API 可回退重算；LP 视图分配表按角色投影。
- **测试策略**：新 API → curl 重复 run 验幂等 + 冲销重算 + SQL 查留存下限；多角色分配表对比。

### M3-05　现金流驾驶舱
- **描述**：应收按到期分层 + 应付(payable)按到期分层 + 净现金流预测曲线 + 三层杠杆占用。层级③投放前校验 `资产回报 − 融资成本 ≥ 安全边际` + 负债率/融资额度上限(P1-14)。
- **覆盖功能点**：8.4(现金流)、P1-14、流程5(应付进驾驶舱)
- **依赖**：M1-12(payable)、M2-02(应收)
- **验收标准**：驾驶舱返应收/应付分层 + 净现金流曲线；投放前校验安全边际不足→拦截；负债率超上限亮灯。
- **测试策略**：新 API+UI → curl 校验分层；浏览器截图净现金流曲线 + 杠杆临界拦截。

### M3-06　账期兑付缺口预警红灯　`[P0·上线红线]`
- **描述**：payable 尾款到期 T-N，规则引擎比对"该账期对应设备在租/逾期/空置 + 未来 N 月回款 + 可动用留存"，`应付到期 − 可用回款 − 可动留存 < 0` → 亮"账期兑付缺口"红灯 + **裁决人 + 补款来源**。层级②杠杆刹车片。
- **覆盖功能点**：P0-H、流程11、P1-15、DESIGN 十一-D
- **依赖**：M3-05
- **验收标准**：造缺口<0 场景 → 红灯亮 + 强制填裁决人+补款来源；缺口≥0 无灯；阈值口径与手算一致。
- **测试策略**：新 API+UI → 造缺口数据 curl → SQL/截图验红灯+裁决人字段必填。

### M3-07　月度报表包六件套 + 经营分析报告七节
- **描述**：`monthly_report`(package_json 六件套·数字来自规则引擎, analysis_json 七节·AI 仅起草·§4.7 透明四件套+idempotent, calendar_status)。月结日历 1日结账→2日报表包→3日核对→5日过报告+分配。`GET /monthly-report?period=&export=xlsx|docx`。报表口径与凭证同源。
- **覆盖功能点**：流程11、4.3(monthly_report)、DESIGN 八(AI 透明四件套)、API-五(monthly-report)
- **依赖**：M3-04、M3-01
- **验收标准**：`GET /monthly-report?period=` 返六件套；导出 xlsx/docx 成功；AI 综述走 invokeLLMWithRecord 落库+可换模型+24h idempotent；报表数字=凭证台账（对账为0差异）。
- **测试策略**：新 API → curl 导出 xlsx/docx；SQL 对账报表 vs 凭证；截图 AI 透明四件套入口。

### M3-08　500万营收红线 + 税务账口径监控
- **描述**：营收逼近 500 万预警（转一般纳税人）；按**税务账**口径计算(P2)。
- **覆盖功能点**：流程12、DESIGN 六(税务合规)、P2(500万税务账口径)
- **依赖**：M3-01
- **验收标准**：税务账累计营收逼近 500 万 → 亮灯预警；口径取 tax ledger（非 ops）。
- **测试策略**：新 API → 造逼近数据 curl → SQL 验取税务账口径 + 亮灯。

### M3-09　回报四源归因 + 三层杠杆（驾驶舱）
- **描述**：驾驶舱「回报归因」：四源=集采差价X%+资金时间价值Y%+价值定价Z%+残值回收W%（四者之和=总税后IRR 可勾稽）；三层杠杆=本金/供应商/融资（资金结构维度，与四源不同维度别混·术语区分）。
- **覆盖功能点**：8.4(回报四源/三层杠杆)、P1-6、DESIGN 十一-C
- **依赖**：M3-05、M1-08
- **验收标准**：四源之和=总IRR（勾稽为0差异）；UI 明确"四源利润"vs"三层杠杆"分区不混。
- **测试策略**：新 API+UI → curl 校验四源合计；浏览器截图归因分区。

---

## M4 · 转让资产

### M4-01　transfer_order 拆头+行　`[P0·上线红线]`
- **描述**：`transfer_order`(头: contract_id, type, total_price, total_gain, status) + `transfer_order_line`(逐台: asset_id, book_value 快照不回写, transfer_price, gain, voucher_id)，与 `contract_asset` 同构，支撑逐台处置/部分转让/单台损益。修 `asset_ids` 违反第一范式。
- **覆盖功能点**：P0-A、4.3(transfer_order/transfer_order_line)、DESIGN 十一-A
- **依赖**：S0-02、M3-02(book_value)
- **验收标准**：`SHOW COLUMNS FROM yc_rent_transfer_order_line`；一张转让单挂 N 行、可只转部分台数；`SUM(line.gain)=head.total_gain`；book_value 为快照(原表变动不影响)。
- **测试策略**：schema → ALTER+SHOW COLUMNS；curl 部分转让 → SQL 查行数+gain 合计+快照不回写。

### M4-02　转让/收回/二手/报废分支 + 名义价守卫
- **描述**：`POST /api/rent/transfer` 四分支：到期转让(市场价10%/30%/35%残值→残值收入凭证+资产出账)、客户不买→收回、二手、报废；账务切换、计税并入价款、合同随转让关闭、设备退出在租池。**名义价硬阈值守卫**：`transfer_price < book_value 或 < 市场价×下限` → 强制升级审批+记录理由(P1-19，禁名义价)。
- **覆盖功能点**：流程9、流程9逆向、P1-19、状态机(transfer_order)、API-五(transfer)、S0-05(转让率35%档)
- **依赖**：M4-01、M1-15(审批)
- **验收标准**：转让 → 残值凭证+资产出账+合同关闭(SQL 验)；transfer_price 低于阈值 → 拦截升级审批+必填理由；四分支各生成对应单据。
- **测试策略**：新 API → curl 四分支 + 名义价负例 → SQL 查凭证/合同/asset 状态。

### M4-03　资产状态机完善（收回待处置→再投放/二手/报废）
- **描述**：补全 asset 状态机：待转让→已转让｜(收回待处置→再投放/二手/报废)；复投飞轮 ↺；再投放回在租池。
- **覆盖功能点**：4.1(状态机)、第四部分状态机(asset)、流程12复投
- **依赖**：M2-06、M4-02
- **验收标准**：收回待处置设备可流转再投放(回在租池)/二手/报废，各生成 asset_event；非法状态跳转被拒。
- **测试策略**：新 API → curl 各流转 → SQL 查 asset_event 链路；负例非法跳转。

### M4-04　维保工单 maintenance
- **描述**：`maintenance`：报修/预防维保/工单 → 移动端扫码定位 → 处理拍照 → 回传更新台账；质保内转供应商、质保期/责任方判定；故障回填配件故障档案(8.1)。
- **覆盖功能点**：流程10、P1-3(maintenance)、8.1(故障档案)
- **依赖**：S0-02、M1-06
- **验收标准**：`SHOW COLUMNS FROM yc_rent_maintenance`；开工单→扫码带出设备→处理→台账更新；质保内标记转供应商责任。
- **测试策略**：schema → SHOW COLUMNS；新 API+移动端 → curl 工单流转 + 浏览器截图扫码定位。

### M4-05　盘点 stocktake + 盘盈亏调整（逆向）
- **描述**：`stocktake`+`stock_diff`：月度盘点账面带出只录差异；账实不符→盘盈亏调整**走单据**(不直改台账)，差异必有调整单闭合。
- **覆盖功能点**：流程10、P1-3(stocktake/stock_diff)、状态机(盘盈亏调整)
- **依赖**：S0-02、M1-06、S0-07
- **验收标准**：`SHOW COLUMNS` stocktake/stock_diff；盘点录差异→生成调整单+凭证→台账经单据更新；无调整单的差异亮"未闭合"灯。
- **测试策略**：schema → SHOW COLUMNS；curl 盘点差异 → SQL 查调整单+凭证；负例差异未闭合亮灯。

---

## M5 · 事·人·BI

### M5-01　任务 task + 派单 + 验证证据
- **描述**：`task`(type/assignee_role/assignee_user/source/status/verify_evidence/transfer_log)：系统派单(逾期分派/跟进待办)+人工派单；每干活页有任务来源+流程条(七律)；完成需验证证据。
- **覆盖功能点**：4.4(task)、第六部分 SOP、附则3(任务来源)
- **依赖**：S0-03
- **验收标准**：`SHOW COLUMNS FROM yc_rent_task`；派单→assignee 待办出现；完成需填 verify_evidence；跨角色 transfer_log 留痕。
- **测试策略**：schema → SHOW COLUMNS；新 API+UI → curl 派单/完成 + 截图待办队列。

### M5-02　审批 approval（投放审批）
- **描述**：`approval` 投放审批：本金回报(层级①)≥目标 & 300万内老板自主、超额协商；审批以本金回报率为准。挂 RBAC(投放审批→老板)。
- **覆盖功能点**：4.4(approval)、流程2(投放审批闸)、第六部分6.3、M1-15
- **依赖**：M1-08、M1-15
- **验收标准**：达标合同发起投放审批→老板审批放行；本金回报<目标 → 不允许进审批；>300万走协商流。
- **测试策略**：新 API → curl 达标/不达标/超额三组 → SQL 查 approval 状态。

### M5-03　花名册 + 提成拆解
- **描述**：花名册页：提成拆到"每单降本/成交贡献"；`user_role_ext`(业务BD/供应链/财务/GP/LP 板块内数据权限，写权限限老板+入 audit·P2)；新增 BD 角色(成交前跟进)。
- **覆盖功能点**：8.4(人/提成)、4.4(user_role_ext)、9.5(BD 角色)、P2
- **依赖**：M1-16、M5-06
- **验收标准**：花名册返各员工提成拆解(每单降本/成交贡献可溯)；user_role_ext 改动限老板且入 audit_log。
- **测试策略**：新 API+UI → curl 提成拆解；SQL 查角色改动 audit；截图花名册。

### M5-04　BI 多维矩阵
- **描述**：BI 矩阵四象限：在租率/回报/账龄 按 品类×客户×供应商×设备×时段 下钻；项目维度 P&L 独立(保密隔离)；客户集中度/账龄/评级分布总览。
- **覆盖功能点**：8.4(BI 多维矩阵/项目P&L)、8.3(客户集中度)、9.3(CRM 概览)
- **依赖**：M3-01、M1-16
- **验收标准**：BI 页可按五维下钻；数字与凭证/合同同源(对账为0);项目 P&L 按 project_id 隔离。
- **测试策略**：新 API+UI → curl 下钻组合 + SQL 对账；浏览器截图矩阵下钻。

### M5-05　PDCA action_item + AI 综述闭环
- **描述**：`action_item`(issue/action/metric/recheck_date/status) PDCA 改进；月度 5日过报告定改进；AI 综述闭环(§4.7 七条: 落库/完整报告/历史/可换模型/prompt fork/溯源 chip/透明四件套)。
- **覆盖功能点**：4.4(action_item)、流程11、DESIGN 八(AI 四件套)、§4.7
- **依赖**：M3-07
- **验收标准**：`SHOW COLUMNS FROM yc_rent_action_item`；报告定改进→action_item 落库+recheck 到期提醒；AI 结论旁挂"🔬AI过程"透明四件套。
- **测试策略**：schema → SHOW COLUMNS；新 API+UI → curl 建改进项 + 截图 AI 透明入口。

### M5-06　audit_log 全留痕（身份可信+只追加）
- **描述**：`audit_log`：红冲/作废/淘汰/拒绝等逆向动作全留痕；留痕存**网关注入身份+请求指纹**(P1-16)，高危操作留痕**只追加不可编辑**。
- **覆盖功能点**：4.4(audit_log)、P1-16、流程12(逆向留痕)
- **依赖**：S0-04、M1-15
- **验收标准**：`SHOW COLUMNS`；每逆向动作落 audit（身份=网关注入非客户端自报）；UPDATE/DELETE audit_log 被拒(只追加)。
- **测试策略**：schema → SHOW COLUMNS；curl 逆向动作 → SQL 查 audit 身份来源；负例试改 audit 被拒。

### M5-07　导入中心
- **描述**：复用小卖账房导入中心内核(映射/预览/去重)；**走同一校验+事件流通道**(P1-17)：禁直写派生/隔离字段、单元格公式前缀转义、owner/project 归属校验、禁"融资租赁"扩自由文本。
- **覆盖功能点**：P1-17、DESIGN 八(导入中心)、DESIGN 十一-B
- **依赖**：S0-07、M1-16
- **验收标准**：导入走事件流(非直写)；含公式的单元格被转义；越 owner/project 归属被拒；含"融资租赁"文本拦截。
- **测试策略**：新 API+UI → 上传含公式/越权/违规文本样例 → 各被拦；SQL 验数据经事件流落库。

### M5-08　对象存储签名 URL + 鉴权代理
- **描述**：合同/现场照对象存储：**短时效签名 URL + 服务端鉴权代理**(P1-20)，禁公开桶/可枚举 key。
- **覆盖功能点**：P1-20、4.1(交付现场照)、DESIGN 十一-B
- **依赖**：S0-03
- **验收标准**：获取文件 → 返短时效签名 URL(过期失效)；直连桶/枚举 key 被拒；越权用户拿不到他人项目文件。
- **测试策略**：新 API → curl 取签名 URL + 过期后重试失败 + 越权负例。

---

## 附：跨 ticket 强制约束（每个 ticket 完成时自查）

1. **单一真相源**（§4.24）：收款态 owner=rent_bill；book_value owner=asset_depreciation_line；派生字段禁直写（current_holder/residual 即时算）。
2. **cron 双 export**（§4.14）：runXxxCron + startXxxScheduler，禁 require.main，在 startup 注册。
3. **LLM 调用**（§4.7/§4.19）：走 invokeLLMWithRecord + 24h idempotent + 透明四件套 + 可换模型；AI 只起草叙述，数字全部规则引擎出 + 数据溯源。
4. **schema 上 prod**（§4.16）：migration 手动 apply 七步，禁自动 ddl。
5. **UI 改动真测**（§4.9/browser-verify）：V1 happy/V2 F5 刷新/V3 幂等/V4 SQL 对账/V5 联动，禁 curl 代 UI 验证。
6. **micro-commit**（§2）：每 ticket 一次 commit；每 3 commit/1 PR 做 git 健康检查。

---

## Phase 2.5 反向核验补票（覆盖率 96%→≥99% · 2026-08-08）

> 独立 code-reviewer 盲审：8P0/20P1/34表/8逆向 均 100%；综合功能点 ~96%。以下补票关闭 <5% 漏点（cron 归属/凭证视图/工作台），无需重写。

### M3-10 [cron] 定时任务集 [强制·防漏]
- **描述**：把所有隐含定时任务钉死调度所有权，每个必 **cron 双 export（runXxxCron + startXxxScheduler）+ startup 注册**（禁 `require.main`/§4.14）。含：①折旧月度计提(依赖M3-02) ②账期兑付缺口 T-N 每日扫描(依赖M3-06·P0-H) ③月报包月结日历「2 日自动生成」(依赖M3-07) ④逾期检测每日扫描→自动开 overdue_case(依赖M2-05) ⑤合同到期前 30 天转让提醒(业务流9) ⑥潜客「下次跟进日」到期亮灯(依赖M1-04)。
- **覆盖功能点**：流程7/8/9/11 的定时触发、P0-H 扫描、ADR-004 折旧计提、CRM 跟进提醒。
- **依赖**：M3-02/M3-06/M3-07/M2-05/M1-04。
- **验收标准**：6 个任务各手动触发 1 次 + SQL 验落库/亮灯；重启看 startup 注册日志；无 `require.main`。
- **测试**：cron 手动触发 + docker log + SQL。

### M3-11 凭证查询·详情·红冲视图
- **描述**：`GET /vouchers` 列表 + 详情 + 双账口径切换 + **通用凭证级红冲入口**（复用 M2-03 红冲内核 + 影响清单确认）。补 DESIGN 五「凭证」视图与 `POST /vouchers/{id}/reverse` 的界面入口。
- **覆盖功能点**：DESIGN 五凭证 API、17 视图之「凭证中心」查询侧。
- **依赖**：M3-01（凭证引擎）、M2-03（红冲内核）。
- **验收标准**：浏览器截图凭证列表/详情/红冲；SQL 验红冲连锁+影响清单。
- **测试**：browser-verify + SQL。

### WT-01 工作台聚合页（各角色驾驶舱首页）
- **描述**：登录落地页，聚合 task 待办 + 亮灯红点（逾期/空置/税务500万/到期/**账期兑付缺口**）+ 老板驾驶舱 KPI（在租率/应收/本月分配/加权回报），**按角色可见**（老板全量、供应链/业务/财务各自视图）。对应 DESIGN 17 视图之「工作台」+ SOP 6.4。
- **覆盖功能点**：DESIGN 二工作台、SOP 6.4 每日节奏、亮灯聚合。
- **依赖**：M1-15/16（权限隔离）、M5-01（任务）、各亮灯票。
- **验收标准**：三角色登录各截图，见对应待办/红点/KPI；无越权数据。
- **测试**：browser-verify 三角色。

### 验收子项追加（并入已有票·非独立票）
- **M3-07 / M5-05**：追加验收项「LLM context 喂入前脱敏（成本价/账期/分配/身份），AI 输出不回写数字」。
- **M1-18**：追加验收项「坏账拨备按应收余额比例计提（参照 16.2万/10年口径）」。

**补票后覆盖率**：功能点 ≈99%+、API 24/24=100%、cron 归属 6/6=100%、8P0/20P1/34表/8逆向 维持 100%。**Phase 2.5 通过。**
