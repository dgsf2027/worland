-- =====================================================================
-- 供应商考察 · 对齐「厂家考察汇总表」(V105)
-- 1) 新增 序号(列表排序) / 成立时间 / 公司地址
-- 2) 注册资本改为「万元原文」文本:表格里有「60*6」这类非数字写法,按原文存取,导入导出不丢信息。
--    历史数值(元)换算为万元文本迁入,再删除旧的 decimal 列。
-- 3) 判定结果允许改判(导入按表格自动更新):合格↔不合格 由服务层同步供应商关联,本迁移不涉及。
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE yc_rent_supplier_inspection
  ADD COLUMN sort_no int DEFAULT NULL COMMENT '序号(列表排序,升序;空排最后)' AFTER id;
ALTER TABLE yc_rent_supplier_inspection
  ADD COLUMN registered_capital_wan varchar(64) DEFAULT NULL COMMENT '注册资本(万元·按原文存,如 200 / 60*6)' AFTER legal_person;
ALTER TABLE yc_rent_supplier_inspection
  ADD COLUMN established_date date DEFAULT NULL COMMENT '成立时间' AFTER registered_capital_wan;
ALTER TABLE yc_rent_supplier_inspection
  ADD COLUMN address varchar(255) DEFAULT NULL COMMENT '公司地址' AFTER business_scope;

UPDATE yc_rent_supplier_inspection
   SET registered_capital_wan = CASE
         WHEN registered_capital = 0 THEN '0'
         ELSE TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(ROUND(registered_capital / 10000, 4) AS CHAR)))
       END
 WHERE registered_capital IS NOT NULL;

ALTER TABLE yc_rent_supplier_inspection DROP COLUMN registered_capital;

CREATE INDEX idx_inspection_sort ON yc_rent_supplier_inspection (sort_no);
CREATE INDEX idx_inspection_company ON yc_rent_supplier_inspection (company_name);
