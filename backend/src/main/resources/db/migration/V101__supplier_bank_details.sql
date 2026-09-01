-- 设备逐件台账：供应商结算信息（公司账户、开户银行）
SET NAMES utf8mb4;

ALTER TABLE yc_rent_supplier
  ADD COLUMN company_account varchar(128) DEFAULT NULL COMMENT '公司账户(开户账号)' AFTER phone,
  ADD COLUMN opening_bank varchar(128) DEFAULT NULL COMMENT '开户银行' AFTER company_account;
