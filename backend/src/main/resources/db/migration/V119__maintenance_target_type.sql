-- =====================================================================
-- 维保工单支持两类对象(V119):设备租赁台账 asset / 资产管理仓库物品 inv_item
--
-- 背景:维保工单原本是独立模块、只能对设备开单。并入「资产管理」后,仓库物品
-- (周转箱/货架配件等)也要能报修。
--
-- 库存数量的唯一写手仍是资产管理的出入库流转(InvService.adjust 写 yc_rent_inv_movement
-- 并调整 stock_qty/repair_qty/scrapped_qty)。工单只是它的单据封装:
--   建单(仓库物品) → 调「送修」把 qty 件从库存移到维修中,movement id 记在 movement_out_id
--   完工          → 按结果调「修好」(维修中→库存)或「报废」(维修中→已报废),记 movement_back_id
-- 不另记一套库存状态,避免双账。
--
-- 存量数据全部是设备工单 → target_type 默认 'asset',asset_id 放开为可空以容纳仓库物品工单。
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
    COMMENT '建单时「送修」的出入库流转 id(yc_rent_inv_movement.id)' AFTER cost,
  ADD COLUMN movement_back_id bigint DEFAULT NULL
    COMMENT '完工时「修好」或「报废」的出入库流转 id' AFTER movement_out_id;

ALTER TABLE yc_rent_maintenance
  MODIFY COLUMN asset_id bigint DEFAULT NULL
    COMMENT '设备(yc_rent_asset.id);target_type=asset 时必填';

CREATE INDEX idx_maintenance_inv_item ON yc_rent_maintenance (inv_item_id);
CREATE INDEX idx_maintenance_target ON yc_rent_maintenance (target_type, status);
