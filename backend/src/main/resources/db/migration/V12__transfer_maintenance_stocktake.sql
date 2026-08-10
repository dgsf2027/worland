-- =====================================================================
-- 沃朗科技租赁板块 · M4 到期转让/处置(逐台) + 维保工单 + 盘点盘盈亏 Schema + 种子 (V12)
-- MySQL 8 · utf8mb4 · InnoDB · 库 worland_dev · 表前缀 yc_rent_
-- 依据: DESIGN_DOC §4.1/4.3(transfer_order 拆头+行 · maintenance · stocktake/stock_diff)
--       + §十一 P0-A(transfer 拆头+逐台行) + ADR-004(残值/账面口径) + 评审 P1-19(名义价硬阈值守卫)
-- 口径: 金额=元 decimal(18,2) · 比率 decimal(18,8) · 手写 migration(§4.16,Flyway boot 自动 apply)
-- 单一真相源(§4.24/§4.17):
--   * transfer_order_line.book_value = 处置时账面价快照(ADR-004·由折旧真相源 asset_depreciation_line 算)——一经落库不回写
--   * gain = transfer_price - book_value 逐台损益,voucher_id 回填残值凭证(billing.postResidual)
--   * 设备状态机仍归 asset_event 驱动(转让→已转让/报废;再投放走 deliver)
--   * 盘点差异不直改台账,生成盘盈亏调整单(stock_diff)闭合(§4.24)
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 转让/处置单(头) —— 到期转让/收回/二手/报废
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_transfer_order (
  id              bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  no              varchar(64)   NOT NULL COMMENT '单号 TR-{合同号/ASSET}-{序}',
  contract_id     bigint        DEFAULT NULL COMMENT '关联合同(到期转让必填;二手/报废按台可空)',
  type            varchar(16)   NOT NULL COMMENT '类型:转让/收回/二手/报废',
  asset_count     int           NOT NULL DEFAULT 0 COMMENT '逐台行数',
  total_price     decimal(18,2) NOT NULL DEFAULT 0 COMMENT 'Σ transfer_price(元)',
  total_gain      decimal(18,2) NOT NULL DEFAULT 0 COMMENT 'Σ gain(元·处置损益)',
  status          varchar(16)   NOT NULL DEFAULT '待过账' COMMENT '状态:待审批/待过账/已完成/已作废',
  need_approval   tinyint(1)    NOT NULL DEFAULT 0 COMMENT '名义价守卫触发强制升级审批(P1-19):0否 1是',
  approval_reason varchar(255)  DEFAULT NULL COMMENT '名义价/低价转让理由(need_approval=1 必录)',
  approved_by     bigint        DEFAULT NULL COMMENT '审批人(财务/老板)',
  approved_at     datetime      DEFAULT NULL COMMENT '审批通过时间',
  operator_id     bigint        DEFAULT NULL COMMENT '经办人',
  biz_time        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '业务时间',
  remark          varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted      tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_transfer_no (no),
  KEY idx_transfer_contract (contract_id),
  KEY idx_transfer_status (status),
  KEY idx_transfer_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='转让/处置单头:到期转让/收回/二手/报废(M4-01)';

-- ---------------------------------------------------------------------
-- 2. 转让/处置单(逐台行) —— book_value 快照不回写 · gain · voucher_id
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_transfer_order_line (
  id                bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  transfer_order_id bigint        NOT NULL COMMENT '所属处置单(yc_rent_transfer_order.id)',
  asset_id          bigint        NOT NULL COMMENT '处置设备(yc_rent_asset.id)',
  book_value        decimal(18,2) DEFAULT NULL COMMENT '处置时账面价快照(元·ADR-004 ops 口径)——一经落库不回写',
  transfer_price    decimal(18,2) NOT NULL DEFAULT 0 COMMENT '转让/处置价(元;报废=0)',
  gain              decimal(18,2) DEFAULT NULL COMMENT '逐台损益 = transfer_price - book_value',
  nominal_flag      tinyint(1)    NOT NULL DEFAULT 0 COMMENT '本行触发名义价守卫:0否 1是',
  voucher_id        bigint        DEFAULT NULL COMMENT '残值/处置凭证(税务账·postResidual 回填)',
  remark            varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  is_deleted        tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_line_order_asset (transfer_order_id, asset_id),
  KEY idx_line_order (transfer_order_id),
  KEY idx_line_asset (asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='转让/处置单逐台行:账面快照+逐台损益+凭证(M4-01/02)';

-- ---------------------------------------------------------------------
-- 3. 维保工单 —— 报修/预防/巡检 · 质保/责任方 · 费用
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_maintenance (
  id                bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  no                varchar(64)   NOT NULL COMMENT '工单号 MT-{序}',
  asset_id          bigint        NOT NULL COMMENT '设备(yc_rent_asset.id)',
  bom_id            bigint        DEFAULT NULL COMMENT '故障配件(yc_rent_asset_bom.id;回写 fault_count)',
  type              varchar(16)   NOT NULL DEFAULT '报修' COMMENT '类型:报修/预防/巡检',
  status            varchar(16)   NOT NULL DEFAULT '待派工' COMMENT '状态:待派工/处理中/已完成/已关闭',
  fault_desc        varchar(512)  DEFAULT NULL COMMENT '故障描述',
  in_warranty       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否质保内:0否 1是(质保内转供应商·费用不计我方)',
  responsible_party varchar(16)   NOT NULL DEFAULT '我方' COMMENT '责任方:我方/供应商',
  supplier_id       bigint        DEFAULT NULL COMMENT '承修供应商(质保内/外部维修)',
  cost              decimal(18,2) NOT NULL DEFAULT 0 COMMENT '维修费用(元;责任方=供应商则不计我方)',
  assignee_id       bigint        DEFAULT NULL COMMENT '派工处理人',
  handle_note       varchar(512)  DEFAULT NULL COMMENT '处理记录',
  reported_at       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '报修时间',
  assigned_at       datetime      DEFAULT NULL COMMENT '派工时间',
  finished_at       datetime      DEFAULT NULL COMMENT '完工时间',
  operator_id       bigint        DEFAULT NULL COMMENT '登记人',
  remark            varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted        tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_maintenance_no (no),
  KEY idx_mt_asset (asset_id),
  KEY idx_mt_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维保工单:报修/预防/巡检+质保责任方+费用(M4-04)';

-- ---------------------------------------------------------------------
-- 4. 盘点单(头) —— 扫码盘点 · 只录差异
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_stocktake (
  id            bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  no            varchar(64)   NOT NULL COMMENT '盘点单号 ST-{序}',
  scope         varchar(64)   DEFAULT NULL COMMENT '盘点范围(全量/品类/项目)',
  status        varchar(16)   NOT NULL DEFAULT '进行中' COMMENT '状态:进行中/已闭合',
  book_count    int           NOT NULL DEFAULT 0 COMMENT '账面台数(带出)',
  scanned_count int           NOT NULL DEFAULT 0 COMMENT '已扫台数',
  diff_count    int           NOT NULL DEFAULT 0 COMMENT '差异条数',
  operator_id   bigint        DEFAULT NULL COMMENT '盘点人',
  biz_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '盘点时间',
  closed_at     datetime      DEFAULT NULL COMMENT '闭合时间',
  remark        varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted    tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_stocktake_no (no),
  KEY idx_st_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点单头:扫码盘点只录差异(M4-05)';

-- ---------------------------------------------------------------------
-- 5. 盘点差异行 —— 账实差异 → 盘盈亏调整单(走单据不直改台账)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_stock_diff (
  id              bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  stocktake_id    bigint       NOT NULL COMMENT '所属盘点单(yc_rent_stocktake.id)',
  asset_id        bigint       NOT NULL COMMENT '设备(yc_rent_asset.id)',
  book_status     varchar(16)  DEFAULT NULL COMMENT '账面状态(盘点时快照)',
  actual_status   varchar(16)  DEFAULT NULL COMMENT '实盘状态(扫码录入;丢失=盘亏)',
  diff_type       varchar(16)  NOT NULL COMMENT '差异类型:盘盈/盘亏/状态不符',
  adjusted        tinyint(1)   NOT NULL DEFAULT 0 COMMENT '是否已生成调整单闭合:0否 1是',
  adjust_event_id bigint       DEFAULT NULL COMMENT '调整落库的 asset_event.id(台账状态经调整单流转)',
  remark          varchar(255) DEFAULT NULL COMMENT '差异说明',
  create_time     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  is_deleted      tinyint(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_diff_stocktake (stocktake_id),
  KEY idx_diff_asset (asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点差异行:账实差异生成盘盈亏调整单闭合(M4-05)';

-- ---------------------------------------------------------------------
-- 6. 种子:名义价硬阈值守卫下限(P1-19) —— 禁硬编码,走 rule_config
--    守卫触发条件:transfer_price < book_value 或 transfer_price < market_price × nominal_price_floor_rate
-- ---------------------------------------------------------------------
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('nominal_price_floor_rate', '', 0.05000000, 'rate', 1, '2023-01-01', NULL, '名义价守卫:转让价低于市场价 5% 强制升级审批(评审 P1-19)');
