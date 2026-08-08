-- =====================================================================
-- 沃朗科技租赁板块 · M1-03/04/05/09 客户 CRM 模块 Schema + 种子 (V4)
-- 依据: DESIGN_DOC §4.2(customer/customer_followup/opportunity) + §十一 P0-E 隔离
--       业务流§8.3(信用画像/LTV/风险敞口) + 第九部分 CRM(线索→跟进→商机→成交→在租→流失)
-- 派生字段(评级 rating/加权信用分/集中度)不落列,由 rule_config 权重+阶梯即时算(§4.17/§4.24)
-- 准入决策(credit_limit/deposit_months/target_irr)由「风控准入 POST」写入,@owner=准入接口(单一写手)
-- LTV/敞口 快照列 @owner=合同/收租模块回写(占位期种子手录;真模块上线后由事件流回填)
-- 隔离: owner_user 行级(业务只见自己+公海) + 敏感字段按角色 DTO 投影(LP 不可见成本/授信)——服务端强制
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 客户主表(CRM)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_customer (
  id               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键(=客户一条命的 symbol,串成交前后)',
  name             varchar(128) NOT NULL COMMENT '客户名称',
  contact          varchar(64)  DEFAULT NULL COMMENT '联系人',
  phone            varchar(32)  DEFAULT NULL COMMENT '联系电话',
  industry         varchar(32)  DEFAULT NULL COMMENT '行业(仓储/电商/物流…)',
  phase            varchar(8)   NOT NULL DEFAULT '线索' COMMENT '销售管道阶段:线索/跟进/商机/成交/在租/流失',
  value_tier       varchar(8)   DEFAULT NULL COMMENT '成交客户价值分层:战略/普通/观察',
  -- 信用画像五维(0-100,录入输入;@owner=业务/风控录入)
  score_profit     smallint     DEFAULT NULL COMMENT '盈利能力,0-100',
  score_cashflow   smallint     DEFAULT NULL COMMENT '现金流,0-100',
  score_stability  smallint     DEFAULT NULL COMMENT '经营稳定,0-100',
  score_history    smallint     DEFAULT NULL COMMENT '历史履约(逾期次数天数),0-100',
  score_industry   smallint     DEFAULT NULL COMMENT '行业风险,0-100',
  -- 风控准入决策(@owner=准入 POST 接口唯一写手;从 rating 建议值经审批落定)
  credit_limit     decimal(18,2) DEFAULT NULL COMMENT '【敏感】授信额度(元);准入决策落定',
  deposit_months   decimal(9,2)  DEFAULT NULL COMMENT '押金月数',
  target_irr       decimal(18,8) DEFAULT NULL COMMENT '【敏感】目标税后 IRR;准入决策落定',
  admission_note   varchar(255)  DEFAULT NULL COMMENT '准入结论备注(拒绝也留痕)',
  -- 行级隔离
  owner_user       bigint        DEFAULT NULL COMMENT '负责业务(user 主键);NULL=公海可认领(P0-E 行级隔离键)',
  project_id       bigint        DEFAULT NULL COMMENT '项目隔离键(P1-4)',
  -- LTV/敞口 快照(@owner=合同/收租模块回写;占位期种子)
  contract_count       int           NOT NULL DEFAULT 0 COMMENT '【派生·快照】累计合同数',
  cumulative_rent      decimal(18,2) NOT NULL DEFAULT 0 COMMENT '【派生·快照】累计收租(元)',
  cumulative_profit    decimal(18,2) NOT NULL DEFAULT 0 COMMENT '【敏感·派生·快照】累计利润LTV(元)',
  renew_rate           decimal(9,4)  DEFAULT NULL COMMENT '【派生·快照】续租率(0-1)',
  exposure_amount      decimal(18,2) NOT NULL DEFAULT 0 COMMENT '【派生·快照】在租敞口(元)',
  receivable_overdue   decimal(18,2) NOT NULL DEFAULT 0 COMMENT '【派生·快照】逾期应收(元)',
  next_follow_date     date          DEFAULT NULL COMMENT '【派生】下次跟进日(followup 写手同步);到期/逾期亮灯',
  create_time      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted       tinyint(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_customer_phase (phase),
  KEY idx_customer_owner (owner_user),
  KEY idx_customer_follow (next_follow_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户 CRM 主表:全生命周期+信用画像+准入+行级隔离(M1-03/04/05)';

-- ---------------------------------------------------------------------
-- 2. 跟进时间线(业务长期跟进)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_customer_followup (
  id               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  customer_id      bigint       NOT NULL COMMENT '客户主键',
  user_id          bigint       DEFAULT NULL COMMENT '跟进人(user 主键)',
  user_name        varchar(64)  DEFAULT NULL COMMENT '跟进人显示名',
  method           varchar(8)   NOT NULL DEFAULT '电话' COMMENT '方式:电话/拜访/微信',
  content          varchar(500) DEFAULT NULL COMMENT '跟进内容',
  result           varchar(255) DEFAULT NULL COMMENT '结果',
  follow_time      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本次跟进时间',
  next_follow_date date         DEFAULT NULL COMMENT '下次跟进日',
  create_time      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  is_deleted       tinyint(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_followup_customer (customer_id, follow_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户跟进时间线(M1-04)';

-- ---------------------------------------------------------------------
-- 3. 商机(销售管道加权预测)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_opportunity (
  id               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  customer_id      bigint        NOT NULL COMMENT '客户主键',
  category         varchar(32)   DEFAULT NULL COMMENT '预估品类:播种墙/货架/阁楼',
  est_amount       decimal(18,2) NOT NULL DEFAULT 0 COMMENT '预估金额(元)',
  win_prob         decimal(9,4)  NOT NULL DEFAULT 0 COMMENT '成交概率(0-1)',
  est_close_month  varchar(7)    DEFAULT NULL COMMENT '预计成交月 YYYY-MM',
  status           varchar(8)    NOT NULL DEFAULT 'open' COMMENT '状态:open/won/lost',
  remark           varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_opp_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商机:预估额×成交概率→管道加权预测(M1-09)';

-- ---------------------------------------------------------------------
-- 4. rule_config 追加:信用画像权重 + 评级阶梯 + 准入矩阵(禁硬编码)
-- ---------------------------------------------------------------------
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_json, value_type, version, effective_from, effective_to, remark) VALUES
 ('customer_credit_weights', '',
  '{"profit":0.20,"cashflow":0.25,"stability":0.20,"history":0.20,"industry":0.15}',
  'json', 1, '2023-01-01', NULL, '信用画像加权:盈利20/现金流25/稳定20/履约20/行业15(业务流§8.3)'),
 ('customer_rating_ladder', '',
  '[{"min":80,"rating":"A"},{"min":60,"rating":"B"},{"min":0,"rating":"C"}]',
  'json', 1, '2023-01-01', NULL, '加权信用分→评级:≥80 A/≥60 B/其余 C(业务流§8.3)'),
 ('customer_admission_matrix', '',
  '{"A":{"credit":500000,"deposit":1,"irr":0.25},"B":{"credit":200000,"deposit":2,"irr":0.30},"C":{"credit":50000,"deposit":3,"irr":0.35}}',
  'json', 1, '2023-01-01', NULL, '评级→授信/押金月/目标IRR 建议矩阵(A宽C严;方案书§三/§4.3)');

-- ---------------------------------------------------------------------
-- 5. 种子数据(对齐 UI mockup 客户CRM页;owner_user 用占位头种子主键)
--    王业务=1007 · 李工=1004(见 UserContextFilter SEED_USERS)
-- ---------------------------------------------------------------------
INSERT INTO yc_rent_customer
 (id, name, contact, phone, industry, phase, value_tier,
  score_profit, score_cashflow, score_stability, score_history, score_industry,
  credit_limit, deposit_months, target_irr, admission_note, owner_user,
  contract_count, cumulative_rent, cumulative_profit, renew_rate, exposure_amount, receivable_overdue, next_follow_date) VALUES
 (1, '德邦仓配',      '刘主管', '13900000001', '仓储', '线索', NULL,
   NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 1007,
   0, 0, 0, NULL, 0, 0, '2026-08-09'),
 (2, '顺丰园区仓',    '赵经理', '13900000002', '物流', '跟进', NULL,
   70, 62, 68, 65, 66, NULL, NULL, NULL, NULL, 1007,
   0, 0, 0, NULL, 0, 0, '2026-08-12'),
 (3, '京东云仓(华南)', '孙总',  '13900000003', '电商', '商机', NULL,
   85, 80, 82, 78, 80, NULL, NULL, NULL, NULL, 1004,
   0, 0, 0, NULL, 0, 0, '2026-08-05'),
 (4, '云山快仓',      '周经理', '13900000004', '仓储', '在租', '战略',
   90, 85, 88, 90, 82, 500000.00, 1, 0.25000000, 'A级战略客户,体系内从优定价', 1004,
   5, 1200000.00, 260000.00, 0.9000, 2400000.00, 0, NULL),
 (5, '阿昌仓储',      '阿昌',   '13900000005', '仓储', '在租', '普通',
   75, 55, 70, 48, 65, 200000.00, 2, 0.30000000, 'B级普通客户,历史有逾期', 1007,
   3, 186000.00, 41000.00, 0.6700, 110000.00, 5181.00, '2026-08-09'),
 (6, '微仓科技',      '钱工',   '13900000006', '电商', '流失', '观察',
   50, 45, 55, 40, 58, NULL, NULL, NULL, '价格未谈拢,复盘归档', 1007,
   0, 0, 0, NULL, 0, 0, NULL);

INSERT INTO yc_rent_opportunity (customer_id, category, est_amount, win_prob, est_close_month, status) VALUES
 (1, '货架',   300000.00,  0.3000, '2026-10', 'open'),
 (2, '播种墙', 500000.00,  0.4000, '2026-09', 'open'),
 (3, '播种墙', 1200000.00, 0.6000, '2026-09', 'open');

INSERT INTO yc_rent_customer_followup (customer_id, user_id, user_name, method, content, result, follow_time, next_follow_date) VALUES
 (5, 1007, '业务', '电话', '逾期催收电话', '承诺3天内付', '2026-08-06 10:00:00', '2026-08-09'),
 (5, 1007, '业务', '微信', '续租意向沟通', '有扩租3台意向',   '2026-07-20 15:00:00', NULL),
 (5, 1007, '业务', '拜访', '首次成交签约', '阁楼货架2年',       '2025-09-10 09:00:00', NULL),
 (5, 1007, '业务', '拜访', '现场看设备',   '潜客转商机',         '2025-08-15 14:00:00', NULL);
