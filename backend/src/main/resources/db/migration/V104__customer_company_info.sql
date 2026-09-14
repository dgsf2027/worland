-- =====================================================================
-- 客户工商信息(V104)
-- 客户 CRM 补齐:法人 / 注册资本 / 业务范围。公司名称沿用 name,主要联系人/联系方式沿用 contact/phone。
-- 口径: 注册资本金额单位为元(前端按万元录入换算);业务范围为 货架/阁楼/播种墙 逗号分隔(固定顺序)。
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE yc_rent_customer
  ADD COLUMN legal_person       varchar(64)   DEFAULT NULL COMMENT '法人'                               AFTER name,
  ADD COLUMN registered_capital decimal(18,2) DEFAULT NULL COMMENT '注册资本(元)'                       AFTER legal_person,
  ADD COLUMN business_scope     varchar(64)   DEFAULT NULL COMMENT '业务范围(逗号分隔):货架/阁楼/播种墙' AFTER registered_capital;
