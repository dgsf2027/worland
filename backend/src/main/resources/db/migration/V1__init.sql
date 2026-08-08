-- =====================================================================
-- 沃朗科技租赁板块 · 冲刺0 核心地基 Schema (S0-02)
-- MySQL 8 · utf8mb4 · InnoDB · 库 worland_dev · 表前缀 yc_rent_
-- 依据: DESIGN_DOC v0.2(§四数据模型) + TODO S0-02/S0-05 + Phase1.5 评审纪要
-- 口径: 金额单位=元(decimal(18,2)) · 比率 decimal(18,8) · 手写 migration,禁 JPA 自动 ddl(§4.16)
-- 手动 apply 七步见 db/migration/README.md
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 版本化规则配置源 (S0-05) —— 所有口径常量的单一真相源,禁硬编码
--    管理费阶梯 / 品类转让率 / 税率 / 目标IRR / 速算系数 / 留存下限 / 融资成本 等
--    带生效期,按业务日期取生效版本(税率 2027-12-31 sunset 命中不同版本)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_rule_config (
  id              bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  rule_key        varchar(64)  NOT NULL COMMENT '规则键,如 tax_vat/transfer_rate/speed_coeff/mgmt_fee_ladder',
  scope_key       varchar(64)  NOT NULL DEFAULT '' COMMENT '限定维度(品类/客户类型/档位),无维度存空串,如 播种墙 / 云山快仓 / 播种墙:25',
  rule_value      decimal(18,8) DEFAULT NULL COMMENT '标量值(比率/金额/月数);阶梯类走 rule_json',
  rule_json       text          DEFAULT NULL COMMENT '结构化值(如管理费阶梯 JSON 数组)',
  value_type      varchar(16)  NOT NULL DEFAULT 'rate' COMMENT '值类型:rate比率/money金额/months月数/json',
  version         int          NOT NULL DEFAULT 1 COMMENT '版本号(同 key+scope 多版本)',
  effective_from  date         NOT NULL COMMENT '生效起(含)',
  effective_to    date         DEFAULT NULL COMMENT '生效止(含);NULL=长期有效',
  remark          varchar(255) DEFAULT NULL COMMENT '说明/出处(如 设立方案书V14 §10.3)',
  create_time     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted      tinyint(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_rule_key_scope_ver (rule_key, scope_key, version),
  KEY idx_rule_lookup (rule_key, scope_key, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='版本化规则配置源:口径常量单一真相源,按业务日期取生效版本(S0-05)';

-- ---------------------------------------------------------------------
-- 2. 期间锁 (S0-02) —— 为后续红冲/凭证锁账守卫打底
--    落 locked 的期间,凭证写一律拒绝,只走显式"上期调整"单(M2-03)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_accounting_period (
  id           bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  period       varchar(7)  NOT NULL COMMENT '会计期间 YYYY-MM',
  book         varchar(8)  NOT NULL COMMENT '账套口径:tax税务(分期收款销售) / ops经营(三层回报)',
  is_locked    tinyint(1)  NOT NULL DEFAULT 0 COMMENT '是否锁账:0开 1锁(锁后禁写本期凭证)',
  locked_by    bigint      DEFAULT NULL COMMENT '锁账人(user 主键)',
  locked_at    datetime    DEFAULT NULL COMMENT '锁账时间',
  remark       varchar(255) DEFAULT NULL COMMENT '备注',
  create_time  datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time  datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted   tinyint(1)  NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_period_book (period, book)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='期间锁:红冲/凭证锁账守卫基表(S0-02)';

-- ---------------------------------------------------------------------
-- 3. 资产事件流基表 (S0-07 先建表) —— 所有写单据 → 事件流 → 凭证 的留痕底座
--    派生数据(asset.status/current_holder)只由事件流算,禁直写(§4.24 单一真相源)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_asset_event (
  id          bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  asset_id    bigint      NOT NULL COMMENT '设备主键(逐件建档,M1-06 建 asset 表)',
  event_type  varchar(24) NOT NULL COMMENT '事件类型:采购/投放/在租/维修/待转让/转让/收回待处置/再投放/二手/报废',
  ref_doc_type varchar(32) DEFAULT NULL COMMENT '来源单据类型(contract/purchase_in/transfer_order 等)',
  ref_doc_id  bigint      DEFAULT NULL COMMENT '来源单据主键',
  biz_time    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '业务发生时间',
  project_id  bigint      DEFAULT NULL COMMENT '项目隔离键(多项目独立核算)',
  operator_id bigint      DEFAULT NULL COMMENT '操作人(网关注入身份)',
  remark      varchar(255) DEFAULT NULL COMMENT '备注',
  create_time datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  is_deleted  tinyint(1)  NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_asset_event (asset_id, biz_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产事件流:单据事件留痕底座,状态机驱动源(S0-07)';
