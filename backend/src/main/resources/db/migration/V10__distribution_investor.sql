-- =====================================================================
-- 沃朗科技租赁板块 · M3 Wave B 结账分配 + 出资人 Schema (V10)
-- MySQL 8 · utf8mb4 · InnoDB · 库 worland_dev · 表前缀 yc_rent_
-- 依据: DESIGN_DOC §4.3(distribution/investor)+§十一 D(留存下限·distribution幂等+冲销)
--       + 系统方案 流程11(结账→管理费阶梯→可分配→50现金/50滚存·每月5号)
--       + 设立方案书 §11分配/§13.1管理费阶梯/§九出资结构
-- 口径: 金额=元 decimal(18,2) · 比率 decimal(18,8) · 手写 migration(§4.16,Flyway boot 自动 apply)
-- 单一真相源(§4.24):
--   * investor = 出资人名册(GP/LP·出资额·比例);比例快照,分配按本表 ratio 投影
--   * distribution = 结账分配头(一 period 一条 active);利润→管理费阶梯→可分配→50现金/50滚存→留存校验
--   * 幂等(§十一 D):同 period 已有 active 分配 → 拒(force 则先冲销旧的置 reversed 再重算)
--   * 留存下限 = max(20万 reserve_floor, 未来3月供应商净应付);不足则压减现金分配、抬高滚存
--   * 每人份额 = 现场按 distribution 快照(cash_50/roll_50)× investor.ratio 即时算(不落冗余表·防漂移)
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 出资人名册(GP/LP · 出资额 · 比例) —— 仅供分配计算,无员工门户
--    seed 首期 200万 四出资方(方案书§九):数智云仓GP20万/小洪LP60万/刘总LP70万/其他LP50万
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_investor (
  id           bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  name         varchar(64)   NOT NULL COMMENT '出资方名称',
  role         varchar(8)    NOT NULL COMMENT '角色:GP 普通合伙人 / LP 有限合伙人',
  amount       decimal(18,2) NOT NULL COMMENT '出资额(元)',
  ratio        decimal(18,8) NOT NULL COMMENT '出资比例(=amount/合计;分配投影键)',
  user_id      bigint        DEFAULT NULL COMMENT '关联登录用户(P0-E 角色可见:LP 仅见自己那份)',
  active       tinyint(1)    NOT NULL DEFAULT 1 COMMENT '是否在册:1是 0退伙',
  remark       varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted   tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_investor_role (role),
  KEY idx_investor_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出资人名册:GP/LP·出资额·比例(M3-04)';

INSERT INTO yc_rent_investor (name, role, amount, ratio, remark) VALUES
 ('数智云仓', 'GP', 200000.00, 0.10000000, '普通合伙人·执行合伙事务+按阶梯提管理费(另按10%出资比例参与分配)'),
 ('小洪',     'LP', 600000.00, 0.30000000, '有限合伙人·数智云仓股东·负责运营执行'),
 ('刘总',     'LP', 700000.00, 0.35000000, '有限合伙人·出资额最高·不参与经营'),
 ('其他出资方','LP', 500000.00, 0.25000000, '有限合伙人·面向 Dpark/集包厂股东开放·20万起认购');

-- ---------------------------------------------------------------------
-- 2. 结账分配头 —— 一 period 一条 active;利润阶梯→可分配→50现金/50滚存→留存校验
--    is_reversal=1 + reverses_id 唯一 = 冲销幂等键(一原分配仅允许被冲销一次)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_rent_distribution (
  id                 bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  distribution_no    varchar(64)   NOT NULL COMMENT '分配单号(唯一)',
  period             varchar(7)    NOT NULL COMMENT '分配期 YYYY-MM',
  biz_date           date          NOT NULL COMMENT '分配业务日期(每月5号)',
  total_capital      decimal(18,2) NOT NULL COMMENT '实缴出资合计(元·=Σ investor.amount·回报率分母)',
  profit_before      decimal(18,2) NOT NULL COMMENT '提取前净利(元·经营账收入−成本·管理费计提基数)',
  return_rate        decimal(18,8) NOT NULL COMMENT '公司回报率(=profit_before/total_capital·管理费阶梯选档依据)',
  mgmt_fee_rate      decimal(18,8) NOT NULL COMMENT '管理费率(阶梯档·走 rule_config mgmt_fee_ladder)',
  mgmt_fee           decimal(18,2) NOT NULL COMMENT '管理费(元·=profit_before×mgmt_fee_rate·先于分配提取)',
  distributable      decimal(18,2) NOT NULL COMMENT '可分配利润(元·=profit_before−mgmt_fee)',
  cash_50            decimal(18,2) NOT NULL COMMENT '现金分配额(元·每月5号发·默认可分配×50%·留存不足时压减)',
  roll_50            decimal(18,2) NOT NULL COMMENT '滚存额(元·留存滚动增值·=distributable−cash_50)',
  reserve_floor      decimal(18,2) NOT NULL COMMENT '留存下限(元·=max(20万,未来3月供应商净应付))',
  reserve_after      decimal(18,2) NOT NULL COMMENT '本次分配后留存(元·=roll_50;须≥reserve_floor)',
  reserve_sufficient tinyint(1)    NOT NULL DEFAULT 1 COMMENT '留存是否达标:1达标 0压减后仍不足(告警)',
  status             varchar(16)   NOT NULL DEFAULT 'active' COMMENT '状态:active 生效 / reversed 已冲销',
  is_reversal        tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否冲销单:0原始 1冲销',
  reverses_id        bigint        DEFAULT NULL COMMENT '冲销指向的原分配 id(幂等键·唯一约束防重复冲销)',
  operator_id        bigint        DEFAULT NULL COMMENT '操作人',
  remark             varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted         tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_distribution_no (distribution_no),
  UNIQUE KEY uk_distribution_reverses (reverses_id) COMMENT '冲销幂等键:一原分配仅允许一条冲销(NULL 不参与唯一)',
  KEY idx_distribution_period (period, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结账分配头:利润阶梯→可分配→50现金/50滚存→留存校验(M3-03/04)';

-- ---------------------------------------------------------------------
-- 3. rule_config 追加:管理费阶梯 v2(校正为方案书§13.1 五档) + 50/50 分配比 + 回报四源权重
--    §4.24 禁硬编码:阶梯/分配比/四源权重全部走 rule_config
-- ---------------------------------------------------------------------
-- 管理费阶梯 v2(§13.1 权威五档):<20%→5% / 20-25%→5% / 25-30%→10% / 30-35%→15% / 35%+→20%
--   取值 = 首个 return_rate < maxReturn 的档(边界 25% 落 25-30 档→10%,佐证方案书§13.2:50万净利→5万管理费)
--   与 v1(V2 四档)并存,getEffective 取 version 最大 → 生效 v2
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_json, value_type, version, effective_from, effective_to, remark) VALUES
 ('mgmt_fee_ladder', '',
  '[{"maxReturn":0.20,"rate":0.05},{"maxReturn":0.25,"rate":0.05},{"maxReturn":0.30,"rate":0.10},{"maxReturn":0.35,"rate":0.15},{"maxReturn":9.99,"rate":0.20}]',
  'json', 2, '2023-01-01', NULL, '管理费阶梯 v2(方案书§13.1 五档):<20/20-25→5%、25-30→10%、30-35→15%、35%+→20%');

-- 50% 现金分配 / 50% 滚存(§11:每月5号当期利润 50% 分配、50% 滚存)
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('distribution_cash_ratio', '', 0.50000000, 'rate', 1, '2023-01-01', NULL, '可分配利润现金分配占比 50%(其余滚存·方案书§11)');

-- 回报四源权重(§8.4:总税后IRR = 集采差价+资金时间价值+价值定价+残值回收;四权重∑=1·可勾稽)
--   术语区分:三层杠杆=资金结构(本金/供应商账期/融资) vs 四源=利润来源(此处)
--   权重入 rule_config 便于按真实业务校准;四源 = 总IRR × 各权重 → ∑ 恒 = 总IRR
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_json, value_type, version, effective_from, effective_to, remark) VALUES
 ('return_attribution_weights', '',
  '{"procurementSpread":0.40,"timeValue":0.25,"valuePricing":0.20,"residual":0.15}',
  'json', 1, '2023-01-01', NULL, '回报四源权重(方案书§8.4):集采差价40%/资金时间价值25%/价值定价20%/残值回收15%(∑=1)');
