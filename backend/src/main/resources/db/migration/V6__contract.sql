-- =====================================================================
-- 沃朗科技租赁板块 · M1-10/11/17/18 合同 + 租金计划 + 押金台账 + 变更 Schema + 种子 (V6)
-- MySQL 8 · utf8mb4 · InnoDB · 库 worland_dev · 表前缀 yc_rent_
-- 依据: DESIGN_DOC §4.2(contract/contract_asset)+§4.3(rent_schedule/deposit_ledger)+§十一(rent_bill 收款真相源属 M2)
--       业务流程4签约(自动生成租金计划 N 期·勾稽=期数×月租+转让价=客户总付**不含押金**) + §8.4(每期租金构成/单笔P&L)
-- 口径: 金额=元 decimal(18,2) · 比率 decimal(18,8) · 手写 migration(§4.16,Flyway boot 自动 apply)
-- 单一真相源(§4.24):
--   * rent_schedule.plan_status 只计划态(未到期/已生成单);收款/逾期/红冲态归 M2 的 rent_bill(收款唯一真相源)
--   * alloc_rent 单台月租分摊单一真值;contract.month_rent=合同合计
--   * 押金单独走 deposit_ledger(收/退/期末抵),不进"客户总付"净额
-- 性质固定 '分期收款销售',禁 '融资租赁'(服务端校验);变更/作废走 contract_change 留痕红冲语义
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 合同主表 —— 不可撤销长约(录要素→电子签→自动生成租金计划)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_contract (
  id                bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  no                varchar(64)   NOT NULL COMMENT '合同编号(唯一)',
  customer_id       bigint        NOT NULL COMMENT '客户(yc_rent_customer.id)',
  term_months       int           NOT NULL COMMENT '租期(月·=租金计划期数 N)',
  month_rent        decimal(18,2) NOT NULL COMMENT '月租合计(元·合同合计;=Σ contract_asset.alloc_rent)',
  deposit           decimal(18,2) NOT NULL DEFAULT 0 COMMENT '押金(元;默认=月租×押金月数,走 deposit_ledger)',
  end_transfer_price decimal(18,2) NOT NULL DEFAULT 0 COMMENT '期末转让价(元;计入客户总付,不含押金)',
  target_irr        decimal(18,8) DEFAULT NULL COMMENT '目标税后 IRR',
  nature            varchar(16)   NOT NULL DEFAULT '分期收款销售' COMMENT '合同性质(固定分期收款销售;禁融资租赁)',
  status            varchar(16)   NOT NULL DEFAULT '草稿' COMMENT '状态:草稿/生效/到期转让/关闭/已作废',
  sign_date         date          DEFAULT NULL COMMENT '签约日',
  start_date        date          DEFAULT NULL COMMENT '起租日(=租金计划首期起算)',
  project_id        bigint        DEFAULT NULL COMMENT '项目隔离键(P1-4)',
  remark            varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted        tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_contract_no (no),
  KEY idx_contract_customer (customer_id),
  KEY idx_contract_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合同主表:不可撤销长约+分期收款销售(M1-10)';

-- ---------------------------------------------------------------------
-- 2. 合同↔设备(一份合同挂 N 台;单台月租分摊)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_contract_asset (
  id           bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  contract_id  bigint        NOT NULL COMMENT '合同主键',
  asset_id     bigint        NOT NULL COMMENT '设备主键',
  alloc_rent   decimal(18,2) NOT NULL DEFAULT 0 COMMENT '单台月租分摊(元·单一真值;Σ=contract.month_rent)',
  create_time  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  is_deleted   tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_ca_contract (contract_id),
  KEY idx_ca_asset (asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合同挂设备:N台+单台月租分摊(M1-11)';

-- ---------------------------------------------------------------------
-- 3. 租金计划(应收计划·逐期明细行) —— 签约自动生成 N 期
--    ⚠ 只计划态;收款/逾期/红冲归 rent_bill(M2 收款真相源),本表 rent_bill_id 回填
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_rent_schedule (
  id           bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  contract_id  bigint        NOT NULL COMMENT '合同主键',
  period_no    int           NOT NULL COMMENT '期次(1..N)',
  due_date     date          NOT NULL COMMENT '应收到期日',
  amount       decimal(18,2) NOT NULL COMMENT '本期应收(元)',
  rent_bill_id bigint        DEFAULT NULL COMMENT '已生成收租单(M2 rent_bill.id;回填)',
  plan_status  varchar(16)   NOT NULL DEFAULT '未到期' COMMENT '计划态:未到期/已生成单(收款态归 rent_bill)',
  create_time  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted   tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_schedule_period (contract_id, period_no),
  KEY idx_schedule_contract (contract_id),
  KEY idx_schedule_due (due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租金计划:应收逐期(签约自动生成N期)(M1-17)';

-- ---------------------------------------------------------------------
-- 4. 押金台账(收/退/期末抵转让价) —— 独立保证金科目,不进客户总付净额
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_deposit_ledger (
  id           bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  contract_id  bigint        NOT NULL COMMENT '合同主键',
  direction    varchar(8)    NOT NULL COMMENT '方向:收/退/期末抵',
  amount       decimal(18,2) NOT NULL COMMENT '金额(元·正数)',
  biz_time     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '业务时间',
  operator_id  bigint        DEFAULT NULL COMMENT '操作人(user 主键)',
  remark       varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  is_deleted   tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_deposit_contract (contract_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='押金台账:收/退/期末抵(M1-18)';

-- ---------------------------------------------------------------------
-- 5. 合同变更留痕(变更/作废/续租/提前结清;含 reverse 红冲语义)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_contract_change (
  id           bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  contract_id  bigint        NOT NULL COMMENT '合同主键',
  change_type  varchar(16)   NOT NULL COMMENT '类型:变更/作废/续租/提前结清',
  is_reverse   tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否红冲(作废=整份红冲):0否 1是',
  before_json  text          DEFAULT NULL COMMENT '变更前快照(要素/剩余期)',
  after_json   text          DEFAULT NULL COMMENT '变更后快照',
  detail       varchar(500)  DEFAULT NULL COMMENT '变更说明/裁决',
  operator_id  bigint        DEFAULT NULL COMMENT '操作人(user 主键)',
  biz_time     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '业务时间',
  create_time  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  is_deleted   tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_change_contract (contract_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合同变更留痕:变更/作废/续租/提前结清(M1)';

-- ---------------------------------------------------------------------
-- 6. rule_config 追加:坏账拨备率(单笔 P&L 用;禁硬编码 §4.24)
-- ---------------------------------------------------------------------
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('contract_bad_debt_rate', '', 0.02000000, 'rate', 1, '2023-01-01', NULL, '单笔P&L坏账拨备率:按收租总额 2% 计提(经营口径占位·M2精确化)');
