-- =====================================================================
-- 删除供应商上游中的「恒丰自动化」「睿捷设备」「广达货架」「11」及其关联关系(V109)
-- 前三个是 V3 的演示种子数据,「11」为线上误建数据。按名称匹配(未删除的),找不到则什么都不做。
-- 处理方式:
--   1) 供货矩阵 yc_rent_supplier_supply        → 逻辑删除
--   2) 设备 / 工程量清单项 / 采购入库单 / 采购明细 / 维保工单 / 供应商考察 上的 supplier_id → 置 NULL(解除关联,单据本身保留)
--   3) 供应商 yc_rent_supplier                  → 逻辑删除
-- 考察记录若曾关联这些供应商,结果保持不变,仅解除关联。
-- =====================================================================

SET NAMES utf8mb4;

CREATE TEMPORARY TABLE tmp_removed_supplier (id bigint NOT NULL PRIMARY KEY);

INSERT INTO tmp_removed_supplier (id)
SELECT id FROM yc_rent_supplier
WHERE is_deleted = 0
  AND TRIM(name) IN ('恒丰自动化', '睿捷设备', '广达货架', '11');

UPDATE yc_rent_supplier_supply SET is_deleted = 1
WHERE supplier_id IN (SELECT id FROM tmp_removed_supplier);

UPDATE yc_rent_asset SET supplier_id = NULL
WHERE supplier_id IN (SELECT id FROM tmp_removed_supplier);

UPDATE yc_rent_asset_bom SET supplier_id = NULL
WHERE supplier_id IN (SELECT id FROM tmp_removed_supplier);

UPDATE yc_rent_purchase_in SET supplier_id = NULL
WHERE supplier_id IN (SELECT id FROM tmp_removed_supplier);

UPDATE yc_rent_purchase_item SET supplier_id = NULL
WHERE supplier_id IN (SELECT id FROM tmp_removed_supplier);

UPDATE yc_rent_maintenance SET supplier_id = NULL
WHERE supplier_id IN (SELECT id FROM tmp_removed_supplier);

UPDATE yc_rent_supplier_inspection SET supplier_id = NULL
WHERE supplier_id IN (SELECT id FROM tmp_removed_supplier);

UPDATE yc_rent_supplier SET is_deleted = 1
WHERE id IN (SELECT id FROM tmp_removed_supplier);

DROP TEMPORARY TABLE tmp_removed_supplier;
