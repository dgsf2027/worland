-- =====================================================================
-- 沃朗科技租赁板块 · M1-06/07 逐件设备 + 配件树 BOM Schema + 种子 (V5)
-- MySQL 8 · utf8mb4 · InnoDB · 库 worland_dev · 表前缀 yc_rent_
-- 依据: DESIGN_DOC §4.1(asset 逐件/asset_bom 自引用配件树) + 业务流§8.1(配件树/成本拆解/残值/故障档案/单台收益) + 流程6投放
-- 口径: 金额=元 decimal(18,2) · 比率 decimal(18,8) · 手写 migration(§4.16,Flyway boot 自动 apply)
-- 单一真相源(§4.24/§4.17):
--   * status 状态机 由 asset_event 驱动(采购/投放/在租/待转让/已转让/收回待处置/报废)
--   * book_value(经营口径) 即时算:采购价-简单直线折旧占位【M3 折旧表 asset_depreciation_line 精确化】
--   * residual_value(残值) 不落列,即时算 = market_price × transfer_rate[category](rule_config)
--   * self_purchase_payback(自购回本期) 即时算 = market_price / monthly_labor_value
--   asset_event 复用 V1 已建 yc_rent_asset_event
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 设备主表 —— 逐件建档(一台一条·序列号唯一)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_asset (
  id                        bigint        NOT NULL AUTO_INCREMENT COMMENT '主键(=一台设备一条命的 symbol)',
  serial_no                 varchar(64)   NOT NULL COMMENT '序列号/条码(逐件唯一)',
  category                  varchar(32)   NOT NULL COMMENT '品类:播种墙/货架/阁楼/配件',
  model                     varchar(128)  DEFAULT NULL COMMENT '型号/规格',
  market_price              decimal(18,2) DEFAULT NULL COMMENT '市场价(元);残值=市场价×品类转让率',
  purchase_price            decimal(18,2) DEFAULT NULL COMMENT '集采价(元·成本口径);book_value 折旧基数',
  supplier_id               bigint        DEFAULT NULL COMMENT '整机供应商(yc_rent_supplier.id)',
  status                    varchar(16)   NOT NULL DEFAULT '采购' COMMENT '状态机(asset_event 驱动):采购/投放/在租/待转让/已转让/收回待处置/报废',
  project_id                bigint        DEFAULT NULL COMMENT '项目隔离键(多项目独立核算·P1-4)',
  monthly_labor_value       decimal(18,2) DEFAULT NULL COMMENT '月替代人工价值(元·价值定价核心输入)',
  replace_headcount         decimal(9,2)  DEFAULT NULL COMMENT '替代人数',
  current_holder_customer_id bigint       DEFAULT NULL COMMENT '【派生】当前承租客户(@owner=合同生效/关闭事件唯一维护)',
  contract_id               bigint        DEFAULT NULL COMMENT '【派生】当前在租合同(@owner=签约/作废事件)',
  purchase_in_id            bigint        DEFAULT NULL COMMENT '采购入库单(M1-12 采购模块回填·先签约后采购)',
  remark                    varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time               datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time               datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted                tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_asset_serial (serial_no),
  KEY idx_asset_status (status),
  KEY idx_asset_category (category),
  KEY idx_asset_contract (contract_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备主表:逐件建档+状态机+价值定价输入(M1-06)';

-- ---------------------------------------------------------------------
-- 2. 配件树 BOM —— 自引用多级(设备→总成/模块→部件→元器件)
--    成本拆解=Σ(qty×unit_cost);残值构成=Σ 部件残值曲线;故障档案=fault_count 按配件
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_asset_bom (
  id             bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  asset_id       bigint        NOT NULL COMMENT '所属设备(yc_rent_asset.id)',
  parent_id      bigint        DEFAULT NULL COMMENT '父节点(自引用);NULL=一级总成',
  name           varchar(128)  NOT NULL COMMENT '配件/模块名:钢构框架/电控系统/PLC控制器…',
  qty            decimal(9,2)  NOT NULL DEFAULT 1 COMMENT '数量',
  unit_cost      decimal(18,2) DEFAULT NULL COMMENT '单价(元)',
  supplier_id    bigint        DEFAULT NULL COMMENT '配件供应商(可挂,配件级比价)',
  life_years     decimal(9,2)  DEFAULT NULL COMMENT '寿命(年)',
  warranty_until date          DEFAULT NULL COMMENT '质保到期日(质保倒计时)',
  repairable     tinyint(1)    NOT NULL DEFAULT 1 COMMENT '是否可维修:0否 1是',
  fault_count    int           NOT NULL DEFAULT 0 COMMENT '累计故障次数(高故障配件预警)',
  residual_rate  decimal(18,8) DEFAULT NULL COMMENT '部件残值率(0-1);钢构保值电控贬值快,空=随品类',
  remark         varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted     tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_bom_asset (asset_id),
  KEY idx_bom_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配件树 BOM:自引用多级+成本/残值/故障/质保(M1-07)';

-- ---------------------------------------------------------------------
-- 3. 种子数据(对齐 UI mockup 设备详情页,便于 E2E)
-- ---------------------------------------------------------------------
INSERT INTO yc_rent_asset
 (id, serial_no, category, model, market_price, purchase_price, supplier_id, status,
  monthly_labor_value, replace_headcount, remark) VALUES
 (1, 'WL-BZQ-0001', '播种墙', '播种墙 V2 标准型', 200000.00, 180000.00, 1, '在租', 15000.00, 3.00, '恒丰整机·已投放在租'),
 (2, 'WL-BZQ-0002', '播种墙', '播种墙 V2 标准型', 200000.00, 180000.00, 1, '投放', 15000.00, 3.00, '待起租'),
 (3, 'WL-HJ-0001',  '货架',   '重型阁楼货架 5m', 60000.00,  42000.00,  3, '采购', 4000.00,  1.00, '广达按图·采购中');

-- 配件树 BOM(设备1:播种墙 = 钢构框架 + 电控系统[含PLC/变频器/线束] + 传感模组 + 传动系统 + 装配辅料)
--   一级总成 id=1..5;二级子件(电控系统 id=2 之下)id=6..8
INSERT INTO yc_rent_asset_bom
 (id, asset_id, parent_id, name, qty, unit_cost, supplier_id, life_years, warranty_until, repairable, fault_count, residual_rate, remark) VALUES
 (1, 1, NULL, '钢构框架',   1, 60000.00, 1, 15, '2027-08-01', 1, 0, 0.40000000, '保值,残值率高'),
 (2, 1, NULL, '电控系统',   1, 45000.00, 4, 8,  '2027-02-01', 1, 1, 0.08000000, '贬值快;质保内换新(=Σ子件)'),
 (3, 1, NULL, '传感模组',   1, 25000.00, 1, 6,  '2027-02-01', 1, 2, 0.05000000, '高故障关注'),
 (4, 1, NULL, '传动系统',   1, 30000.00, 1, 10, '2027-08-01', 1, 0, 0.15000000, ''),
 (5, 1, NULL, '装配辅料',   1, 20000.00, 1, 10, NULL,         0, 0, 0.05000000, '不可维修'),
 (6, 1, 2,    'PLC控制器',  1, 20000.00, 4, 8,  '2027-02-01', 1, 0, 0.08000000, '电控子件'),
 (7, 1, 2,    '变频器',     1, 15000.00, 4, 8,  '2027-02-01', 1, 1, 0.08000000, '电控子件·1次故障'),
 (8, 1, 2,    '线束',       1, 10000.00, 4, 8,  '2027-02-01', 1, 0, 0.05000000, '电控子件');

-- 设备1 事件流(状态机时间轴):采购→投放→在租
INSERT INTO yc_rent_asset_event (asset_id, event_type, ref_doc_type, ref_doc_id, biz_time, operator_id, remark) VALUES
 (1, '采购', 'purchase_in', NULL, '2025-08-20 10:00:00', 1006, '恒丰下单集采'),
 (1, '投放', NULL,          NULL, '2025-09-01 09:00:00', 1006, '现场交付拍照留痕'),
 (1, '在租', NULL,          NULL, '2025-09-10 09:00:00', 1004, '云山快仓起租');
INSERT INTO yc_rent_asset_event (asset_id, event_type, biz_time, operator_id, remark) VALUES
 (2, '采购', '2026-07-01 10:00:00', 1006, '恒丰下单集采'),
 (2, '投放', '2026-07-20 09:00:00', 1006, '入库待起租'),
 (3, '采购', '2026-08-01 10:00:00', 1006, '广达按图采购中');
