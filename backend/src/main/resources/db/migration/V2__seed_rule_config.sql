-- =====================================================================
-- 沃朗科技租赁板块 · rule_config 初始规则灌入 (S0-05)
-- 出处: 设立方案书 V14.0(§三定价原则 / §四三层回报 / §十定价表与速算系数 / §十三管理费)
-- 全部口径常量入库,报价引擎从此取,禁硬编码
-- =====================================================================

-- ---- 税率(小规模纳税人;增值税 3% 减按 1%,2027-12-31 sunset) ----
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('tax_vat',    '', 0.01000000, 'rate', 1, '2023-01-01', '2027-12-31', '增值税小规模 3%减按1%,2027-12-31 sunset(方案书§一)'),
 ('tax_vat',    '', 0.03000000, 'rate', 2, '2028-01-01', NULL,         'sunset 后恢复 3% 征收率'),
 ('tax_surtax', '', 0.12000000, 'rate', 1, '2023-01-01', NULL,         '附加税费,按增值税额 12%(方案书§一)'),
 ('tax_income', '', 0.05000000, 'rate', 1, '2023-01-01', NULL,         '所得税,小微优惠综合口径 5%(方案书§一)');

-- ---- 品类转让率(期末按市场价比例转让) ----
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('transfer_rate', '播种墙', 0.10000000, 'rate', 1, '2023-01-01', NULL, '播种墙期末按市场价 10% 转让(方案书§十)'),
 ('transfer_rate', '货架',   0.30000000, 'rate', 1, '2023-01-01', NULL, '货架/阁楼期末按市场价 30% 转让(方案书§十)');

-- ---- 品类租期(月) ----
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('term_months', '播种墙', 36, 'months', 1, '2023-01-01', NULL, '播种墙 3 年期(方案书§十)'),
 ('term_months', '货架',   60, 'months', 1, '2023-01-01', NULL, '货架 5 年期(方案书§十)');

-- ---- 客户类型目标税后 IRR ----
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('target_irr', '云山快仓', 0.25000000, 'rate', 1, '2023-01-01', NULL, '体系内主要客户,从优定价(方案书§三)'),
 ('target_irr', '其他',     0.30000000, 'rate', 1, '2023-01-01', NULL, '园区租户及其他客户参照行业 30%-35%,默认 30%(方案书§三)');

-- ---- 速算系数(市场价 × 系数 = 建议月租;仅作起价建议,精确值由 IRR 求解) ----
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('speed_coeff', '播种墙:25', 0.03360000, 'rate', 1, '2023-01-01', NULL, '方案书§10.3 播种墙 25%'),
 ('speed_coeff', '播种墙:30', 0.03580000, 'rate', 1, '2023-01-01', NULL, '方案书§10.3 播种墙 30%'),
 ('speed_coeff', '播种墙:35', 0.03790000, 'rate', 1, '2023-01-01', NULL, '方案书§10.3 播种墙 35%'),
 ('speed_coeff', '货架:25',   0.02330000, 'rate', 1, '2023-01-01', NULL, '方案书§10.3 货架 25%'),
 ('speed_coeff', '货架:30',   0.02590000, 'rate', 1, '2023-01-01', NULL, '方案书§10.3 货架 30%'),
 ('speed_coeff', '货架:35',   0.02840000, 'rate', 1, '2023-01-01', NULL, '方案书§10.3 货架 35%');

-- ---- 集采成本占市场价比例(无真实集采价时兜底,18万/20万=0.9) ----
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('purchase_cost_ratio', '', 0.90000000, 'rate', 1, '2023-01-01', NULL, '集采成本/市场价默认比,真实集采价优先(方案书§十:20万→18万)');

-- ---- 价值定价与风控参数 ----
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('deposit_months',         '', 2,      'months', 1, '2023-01-01', NULL, '押金=2 个月租金(方案书§4.3)'),
 ('payback_ceiling_months', '', 18,     'months', 1, '2023-01-01', NULL, '品类准入:承租方自购回本期≤18 月(方案书§三)'),
 ('reserve_floor',          '', 200000, 'money',  1, '2023-01-01', NULL, '留存下限 20 万(评审 P1-15)');

-- ---- 三层回报杠杆参数(校准自方案书§4.4 杠杆临界点表:25%→65.8%、30%→80%) ----
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('financing_cost',       '', 0.06000000, 'rate', 1, '2023-01-01', NULL, '银行资金成本 6%(方案书§4.2)'),
 ('supplier_leverage',    '', 2.00000000, 'rate', 1, '2023-01-01', NULL, '层级②供应商账期放大倍数(无息,自有资金翻倍)'),
 ('financing_leverage_a', '', 2.84000000, 'rate', 1, '2023-01-01', NULL, '层级③斜率 a:③=a×base+b,校准自杠杆临界点表两点'),
 ('financing_leverage_b', '', -0.05200000,'rate', 1, '2023-01-01', NULL, '层级③截距 b:融资成本拖累(25%→65.8%、30%→80% 双点校准)');

-- ---- 管理费阶梯(按公司回报率档提取,先于分配) ----
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_json, value_type, version, effective_from, effective_to, remark) VALUES
 ('mgmt_fee_ladder', '',
  '[{"maxReturn":0.15,"rate":0.05},{"maxReturn":0.25,"rate":0.10},{"maxReturn":0.35,"rate":0.20},{"maxReturn":9.99,"rate":0.20}]',
  'json', 1, '2023-01-01', NULL, '管理费阶梯 5%-20%:15%档→5%、25%档→10%、35%档→20%(方案书§十三)');
