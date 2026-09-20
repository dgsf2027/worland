-- =====================================================================
-- 设备·租赁台账:合同编号 + 合同税率 + 合同清单(V112)
-- 1) yc_rent_asset 增加 contract_no(合同编号,多台设备可共用)与 tax_rate(合同税率 0-1);
--    序列号 serial_no 保留为系统内部唯一标识(建档时自动生成),页面不再录入。
--    contract_no 与「合同·签约与租金计划」按合同号对应,能匹配到合同时回填 contract_id。
-- 2) yc_rent_asset_boq:合同清单(对齐《播种墙工程量清单计价表》9 列:序号/名称/型号/规格/单位/数量/单价/金额/备注)。
--    合计(含税)= Σ 金额,回写设备 purchase_price(合同价);税率用于拆出不含税金额与税额。
--    赠送行金额留空、优惠行金额为负 → amount_manual=1(金额不按 数量×单价 自动算)。
-- 3) 配件 BOM 明细(yc_rent_asset_bom)补 序号/型号/单位 三列,与合同清单同一套格式;
--    BOM 不再联动合同价(由 AssetService 去掉联动),只作配件构成与故障档案。
-- 4) 存量迁移:把原「工程量清单计价表」的一级计价行搬进合同清单,保持合同价口径不变。
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE yc_rent_asset
  ADD COLUMN contract_no varchar(64)   DEFAULT NULL COMMENT '合同编号(多台设备可共用;匹配 yc_rent_contract.no 时回填 contract_id)' AFTER serial_no,
  ADD COLUMN tax_rate    decimal(9,6)  DEFAULT NULL COMMENT '合同税率(0-1,如 0.13);清单金额为含税价' AFTER purchase_price;

CREATE INDEX idx_asset_contract_no ON yc_rent_asset (contract_no);

CREATE TABLE yc_rent_asset_boq (
  id            bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  asset_id      bigint        NOT NULL COMMENT '设备(yc_rent_asset.id)',
  seq           int           NOT NULL DEFAULT 1 COMMENT '序号',
  name          varchar(128)  NOT NULL COMMENT '名称',
  model         varchar(128)  DEFAULT NULL COMMENT '型号',
  spec          varchar(255)  DEFAULT NULL COMMENT '规格',
  unit          varchar(16)   DEFAULT NULL COMMENT '单位',
  qty           decimal(18,4) DEFAULT NULL COMMENT '数量',
  unit_price    decimal(18,2) DEFAULT NULL COMMENT '单价(含税)',
  amount        decimal(18,2) DEFAULT NULL COMMENT '金额(含税);空=表格里的「-」(赠送/不计价)',
  amount_manual tinyint(1)    NOT NULL DEFAULT 0 COMMENT '金额是否手工填写:0=数量×单价自动算 1=手填(赠送/优惠行)',
  remark        varchar(500)  DEFAULT NULL COMMENT '备注',
  create_time   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted    tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_boq_asset (asset_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备合同清单(工程量清单计价表格式,合计=合同价)';

ALTER TABLE yc_rent_asset_bom
  ADD COLUMN seq   int          DEFAULT NULL COMMENT '序号(同合同清单格式)' AFTER parent_id,
  ADD COLUMN model varchar(128) DEFAULT NULL COMMENT '型号' AFTER name,
  ADD COLUMN spec  varchar(255) DEFAULT NULL COMMENT '规格' AFTER model,
  ADD COLUMN unit  varchar(16)  DEFAULT NULL COMMENT '单位' AFTER spec;

-- 存量:原清单一级计价行 → 合同清单(名称/数量/单价/金额/备注照搬;手动合价按手填金额处理)
INSERT INTO yc_rent_asset_boq (asset_id, seq, name, spec, unit, qty, unit_price, amount, amount_manual, remark)
SELECT b.asset_id,
       ROW_NUMBER() OVER (PARTITION BY b.asset_id ORDER BY b.id),
       b.name,
       NULL,
       NULL,
       b.qty,
       b.unit_cost,
       COALESCE(b.subtotal_override, ROUND(COALESCE(b.qty, 1) * COALESCE(b.unit_cost, 0), 2)),
       CASE WHEN b.subtotal_override IS NULL THEN 0 ELSE 1 END,
       b.remark
FROM yc_rent_asset_bom b
WHERE b.is_deleted = 0
  AND b.parent_id IS NULL
  AND (b.unit_cost IS NOT NULL OR b.subtotal_override IS NOT NULL);
