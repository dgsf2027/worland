-- =====================================================================
-- V129 真实合同全链路实用化改造（P0 + P1 + P2）
-- 1. 采购单解除「先签约后采购」硬死锁（contract_id 可空），补齐意向客户/验收/审核/质保/交付/过磅字段
-- 2. 采购合同与合同清单支持明细合计、整单优惠/抹零、实物/安装运输/检测等费用分类
-- 3. 付款节点支持「下单/入库/验收合格/审核通过」触发点 + 「月结N天/次月/次次月」自然月规则
-- 4. 租赁合同支持融资租赁/分期收款销售可配置、利率/固定付款日/销项开票口径，租金计划增加本息拆分
-- 5. 收租单支持部分核销与销项发票闭环跟踪
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE yc_rent_purchase_in
  MODIFY COLUMN contract_id bigint DEFAULT NULL COMMENT '绑定租赁合同(允许先采后签,可空)',
  ADD COLUMN intended_customer_id bigint DEFAULT NULL COMMENT '意向承接客户(先采后签用)' AFTER contract_id,
  ADD COLUMN acceptance_date date DEFAULT NULL COMMENT '验收合格日' AFTER receive_date,
  ADD COLUMN audit_pass_date date DEFAULT NULL COMMENT '结算资料审核通过日' AFTER acceptance_date,
  ADD COLUMN warranty_years int DEFAULT NULL COMMENT '质保年限(年)' AFTER audit_pass_date,
  ADD COLUMN warranty_end_date date DEFAULT NULL COMMENT '质保到期日' AFTER warranty_years,
  ADD COLUMN delivery_address varchar(255) DEFAULT NULL COMMENT '交付/安装地址' AFTER warranty_end_date,
  ADD COLUMN delivery_contact varchar(100) DEFAULT NULL COMMENT '交付联系人及电话' AFTER delivery_address,
  ADD COLUMN weigh_tons decimal(10,2) DEFAULT NULL COMMENT '过磅重量(吨)' AFTER delivery_contact;

ALTER TABLE yc_rent_purchase_contract
  ADD COLUMN detail_subtotal decimal(14,2) DEFAULT NULL COMMENT '明细报价原价合计(元)' AFTER total_amount,
  ADD COLUMN discount_amount decimal(14,2) NOT NULL DEFAULT 0.00 COMMENT '整单优惠/抹零金额(元)' AFTER detail_subtotal,
  ADD COLUMN warranty_years int DEFAULT NULL COMMENT '质保年限(年)' AFTER signed_date,
  ADD COLUMN delivery_address varchar(255) DEFAULT NULL COMMENT '交付/安装地址' AFTER warranty_years,
  ADD COLUMN delivery_contact varchar(100) DEFAULT NULL COMMENT '交付联系人及电话' AFTER delivery_address,
  ADD COLUMN acceptance_date date DEFAULT NULL COMMENT '验收合格日' AFTER delivery_contact,
  ADD COLUMN audit_pass_date date DEFAULT NULL COMMENT '结算资料审核通过日' AFTER acceptance_date;

ALTER TABLE yc_rent_purchase_contract_term
  ADD COLUMN trigger_point varchar(16) DEFAULT NULL COMMENT '触发时点:下单/入库/验收合格/审核通过' AFTER amount,
  ADD COLUMN due_rule varchar(32) NOT NULL DEFAULT 'after_days' COMMENT '到期规则:after_days/month_end_days/next_month_day/next_2_month_day' AFTER trigger_point,
  ADD COLUMN due_days int NOT NULL DEFAULT 0 COMMENT '到期天数' AFTER due_rule,
  ADD COLUMN due_day_of_month int DEFAULT NULL COMMENT '指定自然月日(1-31,空=月末)' AFTER due_days,
  ADD COLUMN due_date date DEFAULT NULL COMMENT '预计或实际到期付款日' AFTER due_day_of_month;

ALTER TABLE yc_rent_contract_payment_term
  MODIFY COLUMN trigger_point varchar(16) NOT NULL COMMENT '触发时点:下单/入库/验收合格/审核通过',
  ADD COLUMN due_rule varchar(32) NOT NULL DEFAULT 'after_days' COMMENT '到期规则:after_days/month_end_days/next_month_day/next_2_month_day' AFTER due_days,
  ADD COLUMN due_day_of_month int DEFAULT NULL COMMENT '指定自然月日(1-31,空=月末)' AFTER due_rule;

ALTER TABLE yc_rent_contract
  ADD COLUMN boq_discount_amount decimal(14,2) NOT NULL DEFAULT 0.00 COMMENT '清单整单优惠/抹零金额(元)' AFTER equipment_total,
  ADD COLUMN final_deal_price decimal(14,2) DEFAULT NULL COMMENT '最终成交价(含税·元)' AFTER boq_discount_amount,
  ADD COLUMN lease_annual_rate decimal(8,4) DEFAULT NULL COMMENT '名义年化利率(%)' AFTER target_irr,
  ADD COLUMN interest_mode varchar(16) DEFAULT NULL COMMENT '计息口径:a/b/c' AFTER lease_annual_rate,
  ADD COLUMN pay_day_of_month int DEFAULT NULL COMMENT '每月固定付款日(1-31,如15)' AFTER start_date,
  ADD COLUMN first_pay_date date DEFAULT NULL COMMENT '首期付款日' AFTER pay_day_of_month,
  ADD COLUMN invoice_tax_rate decimal(6,4) DEFAULT 0.0300 COMMENT '销项专票税率(如0.03)' AFTER tax_rate,
  ADD COLUMN invoice_deadline_days int DEFAULT 10 COMMENT '收款后开票期限(工作日)' AFTER invoice_tax_rate,
  ADD COLUMN overdue_daily_rate decimal(8,6) DEFAULT 0.000500 COMMENT '逾期日违约金率(如0.0005)' AFTER invoice_deadline_days;

ALTER TABLE yc_rent_contract_boq
  ADD COLUMN item_kind varchar(32) NOT NULL DEFAULT '实物配件' COMMENT '明细类型:实物配件/安装运输/检测等费用' AFTER asset_category;

ALTER TABLE yc_rent_asset
  ADD COLUMN deal_price_override decimal(14,2) DEFAULT NULL COMMENT '整单折让后最终成交价(元)' AFTER purchase_price,
  ADD COLUMN warranty_years int DEFAULT NULL COMMENT '质保年限(年)' AFTER remark,
  ADD COLUMN warranty_start_date date DEFAULT NULL COMMENT '质保起始日' AFTER warranty_years,
  ADD COLUMN warranty_end_date date DEFAULT NULL COMMENT '质保到期日' AFTER warranty_start_date,
  ADD COLUMN delivery_address varchar(255) DEFAULT NULL COMMENT '交付/安装地址' AFTER warranty_end_date,
  ADD COLUMN delivery_contact varchar(100) DEFAULT NULL COMMENT '交付联系人及电话' AFTER delivery_address,
  ADD COLUMN weigh_tons decimal(10,2) DEFAULT NULL COMMENT '过磅重量(吨)' AFTER delivery_contact;

ALTER TABLE yc_rent_asset_bom
  ADD COLUMN item_kind varchar(32) NOT NULL DEFAULT '实物配件' COMMENT '明细类型:实物配件/安装运输/检测等费用' AFTER residual_rate;

ALTER TABLE yc_rent_rent_schedule
  ADD COLUMN opening_principal decimal(14,2) DEFAULT NULL COMMENT '期初本金余额(元)' AFTER amount,
  ADD COLUMN interest_amount decimal(14,2) DEFAULT NULL COMMENT '本期利息(元)' AFTER opening_principal,
  ADD COLUMN principal_amount decimal(14,2) DEFAULT NULL COMMENT '本期归还本金(元)' AFTER interest_amount,
  ADD COLUMN closing_principal decimal(14,2) DEFAULT NULL COMMENT '期末本金余额(元)' AFTER principal_amount;

ALTER TABLE yc_rent_rent_bill
  ADD COLUMN invoice_status varchar(16) NOT NULL DEFAULT '未开票' COMMENT '销项发票状态:未开票/待开票/已开票/免开' AFTER voucher_id,
  ADD COLUMN invoice_no varchar(64) DEFAULT NULL COMMENT '销项发票号码' AFTER invoice_status,
  ADD COLUMN invoice_date date DEFAULT NULL COMMENT '销项开票日期' AFTER invoice_no,
  ADD COLUMN invoice_amount decimal(14,2) DEFAULT NULL COMMENT '销项开票金额(元)' AFTER invoice_date,
  ADD COLUMN invoice_due_date date DEFAULT NULL COMMENT '应开票截止日(核销后N工作日)' AFTER invoice_amount,
  ADD COLUMN invoice_tax_rate decimal(6,4) DEFAULT NULL COMMENT '销项税率(如0.03)' AFTER invoice_due_date;

INSERT INTO yc_rent_rule_config
  (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark)
SELECT 'allow_finance_lease_nature', '', 1, 'flag', 1, '2023-01-01', NULL,
       '是否允许合同性质选择「设备融资租赁」及正文/备注出现融资租赁字样(1允许,0严禁)'
WHERE NOT EXISTS (
  SELECT 1 FROM yc_rent_rule_config WHERE rule_key = 'allow_finance_lease_nature' AND scope_key = '' AND is_deleted = 0
);
