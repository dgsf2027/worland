-- =====================================================================
-- 供应商工商/开票/收款信息补齐 (V15 · F6 修 Bug)
-- 现象: 供应商只能靠导入台账建档,且台账缺公司全称/开户行/银行账号等付款必需信息
-- 根因: yc_rent_supplier 建表(V3)只有 name/contact/phone/main_category/status/remark,
--       没有工商主体与收款账户字段 → 采购入库·应付环节拿不到打款账户
-- 口径: 开票四要素(名称/税号/地址电话/开户行账号)完整落列; 账号属财务敏感字段,
--       读接口按 DataScope.canSeeCost 打码(GP/LP 不可见), 与成本/账期同一口径(§4.24)
-- 幂等: 本文件为纯 ADD COLUMN + UPDATE 种子, 重复执行由 Flyway 版本表拦截
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE yc_rent_supplier
  ADD COLUMN full_name    varchar(200) DEFAULT NULL COMMENT '公司全称(工商注册名;开票抬头)'        AFTER name,
  ADD COLUMN tax_no       varchar(32)  DEFAULT NULL COMMENT '统一社会信用代码/纳税人识别号'         AFTER full_name,
  ADD COLUMN reg_address  varchar(255) DEFAULT NULL COMMENT '注册地址(开票用)'                     AFTER tax_no,
  ADD COLUMN reg_phone    varchar(64)  DEFAULT NULL COMMENT '注册电话(开票用;区别于联系人手机)'    AFTER reg_address,
  ADD COLUMN bank_name    varchar(128) DEFAULT NULL COMMENT '开户行(支行全称)'                     AFTER reg_phone,
  ADD COLUMN bank_account varchar(64)  DEFAULT NULL COMMENT '银行账号(财务敏感·按角色打码)'        AFTER bank_name,
  ADD COLUMN account_name varchar(200) DEFAULT NULL COMMENT '收款户名(默认同公司全称;不同则以此为准)' AFTER bank_account,
  -- 枚举取值最长「增值税专用发票」=7 字符, varchar(16) 留足余量(§4.16 禁静默截断)
  ADD COLUMN invoice_type varchar(16)  DEFAULT NULL COMMENT '发票类型:增值税专用发票/增值税普通发票/无票';

CREATE INDEX idx_supplier_tax_no ON yc_rent_supplier (tax_no);

-- 种子补齐(对齐 V3 已有 4 家,便于详情页/导入模板 E2E 看到真实形态)
-- 按 name 定位而非 id,并要求 full_name IS NULL —— 生产上若这些行已被真实资料覆盖/改名,本段不动它
UPDATE yc_rent_supplier SET
  full_name = '苏州恒丰自动化设备有限公司', tax_no = '91320500MA1XXXXX1A',
  reg_address = '江苏省苏州市吴中区木渎镇金枫路 1 号', reg_phone = '0512-6600-0001',
  bank_name = '中国建设银行苏州吴中支行', bank_account = '32050166360800000001',
  account_name = '苏州恒丰自动化设备有限公司', invoice_type = '增值税专用发票'
WHERE name = '恒丰自动化' AND full_name IS NULL;

UPDATE yc_rent_supplier SET
  full_name = '杭州睿捷智能设备有限公司', tax_no = '91330100MA2XXXXX2B',
  reg_address = '浙江省杭州市余杭区仓前街道文一西路 998 号', reg_phone = '0571-8800-0002',
  bank_name = '招商银行杭州余杭支行', bank_account = '571900123400002',
  account_name = '杭州睿捷智能设备有限公司', invoice_type = '增值税专用发票'
WHERE name = '睿捷设备' AND full_name IS NULL;

UPDATE yc_rent_supplier SET
  full_name = '广州广达仓储设备有限公司', tax_no = '91440100MA3XXXXX3C',
  reg_address = '广东省广州市白云区太和镇兴太三路 12 号', reg_phone = '020-3600-0003',
  bank_name = '中国工商银行广州白云支行', bank_account = '3602001019200000003',
  account_name = '广州广达仓储设备有限公司', invoice_type = '增值税普通发票'
WHERE name = '广达货架' AND full_name IS NULL;

UPDATE yc_rent_supplier SET
  full_name = '深圳科瑞电控技术有限公司', tax_no = '91440300MA4XXXXX4D',
  reg_address = '广东省深圳市宝安区西乡街道臣田工业区 A 栋', reg_phone = '0755-2900-0004',
  bank_name = '中国银行深圳宝安支行', bank_account = '7601020100000000004',
  account_name = '深圳科瑞电控技术有限公司', invoice_type = '增值税专用发票'
WHERE name = '科瑞电控' AND full_name IS NULL;
