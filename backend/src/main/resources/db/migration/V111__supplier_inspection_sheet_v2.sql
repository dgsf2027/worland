-- =====================================================================
-- 供应商考察对齐新版《厂家考察汇总表》(20260916 版,V111)
-- 新增列:公司业务范围(公司简介长文本)/业绩(万元·原文)/社保员工(原文)/考察观后感/产品图片说明
-- 原 business_scope 继续存「业务类型」(货架/阁楼/播种墙)。
-- 产品图片走对象存储 yc_rent_file_object(biz_type=supplier_inspection_image, biz_id=考察 id),
-- Excel 导入时从单元格图片(WPS 嵌入图片 / 浮动图片)自动提取。
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE yc_rent_supplier_inspection
  ADD COLUMN company_profile text DEFAULT NULL COMMENT '公司业务范围(公司简介)' AFTER business_scope,
  ADD COLUMN performance_wan varchar(64) DEFAULT NULL COMMENT '业绩/万元(按原文存,如 2000万 / 200~300万 / 未详)' AFTER phone,
  ADD COLUMN social_staff varchar(128) DEFAULT NULL COMMENT '社保员工(按原文存,可带说明)' AFTER performance_wan,
  ADD COLUMN product_image_note varchar(128) DEFAULT NULL COMMENT '产品图片说明(无图片时的文字,如 无播种墙图片)' AFTER social_staff,
  ADD COLUMN impression text DEFAULT NULL COMMENT '考察观后感' AFTER product_image_note;
