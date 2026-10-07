-- =====================================================================
-- 删除合同编号为「1」的测试合同及其设备 + 清掉未关联合同的孤儿租金计划(V121)
--
-- ① 设备 · 租赁台账「合同编号 = 1」:那张合同本身 + 挂在它名下的设备,
--    连同前后关联的租金计划/收租单/逾期案/收回单/押金/变更/工程量清单/付款条件、
--    采购单与应付、凭证与总账流水、折旧行、转让处置行,一起删。
--    合同不能只删一半 —— 留下任何一头,台账或驾驶舱上就会多出一行查不到来源的孤儿数据。
--    「未挂合同」(contract_id IS NULL)的设备按用户口径保留,本迁移不动。
--
-- ② 现金流/分配驾驶舱「累计回款」未关联:
--    累计回款取自 rent_schedule 未核销行(CashflowService.uncollectedReceivables),
--    这个口径原先不看合同还在不在 —— 合同删了、它的租金计划还留着,金额就继续计进
--    应收合计与兑付缺口的「累计回款」。这里把 contract_id 指不到活合同的租金计划物理删掉;
--    CashflowService 同步加了活合同过滤,以后不会再冒出来(数据与口径两头都改)。
--
-- 口径同 V116:
--   * 一律逻辑删除,唯有三处物理删 —— ledger_book(本表没有 is_deleted 列)、
--     rent_schedule(uk_schedule_period 与逻辑删打架,见 V114)、overdue_case(uk_overdue_bill 同理)。
--   * 带唯一键的单号(合同号/收租单号/收回单号/采购单号/凭证号/设备序列号)删除时追加
--     「#DEL{id}」,让原单号以后能重新使用(唯一键对逻辑删除的行同样生效)。
--   * 折旧行走逻辑删:uk_depr_asset_period 含 asset_id,被删设备的 id 不会复用,不会撞键。
--   * 资产管理(仓库)出租单、转让单头只解除合同关联,单据本身保留 —— 那是另一套业务。
-- 找不到的对象跳过,本迁移可在没有这些数据的库上安全执行。
-- =====================================================================

SET NAMES utf8mb4;

CREATE TEMPORARY TABLE tmp121_contract (id bigint NOT NULL PRIMARY KEY);
CREATE TEMPORARY TABLE tmp121_asset    (id bigint NOT NULL PRIMARY KEY);
CREATE TEMPORARY TABLE tmp121_bill     (id bigint NOT NULL PRIMARY KEY);
CREATE TEMPORARY TABLE tmp121_purchase (id bigint NOT NULL PRIMARY KEY);
CREATE TEMPORARY TABLE tmp121_tline    (id bigint NOT NULL PRIMARY KEY);
CREATE TEMPORARY TABLE tmp121_voucher  (id bigint NOT NULL PRIMARY KEY);
CREATE TEMPORARY TABLE tmp121_transfer (id bigint NOT NULL PRIMARY KEY);

-- ---------------------------------------------------------------------
-- 0. 圈定删除集合:合同号恰好是「1」的未删合同,及其名下设备与关联单据
-- ---------------------------------------------------------------------
INSERT INTO tmp121_contract (id)
SELECT id FROM yc_rent_contract
WHERE is_deleted = 0 AND TRIM(no) = '1';

INSERT INTO tmp121_asset (id)
SELECT id FROM yc_rent_asset
WHERE is_deleted = 0 AND contract_id IN (SELECT id FROM tmp121_contract);

INSERT INTO tmp121_bill (id)
SELECT id FROM yc_rent_rent_bill
WHERE is_deleted = 0 AND contract_id IN (SELECT id FROM tmp121_contract);

INSERT INTO tmp121_purchase (id)
SELECT id FROM yc_rent_purchase_in
WHERE is_deleted = 0 AND contract_id IN (SELECT id FROM tmp121_contract);

INSERT INTO tmp121_tline (id)
SELECT id FROM yc_rent_transfer_order_line
WHERE is_deleted = 0 AND asset_id IN (SELECT id FROM tmp121_asset);

-- 凭证:来源单据落在上述集合里的(红冲凭证与原凭证同 source_doc_type/id,一并命中)
INSERT IGNORE INTO tmp121_voucher (id)
SELECT id FROM yc_rent_voucher
WHERE is_deleted = 0 AND source_doc_type = 'rent_bill'
  AND source_doc_id IN (SELECT id FROM tmp121_bill);

INSERT IGNORE INTO tmp121_voucher (id)
SELECT id FROM yc_rent_voucher
WHERE is_deleted = 0 AND source_doc_type = 'purchase_in'
  AND source_doc_id IN (SELECT id FROM tmp121_purchase);

INSERT IGNORE INTO tmp121_voucher (id)
SELECT id FROM yc_rent_voucher
WHERE is_deleted = 0 AND source_doc_type = 'depreciation'
  AND source_doc_id IN (SELECT id FROM tmp121_asset);

INSERT IGNORE INTO tmp121_voucher (id)
SELECT id FROM yc_rent_voucher
WHERE is_deleted = 0 AND source_doc_type = 'transfer_line'
  AND source_doc_id IN (SELECT id FROM tmp121_tline);

-- ---------------------------------------------------------------------
-- 1. 凭证层(总账汇总行物理删 —— ledger_book 没有 is_deleted 列)
-- ---------------------------------------------------------------------
DELETE FROM yc_rent_ledger_book
WHERE voucher_id IN (SELECT id FROM tmp121_voucher);

UPDATE yc_rent_voucher_line SET is_deleted = 1
WHERE voucher_id IN (SELECT id FROM tmp121_voucher);

UPDATE yc_rent_voucher
SET is_deleted = 1,
    voucher_no = CONCAT(LEFT(voucher_no, 64 - CHAR_LENGTH(CONCAT('#DEL', id))), '#DEL', id)
WHERE id IN (SELECT id FROM tmp121_voucher);

-- ---------------------------------------------------------------------
-- 2. 收租层:逾期案(物理删·uk_overdue_bill)→ 收回单 → 收租单
-- ---------------------------------------------------------------------
DELETE FROM yc_rent_overdue_case
WHERE contract_id IN (SELECT id FROM tmp121_contract);

DELETE FROM yc_rent_overdue_case
WHERE rent_bill_id IN (SELECT id FROM tmp121_bill);

UPDATE yc_rent_repossess_order
SET is_deleted = 1,
    no = CONCAT(LEFT(no, 64 - CHAR_LENGTH(CONCAT('#DEL', id))), '#DEL', id)
WHERE is_deleted = 0 AND contract_id IN (SELECT id FROM tmp121_contract);

UPDATE yc_rent_rent_bill
SET is_deleted = 1,
    bill_no = CONCAT(LEFT(bill_no, 64 - CHAR_LENGTH(CONCAT('#DEL', id))), '#DEL', id)
WHERE id IN (SELECT id FROM tmp121_bill);

-- ---------------------------------------------------------------------
-- 3. 合同层(租金计划物理删 —— uk_schedule_period 与逻辑删打架,见 V114)
-- ---------------------------------------------------------------------
DELETE FROM yc_rent_rent_schedule
WHERE contract_id IN (SELECT id FROM tmp121_contract);

UPDATE yc_rent_contract_asset SET is_deleted = 1
WHERE contract_id IN (SELECT id FROM tmp121_contract);

UPDATE yc_rent_deposit_ledger SET is_deleted = 1
WHERE contract_id IN (SELECT id FROM tmp121_contract);

UPDATE yc_rent_contract_change SET is_deleted = 1
WHERE contract_id IN (SELECT id FROM tmp121_contract);

UPDATE yc_rent_contract_boq SET is_deleted = 1
WHERE contract_id IN (SELECT id FROM tmp121_contract);

-- 付款条件挂合同(V118 新增表,V116 那轮还没有)
UPDATE yc_rent_contract_payment_term SET is_deleted = 1
WHERE contract_id IN (SELECT id FROM tmp121_contract);

UPDATE yc_rent_contract
SET is_deleted = 1,
    no = CONCAT(LEFT(no, 64 - CHAR_LENGTH(CONCAT('#DEL', id))), '#DEL', id)
WHERE id IN (SELECT id FROM tmp121_contract);

-- ---------------------------------------------------------------------
-- 4. 采购层(该合同下的采购单:应付 → 明细 → 单头)
-- ---------------------------------------------------------------------
UPDATE yc_rent_payable SET is_deleted = 1
WHERE purchase_in_id IN (SELECT id FROM tmp121_purchase);

UPDATE yc_rent_purchase_item SET is_deleted = 1
WHERE purchase_in_id IN (SELECT id FROM tmp121_purchase);

UPDATE yc_rent_purchase_in
SET is_deleted = 1,
    no = CONCAT(LEFT(no, 64 - CHAR_LENGTH(CONCAT('#DEL', id))), '#DEL', id)
WHERE id IN (SELECT id FROM tmp121_purchase);

-- ---------------------------------------------------------------------
-- 5. 设备层(口径同 V116 第 6 段,补上 V112 的设备工程量清单与盘点差异)
-- ---------------------------------------------------------------------
UPDATE yc_rent_file_object SET is_deleted = 1
WHERE biz_type = 'asset_bom'
  AND biz_id IN (SELECT b.id FROM yc_rent_asset_bom b JOIN tmp121_asset t ON t.id = b.asset_id);

UPDATE yc_rent_asset_bom SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp121_asset);

UPDATE yc_rent_asset_boq SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp121_asset);

UPDATE yc_rent_asset_event SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp121_asset);

UPDATE yc_rent_asset_payment_term SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp121_asset);

UPDATE yc_rent_contract_asset SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp121_asset);

UPDATE yc_rent_payable SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp121_asset);

UPDATE yc_rent_asset_depreciation_line SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp121_asset);

UPDATE yc_rent_maintenance SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp121_asset);

UPDATE yc_rent_stock_diff SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp121_asset);

INSERT IGNORE INTO tmp121_transfer (id)
SELECT DISTINCT transfer_order_id FROM yc_rent_transfer_order_line
WHERE id IN (SELECT id FROM tmp121_tline);

UPDATE yc_rent_transfer_order_line SET is_deleted = 1
WHERE id IN (SELECT id FROM tmp121_tline);

-- 转让单头按剩余行重算台数/金额;一行不剩的整单也删
UPDATE yc_rent_transfer_order o
JOIN tmp121_transfer t ON t.id = o.id
LEFT JOIN (
  SELECT transfer_order_id, COUNT(*) AS cnt,
         COALESCE(SUM(transfer_price), 0) AS price,
         COALESCE(SUM(gain), 0) AS gain
  FROM yc_rent_transfer_order_line
  WHERE is_deleted = 0
  GROUP BY transfer_order_id
) s ON s.transfer_order_id = o.id
SET o.asset_count = COALESCE(s.cnt, 0),
    o.total_price = COALESCE(s.price, 0),
    o.total_gain  = COALESCE(s.gain, 0),
    o.is_deleted  = CASE WHEN COALESCE(s.cnt, 0) = 0 THEN 1 ELSE o.is_deleted END;

UPDATE yc_rent_purchase_item SET asset_id = NULL
WHERE asset_id IN (SELECT id FROM tmp121_asset);

UPDATE yc_rent_asset
SET is_deleted = 1,
    serial_no = CONCAT(LEFT(serial_no, 64 - CHAR_LENGTH(CONCAT('#DEL', id))), '#DEL', id)
WHERE id IN (SELECT id FROM tmp121_asset);

-- ---------------------------------------------------------------------
-- 6. 幸存单据收尾:解除对被删合同/采购单的挂载
-- ---------------------------------------------------------------------
UPDATE yc_rent_asset
SET contract_id = NULL,
    status = CASE WHEN status = '在租' THEN '投放' ELSE status END
WHERE is_deleted = 0 AND contract_id IN (SELECT id FROM tmp121_contract);

UPDATE yc_rent_asset SET purchase_in_id = NULL
WHERE is_deleted = 0 AND purchase_in_id IN (SELECT id FROM tmp121_purchase);

UPDATE yc_rent_transfer_order SET contract_id = NULL
WHERE is_deleted = 0 AND contract_id IN (SELECT id FROM tmp121_contract);

UPDATE yc_rent_inv_rental SET contract_id = NULL
WHERE contract_id IN (SELECT id FROM tmp121_contract);

-- ---------------------------------------------------------------------
-- 7. 孤儿租金计划:contract_id 指不到活合同的,物理删
--    (驾驶舱「累计回款」「应收合计」「兑付缺口」里未关联金额的来源)
-- ---------------------------------------------------------------------
DELETE s FROM yc_rent_rent_schedule s
LEFT JOIN yc_rent_contract c ON c.id = s.contract_id AND c.is_deleted = 0
WHERE c.id IS NULL;

DROP TEMPORARY TABLE tmp121_transfer;
DROP TEMPORARY TABLE tmp121_voucher;
DROP TEMPORARY TABLE tmp121_tline;
DROP TEMPORARY TABLE tmp121_purchase;
DROP TEMPORARY TABLE tmp121_bill;
DROP TEMPORARY TABLE tmp121_asset;
DROP TEMPORARY TABLE tmp121_contract;
