-- =====================================================================
-- 沃朗科技租赁板块 · M5 Wave A 任务中心 + 审批 + 花名册/权限/提成 Schema + 种子 (V13)
-- MySQL 8 · utf8mb4 · InnoDB · 库 worland_dev · 表前缀 yc_rent_
-- 依据: DESIGN_DOC §4.4(task/approval/user_role_ext) + 业务流§8.4(人/提成) + §六 SOP(按角色日常)
--       + UI-mockup(任务·审批/花名册·提成/工作台) + 评审 P0-D(投放审批→老板·RBAC 统一切面)
-- 口径: 金额=元 decimal(18,2) · 比率 decimal(18,8) · 手写 migration(§4.16,Flyway boot 自动 apply)
-- 单一真相源(§4.24/§4.17):
--   * task.transfer_log = 转派留痕 JSON 数组(只追加·每次转派 append 一条{from,to,at,by,reason})
--   * task 完成必校验:verify_required=1 的任务缺 verify_evidence 拒绝置「已完成」(不是打勾就算)
--   * approval 投放审批:principal_return_rate ≥ target_rate 方可发起;amount ≤ self_limit(300万)→自主,超额→协商
--   * commission 提成 = base_amount(降本额/成交额)× rate,funded_from='管理费'(从管理费列支·非成本);幂等键 uk(period,user_id,type,source_ref)
--   * user_role_ext 数据权限复用 DataScope 口径(cost_visible 字段级保密:LP 仅月报不见成本);写权限限老板+入 audit(M5-06)
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 任务中心(task) —— 绑角色绑人 · 系统派单/手动派单 · 转派留痕 · 完成校验
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_task (
  id                bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  no                varchar(64)   NOT NULL COMMENT '任务号 TASK-{yyyyMMdd}-{序}',
  title             varchar(255)  NOT NULL COMMENT '任务标题',
  type              varchar(32)   NOT NULL COMMENT '类型:集采比价/BOM/逾期跟进/催收/合同到期跟进/跟进待办/兑付缺口/投放审批/通用',
  assignee_role     varchar(32)   DEFAULT NULL COMMENT '承接角色:老板/财务/供应链/业务(角色绑定)',
  assignee_user_id  bigint        DEFAULT NULL COMMENT '承接人主键(角色绑人·可空=仅绑角色)',
  assignee_user_name varchar(64)  DEFAULT NULL COMMENT '承接人显示名',
  source            varchar(16)   NOT NULL DEFAULT '派单' COMMENT '来源:系统(自动派)/派单(手动)',
  status            varchar(16)   NOT NULL DEFAULT '待开始' COMMENT '状态:待开始/进行中/已完成/已作废/超时',
  priority          varchar(8)    NOT NULL DEFAULT '中' COMMENT '优先级:高/中/低',
  biz_type          varchar(32)   DEFAULT NULL COMMENT '关联业务对象类型:overdue_case/contract/purchase_in/coverage_gap',
  biz_id            bigint        DEFAULT NULL COMMENT '关联业务对象主键',
  due_date          date          DEFAULT NULL COMMENT '截止日(逾期→超时亮灯)',
  verify_required   tinyint(1)    NOT NULL DEFAULT 0 COMMENT '完成是否需校验证据:0否 1是(如 BOM 需上传泵表)',
  verify_evidence   varchar(500)  DEFAULT NULL COMMENT '完成校验证据(文件URL/说明·verify_required=1 必填)',
  transfer_log      text          DEFAULT NULL COMMENT '转派留痕 JSON 数组(只追加·[{from,to,at,by,reason}])',
  creator_id        bigint        DEFAULT NULL COMMENT '派单人主键',
  creator_name      varchar(64)   DEFAULT NULL COMMENT '派单人显示名',
  finished_at       datetime      DEFAULT NULL COMMENT '完成时间',
  remark            varchar(255)  DEFAULT NULL COMMENT '备注',
  project_id        bigint        DEFAULT NULL COMMENT '项目隔离键(P1-4)',
  create_time       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted        tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_no (no),
  KEY idx_task_assignee_role (assignee_role),
  KEY idx_task_assignee_user (assignee_user_id),
  KEY idx_task_status (status),
  KEY idx_task_source (source),
  KEY idx_task_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务中心:绑角色绑人+转派留痕+完成校验(M5-01)';

-- ---------------------------------------------------------------------
-- 2. 审批(approval) —— 投放审批:本金回报≥目标 & 300万内自主/超额协商
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_approval (
  id                    bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  no                    varchar(64)   NOT NULL COMMENT '审批号 AP-{yyyyMMdd}-{序}',
  type                  varchar(32)   NOT NULL DEFAULT '投放审批' COMMENT '类型:投放审批(接采购/投放)',
  biz_type              varchar(32)   DEFAULT NULL COMMENT '标的类型:purchase_in/contract',
  biz_id                bigint        DEFAULT NULL COMMENT '标的主键',
  subject               varchar(255)  DEFAULT NULL COMMENT '标的描述(如 CG-2026-031 采购)',
  amount                decimal(18,2) NOT NULL DEFAULT 0 COMMENT '投放金额(元)',
  principal_return_rate decimal(18,8) DEFAULT NULL COMMENT '本金回报率(层级①·审批以此为准)',
  target_rate           decimal(18,8) DEFAULT NULL COMMENT '目标本金回报率(达标线快照)',
  self_limit            decimal(18,2) DEFAULT NULL COMMENT '自主额度上限快照(元·rule approval_self_limit)',
  decision_mode         varchar(16)   NOT NULL DEFAULT '自主' COMMENT '裁决方式:自主(≤300万)/协商(超额转合伙人协商)',
  status                varchar(16)   NOT NULL DEFAULT '待审批' COMMENT '状态:待审批/已通过/已驳回',
  applicant_id          bigint        DEFAULT NULL COMMENT '发起人主键',
  applicant_name        varchar(64)   DEFAULT NULL COMMENT '发起人显示名',
  approver_id           bigint        DEFAULT NULL COMMENT '审批人主键(老板)',
  approver_name         varchar(64)   DEFAULT NULL COMMENT '审批人显示名',
  approved_at           datetime      DEFAULT NULL COMMENT '裁决时间',
  decision_reason       varchar(500)  DEFAULT NULL COMMENT '裁决理由(协商结论/驳回原因)',
  remark                varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time           datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time           datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted            tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_approval_no (no),
  KEY idx_approval_status (status),
  KEY idx_approval_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批:投放审批(本金回报≥目标&300万内自主)(M5-02)';

-- ---------------------------------------------------------------------
-- 3. 花名册/角色权限扩展(user_role_ext) —— 板块内数据权限 · 字段级保密 · 项目归属
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_user_role_ext (
  id            bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id       bigint        NOT NULL COMMENT '用户主键(占位期 X-User-Name 映射·切 SSO 后门户下发)',
  user_name     varchar(64)   NOT NULL COMMENT '显示名',
  role          varchar(32)   NOT NULL COMMENT '板块角色:老板/财务/供应链/业务/GP/LP',
  data_scope    varchar(128)  DEFAULT NULL COMMENT '数据权限口径描述(复用 DataScope:全部/供应商客户合同设备/我的客户公海跟进/凭证账分配报表/仅月度报表)',
  cost_visible  tinyint(1)    NOT NULL DEFAULT 1 COMMENT '是否可见成本/账期/授信等敏感字段(字段级保密:LP=0 仅月报不见成本)',
  owner_scoped  tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否仅见自己名下+公海(业务 BD=1 行级隔离)',
  project_id    bigint        DEFAULT NULL COMMENT '项目归属(多项目独立核算·空=全部项目)',
  active        tinyint(1)    NOT NULL DEFAULT 1 COMMENT '在职:0离职 1在职',
  remark        varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted    tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_ext_user (user_id),
  KEY idx_role_ext_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='花名册/角色权限扩展:板块内数据权限+字段级保密(M5-03)';

-- ---------------------------------------------------------------------
-- 4. 提成(commission) —— 供应链降本贡献 / 业务成交贡献 · 从管理费列支
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_commission (
  id                bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  period            varchar(7)    NOT NULL COMMENT '归属期(yyyy-MM)',
  user_id           bigint        NOT NULL COMMENT '受益人主键',
  user_name         varchar(64)   NOT NULL COMMENT '受益人显示名',
  role              varchar(32)   DEFAULT NULL COMMENT '受益人角色:供应链/业务',
  type              varchar(16)   NOT NULL COMMENT '提成类型:集采降本/成交贡献',
  base_amount       decimal(18,2) NOT NULL DEFAULT 0 COMMENT '计提基数(集采降本额=市场价-集采价 / 成交额)',
  rate              decimal(18,8) NOT NULL DEFAULT 0 COMMENT '提成比例(rule commission_rate)',
  commission_amount decimal(18,2) NOT NULL DEFAULT 0 COMMENT '提成金额 = base_amount × rate',
  funded_from       varchar(16)   NOT NULL DEFAULT '管理费' COMMENT '列支科目(从管理费列支·非成本)',
  source_ref        varchar(64)   DEFAULT NULL COMMENT '来源引用(purchase:{id}/contract:{id}·可溯源+幂等)',
  order_count       int           NOT NULL DEFAULT 0 COMMENT '贡献单数(降本单数/成交单数)',
  remark            varchar(255)  DEFAULT NULL COMMENT '备注',
  create_time       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_commission (period, user_id, type, source_ref),
  KEY idx_commission_period (period),
  KEY idx_commission_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提成:降本贡献/成交贡献·从管理费列支(M5-03)';

-- ---------------------------------------------------------------------
-- 5. rule_config 追加:投放自主额度 / 提成比例(禁硬编码 §4.24)
-- ---------------------------------------------------------------------
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('approval_self_limit', '',       3000000.00, 'money', 1, '2023-01-01', NULL, '投放审批自主额度上限 300 万(超额转合伙人协商·业务流§6.3)'),
 ('commission_rate',     '集采降本', 0.05000000, 'rate',  1, '2023-01-01', NULL, '供应链集采降本提成 5%(从管理费列支·降本 5.7万→2850)'),
 ('commission_rate',     '成交贡献', 0.01000000, 'rate',  1, '2023-01-01', NULL, '业务成交贡献提成 1%(按合同成交额·从管理费列支)');

-- ---------------------------------------------------------------------
-- 6. 花名册种子(与 UserContextFilter 占位主键对齐:老板1001/刘总1002/小洪1003/李工1004/财务1005/供应链1006/业务1007)
--    LP(刘总)cost_visible=0 仅月报不见成本;业务(王业务)owner_scoped=1 仅见名下+公海
-- ---------------------------------------------------------------------
INSERT INTO yc_rent_user_role_ext (user_id, user_name, role, data_scope, cost_visible, owner_scoped, project_id, active, remark) VALUES
 (1003, '小洪',   '老板', '全部(运营执行·GP股东)',        1, 0, NULL, 1, '运营执行/GP·数智云仓股东·审批 300 万内自主'),
 (1004, '李工',   '供应链', '供应商/客户/合同/设备',        1, 0, NULL, 1, '供应链管理·集采比价降本·BOM'),
 (1007, '王业务', '业务', '我的客户/公海/跟进',            1, 1, NULL, 1, '业务(BD)·成交前获客跟进·行级隔离(9.5)'),
 (1005, '财务',   '财务', '凭证/账/分配/报表',            1, 0, NULL, 1, '财务·收款核销/凭证/分配/月报'),
 (1002, '刘总',   'LP',   '仅月度报表(不见上下游价)',       0, 0, NULL, 1, 'LP 出资人(不操作系统)·字段级保密:成本价/账期不可见');
