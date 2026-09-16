-- =====================================================================
-- 删除设备·租赁台账中的 WL-BZQ-0002、WL-HJ-0001、WL-BZQ-0003、WL-BZQ-0004、WL-HJ-0002、WL-HJ-0003
-- 及其关联关系(V110)。按序列号匹配未删除的设备,找不到的跳过。均为逻辑删除:
--   设备自身:工程量清单项(及清单附件)、状态事件、合同付款条件、合同挂载
--   关联单据:逐台应付、折旧计提行、维保工单、转让单行(转让单头按剩余行重算,行删空则单头一并删除)
--   采购明细:保留(采购单据不删),仅把回填的 asset_id 置空
-- 设备序列号加「#DEL{id}」后缀后逻辑删除,原序列号以后可以重新使用(serial_no 唯一键含已删除行)。
-- 已生成的折旧/处置凭证分录不在此处改动。
-- =====================================================================

SET NAMES utf8mb4;

CREATE TEMPORARY TABLE tmp_removed_asset (id bigint NOT NULL PRIMARY KEY);

INSERT INTO tmp_removed_asset (id)
SELECT id FROM yc_rent_asset
WHERE is_deleted = 0
  AND serial_no IN ('WL-BZQ-0002', 'WL-HJ-0001', 'WL-BZQ-0003', 'WL-BZQ-0004', 'WL-HJ-0002', 'WL-HJ-0003');

-- ---------- 设备自身数据 ----------
UPDATE yc_rent_file_object SET is_deleted = 1
WHERE biz_type = 'asset_bom'
  AND biz_id IN (SELECT b.id FROM yc_rent_asset_bom b JOIN tmp_removed_asset t ON t.id = b.asset_id);

UPDATE yc_rent_asset_bom SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp_removed_asset);

UPDATE yc_rent_asset_event SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp_removed_asset);

UPDATE yc_rent_asset_payment_term SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp_removed_asset);

UPDATE yc_rent_contract_asset SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp_removed_asset);

-- ---------- 关联单据 ----------
UPDATE yc_rent_payable SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp_removed_asset);

UPDATE yc_rent_asset_depreciation_line SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp_removed_asset);

UPDATE yc_rent_maintenance SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp_removed_asset);

CREATE TEMPORARY TABLE tmp_touched_transfer (id bigint NOT NULL PRIMARY KEY);

INSERT INTO tmp_touched_transfer (id)
SELECT DISTINCT l.transfer_order_id FROM yc_rent_transfer_order_line l
JOIN tmp_removed_asset t ON t.id = l.asset_id
WHERE l.is_deleted = 0;

UPDATE yc_rent_transfer_order_line SET is_deleted = 1
WHERE asset_id IN (SELECT id FROM tmp_removed_asset);

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
WHERE asset_id IN (SELECT id FROM tmp_removed_asset);

-- ---------- 设备 ----------
UPDATE yc_rent_asset SET is_deleted = 1, serial_no = CONCAT(serial_no, '#DEL', id)
WHERE id IN (SELECT id FROM tmp_removed_asset);

DROP TEMPORARY TABLE tmp_touched_transfer;
DROP TEMPORARY TABLE tmp_removed_asset;
