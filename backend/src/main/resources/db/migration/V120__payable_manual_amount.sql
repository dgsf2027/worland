-- =====================================================================
-- 应付金额手工调整标记(V120)
--
-- 背景:采购应付整单开放编辑(试跑阶段要能补/改数据)。应付金额本来是
-- 「设备合同价 × 合同付款方式比例」算出来的,一旦允许手工改,就必须让系统知道
-- 哪些行是人改过的 —— 否则后面改合同付款方式时,AssetPaymentService 重算
-- 会把手工值悄悄冲掉,对账时也分不清是算出来的还是人填的。
--
-- 口径与仓库里已有的手工标记一致:yc_rent_contract_boq.amount_manual、
-- yc_rent_asset_bom.subtotal_override。
--   amount_manual = 1 → 重算「待付」应付时跳过本行,保留人工值
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE yc_rent_payable
  ADD COLUMN amount_manual tinyint(1) NOT NULL DEFAULT 0
    COMMENT '金额是否手工调整过:1=人工改过,重算待付应付时跳过本行并保留人工值' AFTER amount;
