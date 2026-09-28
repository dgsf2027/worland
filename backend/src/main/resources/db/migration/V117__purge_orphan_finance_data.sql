-- =====================================================================
-- 清理三个财务模块里来源单据已不存在的测试数据(V117)
--
-- 界定口径(用户确认):按「来源单据是否还在」
--   * 凭证 / 分录 / 总账汇总行 / 折旧行 —— 来源单据已逻辑删除或查不到的一律删
--   * 结账分配 / 月度报表 —— 清空(按期间汇总,没有合同维度,现存的都是测试期跑出来的)
--   * 出资人名册 —— 保留(数智云仓/小洪/刘总/其他出资方是真实出资结构)
--   * LLM 调用日志 —— 保留(接口调用审计,不是业务数据)
--
-- 凭证来源类型与 id 的对应(见 VoucherService 的 post 调用):
--   rent_bill→收租单  purchase_in→采购单  transfer_line→转让行  depreciation→设备
--   manual 手工凭证保留:没有来源单据可判定。
--
-- 逻辑删 vs 物理删:
--   * yc_rent_ledger_book        物理删 —— 本表没有 is_deleted 列
--   * yc_rent_monthly_report     物理删 —— 唯一键是 (period, is_deleted),
--                                同账期只容得下「一条有效 + 一条已删」,
--                                逻辑删会在该账期已有已删行时撞 Duplicate entry
--   * yc_rent_rent_distribution  物理删 —— uk_distribution_no 唯一,
--                                逻辑删的行仍占着分配单号,以后同号分配插不回去
--   其余走逻辑删;凭证号加「#DEL{id}」让原号可以重新使用。
--   折旧行走逻辑删:uk_depr_asset_period 含 asset_id,被删设备的 id 不会复用,不会撞键。
--
-- 500 万红线是从 ledger_book 现算的,没有独立数据,跟着一起干净。
-- 找不到对象时什么都不做,本迁移在干净库上可安全执行。
-- =====================================================================

SET NAMES utf8mb4;

CREATE TEMPORARY TABLE tmp_orphan_voucher (id bigint NOT NULL PRIMARY KEY);
CREATE TEMPORARY TABLE tmp_orphan_depr (id bigint NOT NULL PRIMARY KEY);

-- ---------------------------------------------------------------------
-- 1. 圈定来源单据已不在的凭证
-- ---------------------------------------------------------------------
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

-- 来源 id 为空的非手工凭证也算孤儿(无从追溯)
INSERT IGNORE INTO tmp_orphan_voucher (id)
SELECT id FROM yc_rent_voucher
WHERE is_deleted = 0 AND source_doc_type <> 'manual' AND source_doc_id IS NULL;

-- ---------------------------------------------------------------------
-- 2. 凭证层:总账汇总行物理删 → 分录逻辑删 → 凭证头逻辑删并改号
-- ---------------------------------------------------------------------
DELETE FROM yc_rent_ledger_book
WHERE voucher_id IN (SELECT id FROM tmp_orphan_voucher);

UPDATE yc_rent_voucher_line SET is_deleted = 1
WHERE voucher_id IN (SELECT id FROM tmp_orphan_voucher);

UPDATE yc_rent_voucher
SET is_deleted = 1,
    voucher_no = CONCAT(LEFT(voucher_no, 64 - CHAR_LENGTH(CONCAT('#DEL', id))), '#DEL', id)
WHERE id IN (SELECT id FROM tmp_orphan_voucher);

-- ---------------------------------------------------------------------
-- 3. 折旧行:设备已删或查不到
--    (先灌临时表再按 id 删,避免 UPDATE 的 WHERE 子查询引用被更新表报 1093)
-- ---------------------------------------------------------------------
INSERT IGNORE INTO tmp_orphan_depr (id)
SELECT d.id FROM yc_rent_asset_depreciation_line d
WHERE d.is_deleted = 0
  AND NOT EXISTS (SELECT 1 FROM yc_rent_asset a
                  WHERE a.id = d.asset_id AND a.is_deleted = 0);

UPDATE yc_rent_asset_depreciation_line SET is_deleted = 1
WHERE id IN (SELECT id FROM tmp_orphan_depr);

-- ---------------------------------------------------------------------
-- 4. 结账分配与月度报表:清空(物理删,理由见文件头)
-- ---------------------------------------------------------------------
DELETE FROM yc_rent_rent_distribution;
DELETE FROM yc_rent_monthly_report;

DROP TEMPORARY TABLE tmp_orphan_depr;
DROP TEMPORARY TABLE tmp_orphan_voucher;
