-- =====================================================================
-- 沃朗科技租赁板块 · M3 Wave C 月度报表包 + AI 网关 + 提醒 Schema (V11)
-- MySQL 8 · utf8mb4 · InnoDB · 库 worland_dev · 表前缀 yc_rent_
-- 依据: DESIGN_DOC §4.3(monthly_report:period/package_json 六件套/analysis_json 七节/calendar_status)
--       + §十一(AI 只起草综述·七节数字必引 package_json 不由 AI 重算·LLM context 喂入前脱敏)
--       + 系统方案 流程11(财务日历:1日待人工/2日报表包自动/3日核对/5日过报告+分配)
--       + §15 反向核验 M3-10(定时任务集:月报2日自动/合同到期30天提醒/潜客跟进到期提醒·各 cron 双 export)
-- 口径: 金额=元 · 手写 migration(§4.16,Flyway boot 自动 apply) · 只读聚合不重算(§4.24 单一真相源)
-- 单一真相源(§4.24):
--   * monthly_report = 一 period 一条快照;package_json=六件套聚合(收租台账/利润表/现金流水/往来/资产快照/分配表)
--     analysis_json=七节经营分析(每节数字由规则引擎从 package_json 填·带溯源;综述叙述由 LLM mock 起草不回写数字)
--   * llm_call_log = AI 透明四件套(推理过程/完整输出/置信分/原始数据·脱敏后)统一落库;LLM 永不算数
--   * reminder = 到期提醒落库(合同到期30天/潜客跟进到期);cron 幂等 upsert(type+ref_id+due_date 唯一)
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. AI 调用记录(透明四件套统一落库)—— 照园区小卖账房 yc_vend_llm_call_log
--    铁律#7:LLM 永不直接算数,只起草综述;每个 AI 结论旁挂 🔬 过程入口指向本表。
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_llm_call_log (
  id                  bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  scene               varchar(64)   NOT NULL COMMENT '接入点场景(月报起草/…)',
  model               varchar(64)   DEFAULT NULL COMMENT '模型名(可配不写死;mock 断路=mock(路由目标))',
  prompt_fingerprint  varchar(64)   DEFAULT NULL COMMENT 'Prompt 模板指纹(版本追踪)',
  idempotent_key      varchar(191)  DEFAULT NULL COMMENT '幂等键(接入点+业务key+当日·24h 窗口)',
  cache_hit           tinyint(1)    NOT NULL DEFAULT 0 COMMENT '是否缓存命中',
  input_digest        longtext      COMMENT '四件套·原始数据(脱敏后 JSON:剔成本/分配/身份)',
  reasoning           longtext      COMMENT '四件套·推理过程(规则引擎计算说明)',
  output_text         longtext      COMMENT '四件套·完整输出(AI 综述全文)',
  confidence          decimal(5,2)  DEFAULT NULL COMMENT '四件套·置信分(规则真算=数据完备度)',
  confidence_source   varchar(16)   DEFAULT NULL COMMENT '置信分来源:computed 真算 / fixed 固定基准',
  tokens_in           int           DEFAULT NULL COMMENT '输入 token(mock=0)',
  tokens_out          int           DEFAULT NULL COMMENT '输出 token(mock=0)',
  duration_ms         int           DEFAULT NULL COMMENT '耗时毫秒',
  call_status         varchar(8)    NOT NULL DEFAULT '成功' COMMENT '状态:成功/失败/降级',
  create_time         datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted          tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_llm_idem (idempotent_key),
  KEY idx_llm_scene (scene)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 调用记录:透明四件套统一落库(M3-07)';

-- ---------------------------------------------------------------------
-- 2. 月度报表快照 —— 一 period 一条;六件套 + 七节 + 财务日历状态
--    package_json / analysis_json 存 JSON 快照(2日 cron 自动生成 或 按需实时聚合回填)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_monthly_report (
  id                bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  period            varchar(7)    NOT NULL COMMENT '账期 YYYY-MM',
  package_json      longtext      COMMENT '六件套聚合快照 JSON(收租台账/利润表/现金流水/往来/资产快照/分配表)',
  analysis_json     longtext      COMMENT '七节经营分析快照 JSON(数字引 package_json·带溯源)',
  calendar_status   varchar(16)   DEFAULT NULL COMMENT '财务日历状态:PENDING/PACKAGE_READY/REVIEWED/REPORTED',
  llm_call_id       bigint        DEFAULT NULL COMMENT '综述 AI 调用记录 id(透明四件套锚点)',
  generated_by      varchar(16)   DEFAULT NULL COMMENT '生成方式:cron 2日自动 / manual 人工触发',
  generated_at      datetime      DEFAULT NULL COMMENT '快照生成时间',
  create_time       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted        tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_monthly_period (period, is_deleted),
  KEY idx_monthly_period (period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='月度报表快照:六件套+七节+财务日历(M3-07)';

-- ---------------------------------------------------------------------
-- 3. 到期提醒 —— cron 扫描落库(合同到期前30天转让提醒 / 潜客下次跟进到期提醒)
--    幂等:同 type+ref_id+due_date 只留一条(cron 重复跑不重复插)
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_reminder (
  id           bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  type         varchar(24)   NOT NULL COMMENT '提醒类型:contract_expiry 合同到期 / followup_due 跟进到期',
  ref_id       bigint        NOT NULL COMMENT '关联单据 id(合同id / 客户id)',
  ref_no       varchar(64)   DEFAULT NULL COMMENT '关联单据编号(合同号/客户名)',
  title        varchar(255)  NOT NULL COMMENT '提醒标题(人话)',
  due_date     date          DEFAULT NULL COMMENT '触发基准日(到期日/下次跟进日)',
  days_left    int           DEFAULT NULL COMMENT '距触发天数(负=已逾期)',
  owner_role   varchar(16)   DEFAULT NULL COMMENT '责任角色:业务/供应链/财务',
  status       varchar(16)   NOT NULL DEFAULT 'OPEN' COMMENT '状态:OPEN 待处理 / DONE 已处理',
  create_time  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted   tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  UNIQUE KEY uk_reminder (type, ref_id, due_date),
  KEY idx_reminder_status (status),
  KEY idx_reminder_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='到期提醒:合同到期30天/潜客跟进到期(M3-10)';

-- 4. rule_config:AI 月报模型(可换模型·不写死·字符串存 rule_json)+ 到期提醒提前天数
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, rule_json, value_type, version, effective_from, effective_to, remark) VALUES
 ('ai_model_monthly',            '', NULL, 'kimi-k2', 'string', 1, '2023-01-01', NULL, '月报起草默认模型(设置中心可改·长上下文喂整月数据·§12.2)'),
 ('reminder_contract_expiry_days', '', 30, NULL,     'days',   1, '2023-01-01', NULL, '合同到期前 N 天生成转让提醒(M3-10)'),
 ('reminder_followup_lead_days',   '', 0,  NULL,     'days',   1, '2023-01-01', NULL, '潜客下次跟进日提前 N 天提醒(0=当日到期,M3-10)');
