-- =====================================================================
-- 供应商考察(V103)
-- 流程: 新增考察 → 上传考察记录压缩包(图片/视频) → 判定合格 / 不合格
--   合格   → 自动在「供应商·上游」建档(阶段=入库),supplier_id 回填关联
--   不合格 → 不进入供应商池,supplier_id 保持 NULL
-- 口径: 判定为终态,不可改判;需复查请新建一条考察记录。
--       注册资本金额单位为元(全系统统一口径),前端按万元录入换算。
--       压缩包走对象存储 yc_rent_file_object(biz_type=supplier_inspection, biz_id=本表 id)。
-- =====================================================================

SET NAMES utf8mb4;

CREATE TABLE yc_rent_supplier_inspection (
  id                 bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  company_name       varchar(128)  NOT NULL COMMENT '公司名称',
  legal_person       varchar(64)   DEFAULT NULL COMMENT '法人',
  registered_capital decimal(18,2) DEFAULT NULL COMMENT '注册资本(元)',
  business_scope     varchar(64)   DEFAULT NULL COMMENT '业务范围(逗号分隔):货架/阁楼/播种墙',
  contact            varchar(64)   DEFAULT NULL COMMENT '主要联系人',
  phone              varchar(32)   DEFAULT NULL COMMENT '联系方式',
  result             varchar(8)    NOT NULL DEFAULT '待考察' COMMENT '考察结果:待考察/合格/不合格',
  conclusion         varchar(255)  DEFAULT NULL COMMENT '考察结论说明(判定时填写)',
  decided_by         bigint        DEFAULT NULL COMMENT '判定人(user 主键)',
  decided_by_name    varchar(64)   DEFAULT NULL COMMENT '判定人姓名(留痕快照)',
  decided_at         datetime      DEFAULT NULL COMMENT '判定时间',
  supplier_id        bigint        DEFAULT NULL COMMENT '合格后关联的供应商(yc_rent_supplier.id);不合格为 NULL',
  project_id         bigint        DEFAULT NULL COMMENT '项目隔离键',
  remark             varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted         tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_inspection_result (result),
  KEY idx_inspection_supplier (supplier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商考察:合格自动进入供应商池并关联';
