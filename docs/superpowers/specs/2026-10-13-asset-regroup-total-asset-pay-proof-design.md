# 资产管理重组 + 总资产报表 + 资金进出强制凭证

日期：2026-10-13 · 状态：待评审 · 来源：10-07 会议纪要《供应商客户资产管理及AI应用》

## 背景与目标

10-07 会议对系统提出了三项明确决策，本文是第一批落地（子项目 A）：

1. **资产管理重组**：「维保工单」「损坏与赔偿」「设置」不属于资产本身，移出资产管理，归入新的「服务」模块。
2. **总资产报表**：在资产台账里补充设备总资产价值，分「含折旧」「不含折旧」两版。
3. **付款凭证强制留存**：钱的进出不能只点按钮，必须先上传银行回单或支付截图。

不在本次范围：资产盘点与定期维护计划（子项目 B）、供应商营业执照识别与 AI 合格判定（子项目 C）、电子合同对接 Token 中台（下月，另立项）、RFID 盘点（远期）、财务双账凭证逻辑梳理（纪要未给出具体要求）。

---

## 一、资产管理重组

### 口径

资产管理只管资产本身：在哪、几套、状态、进出。售后和规则配置归「服务」。

### 页面归属

| 标签页 | 现在 | 调整后 |
|---|---|---|
| 提醒与报表 | 资产管理 | 资产管理（去掉「赔偿待收」一类） |
| 资产台账 | 资产管理 | 资产管理 |
| 出租管理 | 资产管理 | 资产管理 |
| 出入库记录 | 资产管理 | 资产管理 |
| 维保工单 | 资产管理 | **服务** |
| 损坏与赔偿 | 资产管理 | **服务** |
| 设置（赔偿价目 + 标签企业信息） | 资产管理 | **服务** |

子项目 B 再给资产管理加「资产盘点」「维护计划」两个标签页。

### 实现

- 前端新增视图 `views/Service.vue`，路由 `/service`，标签页 `maintenance` / `damages` / `settings`，支持 `?tab=` 直达。三个标签页的内容从 `Inventory.vue` 原样搬过去，抽成组件，不改交互。
- 侧栏新增「🛠️ 服务 · 维保/赔偿/设置」；资产管理菜单文字改为「资产管理 · 台账/出租/出入库」，路由 title 同步。
- 旧路径重定向：`/maintenance` → `/service?tab=maintenance`；`/inventory?tab=maintenance|damages|settings` 进入时改跳 `/service` 对应标签页（旧书签不 404）。
- 「提醒与报表」：后端 `InvService.overview` 不再生成「赔偿待收」提醒，`pendingCompensation` 字段保留；服务模块「损坏与赔偿」页顶部显示待收合计。
- 文案修正：`MovementForm.vue` 里「资产管理 › 设置」改为「服务 › 设置」；扫码页提示语不变（出租管理仍在资产管理）。
- **后端接口路径与权限不变**（仍是 `/rent/inventory/damages`、`/comp-prices`、`/company`、`/rent/maintenance`），只动页面归属，对数据零影响。

---

## 二、总资产报表

### 口径

- 统计对象：设备·租赁台账（`yc_rent_asset`），状态为 采购 / 投放 / 在租 / 待转让 / 收回待处置 的设备；已转让、报废不计。
- **不含折旧（原值）** = Σ 集采价 `purchase_price`
- **含折旧（净值）** = Σ（集采价 − 该设备累计已计提折旧 Σ`yc_rent_asset_depreciation_line.depr_amount`，经营口径 ops）
- 截至时点：折旧行里最大的 `period`（页面注明「折旧计提至 YYYY-MM」）。不另做按任意日期回溯。
- 集采价为空的设备单独计数提示「N 台缺集采价，未计入」，不按 0 混进合计。
- 仓库实物（资产管理按批计数）没有单价，不计入；页面注明。

### 接口

`GET /rent/assets/total-value`（老板、财务）→

```
{
  depreciatedThrough: "2026-09",      // 最近计提期,无则 null
  assetCount, originalTotal, accumulatedDepreciation, netTotal,
  missingPriceCount,
  byCategory: [{ category, count, originalTotal, accumulatedDepreciation, netTotal }],
  byStatus:   [{ status,   count, originalTotal, accumulatedDepreciation, netTotal }]
}
```

`GET /rent/assets/total-value/export` 导出 Excel（两张表：按品类、按状态，末行合计），沿用现有 Excel 导出写法。

计算放在新的 `AssetValueService`（纯计算部分抽静态方法，便于单测）；累计折旧用一次 group by 查询，不逐台查。

### 页面

「设备·租赁台账」页新增标签页「总资产」：顶部两张大数卡（不含折旧 / 含折旧）+ 累计折旧，下面两张表（按品类、按状态），右上角导出。非老板/财务看不到该标签页。

---

## 三、资金进出强制凭证

### 口径

**先传凭证，再登记。** 登记请求必须带凭证文件 id，缺了后端直接拒绝；前端在没选文件时按钮置灰。不采用「先登记后补传」——那样仍然可以只点按钮，违背纪要。

### 覆盖的动作

| 动作 | 接口 | 凭证 |
|---|---|---|
| 采购应付登记付款 | `POST /rent/purchase/payables/{id}/pay` | 必须 |
| 收租到账核销 | `POST /rent/bills/{id}/match` | 必须 |
| 收租批量核销 | `POST /rent/bills/batch-match` | 必须（一份回单可挂多张单） |
| 收租退款 | `POST /rent/bills/{id}/refund` | 必须 |
| 逾期还款恢复 | `POST /rent/overdue/{id}/repay` | 必须 |
| 损坏赔偿 → 已收取 | `PUT /rent/inventory/damages/{id}/settle` | 必须 |
| 损坏赔偿 → 已减免 | 同上 | 不动钱，不要凭证，**必须填原因** |

不覆盖：撤销付款、红冲（都是纠错，原凭证保留不动）；股东分配目前只有「计算分配」没有「实际打款」动作，等加打款步骤时再接入。

### 数据模型

新表 `yc_rent_pay_proof`（V122）：

| 列 | 说明 |
|---|---|
| `id` | 主键 |
| `file_id` | `yc_rent_file_object.id` |
| `biz_type` | `payable` / `rent_bill` / `overdue_case` / `inv_damage` |
| `biz_id` | 对应单据 id |
| `action` | 付款 / 核销 / 退款 / 还款 / 赔偿收取 |
| `create_by_name`, `create_time` | 谁、何时挂的 |

唯一键 (`file_id`, `biz_type`, `biz_id`)。一个文件可挂多张单据（批量核销）。

### 流程

1. 前端先上传：`POST /rent/files`，`bizType=pay_proof`，不带 `bizId`。只收 jpg/jpeg/png/pdf，单个 ≤ 10MB。返回 `fileId`。
2. 登记请求带 `proofFileIds: [..]`（各请求 DTO 新增该字段）。
3. 后端 `PayProofService.bind(bizType, bizIds, action, fileIds)`，在登记的同一事务里：
   - `fileIds` 为空 → 400「请先上传付款凭证（银行回单或支付截图）」
   - 文件不存在、`bizType` 不是 `pay_proof`、不是当前用户上传的 → 400
   - 校验通过后写关联行，再执行原登记逻辑；任一步失败整体回滚。
4. 查看：`GET /rent/pay-proofs?bizType=&bizId=` 返回凭证列表（文件名、上传人、时间、签名下载链接，复用现有签名 URL）。
5. 补传（历史单据）：`POST /rent/pay-proofs`（`bizType, bizId, fileIds`），只对已处于已付/已核销/已收取状态的单据开放，角色同原登记动作。

凭证**不可删除**，只能追加；撤销付款不删凭证。`pay_proof` 不进 `DELETABLE_BIZ_TYPES`。

### 历史数据

上线前已登记的单据不受影响：详情里显示「历史无凭证」并提供「补传凭证」按钮。现有 `cash-check` 稽核新增一项「已付/已核销但无凭证 N」。

### 页面

采购应付、收租单、逾期案、损坏与赔偿四处的登记弹窗加「付款凭证」上传区（拖拽或点选，可多张，带缩略图/文件名）；未上传时确认按钮置灰。各单据详情加「凭证」栏，点开预览。

---

## 测试

- 单元测试：
  - `PayProofService`：缺凭证、文件不存在、文件类型不对、他人文件、批量挂多单、事务回滚。
  - 总资产计算：含/不含折旧、排除终态、缺集采价不入合计、无折旧行时净值 = 原值。
  - 赔偿「已减免」无原因拒绝。
- 接口实测：上述 6 个资金动作各走一遍「不带凭证被拒 → 带凭证成功 → 查得到凭证」；总资产接口与 Excel 导出。
- 前端：本地启动，截图核对侧栏、资产管理与服务两个页面的标签页、旧书签跳转、登记弹窗按钮置灰、总资产标签页。

## 附带调整：核销权限收紧

收租核销（单笔、批量）与逾期还款恢复三个接口原来**没有角色限制**，任何登录用户都能调用。本次一并加 `@RequireRole({"财务","老板"})`，与登记付款一致（2026-10-13 xuan 确认）。
