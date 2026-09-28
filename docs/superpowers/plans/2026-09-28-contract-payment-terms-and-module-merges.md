# 合同付款方式 / 维保并入资产管理 / 转让关联台账 / 财务测试数据清理 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把付款条件从设备搬到合同并与租赁台账双向打通（应付在下单时全量生成），维保工单并入资产管理且支持仓库物品报修，转让·处置与台账互相可跳转，清理三个财务模块里来源单据已不存在的测试数据。

**Architecture:** 合同成为付款方式的唯一真相源（新表 `yc_rent_contract_payment_term`），设备按自己的合同价 × 段比例算出预计付款，应付在采购下单时一次生成全部阶段、未触发阶段的到期日按采购单的「预计入库日」推算并标 `due_provisional`，入库时改写为真实日期。维保工单增加 `target_type` 维度，仓库物品的库存数量仍只由 `InvService` 的出入库流转写。转让侧只改 DTO 与前端选择器，不动表结构。

**Tech Stack:** Spring Boot 2.7.18 / Java 17→release 8 / MyBatis-Plus 3.5.5（全局逻辑删除 `isDeleted`）/ Flyway（只追加）/ MySQL 8 / Vue 3.4 + Vite 5 + TS + Element Plus 2.14 + pnpm 9

**Spec:** `docs/superpowers/specs/2026-09-28-contract-payment-terms-and-module-merges-design.md`

## Global Constraints

- Flyway 迁移只追加不改旧文件；本次新增 **V117**（财务清理）、**V118**（合同付款方式）、**V119**（维保对象类型）。当前最新是 V116。
- 后端编译：`mvn -s settings.xml -B -o clean package`；跑中文测试必须设 `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8`（Windows 默认 GBK 会失败）。
- 前端：`pnpm typecheck` 然后 `pnpm build`，两者都必须过。
- 回归基线：**81 个单测全过**，前端 typecheck + build 全过。
- 提交信息用中文 `类型(范围): 说明`，末尾加 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`。改动编译+单测通过后直接推 main，不开分支、不 force push。
- 金额一律 `BigDecimal`，`setScale(2, RoundingMode.HALF_UP)`；比率 `decimal(18,8)`。
- 成本敏感字段（合同价/应付金额/未付）对 GP/LP 打码，判定走 `DataScope.canSeeCost(UserContext.getRole())`。
- 删除带唯一键的业务单号时追加 `#DEL{id}`（逻辑删除的行仍占唯一键）。
- 逻辑删除与唯一键冲突的表必须物理删：`yc_rent_ledger_book`（无 `is_deleted` 列）、`yc_rent_rent_schedule`（`uk_schedule_period`）、`yc_rent_overdue_case`（`uk_overdue_bill`）。
- 本机 MySQL 验证：`D:\mysql8`，`127.0.0.1:3399`，root 空密码，不开机自启。跑 jar 要传 `--spring.datasource.password=`（PowerShell 里 `$env:X=''` 等于删变量，会退回 yml 默认的 `vend123`）。mysql.exe 客户端必须从 Bash 工具跑并 `</dev/null`（用文件重定向时不要加 `</dev/null`，后者会覆盖前者）。
- 业务原表（`E:\云山项目\...`）含真实联系人，**不提交进仓库**。
- 生产部署由有服务器权限的人执行，本计划不含部署步骤。

---

## 文件结构

### Phase 1 — 财务测试数据清理

| 文件 | 职责 |
|---|---|
| `backend/src/main/resources/db/migration/V117__purge_orphan_finance_data.sql` | 新建。按来源单据是否健在清理凭证/分录/总账行/折旧行，清空结账分配与月度报表 |
| `CODEMAP.md` | 迁移表追加 V117 一行 |

### Phase 2 — 转让·处置关联台账

| 文件 | 职责 |
|---|---|
| `backend/.../modules/transfer/dto/TransferDtos.java` | 修改。列表行与详情行补设备/合同展示字段 |
| `backend/.../modules/transfer/service/TransferService.java` | 修改。填充上述字段（设备标签、账面价、合同号、客户名） |
| `backend/.../modules/asset/dto/AssetDetailResponse.java` | 修改。新增 `disposals` 处置记录列表 |
| `backend/.../modules/asset/dto/AssetListItem.java` | 修改。新增 `disposalStatus` |
| `backend/.../modules/asset/service/AssetService.java` | 修改。详情填充处置记录，列表填充处置状态（批量查，不 N+1） |
| `frontend/src/api/transfer.ts` | 修改。类型补字段 |
| `frontend/src/views/Transfer.vue` | 修改。手填 ID → 搜索选择器；列表/详情显示设备标签可跳台账 |
| `frontend/src/api/asset.ts` | 修改。类型补 `disposals` / `disposalStatus` |
| `frontend/src/views/Asset.vue` | 修改。详情新增「转让/处置记录」区块；列表新增「处置状态」列 |
| `backend/src/test/java/.../transfer/TransferLinkTest.java` | 新建。DTO 填充与处置记录聚合的单测 |

### Phase 3 — 维保工单并入资产管理

| 文件 | 职责 |
|---|---|
| `backend/src/main/resources/db/migration/V119__maintenance_target_type.sql` | 新建。加 `target_type`/`inv_item_id`/`qty`/`movement_out_id`/`movement_back_id`，`asset_id` 改可空 |
| `backend/.../modules/maintenance/domain/Maintenance.java` | 修改。新增字段 |
| `backend/.../modules/maintenance/dto/MaintenanceDtos.java` | 修改。建单请求加 `targetType`/`invItemId`/`qty`；列表行加对象展示字段 |
| `backend/.../modules/maintenance/service/MaintenanceService.java` | 修改。对象类型互斥校验；仓库物品建单/完工调 `InvService` 的送修/修好/报废 |
| `backend/.../modules/inventory/service/InvService.java` | 修改。抽出供维保调用的送修/修好/报废公开方法（复用现有 `moveQty` 流转，不新增库存写手） |
| `frontend/src/views/Inventory.vue` | 修改。新增「维保工单」标签页 |
| `frontend/src/api/maintenance.ts` | 修改。类型补字段 |
| `frontend/src/views/Maintenance.vue` | 删除 |
| `frontend/src/router/index.ts` | 修改。`/maintenance` 改为重定向到 `/inventory` |
| `frontend/src/App.vue` | 修改。撤掉维保菜单项 |
| `backend/src/test/java/.../maintenance/MaintenanceTargetTest.java` | 新建。互斥校验 + 仓库物品报修联动库存的单测 |

### Phase 4 — 合同付款方式

| 文件 | 职责 |
|---|---|
| `backend/src/main/resources/db/migration/V118__contract_payment_term.sql` | 新建。建表 + 反推迁移 + `term_id` 重映射 + 补齐未触发阶段应付 + `expect_receive_date`/`due_provisional` 字段与 `purchase_lead_days` 规则 |
| `backend/.../modules/contract/domain/ContractPaymentTerm.java` | 新建。实体 |
| `backend/.../modules/contract/mapper/ContractPaymentTermMapper.java` | 新建 |
| `backend/.../modules/contract/service/ContractPaymentService.java` | 新建。付款方式读写、校验、已付锁定、变更后重算待付应付、覆盖设备汇总 |
| `backend/.../modules/contract/interfaces/ContractController.java` | 修改。新增 `GET/PUT /rent/contracts/{id}/payment-terms` |
| `backend/.../modules/asset/service/AssetPaymentService.java` | 修改。付款条件来源改合同；应付全量生成 + `due_provisional`；入库改写到期日；`updateTerms` 移除（改由合同侧驱动） |
| `backend/.../modules/purchase/domain/PurchaseIn.java` | 修改。新增 `expectReceiveDate` |
| `backend/.../modules/purchase/domain/Payable.java` | 修改。新增 `dueProvisional` |
| `backend/.../modules/purchase/service/PurchaseService.java` | 修改。下单不接收付款条件，改取合同付款方式；入库改写预估到期日 |
| `backend/.../modules/purchase/dto/PurchaseOrderRequest.java` | 修改。移除 `paymentTerms`，新增 `expectReceiveDate` |
| `frontend/src/api/contract.ts` / `views/Contract.vue` | 修改。合同付款方式编辑区 + 覆盖设备汇总 |
| `frontend/src/views/Purchase.vue` | 修改。下单弹窗改只读显示继承条件 + 预计入库日 |
| `frontend/src/views/Asset.vue` | 修改。付款计划改只读 + 跳合同链接；列表加「未付」列 |
| `backend/src/test/java/.../contract/ContractPaymentServiceTest.java` | 新建 |
| `backend/src/test/java/.../asset/AssetPaymentServiceTest.java` | 修改。适配新口径 |

---

## Phase 1 — 财务测试数据清理（V117）

### Task 1: V117 清理迁移

**Files:**
- Create: `backend/src/main/resources/db/migration/V117__purge_orphan_finance_data.sql`
- Modify: `CODEMAP.md`（迁移表末尾，V116 行之后）

**Interfaces:**
- Consumes: 无
- Produces: 无代码接口；产出一条可重复安全执行的迁移

- [ ] **Step 1: 写迁移**

来源单据类型与 id 的对应（`VoucherService` 里的 `post(...)` 调用）：`rent_bill`→`yc_rent_rent_bill.id`、`purchase_in`→`yc_rent_purchase_in.id`、`transfer_line`→`yc_rent_transfer_order_line.id`、`depreciation`→`yc_rent_asset.id`。`manual` 保留。

```sql
-- =====================================================================
-- 清理三个财务模块里来源单据已不存在的测试数据(V117)
-- 口径:凭证/分录/总账行/折旧行 —— 来源单据已逻辑删除或查不到的一律删;
--      结账分配与月度报表清空(按期间汇总,无合同维度,现存的都是测试期跑的);
--      出资人名册保留(真实出资结构),LLM 调用日志保留(接口审计)。
-- manual 手工凭证保留:没有来源单据可判定。
-- ledger_book 无 is_deleted 列 → 物理删。凭证号加 #DEL{id} 让原号可复用。
-- =====================================================================

SET NAMES utf8mb4;

CREATE TEMPORARY TABLE tmp_orphan_voucher (id bigint NOT NULL PRIMARY KEY);

INSERT IGNORE INTO tmp_orphan_voucher (id)
SELECT v.id FROM yc_rent_voucher v
WHERE v.is_deleted = 0 AND v.source_doc_type = 'rent_bill'
  AND NOT EXISTS (SELECT 1 FROM yc_rent_rent_bill b
                  WHERE b.id = v.source_doc_id AND b.is_deleted = 0);

INSERT IGNORE INTO tmp_orphan_voucher (id)
SELECT v.id FROM yc_rent_voucher v
WHERE v.is_deleted = 0 AND v.source_doc_type = 'purchase_in'
  AND NOT EXISTS (SELECT 1 FROM yc_rent_purchase_in p
                  WHERE p.id = v.source_doc_id AND p.is_deleted = 0);

INSERT IGNORE INTO tmp_orphan_voucher (id)
SELECT v.id FROM yc_rent_voucher v
WHERE v.is_deleted = 0 AND v.source_doc_type = 'transfer_line'
  AND NOT EXISTS (SELECT 1 FROM yc_rent_transfer_order_line l
                  WHERE l.id = v.source_doc_id AND l.is_deleted = 0);

INSERT IGNORE INTO tmp_orphan_voucher (id)
SELECT v.id FROM yc_rent_voucher v
WHERE v.is_deleted = 0 AND v.source_doc_type = 'depreciation'
  AND NOT EXISTS (SELECT 1 FROM yc_rent_asset a
                  WHERE a.id = v.source_doc_id AND a.is_deleted = 0);

DELETE FROM yc_rent_ledger_book WHERE voucher_id IN (SELECT id FROM tmp_orphan_voucher);

UPDATE yc_rent_voucher_line SET is_deleted = 1
WHERE voucher_id IN (SELECT id FROM tmp_orphan_voucher);

UPDATE yc_rent_voucher
SET is_deleted = 1,
    voucher_no = CONCAT(LEFT(voucher_no, 64 - CHAR_LENGTH(CONCAT('#DEL', id))), '#DEL', id)
WHERE id IN (SELECT id FROM tmp_orphan_voucher);

-- 折旧行:设备已删或查不到
UPDATE yc_rent_asset_depreciation_line SET is_deleted = 1
WHERE is_deleted = 0
  AND NOT EXISTS (SELECT 1 FROM yc_rent_asset a
                  WHERE a.id = yc_rent_asset_depreciation_line.asset_id AND a.is_deleted = 0);

-- 结账分配与月度报表:清空(物理删)
DELETE FROM yc_rent_rent_distribution;
DELETE FROM yc_rent_monthly_report;

DROP TEMPORARY TABLE tmp_orphan_voucher;
```

注意：`UPDATE yc_rent_asset_depreciation_line ... NOT EXISTS (... yc_rent_asset_depreciation_line.asset_id)` 在 MySQL 里合法（子查询引用被更新表的列，不是从被更新表 SELECT）。若报 1093，改成先灌临时表再按 id IN 删。

- [x] **Step 2（执行时已核实，结论与初稿相反）：这两张表必须物理删**

`yc_rent_monthly_report` 的唯一键是 `(period, is_deleted)` —— 同账期只容得下「一条有效 + 一条已删」，逻辑删会在该账期已有已删行时撞 `Duplicate entry`。`yc_rent_rent_distribution` 的 `uk_distribution_no` 唯一，逻辑删的行仍占着分配单号。两处都改成 `DELETE FROM`，理由写在迁移文件头。

- [ ] **Step 3: 本机 MySQL 验证（造孤儿 + 对照组）**

启动 MySQL，建库，跑到 V116，灌数据：来源健在的凭证/折旧行（对照组，不该删）、来源已逻辑删除的凭证（该删）、来源 id 根本不存在的凭证（该删）、`manual` 凭证（该留）、一条分配、一条月报、出资人名册（该留）。再重启让 Flyway 跑 V117，逐表核对。

- [ ] **Step 4: 回归**

Run: `mvn -s settings.xml -B -o clean package`（带 `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8`）
Expected: `Tests run: 81, Failures: 0, Errors: 0`，`BUILD SUCCESS`

- [ ] **Step 5: 更新 CODEMAP 并提交**

```bash
git add -A && git commit -m "chore(data): 清理来源单据已不存在的凭证/折旧/分配/月报测试数据(V117)"
```

---

## Phase 2 — 转让·处置关联台账

### Task 2: 转让 DTO 与服务补设备/合同展示字段

**Files:**
- Modify: `backend/src/main/java/top/aole/rent/modules/transfer/dto/TransferDtos.java`
- Modify: `backend/src/main/java/top/aole/rent/modules/transfer/service/TransferService.java`
- Test: `backend/src/test/java/top/aole/rent/modules/transfer/TransferLinkTest.java`（新建）

**Interfaces:**
- Consumes: `AssetMapper`、`ContractMapper`、`CustomerMapper`（已有）；`PurchaseService.assetLabel(Asset)` 是 `static`，可直接复用作设备标签规则
- Produces:
  - `TransferDtos.LineRow` 新增 getter：`getAssetLabel()`、`getSerialNo()`、`getCategory()`、`getModel()`、`getAssetStatus()`、`getBookValue()`
  - `TransferDtos.OrderRow` / 详情头新增：`getContractNo()`、`getCustomerName()`

- [ ] **Step 1: 先读现状，确认字段名与现有填充位置**

Run:
```bash
cd "/c/Users/李伟/Documents/worland" && cat backend/src/main/java/top/aole/rent/modules/transfer/dto/TransferDtos.java && grep -n "LineRow\|OrderRow\|setAsset\|assetMapper" backend/src/main/java/top/aole/rent/modules/transfer/service/TransferService.java
```
Expected: 看清现有 DTO 内部类名与 service 的组装方法，后续步骤按实际类名落字段（若类名与本计划假设的 `LineRow`/`OrderRow` 不同，以实际为准并在本计划里改掉）。

- [ ] **Step 2: 写失败测试**

```java
@Test
void 转让行带出设备标签与账面价() {
    Asset a = new Asset();
    a.setId(7L); a.setSerialNo("AS-007"); a.setCategory("播种墙");
    a.setModel("播种墙 V2"); a.setStatus("在租"); a.setBookValue(new BigDecimal("177000.00"));
    when(assetMapper.selectById(7L)).thenReturn(a);
    // …构造一条 transfer_order_line(asset_id=7) 与单头，调 service.detail(orderId)
    TransferDtos.LineRow row = service.detail(1L).getLines().get(0);
    assertEquals("播种墙 · 播种墙 V2", row.getAssetLabel());
    assertEquals("AS-007", row.getSerialNo());
    assertEquals("在租", row.getAssetStatus());
    assertEquals(new BigDecimal("177000.00"), row.getBookValue());
}

@Test
void 设备已删时退回序列号且不抛异常() {
    when(assetMapper.selectById(8L)).thenReturn(null);
    TransferDtos.LineRow row = service.detail(2L).getLines().get(0);
    assertEquals("#8", row.getAssetLabel());
    assertNull(row.getBookValue());
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn -s settings.xml -B -o test -Dtest=TransferLinkTest`
Expected: 编译失败（`getAssetLabel()` 不存在）

- [ ] **Step 4: 加字段与填充**

DTO 加字段（Lombok `@Data`，照现有风格写 javadoc）；service 在组装每行时 `assetMapper.selectById`，命中则填标签/序列号/品类/型号/状态/账面价，未命中填 `"#" + assetId`。单头按 `contract_id` 查合同号与客户名（`contractMapper` + `customerMapper`，空值退回 `null`）。

- [ ] **Step 5: 运行确认通过**

Run: `mvn -s settings.xml -B -o test -Dtest=TransferLinkTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat(transfer): 转让/处置单带出设备标签与账面价、合同与客户"
```

### Task 3: 设备侧反向显示处置记录

**Files:**
- Modify: `backend/src/main/java/top/aole/rent/modules/asset/dto/AssetDetailResponse.java`
- Modify: `backend/src/main/java/top/aole/rent/modules/asset/dto/AssetListItem.java`
- Modify: `backend/src/main/java/top/aole/rent/modules/asset/service/AssetService.java`
- Test: `backend/src/test/java/top/aole/rent/modules/transfer/TransferLinkTest.java`（追加）

**Interfaces:**
- Consumes: `TransferOrderLineMapper`、`TransferOrderMapper`（注入 `AssetService`）
- Produces:
  - `AssetDetailResponse.DisposalLine`：`id`、`orderId`、`orderNo`、`type`、`transferPrice`、`gain`、`status`、`bizTime`
  - `AssetDetailResponse.getDisposals()` → `List<DisposalLine>`
  - `AssetListItem.getDisposalStatus()` → `String`，格式 `类型 · 状态`，无记录为 `null`

- [ ] **Step 1: 写失败测试**

```java
@Test
void 设备详情列出处置记录_按业务时间倒序() {
    // 两行:2026-01-15 转让/已完成、2026-03-01 二手/待过账
    List<AssetDetailResponse.DisposalLine> d = assetService.detail(7L).getDisposals();
    assertEquals(2, d.size());
    assertEquals("二手", d.get(0).getType());      // 最近的在前
    assertEquals("待过账", d.get(0).getStatus());
    assertEquals("TR-2026-002", d.get(0).getOrderNo());
}

@Test
void 列表处置状态取最近一次_无记录为空() {
    assertEquals("二手 · 待过账", listItemOf(7L).getDisposalStatus());
    assertNull(listItemOf(9L).getDisposalStatus());
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -s settings.xml -B -o test -Dtest=TransferLinkTest`
Expected: FAIL / 编译不过

- [ ] **Step 3: 实现**

`detail()` 里按 `asset_id` 查 `transfer_order_line`（`is_deleted=0`），批量取单头，按 `biz_time` 倒序组装。列表侧**必须批量**：一次 `in(assetIds)` 查所有行 + 一次查单头，在内存里按设备分组取最近一条，禁止逐台查（参照 `RentCoverageService.byContract` 的批量写法）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -s settings.xml -B -o test -Dtest=TransferLinkTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat(asset): 设备详情显示转让/处置记录,列表加处置状态列"
```

### Task 4: 前端选择器与双向跳转

**Files:**
- Modify: `frontend/src/api/transfer.ts`、`frontend/src/views/Transfer.vue`
- Modify: `frontend/src/api/asset.ts`、`frontend/src/views/Asset.vue`

**Interfaces:**
- Consumes: `fetchAssets(params)`（`@/api/asset`，支持 `contractId`/`page`/`size`）、`fetchContracts(params)`（`@/api/contract`）
- Produces: 无（纯 UI）

- [ ] **Step 1: 到期转让页签的合同选择器**

`frontend/src/views/Transfer.vue:146` 的 `<el-input-number v-model="expiryForm.contractId" placeholder="合同ID">` 换成：

```vue
<el-select v-model="expiryForm.contractId" filterable clearable style="width:320px"
  :loading="contractsLoading" placeholder="选合同" @change="loadContractAssets">
  <el-option v-for="c in contracts" :key="c.id"
    :label="`${c.no} · ${c.customerName || ''} · ${c.status}`" :value="c.id" />
</el-select>
```

选中后调 `fetchAssets({ contractId, page: 1, size: 500 })` 列出该合同挂载的设备供确认（只读表格：品类·型号·序列号·状态·账面价）。

- [ ] **Step 2: 复投飞轮页签的设备选择器**

`frontend/src/views/Transfer.vue:166` 的 `<el-input-number v-model="disposeForm.assetId" placeholder="设备ID">` 换成设备搜索选择器，label 为 `品类 · 型号 · 序列号（状态）`；选中后把该设备的账面价显示在旁边，二手处置时用于判断是否触发名义价守卫。

- [ ] **Step 3: 列表与详情显示设备标签可跳转**

详情表格里 `prop="serialNo"` 那列改成可点链接，`@click="goAsset(row.assetId)"`，跳 `/asset?id=<id>`（写法照 `Purchase.vue` 的 `goAsset`）。

- [ ] **Step 4: 设备详情加「转让/处置记录」区块 + 列表加「处置状态」列**

`Asset.vue` 详情里新增表格（单号可点跳 `/transfer?id=<orderId>`），列表新增一列读 `row.disposalStatus`，空显示 `—`。

- [ ] **Step 5: typecheck + build**

Run: `pnpm typecheck` 然后 `pnpm build`
Expected: 两者 exit 0

- [ ] **Step 6: 本机联调**

启 MySQL + jar（`--rent.auth.placeholder-headers-enabled=true`），造一份合同 + 两台设备 + 一张转让单，实测：合同选择器带出设备、设备选择器带出账面价、详情能跳台账、台账详情能看到处置记录并跳回转让单。

- [ ] **Step 7: 提交**

```bash
git add -A && git commit -m "feat(transfer): 转让/处置改为从台账与合同选择,与设备详情双向跳转"
```

---

## Phase 3 — 维保工单并入资产管理

### Task 5: V119 加对象类型维度

**Files:**
- Create: `backend/src/main/resources/db/migration/V119__maintenance_target_type.sql`
- Modify: `backend/src/main/java/top/aole/rent/modules/maintenance/domain/Maintenance.java`

**Interfaces:**
- Produces: `Maintenance` 新增 `targetType`(String)、`invItemId`(Long)、`qty`(Integer)、`movementOutId`(Long)、`movementBackId`(Long)

- [ ] **Step 1: 写迁移**

```sql
-- =====================================================================
-- 维保工单支持两类对象(V119):设备租赁台账 asset / 资产管理仓库物品 inv_item
-- 仓库物品的库存数量仍只由出入库流转(yc_rent_inv_movement)一处写,
-- 工单把送修/修好/报废的 movement id 记下来,不另记一套库存状态。
-- 存量数据全部是设备工单 → target_type 默认 'asset'。
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE yc_rent_maintenance
  ADD COLUMN target_type varchar(8) NOT NULL DEFAULT 'asset'
    COMMENT '对象类型:asset 设备租赁台账 / inv_item 资产管理仓库物品' AFTER no,
  ADD COLUMN inv_item_id bigint DEFAULT NULL
    COMMENT '仓库物品(yc_rent_inv_item.id);target_type=inv_item 时必填' AFTER asset_id,
  ADD COLUMN qty int NOT NULL DEFAULT 1
    COMMENT '送修数量(仅 inv_item 用;设备工单恒为 1)' AFTER inv_item_id,
  ADD COLUMN movement_out_id bigint DEFAULT NULL
    COMMENT '建单时「送修」的出入库流转 id' AFTER cost,
  ADD COLUMN movement_back_id bigint DEFAULT NULL
    COMMENT '完工时「修好」或「报废」的出入库流转 id' AFTER movement_out_id;

ALTER TABLE yc_rent_maintenance MODIFY COLUMN asset_id bigint DEFAULT NULL
  COMMENT '设备(yc_rent_asset.id);target_type=asset 时必填';

CREATE INDEX idx_maintenance_inv_item ON yc_rent_maintenance (inv_item_id);
```

- [ ] **Step 2: 实体加字段**

- [ ] **Step 3: 跑迁移确认能过**

Run: 本机 MySQL 跑到 V119
Expected: `Successfully applied ... now at version v119`，且已有工单的 `target_type` 都是 `asset`

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "feat(maintenance): 工单增加对象类型维度,支持仓库物品(V119)"
```

### Task 6: 服务层互斥校验与库存联动

**Files:**
- Modify: `backend/src/main/java/top/aole/rent/modules/maintenance/dto/MaintenanceDtos.java`
- Modify: `backend/src/main/java/top/aole/rent/modules/maintenance/service/MaintenanceService.java`
- Modify: `backend/src/main/java/top/aole/rent/modules/inventory/service/InvService.java`
- Test: `backend/src/test/java/top/aole/rent/modules/maintenance/MaintenanceTargetTest.java`（新建）

**Interfaces:**
- Consumes: `InvService` 现有的数量流转常量与 `moveQty`（`M_TO_REPAIR` 送修 / `M_REPAIRED` 修好 / 报废，见 `InvService.java:204-212`）
- Produces:
  - `InvService.moveToRepair(Long itemId, int qty, String remark)` → `Long` movementId
  - `InvService.moveRepaired(Long itemId, int qty, String remark)` → `Long` movementId
  - `InvService.moveScrapFromRepair(Long itemId, int qty, String remark)` → `Long` movementId
  - `MaintenanceDtos.CreateRequest` 新增 `targetType`、`invItemId`、`qty`

- [ ] **Step 1: 写失败测试**

```java
@Test
void 设备工单必须只填设备() {
    CreateRequest r = new CreateRequest();
    r.setTargetType("asset");
    r.setInvItemId(5L);
    BizException e = assertThrows(BizException.class, () -> service.create(r));
    assertTrue(e.getMessage().contains("设备工单不能同时指定仓库物品"));
}

@Test
void 仓库物品工单必须填物品与数量() {
    CreateRequest r = new CreateRequest();
    r.setTargetType("inv_item");
    BizException e = assertThrows(BizException.class, () -> service.create(r));
    assertTrue(e.getMessage().contains("请选择仓库物品"));
}

@Test
void 仓库物品报修调送修流转并记下movement() {
    when(invService.moveToRepair(eq(5L), eq(3), anyString())).thenReturn(88L);
    CreateRequest r = new CreateRequest();
    r.setTargetType("inv_item"); r.setInvItemId(5L); r.setQty(3); r.setType("报修");
    service.create(r);
    verify(invService).moveToRepair(eq(5L), eq(3), anyString());
    ArgumentCaptor<Maintenance> c = ArgumentCaptor.forClass(Maintenance.class);
    verify(maintenanceMapper).insert(c.capture());
    assertEquals(88L, c.getValue().getMovementOutId());
}

@Test
void 完工按结果走修好或报废() {
    // scrapped=false → moveRepaired;scrapped=true → moveScrapFromRepair
    service.handle(1L, handleReq(false));
    verify(invService).moveRepaired(eq(5L), eq(3), anyString());
    service.handle(2L, handleReq(true));
    verify(invService).moveScrapFromRepair(eq(5L), eq(3), anyString());
}

@Test
void 故障配件只能挂在设备工单上() {
    CreateRequest r = new CreateRequest();
    r.setTargetType("inv_item"); r.setInvItemId(5L); r.setQty(1); r.setBomId(9L);
    BizException e = assertThrows(BizException.class, () -> service.create(r));
    assertTrue(e.getMessage().contains("故障配件只能用于设备工单"));
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -s settings.xml -B -o test -Dtest=MaintenanceTargetTest`
Expected: FAIL

- [ ] **Step 3: 实现**

`InvService` 里把现有的送修/修好/报废流转抽成上面三个公开方法（内部仍走 `moveQty` + 写 `inv_movement`，**不新增库存写手**），返回 movement id。`MaintenanceService.create` 先做互斥校验，`target_type=inv_item` 时调 `moveToRepair` 并存 `movementOutId`；`handle`（完工）按 `scrapped` 走 `moveRepaired` 或 `moveScrapFromRepair`，存 `movementBackId`；关闭（未修）走 `moveRepaired` 退回库存并在备注写明。设备工单路径行为完全不变。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -s settings.xml -B -o test -Dtest=MaintenanceTargetTest`
Expected: PASS

- [ ] **Step 5: 全量回归**

Run: `mvn -s settings.xml -B -o clean package`
Expected: 81 + 新增用例全过

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat(maintenance): 仓库物品报修联动出入库流转,对象类型互斥校验"
```

### Task 7: 页面并入资产管理

**Files:**
- Modify: `frontend/src/views/Inventory.vue`（新增标签页，插在「损坏与赔偿」之后、「设置」之前）
- Modify: `frontend/src/api/maintenance.ts`
- Delete: `frontend/src/views/Maintenance.vue`
- Modify: `frontend/src/router/index.ts`、`frontend/src/App.vue`

**Interfaces:**
- Consumes: `fetchMaintenances`/`createMaintenance`/`assignMaintenance`/`handleMaintenance`/`fetchSpareAlert`（`@/api/maintenance`，签名不变，`createMaintenance` 的 body 多 `targetType`/`invItemId`/`qty`）
- Produces: 无

- [ ] **Step 1: 把 Maintenance.vue 的列表/报修/派工/回写搬进 Inventory.vue 的新标签页**

报修表单顶部加对象类型单选（台账设备 / 仓库物品）：选设备时用设备搜索选择器（`fetchAssets`）；选仓库物品时用物品选择器（资产管理已有的物品列表数据）+ 数量输入，数量上限为该物品的库存数。

- [ ] **Step 2: 撤菜单 + 路由重定向**

`App.vue` 删掉 `<el-menu-item index="/maintenance">` 那行；`router/index.ts` 里 `/maintenance` 改为 `redirect: '/inventory'`，并删掉对 `Maintenance.vue` 的 import。

- [ ] **Step 3: 删除 `frontend/src/views/Maintenance.vue`**

- [ ] **Step 4: typecheck + build**

Run: `pnpm typecheck` 然后 `pnpm build`
Expected: 两者 exit 0，且没有指向已删文件的残留 import

- [ ] **Step 5: 本机联调**

对设备开一单走 报修→派工→完工；对仓库物品开一单，核对建单后 `stock_qty` 减、`repair_qty` 加，完工选"修好"后回到 `stock_qty`，选"报废"后进 `scrapped_qty`，且 `inv_movement` 里有对应的两条流转。

- [ ] **Step 6: 更新 CODEMAP（模块表与前端路由表）并提交**

```bash
git add -A && git commit -m "refactor(maintenance): 维保工单并入资产管理模块,撤独立菜单"
```

---

## Phase 4 — 合同付款方式

### Task 8: 实体、表与校验服务

**Files:**
- Create: `backend/src/main/resources/db/migration/V118__contract_payment_term.sql`（本任务只建表与字段，数据迁移在 Task 11）
- Create: `backend/.../modules/contract/domain/ContractPaymentTerm.java`
- Create: `backend/.../modules/contract/mapper/ContractPaymentTermMapper.java`
- Create: `backend/.../modules/contract/service/ContractPaymentService.java`
- Test: `backend/src/test/java/top/aole/rent/modules/contract/ContractPaymentServiceTest.java`（新建）

**Interfaces:**
- Consumes: `PaymentTermDtos.TermInput`（已有，字段 `stageName`/`ratio`/`triggerPoint`/`dueDays`）
- Produces:
  - `ContractPaymentService.normalize(List<TermInput>)` → `List<TermInput>`（校验+规范化，逻辑照搬 `AssetPaymentService.normalize`）
  - `ContractPaymentService.terms(Long contractId)` → `List<ContractPaymentTerm>`
  - `ContractPaymentService.termsAsInput(Long contractId)` → `List<TermInput>`
  - `ContractPaymentService.replaceAll(Long contractId, List<TermInput>)` → `void`（已付阶段锁定，变更后触发待付应付重算）
  - `ContractPaymentService.describe(Long contractId)` → `String`（`首付30%(下单) / 验收60%(入库)` 摘要）
  - `ContractPaymentService.ensureDefault(Long contractId, BigDecimal firstPayRatio, Integer accountDays)` → `List<ContractPaymentTerm>`（没设过就按 `rule_config[payable_stage_ratio]` 写入默认三段）

- [ ] **Step 1: 写迁移的建表部分**

```sql
CREATE TABLE yc_rent_contract_payment_term (
  id            bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  contract_id   bigint        NOT NULL COMMENT '合同(yc_rent_contract.id)',
  seq           int           NOT NULL COMMENT '段序号(1 起)',
  stage_name    varchar(16)   NOT NULL COMMENT '阶段名:首付/验收/尾款…(同合同内不重复,「退款红字」保留)',
  ratio         decimal(18,8) NOT NULL COMMENT '付款比例(0-1),同合同各段合计=1',
  trigger_point varchar(8)    NOT NULL COMMENT '触发时点:下单/入库',
  due_days      int           NOT NULL DEFAULT 0 COMMENT '到期天数(触发日+N 天)',
  create_time   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted    tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_cpt_contract (contract_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合同付款方式:唯一真相源,设备与采购单继承';

ALTER TABLE yc_rent_purchase_in
  ADD COLUMN expect_receive_date date DEFAULT NULL
    COMMENT '预计入库日(未入库时用于推算入库阶段应付到期日)' AFTER order_date;

ALTER TABLE yc_rent_payable
  ADD COLUMN due_provisional tinyint(1) NOT NULL DEFAULT 0
    COMMENT '到期日是否为预估(按预计入库日推算;实际入库后改写并置 0)' AFTER due_date;

INSERT INTO yc_rent_rule_config
 (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('purchase_lead_days', '', 30, 'months', 1, '2023-01-01', NULL,
  '采购提前期(天):未填预计入库日时 = 下单日 + 本值,用于推算入库阶段应付到期日');
```

注意：`value_type` 取值见 `V1__init.sql` 注释（`rate`/`money`/`months`/`json`），天数没有专门类型，沿用 `months` 并在 remark 写明单位是天；若后续新增 `days` 类型再统一。

- [ ] **Step 2: 写失败测试**

```java
@Test
void 比例合计不足百分之百被拒() {
    BizException e = assertThrows(BizException.class, () -> service.replaceAll(60L,
            Arrays.asList(term("首付", "0.3", "下单", 0), term("尾款", "0.5", "入库", 90))));
    assertTrue(e.getMessage().contains("合计须为 100%"));
}

@Test
void 阶段名重复被拒() {
    BizException e = assertThrows(BizException.class, () -> service.replaceAll(60L,
            Arrays.asList(term("首付", "0.5", "下单", 0), term("首付", "0.5", "入库", 0))));
    assertTrue(e.getMessage().contains("名称重复"));
}

@Test
void 触发点只能是下单或入库() {
    BizException e = assertThrows(BizException.class, () -> service.replaceAll(60L,
            Arrays.asList(term("首付", "1", "验收", 0))));
    assertTrue(e.getMessage().contains("下单 或 入库"));
}

@Test
void 已付阶段不能删也不能改比例() {
    // payable(stage=首付, status=已付, term_id=该段)
    BizException e = assertThrows(BizException.class, () -> service.replaceAll(60L,
            Arrays.asList(term("验收", "1", "入库", 0))));
    assertTrue(e.getMessage().contains("已付款,不能删除或修改比例"));
}

@Test
void 没设过付款方式时按规则写入默认三段() {
    List<ContractPaymentTerm> t = service.ensureDefault(60L, null, null);
    assertEquals(3, t.size());
    assertEquals("首付", t.get(0).getStageName());
    assertEquals("下单", t.get(0).getTriggerPoint());
    assertEquals(90, t.get(2).getDueDays());
}

@Test
void 摘要格式() {
    assertEquals("首付30%(下单) / 验收60%(入库) / 尾款10%(入库+90天)", service.describe(60L));
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn -s settings.xml -B -o test -Dtest=ContractPaymentServiceTest`
Expected: 编译失败（类不存在）

- [ ] **Step 4: 实现实体、mapper、服务**

`normalize`/`describe`/默认三段的逻辑从 `AssetPaymentService` 搬过来（那边保留计算类方法 `expectedAmounts`，校验类方法迁到合同侧；不要两处各留一份）。已付阶段判定：按 `payable.status='已付'` 的 `stage` 名 + 其 `term_id` 对应比例，逻辑照搬 `AssetPaymentService.paidStages`，但按合同下所有设备聚合。

- [ ] **Step 5: 运行确认通过**

Run: `mvn -s settings.xml -B -o test -Dtest=ContractPaymentServiceTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat(contract): 新增合同付款方式(表+校验服务)"
```

### Task 9: 应付全量生成与预估到期日

**Files:**
- Modify: `backend/.../modules/asset/service/AssetPaymentService.java`
- Modify: `backend/.../modules/purchase/domain/PurchaseIn.java`、`Payable.java`
- Test: `backend/src/test/java/top/aole/rent/modules/asset/AssetPaymentServiceTest.java`（改造，现有 4 个用例要适配新口径）

**Interfaces:**
- Consumes: `ContractPaymentService.terms(contractId)`
- Produces:
  - `AssetPaymentService.onOrder(PurchaseIn p, PurchaseItem item, Long assetId, Long contractId)`（不再收 `List<TermInput>`）
  - `AssetPaymentService.onReceive(PurchaseIn p, PurchaseItem item, Long assetId)`：不再新建应付，改为**把该设备 `due_provisional=1` 的待付应付的到期日改写**为 `receiveDate + dueDays` 并置 0
  - `AssetPaymentService.plan(Asset a, boolean seeCost)`：`PaymentTermDtos.PaymentPlan` 新增 `contractId`/`contractNo`/`inherited=true`，每段 `TermLine` 新增 `dueProvisional`

- [ ] **Step 1: 写失败测试**

```java
@Test
void 下单即生成全部阶段_入库阶段到期日用预计入库日并标预估() {
    PurchaseIn p = purchase("2026-03-01", "2026-03-31");   // 下单日 / 预计入库日
    service.onOrder(p, item, 201L, 60L);
    List<Payable> got = captureInserted();
    assertEquals(3, got.size());
    assertEquals(LocalDate.parse("2026-03-01"), got.get(0).getDueDate());   // 首付 下单+0
    assertEquals(0, got.get(0).getDueProvisional());
    assertEquals(LocalDate.parse("2026-03-31"), got.get(1).getDueDate());   // 验收 预计入库+0
    assertEquals(1, got.get(1).getDueProvisional());
    assertEquals(LocalDate.parse("2026-06-29"), got.get(2).getDueDate());   // 尾款 预计入库+90
    assertEquals(1, got.get(2).getDueProvisional());
}

@Test
void 实际入库后改写预估到期日并清标记_不新增应付() {
    service.onReceive(purchaseReceived("2026-04-10"), item, 201L);
    verify(payableMapper, never()).insert(any());
    // 验收 → 2026-04-10,尾款 → 2026-07-09,due_provisional 都变 0
}

@Test
void 三段金额合计等于设备合同价_末段补差() {
    // price=219036,比例 30/60/10 → 65710.80 / 131421.60 / 21903.60
}

@Test
void 已红冲的采购单不生成应付() {
    service.onOrder(reversedPurchase(), item, 201L, 60L);
    verify(payableMapper, never()).insert(any());
}

@Test
void 合同没有付款方式时不生成应付而是报错() {
    when(contractPaymentService.terms(60L)).thenReturn(Collections.emptyList());
    BizException e = assertThrows(BizException.class, () -> service.onOrder(p, item, 201L, 60L));
    assertTrue(e.getMessage().contains("合同还没设置付款方式"));
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -s settings.xml -B -o test -Dtest=AssetPaymentServiceTest`
Expected: FAIL

- [ ] **Step 3: 实现**

`generate(...)` 去掉 `orderStages`/`receiveStages` 两个开关，改为所有段都生成；`入库` 段的基准日 = `p.getReceiveDate() != null ? receiveDate : p.getExpectReceiveDate()`，用到 `expectReceiveDate` 时置 `dueProvisional=1`。`onReceive` 改为改写而非插入。旧版整单应付的兼容判断（`hasLegacy`）保留：整单已有该阶段时仍跳过，避免重复计负债。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -s settings.xml -B -o test -Dtest=AssetPaymentServiceTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat(purchase): 应付在下单时全量生成,未触发阶段到期日按预计入库日推算"
```

### Task 10: 采购与前端接线

**Files:**
- Modify: `backend/.../modules/purchase/service/PurchaseService.java`、`dto/PurchaseOrderRequest.java`
- Modify: `backend/.../modules/contract/interfaces/ContractController.java`
- Modify: `frontend/src/api/contract.ts`、`views/Contract.vue`
- Modify: `frontend/src/views/Purchase.vue`、`api/purchase.ts`
- Modify: `frontend/src/views/Asset.vue`、`api/asset.ts`
- Test: `backend/src/test/java/top/aole/rent/modules/contract/ContractPaymentServiceTest.java`（追加覆盖设备汇总用例）

**Interfaces:**
- Produces:
  - `GET /rent/contracts/{id}/payment-terms` → `{ terms: TermLine[], describe: String, assets: [{assetId, label, serialNo, purchasePrice}], expectedTotalByStage: [...] }`
  - `PUT /rent/contracts/{id}/payment-terms` ← `{ terms: TermInput[] }`
  - 两个端点都加 `@RequireRole(value = {"老板","财务","供应链"}, action = "合同付款方式", targetType = "contract")`

- [ ] **Step 1: 后端接线**

`PurchaseOrderRequest` 去掉 `paymentTerms`、加 `expectReceiveDate`；`PurchaseService.order()` 改为取合同付款方式（没有则 `ensureDefault` 并在日志里记一行），`expectReceiveDate` 为空时按 `rule_config[purchase_lead_days]` 算；`receive()` 调新的 `onReceive`。

- [ ] **Step 2: 合同页付款方式编辑区**

复用 `frontend/src/components/PaymentTermsEditor.vue`；下方显示覆盖的台账设备与各段合计预计付款金额。

- [ ] **Step 3: 采购下单弹窗改只读**

去掉 `PaymentTermsEditor`，改为显示继承来的付款方式摘要 + 「到合同里修改」链接（跳 `/contract?id=<id>`）+ 一个「预计入库日」日期选择器（默认下单日+提前期）。

- [ ] **Step 4: 设备详情付款计划改只读 + 台账列表加「未付」列**

付款计划区块标题改成「付款计划（继承自合同 {合同号}）」，去掉编辑按钮，改为跳合同的链接；预估到期日后面加「(预计)」标注。列表新增「未付」列，成本口径打码。

- [ ] **Step 5: typecheck + build**

Run: `pnpm typecheck` 然后 `pnpm build`
Expected: exit 0

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat(contract): 付款方式挂合同并与租赁台账打通,采购下单改为继承"
```

### Task 11: V118 数据迁移

**Files:**
- Modify: `backend/src/main/resources/db/migration/V118__contract_payment_term.sql`（在 Task 8 的建表之后追加数据迁移段）

**Interfaces:**
- Consumes: 无
- Produces: 无

- [ ] **Step 1: 写反推与补齐**

顺序：
1. `expect_receive_date` 回填：已入库的用 `receive_date`，未入库的用 `order_date + 30`
2. 每份未删合同的付款方式反推：用 `ROW_NUMBER() OVER (PARTITION BY ...)` 对「该合同下各设备的条件签名（阶段名+比例+触发点+账期 拼串后排序）」按覆盖设备数倒序取第一名（写法参考 `V110__remove_assets.sql` 里的 `ROW_NUMBER` 用法）；取不到的走默认三段
3. 不一致的合同：`remark` 追加一行「付款方式按覆盖设备最多的一套迁移,存在 N 种差异待复核」
4. `payable.term_id` 按 `stage_name` 映射到新合同段；映射不上的保留原值并在 `remark` 追加「(历史条件)」
5. 补齐未触发阶段的应付：`purchase_in.status <> '已红冲'`、设备有 `purchase_price`、该(设备,阶段)尚无应付行 → 插「待付」，到期日按 `expect_receive_date + due_days`，`due_provisional = 1`，`remark = '合同付款方式补齐'`；已入库的用 `receive_date` 且 `due_provisional = 0`

- [ ] **Step 2: 本机 MySQL 验证**

造三类合同：条件一致 / 条件不一致（核对取覆盖最多那套 + 备注留痕）/ 名下无条件（走默认三段）。造一张已下单未入库的采购单，核对补出了入库阶段应付、到期日 = 下单日+30、带标记与备注；再走一次入库，核对到期日改写为真实日期、标记清零。对照组：已付阶段没动、已红冲单没补。

- [ ] **Step 3: 全量回归**

Run: `mvn -s settings.xml -B -o clean package`；前端 `pnpm typecheck && pnpm build`
Expected: 全过

- [ ] **Step 4: 接口实测**

合同付款方式读写、设备详情继承展示、采购下单→入库全流程、现金流驾驶舱的到期分层数字对得上。

- [ ] **Step 5: 更新 CODEMAP 并提交**

```bash
git add -A && git commit -m "feat(contract): 付款方式数据迁移与未触发阶段应付补齐(V118)"
```

---

## 自查

**Spec 覆盖**：付款条件搬合同 → Task 8/9/10/11；与台账双向关联 → Task 10 Step 4（台账侧）+ Step 2（合同侧）；任一条件即计入累计应付与未付 → Task 9；维保并入 + 仓库物品报修 → Task 5/6/7；转让双向打通 → Task 2/3/4；财务清理 → Task 1。迁移拆分 V117/V118/V119 与实施顺序 → 四个 Phase 的排列。

**风险对应**：累计应付变大 → Task 11 Step 1.5 的统一备注；反推不唯一 → Task 11 Step 1.3 的合同备注留痕；`term_id` 按阶段名映射 → Task 11 Step 1.4；菜单撤掉 → Task 7 Step 2 的路由重定向。

**类型一致性**：`ContractPaymentService` 的方法名在 Task 8 定义、Task 9/10 引用一致（`terms`/`termsAsInput`/`replaceAll`/`describe`/`ensureDefault`）；`InvService` 三个流转方法在 Task 6 定义并只在同任务内使用；`due_provisional` 列名与实体字段 `dueProvisional` 在 Task 8/9/11 一致。

**待执行时确认的假设**：Task 2 Step 1 先读 `TransferDtos` 的真实内部类名（本计划按 `LineRow`/`OrderRow` 假设），不一致时以代码为准。
