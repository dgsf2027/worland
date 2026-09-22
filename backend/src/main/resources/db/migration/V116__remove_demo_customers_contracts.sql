-- =====================================================================
-- 删除演示/虚拟数据及其全部关联关系(V116)
--
-- 四批(用户逐条点名):
--   ① 供应商上游「科瑞电控」(V3 演示种子)
--   ② 客户「德邦仓配 / 顺丰园区仓 / 京东云仓(华南) / 云山快仓 / 阿昌仓储 / 微仓科技」(V4 演示种子)
--   ③ 设备租赁台账「设备1」= 种子设备 WL-BZQ-0001,以及序列号/型号/品类叫「设备1」的自建设备
--   ④ 合同 WL-C-2026-001 / 002 / 003 及其前后关联的应付、收租、凭证等
--
-- 删除集合:
--   客户集 = 名字命中上述 6 个的未删客户
--   合同集 = 客户集名下所有合同 ∪ 单号命中 WL-C-2026-001/002/003 的合同
--   设备集 = 见 ③
--
-- 口径:
--   * 一律逻辑删除,唯有三处物理删 —— ledger_book(本表没有 is_deleted 列)、
--     rent_schedule(uk_schedule_period 与逻辑删打架,见 V114)、overdue_case(uk_overdue_bill 同理)。
--   * 带唯一键的单号(合同号/收租单号/采购单号/收回单号/凭证号/设备序列号)删除时追加「#DEL{id}」,
--     让原单号以后能重新使用(唯一键对逻辑删除的行同样生效)。
--   * 折旧行走逻辑删:uk_depr_asset_period 含 asset_id,被删设备的 id 不会复用,不会撞键。
--   * 没被删但挂在被删合同/客户上的设备,解除挂载并把「在租」回落为「投放」,
--     否则台账上会留下一台在租却查不到合同的孤儿设备。
--   * 资产管理(仓库)出租单只解除客户/合同关联(含客户名快照),单据本身保留 —— 那是另一套业务。
--   * 报价定价档 rule_config[target_irr / 云山快仓] 一并删除(前端下拉同步移除,默认改「其他」)。
-- 找不到的对象跳过,本迁移可在没有这些数据的库上安全执行。
-- =====================================================================

SET NAMES utf8mb4;

CREATE TEMPORARY TABLE tmp_del_customer (id bigint NOT NULL PRIMARY KEY);
CREATE TEMPORARY TABLE tmp_del_contract (id bigint NOT NULL PRIMARY KEY);
CREATE TEMPORARY TABLE tmp_del_asset    (id bigint NOT NULL PRIMARY KEY);
CREATE TEMPORARY TABLE tmp_del_bill     (id bigint NOT NULL PRIMARY KEY);
CREATE TEMPORARY TABLE tmp_del_purchase (id bigint NOT NULL PRIMARY KEY);
CREATE TEMPORARY TABLE tmp_del_tline    (id bigint NOT NULL PRIMARY KEY);
CREATE TEMPORARY TABLE tmp_del_voucher  (id bigint NOT NULL PRIMARY KEY);
CREATE TEMPORARY TABLE tmp_del_supplier (id bigint NOT NULL PRIMARY KEY);
CREATE TEMPORARY TABLE tmp_touched_transfer (id bigint NOT NULL PRIMARY KEY);

-- ---------------------------------------------------------------------
-- 0. 圈定删除集合
-- ---------------------------------------------------------------------
INSERT INTO tmp_del_customer (id)
SELECT id FROM yc_rent_customer
WHERE is_deleted = 0
  AND TRIM(name) IN ('德邦仓配', '顺丰园区仓', '京东云仓(华南)', '云山快仓', '阿昌仓储', '微仓科技');

INSERT INTO tmp_del_contract (id)
SELECT id FROM yc_rent_contract
WHERE is_deleted = 0
  AND (customer_id IN (SELECT id FROM tmp_del_customer)
       OR TRIM(no) IN ('WL-C-2026-001', 'WL-C-2026-002', 'WL-C-2026-003'));

INSERT INTO tmp_del_asset (id)
SELECT id FROM yc_rent_asset
WHERE is_deleted = 0
  AND (TRIM(serial_no) = 'WL-BZQ-0001'
       OR TRIM(serial_no) = '设备1'
       OR TRIM(model) = '设备1'
       OR TRIM(category) = '设备1');

INSERT INTO tmp_del_bill (id)
SELECT id FROM yc_rent_rent_bill
WHERE is_deleted = 0 AND contract_id IN (SELECT id FROM tmp_del_contract);

INSERT INTO tmp_del_purchase (id)
SELECT id FROM yc_rent_purchase_in
WHERE is_deleted = 0 AND contract_id IN (SELECT id FROM tmp_del_contract);

INSERT INTO tmp_del_tline (id)
SELECT id FROM yc_rent_transfer_order_line
WHERE is_deleted = 0 AND asset_id IN (SELECT id FROM tmp_del_asset);

INSERT INTO tmp_del_supplier (id)
SELECT id FROM yc_rent_supplier
WHERE is_deleted = 0 AND TRIM(name) = '科瑞电控';

-- 凭证:来源单据落在上述集合里的(红冲凭证与原凭证同 source_doc_type/id,一并命中)
INSERT IGNORE INTO tmp_del_voucher (id)
SELECT id FROM yc_rent_voucher
WHERE is_deleted = 0 AND source_doc_type = 'rent_bill'
  AND source_doc_id IN (SELECT id FROM tmp_del_bill);

INSERT IGNORE INTO tmp_del_voucher (id)
SELECT id FROM yc_rent_voucher
WHERE is_deleted = 0 AND source_doc_type = 'purchase_in'
  AND source_doc_id IN (SELECT id FROM tmp_del_purchase);

INSERT IGNORE INTO tmp_del_voucher (id)
SELECT id FROM yc_rent_voucher
WHERE is_deleted = 0 AND source_doc_type = 'depreciation'
  AND source_doc_id IN (SELECT id FROM tmp_del_asset);

INSERT IGNORE INTO tmp_del_voucher (id)
SELECT id FROM yc_rent_voucher
WHERE is_deleted = 0 AND source_doc_type = 'transfer_line'
  AND source_doc_id IN (SELECT id FROM tmp_del_tline);

-- ---------------------------------------------------------------------
-- 1. 凭证层(总账汇总行物理删 —— ledger_book 没有 is_deleted 列)
-- ---------------------------------------------------------------------
DELETE FROM yc_rent_ledger_book
WHERE voucher_id IN (SELECT id FROM tmp_del_voucher);

UPDATE yc_rent_voucher_line SET is_deleted = 1
WHERE voucher_id IN (SELECT id FROM tmp_del_voucher);

UPDATE yc_rent_voucher
SET is_deleted = 1,
    voucher_no = CONCAT(LEFT(voucher_no, 64 - CHAR_LENGTH(CONCAT('#DEL', id))), '#DEL', id)
WHERE id IN (SELECT id FROM tmp_del_voucher);

-- ---------------------------------------------------------------------
-- 2. 收租层:逾期案(物理删·uk_overdue_bill)→ 收回单 → 收租单
-- ---------------------------------------------------------------------
DELETE FROM yc_rent_overdue_case
WHERE contract_id IN (SELECT id FROM tmp_del_contract);

DELETE FROM yc_rent_overdue_case
WHERE rent_bill_id IN (SELECT id FROM tmp_del_bill);

UPDATE yc_rent_repossess_order
SET is_deleted = 1,
    no = CONCAT(LEFT(no, 64 - CHAR_LENGTH(CONCAT('#DEL', id))), '#DEL', id)
WHERE is_deleted = 0 AND contract_id IN (SELECT id FROM tmp_del_contract);

UPDATE yc_rent_rent_bill
SET is_deleted = 1,
    bill_no = CONCAT(LEFT(bill_no, 64 - CHAR_LENGTH(CONCAT('#DEL', id))), '#DEL', id)
WHERE id IN (SELECT id FROM tmp_del_bill);

-- ---------------------------------------------------------------------
-- 3. 租金计划(物理删 —— uk_schedule_period 与逻辑删打架,见 V114)
-- ---------------------------------------------------------------------
DELETE FROM yc_rent_rent_schedule
WHERE contract_id IN (SELECT id FROM tmp_del_contract);

-- ---------------------------------------------------------------------
-- 4. 合同层
-- ---------------------------------------------------------------------
UPDATE yc_rent_contract_asset SET is_deleted = 1
WHERE contract_id IN (SELECT id FROM tmp_del_contract);

UPDATE yc_rent_deposit_ledger SET is_deleted = 1
WHERE contract_id IN (SELECT id FROM tmp_del_contract);

UPDATE yc_rent_contract_change SET is_deleted = 1
WHERE contract_id IN (SELECT id FROM tmp_del_contract);

UPDATE yc_rent_contract_boq SET is_deleted = 1
WHERE contract_id IN (SELECT id FROM tmp_del_contract);

UPDATE yc_rent_contract
SET is_deleted = 1,
    no = CONCAT(LEFT(no, 64 - CHAR_LENGTH(CONCAT('#DEL', id))), '#DEL', id)
WHERE id IN (SELECT id FROM tmp_del_contract);

-- ---------------------------------------------------------------------
-- 5. 采购层(被删合同下的采购单:应付 → 明细 → 单头)
-- ---------------------------------------------------------------------
UPDATE yc_rent_payable SET is_deleted = 1
WHERE purchase_in_id IN (SELECT id FROM tmp_del_purchase);

UPDATE yc_rent_purchase_item SET is_deleted = 1
WHERE purchase_in_id IN (SELECT id FROM tmp_del_purchase);

UPDATE yc_rent_purchase_in
SET is_deleted = 1,
    no = CONCAT(LEFT(no, 64 - CHAR_LENGTH(CONCAT('#DEL', id))), '#DEL', id)
WHERE id IN (SELECT id FROM tmp_del_purchase);

-- ---------------------------------------------------------------------
-- 6. 设备层(「设备1」及其自身数据与关联单据,口径同 V110)
-- ---------------------------------------------------------------------
UPDATE yc_rent_file_object SET is_deleted = 1
WHERE biz_type = 'asset_bom'
  AND biz_id IN (SELECT b.id FROM yc_rent_asset_bom b JOIN tmp_del_asset t ON t.id = b.asset_id);

UPDATE yc_rent_asset_bom SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp_del_asset);

UPDATE yc_rent_asset_event SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp_del_asset);

UPDATE yc_rent_asset_payment_term SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp_del_asset);

UPDATE yc_rent_contract_asset SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp_del_asset);

UPDATE yc_rent_payable SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp_del_asset);

UPDATE yc_rent_asset_depreciation_line SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp_del_asset);

UPDATE yc_rent_maintenance SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp_del_asset);

INSERT INTO tmp_touched_transfer (id)
SELECT DISTINCT transfer_order_id FROM yc_rent_transfer_order_line
WHERE id IN (SELECT id FROM tmp_del_tline);

UPDATE yc_rent_transfer_order_line SET is_deleted = 1
WHERE id IN (SELECT id FROM tmp_del_tline);

UPDATE yc_rent_transfer_order o
JOIN tmp_touched_transfer t ON t.id = o.id
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
WHERE asset_id IN (SELECT id FROM tmp_del_asset);

UPDATE yc_rent_asset
SET is_deleted = 1,
    serial_no = CONCAT(LEFT(serial_no, 64 - CHAR_LENGTH(CONCAT('#DEL', id))), '#DEL', id)
WHERE id IN (SELECT id FROM tmp_del_asset);

-- ---------------------------------------------------------------------
-- 7. 幸存设备收尾:解除对被删合同/客户/采购单的挂载,「在租」回落为「投放」
-- ---------------------------------------------------------------------
UPDATE yc_rent_asset
SET contract_id = NULL,
    status = CASE WHEN status = '在租' THEN '投放' ELSE status END
WHERE is_deleted = 0 AND contract_id IN (SELECT id FROM tmp_del_contract);

UPDATE yc_rent_asset SET current_holder_customer_id = NULL
WHERE is_deleted = 0 AND current_holder_customer_id IN (SELECT id FROM tmp_del_customer);

UPDATE yc_rent_asset SET intended_customer_id = NULL
WHERE is_deleted = 0 AND intended_customer_id IN (SELECT id FROM tmp_del_customer);

UPDATE yc_rent_asset SET purchase_in_id = NULL
WHERE is_deleted = 0 AND purchase_in_id IN (SELECT id FROM tmp_del_purchase);

UPDATE yc_rent_inv_rental SET contract_id = NULL
WHERE contract_id IN (SELECT id FROM tmp_del_contract);

-- 出租单上的客户名是下单时的快照,清 customer_id 的同时改写,否则界面上还能看到已删客户的名字
-- (customer_name 是 NOT NULL,写占位串而不是 NULL)
UPDATE yc_rent_inv_rental SET customer_id = NULL, customer_name = '(客户已删除)'
WHERE customer_id IN (SELECT id FROM tmp_del_customer);

-- ---------------------------------------------------------------------
-- 8. 客户层(客户名无唯一键,不必改名)
-- ---------------------------------------------------------------------
UPDATE yc_rent_opportunity SET is_deleted = 1
WHERE customer_id IN (SELECT id FROM tmp_del_customer);

UPDATE yc_rent_customer_followup SET is_deleted = 1
WHERE customer_id IN (SELECT id FROM tmp_del_customer);

UPDATE yc_rent_customer SET is_deleted = 1
WHERE id IN (SELECT id FROM tmp_del_customer);

-- ---------------------------------------------------------------------
-- 9. 供应商「科瑞电控」(口径同 V109:解除引用,单据本身保留)
-- ---------------------------------------------------------------------
UPDATE yc_rent_supplier_supply SET is_deleted = 1
WHERE supplier_id IN (SELECT id FROM tmp_del_supplier);

UPDATE yc_rent_asset SET supplier_id = NULL
WHERE supplier_id IN (SELECT id FROM tmp_del_supplier);

UPDATE yc_rent_asset_bom SET supplier_id = NULL
WHERE supplier_id IN (SELECT id FROM tmp_del_supplier);

UPDATE yc_rent_purchase_in SET supplier_id = NULL
WHERE supplier_id IN (SELECT id FROM tmp_del_supplier);

UPDATE yc_rent_purchase_item SET supplier_id = NULL
WHERE supplier_id IN (SELECT id FROM tmp_del_supplier);

UPDATE yc_rent_maintenance SET supplier_id = NULL
WHERE supplier_id IN (SELECT id FROM tmp_del_supplier);

UPDATE yc_rent_supplier_inspection SET supplier_id = NULL
WHERE supplier_id IN (SELECT id FROM tmp_del_supplier);

UPDATE yc_rent_supplier SET is_deleted = 1
WHERE id IN (SELECT id FROM tmp_del_supplier);

-- ---------------------------------------------------------------------
-- 10. 报价定价档:目标IRR「云山快仓」25%(前端下拉同步移除,默认改「其他」30%)
-- ---------------------------------------------------------------------
UPDATE yc_rent_rule_config SET is_deleted = 1
WHERE rule_key = 'target_irr' AND scope_key = '云山快仓';

DROP TEMPORARY TABLE tmp_touched_transfer;
DROP TEMPORARY TABLE tmp_del_supplier;
DROP TEMPORARY TABLE tmp_del_voucher;
DROP TEMPORARY TABLE tmp_del_tline;
DROP TEMPORARY TABLE tmp_del_purchase;
DROP TEMPORARY TABLE tmp_del_bill;
DROP TEMPORARY TABLE tmp_del_asset;
DROP TEMPORARY TABLE tmp_del_contract;
DROP TEMPORARY TABLE tmp_del_customer;
