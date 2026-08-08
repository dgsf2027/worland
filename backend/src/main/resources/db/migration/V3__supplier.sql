-- =====================================================================
-- 沃朗科技租赁板块 · M1-01/02 供应商模块 Schema + 种子 (V3)
-- MySQL 8 · utf8mb4 · InnoDB · 库 worland_dev · 表前缀 yc_rent_
-- 依据: DESIGN_DOC §4.2(supplier/supplier_supply) + 业务流§8.2 供货矩阵/价格构成/履约雷达
-- 口径: 金额=元 decimal(18,2) · 比率 decimal(18,8) · 评分 0-100(smallint) · 手写 migration(§4.16)
-- 派生字段(履约加权总分)不落列,由 rule_config 权重即时算(§4.17/§4.24 单一真相源)
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 供应商主表 —— 上游关系生命周期(接触/试样/入库/主供/备供/淘汰)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_supplier (
  id             bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  name           varchar(128) NOT NULL COMMENT '供应商名称',
  contact        varchar(64)  DEFAULT NULL COMMENT '联系人',
  phone          varchar(32)  DEFAULT NULL COMMENT '联系电话',
  main_category  varchar(32)  DEFAULT NULL COMMENT '主营品类:播种墙/货架/阁楼/配件等',
  status         varchar(8)   NOT NULL DEFAULT '接触' COMMENT '关系阶段:接触/试样/入库/主供/备供/淘汰',
  retire_reason  varchar(255) DEFAULT NULL COMMENT '淘汰/停用原因(留痕)',
  retired_by     bigint       DEFAULT NULL COMMENT '淘汰操作人(user 主键)',
  retired_at     datetime     DEFAULT NULL COMMENT '淘汰时间',
  project_id     bigint       DEFAULT NULL COMMENT '项目隔离键(多项目独立核算·P1-4)',
  remark         varchar(255) DEFAULT NULL COMMENT '备注',
  create_time    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted     tinyint(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_supplier_status (status),
  KEY idx_supplier_category (main_category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商主表:上游关系生命周期(M1-01)';

-- ---------------------------------------------------------------------
-- 2. 供货矩阵 —— 一家供应商供哪些整机/配件,含报价/账期/履约五维评分/价格构成
--    履约五维(quality/delivery/service/price/term)为录入输入(@owner=供应链录入)
--    加权总分不落列,由 rule_config[supplier_score_weights] 即时算(§4.24)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_supplier_supply (
  id              bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  supplier_id     bigint        NOT NULL COMMENT '供应商主键',
  item_type       varchar(8)    NOT NULL DEFAULT '整机' COMMENT '供货类型:整机/配件',
  item_name       varchar(128)  NOT NULL COMMENT '供何物:播种墙整机/电控系统/传感模组…',
  category        varchar(32)   DEFAULT NULL COMMENT '所属品类:播种墙/货架/阁楼/配件',
  quote_price     decimal(18,2) DEFAULT NULL COMMENT '集采报价(元);按图报价时为空',
  first_pay_ratio decimal(18,8) DEFAULT NULL COMMENT '首付比例(0-1),越低=层级②供应商杠杆越大',
  account_days    int           DEFAULT NULL COMMENT '账期(天)',
  no_interest     tinyint(1)    NOT NULL DEFAULT 1 COMMENT '账期是否无息:0否 1是',
  can_single_buy  tinyint(1)    NOT NULL DEFAULT 1 COMMENT '是否可单采(配件级比价):0否 1是',
  -- 履约五维评分(0-100,录入输入)
  score_quality   smallint      DEFAULT NULL COMMENT '品质分(故障/退货率),0-100',
  score_delivery  smallint      DEFAULT NULL COMMENT '交期分(准时率),0-100',
  score_service   smallint      DEFAULT NULL COMMENT '服务分(响应/到场),0-100',
  score_price     smallint      DEFAULT NULL COMMENT '价格分(vs市场/BOM),0-100',
  score_term      smallint      DEFAULT NULL COMMENT '账期分(首付/返利),0-100',
  -- 价格构成(vs BOM 识别虚高;@owner=下场泡工厂录入,可空)
  cost_material   decimal(18,2) DEFAULT NULL COMMENT '价格构成:材料(元)',
  cost_processing decimal(18,2) DEFAULT NULL COMMENT '价格构成:加工(元)',
  profit_amount   decimal(18,2) DEFAULT NULL COMMENT '价格构成:利润(元)',
  bom_estimate    decimal(18,2) DEFAULT NULL COMMENT '我方 BOM 估算(元),vs 报价识别虚高',
  is_primary      tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否该供应商代表供货项(池列表取此行报价/评分)',
  remark          varchar(255)  DEFAULT NULL COMMENT '备注(现金折扣/阶梯返利/维保返点)',
  create_time     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted      tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_supply_supplier (supplier_id),
  KEY idx_supply_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供货矩阵:整机/配件报价+账期+履约五维+价格构成(M1-02)';

-- ---------------------------------------------------------------------
-- 3. rule_config 追加:履约评分权重 + 单一依赖阈值(禁硬编码,§4.24)
-- ---------------------------------------------------------------------
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_json, value_type, version, effective_from, effective_to, remark) VALUES
 ('supplier_score_weights', '',
  '{"quality":0.30,"delivery":0.25,"service":0.15,"price":0.20,"term":0.10}',
  'json', 1, '2023-01-01', NULL, '履约加权总分权重:品质30/交期25/服务15/价格20/账期10(业务流§8.2)');

INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('supplier_min_per_category', '', 2, 'months', 1, '2023-01-01', NULL, '每品类可用供应商下限:<2 家亮单一依赖警(业务流§8.2/流程1)');

-- ---------------------------------------------------------------------
-- 4. 种子数据(对齐 UI mockup 供应商页,便于 E2E)
-- ---------------------------------------------------------------------
INSERT INTO yc_rent_supplier (id, name, contact, phone, main_category, status, remark) VALUES
 (1, '恒丰自动化', '张经理', '13800000001', '播种墙',   '主供', '播种墙整机主供,已谈成30%首付'),
 (2, '睿捷设备',   '李经理', '13800000002', '播种墙',   '备供', '播种墙整机备选'),
 (3, '广达货架',   '王经理', '13800000003', '货架',     '试样', '货架/阁楼按图报价,谈判中'),
 (4, '科瑞电控',   '陈工',   '13800000004', '配件',     '备供', '电控系统配件供应,质保内换新');

-- 恒丰:播种墙整机(代表项) + 电控系统 + 传感模组(配件级比价)
INSERT INTO yc_rent_supplier_supply
 (supplier_id, item_type, item_name, category, quote_price, first_pay_ratio, account_days, no_interest, can_single_buy,
  score_quality, score_delivery, score_service, score_price, score_term,
  cost_material, cost_processing, profit_amount, bom_estimate, is_primary, remark) VALUES
 (1, '整机', '播种墙 整机', '播种墙', 180000.00, 0.30000000, 60, 1, 1, 88, 82, 90, 85, 84, 94000.00, 47000.00, 39000.00, 176000.00, 1, '现金折扣2%/阶梯返利/维保返点另计'),
 (1, '配件', '电控系统',    '配件',    26000.00, 0.40000000, 45, 1, 1, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, '可单采'),
 (1, '配件', '传感模组',    '配件',    11000.00, 0.40000000, 45, 1, 0, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, '不可单采,随整机');

-- 睿捷:播种墙整机备供
INSERT INTO yc_rent_supplier_supply
 (supplier_id, item_type, item_name, category, quote_price, first_pay_ratio, account_days, no_interest, can_single_buy,
  score_quality, score_delivery, score_service, score_price, score_term, is_primary) VALUES
 (2, '整机', '播种墙 整机', '播种墙', 186000.00, 0.40000000, 30, 1, 1, 80, 78, 76, 79, 72, 1);

-- 广达:货架按图报价(履约未评)
INSERT INTO yc_rent_supplier_supply
 (supplier_id, item_type, item_name, category, quote_price, first_pay_ratio, account_days, no_interest, can_single_buy, is_primary, remark) VALUES
 (3, '整机', '货架/阁楼', '货架', NULL, 0.50000000, 0, 1, 1, 1, '按图报价,待试样评分');

-- 科瑞:电控系统配件备供
INSERT INTO yc_rent_supplier_supply
 (supplier_id, item_type, item_name, category, quote_price, first_pay_ratio, account_days, no_interest, can_single_buy,
  score_quality, score_delivery, score_service, score_price, score_term, is_primary) VALUES
 (4, '配件', '电控系统', '配件', 26000.00, 0.40000000, 45, 1, 1, 85, 80, 82, 78, 80, 1);
