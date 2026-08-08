-- =====================================================================
-- 沃朗科技租赁板块 · M1-12/13/15 采购入库+应付+退货 + 敏感操作 audit Schema + 种子 (V7)
-- MySQL 8 · utf8mb4 · InnoDB · 库 worland_dev · 表前缀 yc_rent_
-- 依据: DESIGN_DOC §4.3(purchase_in/purchase_item 逐件生成 asset · payable 首付/验收/尾款)
--       + 业务流§流程5采购(先签约后采购/退货红冲/应付计划) + §十一 P0-D(敏感操作×角色 audit 留痕)
-- 口径: 金额=元 decimal(18,2) · 比率 decimal(18,8) · 手写 migration(§4.16,Flyway boot 自动 apply)
-- 单一真相源(§4.24):
--   * purchase_in 绑 contract_id(先签约后采购校验:无合同不允许建单)
--   * purchase_item 逐件·入库时自动生成 yc_rent_asset(回填 asset_id / asset.purchase_in_id)
--   * payable 应付计划(首付/验收/尾款·到期日·状态) —— 待付 payable 即层级②"负债"口径(M3 兑付缺口扫描读此)
--   * 退货红冲:purchase_in→已红冲 + payable→红冲 + 对应 asset 报废释放,全程 asset_event/audit 留痕
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 采购入库单(头) —— 先签约后采购:必须绑一份存续合同
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_purchase_in (
  id             bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  no             varchar(64)   NOT NULL COMMENT '采购单号(唯一)',
  contract_id    bigint        NOT NULL COMMENT '绑定合同(yc_rent_contract.id·先签约后采购,无合同不允许建单)',
  supplier_id    bigint        DEFAULT NULL COMMENT '整机供应商(yc_rent_supplier.id)',
  status         varchar(16)   NOT NULL DEFAULT '已下单' COMMENT '状态:已下单/已入库/已红冲',
  total_amount   decimal(18,2) NOT NULL DEFAULT 0 COMMENT '采购总额(元·=Σ purchase_item.purchase_price)',
  first_pay_ratio decimal(18,8) DEFAULT NULL COMMENT '首付比例(空=取 rule payable_stage_ratio[首付])',
  account_days   int           DEFAULT NULL COMMENT '尾款账期天数(空=取 rule payable_tail_days)',
  order_date     date          DEFAULT NULL COMMENT '下单日(首付到期日基准)',
  receive_date   date          DEFAULT NULL COMMENT '入库日(验收/尾款到期日基准·逐件生成 asset)',
  project_id     bigint        DEFAULT NULL COMMENT '项目隔离键(P1-4)',
  remark         varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted     tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_purchase_no (no),
  KEY idx_purchase_contract (contract_id),
  KEY idx_purchase_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购入库单头:先签约后采购(M1-12)';

-- ---------------------------------------------------------------------
-- 2. 采购明细(逐件) —— 入库时逐件生成 yc_rent_asset,回填 asset_id
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_purchase_item (
  id                  bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  purchase_in_id      bigint        NOT NULL COMMENT '采购单头(yc_rent_purchase_in.id)',
  serial_no           varchar(64)   NOT NULL COMMENT '序列号(逐件唯一·生成 asset.serial_no)',
  category            varchar(32)   NOT NULL COMMENT '品类:播种墙/货架/阁楼/配件',
  model               varchar(128)  DEFAULT NULL COMMENT '型号/规格',
  market_price        decimal(18,2) DEFAULT NULL COMMENT '市场价(元)',
  purchase_price      decimal(18,2) DEFAULT NULL COMMENT '集采价(元·成本口径)',
  supplier_id         bigint        DEFAULT NULL COMMENT '配件/整机供应商',
  monthly_labor_value decimal(18,2) DEFAULT NULL COMMENT '月替代人工价值(元·价值定价输入)',
  replace_headcount   decimal(9,2)  DEFAULT NULL COMMENT '替代人数',
  asset_id            bigint        DEFAULT NULL COMMENT '【回填】入库生成的 yc_rent_asset.id',
  remark              varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time         datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted          tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_item_purchase (purchase_in_id),
  KEY idx_item_asset (asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购明细逐件:入库自动生成 asset(M1-12)';

-- ---------------------------------------------------------------------
-- 3. 应付计划(首付/验收/尾款) —— 待付即层级②"负债"(M3 兑付缺口扫描口径)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_payable (
  id             bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  purchase_in_id bigint        NOT NULL COMMENT '采购单头(yc_rent_purchase_in.id)',
  stage          varchar(16)   NOT NULL COMMENT '阶段:首付/验收/尾款/退款红字',
  due_date       date          DEFAULT NULL COMMENT '应付到期日(层级②兑付缺口 T-N 扫描基准)',
  amount         decimal(18,2) NOT NULL COMMENT '应付金额(元·退款红字为负数)',
  status         varchar(16)   NOT NULL DEFAULT '待付' COMMENT '状态:待付/已付/红冲(红冲不计入负债)',
  paid_date      date          DEFAULT NULL COMMENT '实付日',
  remark         varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted     tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_payable_purchase (purchase_in_id),
  KEY idx_payable_due (due_date),
  KEY idx_payable_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应付计划:首付/验收/尾款+负债口径(M1-13)';

-- ---------------------------------------------------------------------
-- 4. 敏感操作审计留痕(P0-D) —— 红冲/作废/淘汰/退货/投放审批 统一切面写入
--    留痕≠管控:先卡权限(切面)再执行,越权(DENIED)与放行执行(EXECUTED)均落此表,只追加
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_audit_log (
  id            bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  action        varchar(32)   NOT NULL COMMENT '敏感操作:合同作废/供应商淘汰/采购退货/投放审批/资产状态流转',
  target_type   varchar(32)   DEFAULT NULL COMMENT '对象类型:contract/supplier/purchase_in/asset',
  target_id     bigint        DEFAULT NULL COMMENT '对象主键',
  result        varchar(16)   NOT NULL COMMENT '结果:DENIED(越权拦截)/EXECUTED(放行执行)',
  operator_id   bigint        DEFAULT NULL COMMENT '操作人(占位期 X-User-Name 解析主键)',
  operator_name varchar(64)   DEFAULT NULL COMMENT '操作人显示名(网关注入身份·P1-16 不可信身份追溯)',
  operator_role varchar(32)   DEFAULT NULL COMMENT '操作人角色',
  detail        varchar(500)  DEFAULT NULL COMMENT '明细/裁决/拒绝原因',
  create_time   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
  PRIMARY KEY (id),
  KEY idx_audit_action (action),
  KEY idx_audit_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感操作审计留痕:统一切面写入(M1-15·P0-D)';

-- ---------------------------------------------------------------------
-- 5. rule_config 追加:应付阶段比例 / 尾款账期 / 空置亮灯阈值(禁硬编码 §4.24)
-- ---------------------------------------------------------------------
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('payable_stage_ratio', '首付', 0.30000000, 'rate',   1, '2023-01-01', NULL, '采购首付比例 30%(下单付·业务流程5)'),
 ('payable_stage_ratio', '验收', 0.60000000, 'rate',   1, '2023-01-01', NULL, '到货验收付 60%(入库付)'),
 ('payable_stage_ratio', '尾款', 0.10000000, 'rate',   1, '2023-01-01', NULL, '质保尾款 10%(账期后付·三段合计=100%)'),
 ('payable_tail_days',   '',     90,         'months', 1, '2023-01-01', NULL, '尾款账期天数默认 90 天(供应商 account_days 优先)'),
 ('idle_alert_days',     '',     30,         'months', 1, '2023-01-01', NULL, '空置亮灯阈值:投放超 30 天未起租 或 收回待处置 亮灯(流程6)');
