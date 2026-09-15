-- =====================================================================
-- 设备合同付款条件(自定义多段)+ 应付逐台生成(V107)
-- 1) yc_rent_asset_payment_term:每台设备的付款阶段(名称/比例/触发时点/到期天数),各段比例合计 100%。
--    预计付款金额 = 设备集采价 × 比例,不落列即时算(末段补差保证合计 = 集采价)。
--    采购下单时条件先挂在采购明细(purchase_item_id)上,入库生成设备后回填 asset_id。
-- 2) yc_rent_payable 增加 asset_id / purchase_item_id / term_id:应付按设备逐台生成,
--    触发时点=下单 的阶段在下单时生成,=入库 的阶段在入库时生成。旧的整单应付这三列为 NULL,不受影响。
-- =====================================================================

SET NAMES utf8mb4;

CREATE TABLE yc_rent_asset_payment_term (
  id                bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  asset_id          bigint        DEFAULT NULL COMMENT '设备(yc_rent_asset.id);入库前为 NULL',
  purchase_item_id  bigint        DEFAULT NULL COMMENT '采购明细(yc_rent_purchase_item.id);非采购建档设备为 NULL',
  seq               int           NOT NULL DEFAULT 1 COMMENT '阶段顺序',
  stage_name        varchar(16)   NOT NULL COMMENT '阶段名称:首付/验收/尾款/预付/发货…',
  ratio             decimal(18,8) NOT NULL COMMENT '付款比例(0-1),同一设备各段合计=1',
  trigger_point     varchar(8)    NOT NULL DEFAULT '入库' COMMENT '触发时点:下单/入库(决定应付生成时机与到期基准日)',
  due_days          int           NOT NULL DEFAULT 0 COMMENT '到期天数(触发日 + N 天)',
  create_time       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted        tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_payterm_asset (asset_id),
  KEY idx_payterm_item (purchase_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备合同付款条件(自定义多段,预计付款=集采价×比例)';

ALTER TABLE yc_rent_payable
  ADD COLUMN asset_id bigint DEFAULT NULL COMMENT '设备(逐台应付;旧整单应付为 NULL)' AFTER purchase_in_id;
ALTER TABLE yc_rent_payable
  ADD COLUMN purchase_item_id bigint DEFAULT NULL COMMENT '采购明细(逐台应付;旧整单应付为 NULL)' AFTER asset_id;
ALTER TABLE yc_rent_payable
  ADD COLUMN term_id bigint DEFAULT NULL COMMENT '来源付款条件(yc_rent_asset_payment_term.id)' AFTER purchase_item_id;
CREATE INDEX idx_payable_asset ON yc_rent_payable (asset_id);
