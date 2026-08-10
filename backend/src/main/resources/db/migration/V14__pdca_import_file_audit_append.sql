-- =====================================================================
-- 沃朗科技租赁板块 · M5 Wave B  PDCA + 导入中心 + 对象存储 + 审计只追加强化 (V14)
-- MySQL 8 · utf8mb4 · InnoDB · 库 worland_dev · 表前缀 yc_rent_
-- 依据: DESIGN_DOC §4.4(action_item/audit_log) + §二(导入中心 映射/预览/去重 · 对象存储签名URL)
--       + 业务流§8.4(BI 多维矩阵) + 流程11(PDCA 每指标红绿灯→改进→到期回查)
--       + 评审 P1-16(审计只追加不可编辑)/P1-17(导入走同一校验+事件流·公式前缀转义·owner/project校验)
--         /P1-20(短时效签名URL+服务端鉴权代理·禁公开桶)
-- 口径: 金额=元 decimal(18,2) · 比率 decimal(18,8) · 手写 migration(§4.16 Flyway boot 自动 apply)
-- 单一真相源(§4.24):BI 只读聚合不落表(复用各 service);导入走正常单据同一校验+事件流通道(禁直写派生/隔离字段)
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. PDCA 改进项(action_item) —— 每指标红绿灯 → 改进措施 → 到期回查(§流程11 · DESIGN §4.4)
--    结构化验证指标(metric_key + target_value + compare_op)到期自动回查:
--    取指标当前值 vs 目标 → 通过关闭 / 未达升级 / 取不到需人工判定
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_action_item (
  id             bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  no             varchar(64)   NOT NULL COMMENT '改进项号 PDCA-{yyyyMMdd}-{序}',
  metric_scene   varchar(32)   NOT NULL COMMENT '来源指标环节:在租率/加权回报/应收账龄/资产周转/回款率',
  issue          varchar(500)  NOT NULL COMMENT '问题描述(如"货架品类在租率仅58%低于目标")',
  action         varchar(500)  NOT NULL COMMENT '改进措施(如"下调货架月租10%去化闲置")',
  metric_key     varchar(64)   DEFAULT NULL COMMENT '验证指标键(PdcaMetricService 注册表);NULL=需人工判定',
  metric_param   varchar(128)  DEFAULT NULL COMMENT '指标参数(如品类=货架/客户ID)',
  target_value   decimal(18,8) DEFAULT NULL COMMENT '目标值(达标线)',
  compare_op     varchar(4)    DEFAULT NULL COMMENT '比较方向:>=(越高越好)/<=(越低越好)',
  baseline_value decimal(18,8) DEFAULT NULL COMMENT '登记时指标基线值(改进前起点)',
  verify_value   decimal(18,8) DEFAULT NULL COMMENT '回查时指标实际值',
  verify_result  varchar(16)   DEFAULT NULL COMMENT '回查结果:通过/未达/需人工判定',
  verify_note    varchar(500)  DEFAULT NULL COMMENT '回查说明',
  verified_at    datetime      DEFAULT NULL COMMENT '最近一次回查时间',
  recheck_date   date          NOT NULL COMMENT '到期回查日(到点驾驶舱/cron 提醒)',
  owner_role     varchar(32)   DEFAULT NULL COMMENT '负责角色:老板/财务/供应链/业务',
  owner_user_id  bigint        DEFAULT NULL COMMENT '负责人主键',
  owner_user_name varchar(64)  DEFAULT NULL COMMENT '负责人显示名',
  status         varchar(16)   NOT NULL DEFAULT '进行中' COMMENT '状态:进行中/验证通过/未见效升级/已关闭',
  task_id        bigint        DEFAULT NULL COMMENT '关联任务(派单到工作台·yc_rent_task)',
  ai_draft       tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否 AI 起草(综述来自 LlmGateway mock)',
  llm_call_id    bigint        DEFAULT NULL COMMENT 'AI 调用记录(透明四件套)',
  creator_id     bigint        DEFAULT NULL COMMENT '登记人主键',
  creator_name   varchar(64)   DEFAULT NULL COMMENT '登记人显示名',
  project_id     bigint        DEFAULT NULL COMMENT '项目隔离键(P1-4)',
  remark         varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted     tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_action_no (no),
  KEY idx_action_status (status),
  KEY idx_action_recheck (recheck_date),
  KEY idx_action_scene (metric_scene)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PDCA 改进项:每指标红绿灯→改进→到期回查(M5-05)';

-- ---------------------------------------------------------------------
-- 2. 导入作业(import_job) —— Excel 映射/预览/去重 · 走同一校验+事件流通道(P1-17)
--    禁直写派生/隔离字段;单元格公式前缀(=+-@)转义;owner/project 归属校验;类型/大小/条数限流
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_import_job (
  id             bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  no             varchar(64)   NOT NULL COMMENT '导入作业号 IMP-{yyyyMMdd}-{序}',
  target_type    varchar(32)   NOT NULL COMMENT '导入目标:supplier(供应商)/customer(客户)/asset(设备)',
  file_name      varchar(255)  DEFAULT NULL COMMENT '原始文件名',
  file_size      bigint        DEFAULT NULL COMMENT '文件字节数(限流校验)',
  total_rows     int           NOT NULL DEFAULT 0 COMMENT '解析总行数',
  mapping_json   text          DEFAULT NULL COMMENT '列映射 JSON({excel列名:目标字段})',
  ok_rows        int           NOT NULL DEFAULT 0 COMMENT '校验通过行数',
  dup_rows       int           NOT NULL DEFAULT 0 COMMENT '去重命中行数(唯一键冲突)',
  err_rows       int           NOT NULL DEFAULT 0 COMMENT '校验失败行数',
  escaped_cells  int           NOT NULL DEFAULT 0 COMMENT '公式注入转义单元格数(=+-@ 前缀)',
  imported_rows  int           NOT NULL DEFAULT 0 COMMENT '实际入库行数(走 service 事件流)',
  status         varchar(16)   NOT NULL DEFAULT '待确认' COMMENT '状态:待确认(预览)/已导入/已作废',
  preview_json   mediumtext    DEFAULT NULL COMMENT '预览结果 JSON(逐行 ok/dup/err + 转义标记)',
  project_id     bigint        DEFAULT NULL COMMENT '归属项目(owner/project 校验)',
  operator_id    bigint        DEFAULT NULL COMMENT '导入人主键',
  operator_name  varchar(64)   DEFAULT NULL COMMENT '导入人显示名',
  operator_role  varchar(32)   DEFAULT NULL COMMENT '导入人角色',
  committed_at   datetime      DEFAULT NULL COMMENT '确认入库时间',
  remark         varchar(500)  DEFAULT NULL COMMENT '备注/失败摘要',
  create_time    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted     tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_import_no (no),
  KEY idx_import_target (target_type),
  KEY idx_import_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入作业:映射/预览/去重·走同一校验+事件流(M5-07)';

-- ---------------------------------------------------------------------
-- 3. 文件对象(file_object) —— 合同/现场照 · 短时效签名URL + 服务端鉴权代理(P1-20)
--    本地文件系统模拟对象存储;禁公开桶/可枚举 key:storage_key 用 UUID,访问必带签名 token 过服务端鉴权
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_file_object (
  id             bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  storage_key    varchar(64)   NOT NULL COMMENT '存储键(UUID·不可枚举·非顺序)',
  biz_type       varchar(32)   NOT NULL COMMENT '业务类型:contract(合同)/site_photo(现场照)/import(导入原件)',
  biz_id         bigint        DEFAULT NULL COMMENT '关联业务对象主键(行级隔离依据)',
  file_name      varchar(255)  NOT NULL COMMENT '原始文件名',
  content_type   varchar(128)  DEFAULT NULL COMMENT 'MIME 类型',
  file_size      bigint        NOT NULL DEFAULT 0 COMMENT '字节数',
  storage_path   varchar(500)  NOT NULL COMMENT '本地相对路径(storage/ 下·模拟对象存储)',
  project_id     bigint        DEFAULT NULL COMMENT '项目隔离键',
  owner_role     varchar(32)   DEFAULT NULL COMMENT '可见角色约束(空=经营角色可见·成本敏感另判)',
  uploader_id    bigint        DEFAULT NULL COMMENT '上传人主键',
  uploader_name  varchar(64)   DEFAULT NULL COMMENT '上传人显示名',
  create_time    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  is_deleted     tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_file_storage_key (storage_key),
  KEY idx_file_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件对象:合同/现场照·签名URL+服务端鉴权代理(M5-08)';

-- ---------------------------------------------------------------------
-- 4. 审计只追加强化(P1-16) —— audit_log 补 请求指纹(IP/URI/请求号) + 只追加不可编辑触发器
--    切真 SSO 前注明:法律级抗抵赖(哈希链/WORM)待真身份就绪;当前为应用层+DB 触发器双闸
-- ---------------------------------------------------------------------
ALTER TABLE yc_rent_audit_log
  ADD COLUMN client_ip   varchar(64)  DEFAULT NULL COMMENT '请求来源IP(网关注入·请求指纹)' AFTER operator_role,
  ADD COLUMN request_uri varchar(255) DEFAULT NULL COMMENT '请求URI(请求指纹)' AFTER client_ip,
  ADD COLUMN request_id  varchar(64)  DEFAULT NULL COMMENT '请求号(链路追踪·请求指纹)' AFTER request_uri;

-- 只追加不可编辑:任何 UPDATE / DELETE 直接 SIGNAL 报错(DB 层最后一道闸·应用层再拒一次)
-- 占位期注:法律级抗抵赖(哈希链前后串接·WORM 存储)待真 SSO 身份就绪后补,当前口径不变
DROP TRIGGER IF EXISTS trg_audit_log_no_update;
DROP TRIGGER IF EXISTS trg_audit_log_no_delete;

CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE ON yc_rent_audit_log
FOR EACH ROW SIGNAL SQLSTATE '45000'
  SET MESSAGE_TEXT = '审计日志只追加不可编辑(P1-16):UPDATE 被拒绝';

CREATE TRIGGER trg_audit_log_no_delete BEFORE DELETE ON yc_rent_audit_log
FOR EACH ROW SIGNAL SQLSTATE '45000'
  SET MESSAGE_TEXT = '审计日志只追加不可编辑(P1-16):DELETE 被拒绝';

-- ---------------------------------------------------------------------
-- 5. rule_config 追加:PDCA 红绿灯阈值 + 导入限流 + 签名URL时效(禁硬编码 §4.24)
-- ---------------------------------------------------------------------
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('pdca_threshold', 'occupancy_rate',   0.80000000, 'rate',  1, '2023-01-01', NULL, 'PDCA 在租率红绿灯:≥80%绿 / <80%红(越高越好)'),
 ('pdca_threshold', 'weighted_return',  0.15000000, 'rate',  1, '2023-01-01', NULL, 'PDCA 加权回报红绿灯:≥15%绿 / <15%红(越高越好)'),
 ('pdca_threshold', 'receivable_aging', 30.00000000,'number',1, '2023-01-01', NULL, 'PDCA 应收账龄红绿灯:≤30天绿 / >30天红(越低越好)'),
 ('pdca_threshold', 'asset_turnover',   0.90000000, 'rate',  1, '2023-01-01', NULL, 'PDCA 资产周转(投放率)红绿灯:≥90%绿 / <90%红(越高越好)'),
 ('pdca_threshold', 'collect_rate',     0.85000000, 'rate',  1, '2023-01-01', NULL, 'PDCA 回款率红绿灯:≥85%绿 / <85%红(越高越好)'),
 ('import_limit',   'max_file_bytes',   5242880.00, 'number',1, '2023-01-01', NULL, '导入文件大小上限 5MB(超限拒收)'),
 ('import_limit',   'max_rows',         2000.00,    'number',1, '2023-01-01', NULL, '导入单次条数上限 2000 行(超限拒收)'),
 ('file_sign_ttl',  'seconds',          300.00,     'number',1, '2023-01-01', NULL, '签名URL短时效 300 秒(过期失效·P1-20)');
