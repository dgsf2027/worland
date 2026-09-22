-- =====================================================================
-- 清理「未关联设备租赁台账」的历史采购单与应付(V115)
--
-- 背景:改造前(V112 之前)采购单的明细是手填序列号建的,不指向设备租赁台账;
-- 改造后采购下单改为勾选合同清单生成的台账设备(purchase_item.asset_id 必填)。
-- 遗留的那批老单据既对不上台账,也对不上收租对照,留在列表里只会干扰判断。
--
-- 删除口径(三个条件必须同时满足,任一不满足即保留):
--   ① status = '已下单'          —— 已入库(设备已进台账)/已红冲的一律不动
--   ② 该单所有明细的 asset_id 全为空,或所属合同已不存在 —— 即「没绑设备/没绑合同」
--   ③ 该单没有任何一笔 payable 处于 '已付'  —— 付过钱的单据保留,财务上要能追溯
--
-- 动作:单头 + 明细 + 应付三张表逻辑删除;单号追加 '#DEL{id}' 后缀,
--       让原单号以后能重新使用(uk_purchase_no 唯一键对逻辑删除行同样生效)。
-- 设备台账不动 —— 按口径②这些单本就没有生成过设备。
-- =====================================================================

SET NAMES utf8mb4;

CREATE TEMPORARY TABLE tmp_unlinked_purchase (id bigint NOT NULL PRIMARY KEY);

INSERT INTO tmp_unlinked_purchase (id)
SELECT p.id
FROM yc_rent_purchase_in p
WHERE p.is_deleted = 0
  AND p.status = '已下单'
  -- ② 没有任何一条明细关联到台账设备,或所属合同已不存在
  AND (
        NOT EXISTS (
          SELECT 1 FROM yc_rent_purchase_item i
          WHERE i.purchase_in_id = p.id AND i.is_deleted = 0 AND i.asset_id IS NOT NULL
        )
        OR NOT EXISTS (
          SELECT 1 FROM yc_rent_contract c
          WHERE c.id = p.contract_id AND c.is_deleted = 0
        )
      )
  -- ③ 没有已付款的应付
  AND NOT EXISTS (
        SELECT 1 FROM yc_rent_payable y
        WHERE y.purchase_in_id = p.id AND y.is_deleted = 0 AND y.status = '已付'
      );

UPDATE yc_rent_payable SET is_deleted = 1
WHERE purchase_in_id IN (SELECT id FROM tmp_unlinked_purchase);

UPDATE yc_rent_purchase_item SET is_deleted = 1
WHERE purchase_in_id IN (SELECT id FROM tmp_unlinked_purchase);

-- 单号列只有 varchar(64):先按后缀长度截原单号,保证「#DEL{id}」完整写进去不被截掉
UPDATE yc_rent_purchase_in
SET no = CONCAT(LEFT(no, 64 - CHAR_LENGTH(CONCAT('#DEL', id))), '#DEL', id), is_deleted = 1
WHERE id IN (SELECT id FROM tmp_unlinked_purchase);

DROP TEMPORARY TABLE tmp_unlinked_purchase;
