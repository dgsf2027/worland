-- =====================================================================
-- 设备租赁合同:合同清单(《工程量清单计价表》)+ 合同税率 + 设备总价(V113)
-- 口径调整(纠正 V112):清单挂在**合同**上 —— 一份设备租赁合同一张清单,里面可以有播种墙、货架、运费、
-- 安装调试费、合作优惠等多行;清单含税合计 = 该合同的设备总价,供租金/IRR 参考。
-- 合同编号与税率以合同为准(设备只显示所属合同);清单行可按数量一键生成设备并回挂到本合同。
-- V112 在设备上加的 contract_no/tax_rate 与 yc_rent_asset_boq 不再使用,但保留不删(历史录入不丢),
-- 其中已挂合同的设备级清单在本迁移里搬到对应合同下。
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE yc_rent_contract
  ADD COLUMN tax_rate        decimal(9,6)  DEFAULT NULL COMMENT '合同税率(0-1,如 0.13);清单金额为含税价' AFTER target_irr,
  ADD COLUMN equipment_total decimal(18,2) DEFAULT NULL COMMENT '设备总价(含税)= 合同清单合计' AFTER tax_rate;

CREATE TABLE yc_rent_contract_boq (
  id              bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  contract_id     bigint        NOT NULL COMMENT '合同(yc_rent_contract.id)',
  seq             int           NOT NULL DEFAULT 1 COMMENT '序号',
  name            varchar(128)  NOT NULL COMMENT '名称',
  model           varchar(128)  DEFAULT NULL COMMENT '型号',
  spec            varchar(255)  DEFAULT NULL COMMENT '规格',
  unit            varchar(16)   DEFAULT NULL COMMENT '单位',
  qty             decimal(18,4) DEFAULT NULL COMMENT '数量',
  unit_price      decimal(18,2) DEFAULT NULL COMMENT '单价(含税)',
  amount          decimal(18,2) DEFAULT NULL COMMENT '金额(含税);空=表格里的「-」(赠送/不计价)',
  amount_manual   tinyint(1)    NOT NULL DEFAULT 0 COMMENT '金额是否手工填写:0=数量×单价自动算 1=手填(赠送/优惠行)',
  asset_category  varchar(16)   DEFAULT NULL COMMENT '生成设备时的品类:播种墙/货架/阁楼/配件;空=不生成设备',
  generated_count int           NOT NULL DEFAULT 0 COMMENT '已按本行生成的设备台数',
  remark          varchar(500)  DEFAULT NULL COMMENT '备注',
  create_time     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted      tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_contract_boq (contract_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合同清单(工程量清单计价表;合计=合同设备总价)';

ALTER TABLE yc_rent_asset
  ADD COLUMN boq_line_id bigint DEFAULT NULL COMMENT '由哪一行合同清单生成(yc_rent_contract_boq.id)' AFTER contract_id;
CREATE INDEX idx_asset_boq_line ON yc_rent_asset (boq_line_id);

-- 存量:V112 的设备级清单 → 所属合同的合同清单(设备未挂合同的保留在原表,不搬)
INSERT INTO yc_rent_contract_boq (contract_id, seq, name, model, spec, unit, qty, unit_price, amount, amount_manual, remark)
SELECT a.contract_id,
       ROW_NUMBER() OVER (PARTITION BY a.contract_id ORDER BY b.asset_id, b.seq, b.id),
       b.name, b.model, b.spec, b.unit, b.qty, b.unit_price, b.amount, b.amount_manual, b.remark
FROM yc_rent_asset_boq b
JOIN yc_rent_asset a ON a.id = b.asset_id AND a.is_deleted = 0
WHERE b.is_deleted = 0
  AND a.contract_id IS NOT NULL;

-- 存量:设备上录的税率搬到合同(同合同多台取其一;合同已填的不覆盖)
UPDATE yc_rent_contract c
JOIN (SELECT contract_id, MAX(tax_rate) AS tax_rate FROM yc_rent_asset
      WHERE is_deleted = 0 AND contract_id IS NOT NULL AND tax_rate IS NOT NULL
      GROUP BY contract_id) t ON t.contract_id = c.id
SET c.tax_rate = t.tax_rate
WHERE c.tax_rate IS NULL;

-- 设备总价 = 合同清单含税合计
UPDATE yc_rent_contract c
JOIN (SELECT contract_id, ROUND(SUM(COALESCE(amount, 0)), 2) AS total
      FROM yc_rent_contract_boq WHERE is_deleted = 0 GROUP BY contract_id) s ON s.contract_id = c.id
SET c.equipment_total = s.total;
