-- =====================================================================
-- 沃朗科技租赁板块 · M2 收租闭环 Schema (V8)
-- MySQL 8 · utf8mb4 · InnoDB · 库 worland_dev · 表前缀 yc_rent_
-- 依据: DESIGN_DOC §4.3(rent_bill 收款真相源/overdue_case) + §十一 P0-F(红冲原子性+幂等键+锁账守卫)
--       + 系统方案 流程7收租/流程8逾期三步走 + 状态机
-- 口径: 金额=元 decimal(18,2) · 手写 migration(§4.16,Flyway boot 自动 apply)
-- 单一真相源(§4.24):
--   * rent_bill = 收款态 owner(待收/已核销/逾期/红冲);rent_schedule.plan_status 单向回写(未到期/已生成单)
--   * 红冲(P0-F):单事务原子 + reverses_id 唯一约束(幂等键·防重复红冲) + 锁账守卫(is_locked 期拒写)
--   * 罚息率/收租单提前天数/逾期宽限 走 rule_config,禁硬编码
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 收租单(收款真相源) —— 到期 T-3 由 cron 按 rent_schedule 生成
--    status: 待收/已核销/逾期/红冲   bill_kind: 正常/红冲/退款/罚息
--    reverses_id 唯一约束 = 红冲幂等键(一张原单只允许被红冲一次,重复红冲 DB 层拒)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_rent_bill (
  id              bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  bill_no         varchar(64)   NOT NULL COMMENT '收租单号(唯一)',
  contract_id     bigint        NOT NULL COMMENT '合同(yc_rent_contract.id)',
  period_no       int           NOT NULL DEFAULT 0 COMMENT '对应租金计划期次(罚息/退款可 0)',
  due_date        date          DEFAULT NULL COMMENT '应收到期日(逾期扫描基准)',
  amount          decimal(18,2) NOT NULL COMMENT '应收金额(元·红冲/退款为负数)',
  received_amount decimal(18,2) NOT NULL DEFAULT 0 COMMENT '已收金额(核销时写入)',
  status          varchar(16)   NOT NULL DEFAULT '待收' COMMENT '收款态:待收/已核销/逾期/红冲',
  bill_kind       varchar(8)    NOT NULL DEFAULT '正常' COMMENT '单据性质:正常/红冲/退款/罚息',
  matched_at      datetime      DEFAULT NULL COMMENT '核销时间(到账匹配成功)',
  account_period  varchar(7)    DEFAULT NULL COMMENT '记账期 YYYY-MM(核销时=matched 月·锁账守卫基准)',
  reverses_id     bigint        DEFAULT NULL COMMENT '红冲指向的原收租单 id(幂等键·唯一约束防重复红冲)',
  ref_bill_id     bigint        DEFAULT NULL COMMENT '退款指向的原核销单 id(退款追溯)',
  voucher_id      bigint        DEFAULT NULL COMMENT '【M3 钩子·预留】收入凭证 id(M2 恒 NULL,稽核据此亮"已核销缺凭证")',
  operator_id     bigint        DEFAULT NULL COMMENT '操作人(user 主键)',
  remark          varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted      tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_bill_no (bill_no),
  UNIQUE KEY uk_bill_reverses (reverses_id) COMMENT 'P0-F 红冲幂等键:一原单仅允许一条红冲(NULL 不参与唯一)',
  KEY idx_bill_contract (contract_id),
  KEY idx_bill_status (status),
  KEY idx_bill_due (due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收租单:收款唯一真相源+红冲幂等键(M2-01/02/03)';

-- ---------------------------------------------------------------------
-- 2. 逾期案(三步走:延期→罚息→锁机→收回→关闭;还款恢复关闭)
--    内外一视同仁,物权在我方。每案必"裁决人 owner + 期限 deadline"。
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_overdue_case (
  id             bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  rent_bill_id   bigint        NOT NULL COMMENT '触发逾期的收租单(yc_rent_rent_bill.id)',
  contract_id    bigint        NOT NULL COMMENT '合同(yc_rent_contract.id)',
  step           varchar(16)   NOT NULL DEFAULT '延期' COMMENT '当前步:延期/罚息/锁机/收回/关闭',
  status         varchar(16)   NOT NULL DEFAULT '开启' COMMENT '案态:开启/关闭(还款/收回后关闭)',
  penalty_amount decimal(18,2) NOT NULL DEFAULT 0 COMMENT '累计罚息(元)',
  next_action    varchar(64)   DEFAULT NULL COMMENT '下一步动作提示',
  deadline       date          DEFAULT NULL COMMENT '本步处置期限(必填·催办基准)',
  owner          varchar(32)   DEFAULT NULL COMMENT '裁决人(角色/人名)',
  opened_at      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开案时间',
  closed_at      datetime      DEFAULT NULL COMMENT '关案时间',
  remark         varchar(500)  DEFAULT NULL COMMENT '处置留痕(逐步追加)',
  create_time    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted     tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_overdue_bill (rent_bill_id) COMMENT '一张逾期收租单只开一个案(幂等·防重复开案)',
  KEY idx_overdue_contract (contract_id),
  KEY idx_overdue_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='逾期案:三步走处置+裁决人期限(M2-05)';

-- ---------------------------------------------------------------------
-- 3. 收回单(逾期收回/提前收回) —— 收回时生成,联动 asset→收回待处置
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_repossess_order (
  id              bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  no              varchar(64)  NOT NULL COMMENT '收回单号(唯一)',
  contract_id     bigint       NOT NULL COMMENT '合同(yc_rent_contract.id)',
  overdue_case_id bigint       DEFAULT NULL COMMENT '关联逾期案(yc_rent_overdue_case.id)',
  asset_count     int          NOT NULL DEFAULT 0 COMMENT '收回设备数(转收回待处置)',
  disposal_status varchar(16)  NOT NULL DEFAULT '待处置' COMMENT '处置态:待处置(再投放/二手/报废 M4 细化)',
  operator_id     bigint       DEFAULT NULL COMMENT '操作人(user 主键)',
  reason          varchar(255) DEFAULT NULL COMMENT '收回事由',
  biz_time        datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收回业务时间',
  create_time     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  is_deleted      tinyint(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_repossess_no (no),
  KEY idx_repossess_contract (contract_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收回单:逾期收回联动设备收回待处置(M2-06)';

-- ---------------------------------------------------------------------
-- 4. rule_config 追加:收租口径常量(禁硬编码 §4.24)
-- ---------------------------------------------------------------------
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('rent_bill_gen_lead_days', '', 3,          'months', 1, '2023-01-01', NULL, '收租单提前生成天数:到期日 T-3 生成(流程7)'),
 ('overdue_grace_days',      '', 0,          'months', 1, '2023-01-01', NULL, '逾期宽限天数:到期日 + N 天后仍待收→自动开案(0=次日即逾期)'),
 ('rent_penalty_rate',       '', 0.00050000, 'rate',   1, '2023-01-01', NULL, '逾期罚息日利率 0.05%/天(罚息单=逾期额×率×逾期天数·流程8)');
