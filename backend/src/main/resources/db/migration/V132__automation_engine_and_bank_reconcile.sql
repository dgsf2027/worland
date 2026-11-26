-- =========================================================================
-- V132: 租赁公司系统自动化引擎（一期：统一推送/4类扫描/总控台 + 二期：银行流水智能核销/事件级联/自愈巡检）
-- =========================================================================

CREATE TABLE IF NOT EXISTS yc_rent_automation_rule (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_key          VARCHAR(64)   NOT NULL COMMENT '唯一规则键',
    rule_name         VARCHAR(128)  NOT NULL COMMENT '规则名称',
    category          VARCHAR(32)   NOT NULL DEFAULT '定时扫描' COMMENT '分类:推送简报/定时扫描/事件联动/自愈巡检',
    schedule_desc     VARCHAR(64)   NULL COMMENT '执行频次说明',
    enabled           TINYINT       NOT NULL DEFAULT 1 COMMENT '是否启用(1=开/0=关)',
    lead_days         INT           NULL COMMENT '提前预警天数(如 T-7/T-60)',
    threshold_amount  DECIMAL(18,2) NULL COMMENT '金额阈值(如资金安全水位/1元留购上限)',
    target_role       VARCHAR(32)   NULL DEFAULT '老板' COMMENT '默认触达角色:老板/财务/业务/全员',
    description       VARCHAR(255)  NULL COMMENT '规则说明',
    last_run_at       DATETIME      NULL COMMENT '最近执行时间',
    last_status       VARCHAR(16)   NULL COMMENT '最近状态:SUCCESS/SKIPPED/FAILED',
    last_summary      VARCHAR(512)  NULL COMMENT '最近执行摘要',
    create_time       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted        TINYINT       NOT NULL DEFAULT 0,
    UNIQUE KEY uk_auto_rule_key (rule_key)
) COMMENT='系统自动化规则配置与运行状态(V132)';

CREATE TABLE IF NOT EXISTS yc_rent_notify_channel (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(64)  NOT NULL COMMENT '通道名称(如 老板企微群/财务钉钉群)',
    channel_type  VARCHAR(24)  NOT NULL DEFAULT 'wecom' COMMENT 'wecom/dingtalk/feishu/generic',
    webhook_url   VARCHAR(512) NOT NULL COMMENT 'Webhook 地址',
    secret        VARCHAR(256) NULL COMMENT '加签密钥(钉钉/飞书可选)',
    target_role   VARCHAR(32)  NOT NULL DEFAULT '老板' COMMENT '接收角色:老板/财务/业务/全员',
    enabled       TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    last_sent_at  DATETIME     NULL COMMENT '最近推送时间',
    last_error    VARCHAR(512) NULL COMMENT '最近错误信息',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted    TINYINT      NOT NULL DEFAULT 0
) COMMENT='自动化外推消息通道(企微/钉钉/飞书/通用Webhook · V132)';

CREATE TABLE IF NOT EXISTS yc_rent_automation_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_key        VARCHAR(64)  NOT NULL,
    rule_name       VARCHAR(128) NOT NULL,
    trigger_source  VARCHAR(16)  NOT NULL DEFAULT 'CRON' COMMENT 'CRON/MANUAL/EVENT',
    status          VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/SKIPPED/FAILED',
    affected_count  INT          NOT NULL DEFAULT 0 COMMENT '处理/命中条数',
    pushed_count    INT          NOT NULL DEFAULT 0 COMMENT '外推成功通道数',
    summary         VARCHAR(512) NULL COMMENT '简要摘要',
    detail_text     TEXT         NULL COMMENT '详细报文/推送内容预览',
    duration_ms     BIGINT       NOT NULL DEFAULT 0,
    executed_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_auto_log_key_time (rule_key, executed_at)
) COMMENT='系统自动化执行日志(V132)';

CREATE TABLE IF NOT EXISTS yc_rent_bank_statement (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no           VARCHAR(64)   NOT NULL COMMENT '导入批次号',
    tx_date            DATE          NOT NULL COMMENT '交易日期',
    counterparty_name  VARCHAR(128)  NULL COMMENT '对方户名',
    amount             DECIMAL(18,2) NOT NULL COMMENT '交易金额(正数)',
    direction          VARCHAR(8)    NOT NULL DEFAULT 'IN' COMMENT 'IN=收款 / OUT=付款',
    bank_serial        VARCHAR(128)  NULL COMMENT '银行流水号(去重键)',
    memo               VARCHAR(255)  NULL COMMENT '银行摘要/备注',
    match_status       VARCHAR(16)   NOT NULL DEFAULT '未匹配' COMMENT '已匹配/待确认/未匹配/已核销/已忽略',
    match_type         VARCHAR(32)   NULL COMMENT 'RENT_BILL/PURCHASE_TERM/NONE',
    matched_biz_id     BIGINT        NULL COMMENT '匹配的收租单ID或采购节点ID',
    matched_biz_no     VARCHAR(64)   NULL COMMENT '匹配的单据号/合同号',
    matched_customer   VARCHAR(128)  NULL COMMENT '匹配客户或供应商名',
    confidence         INT           NOT NULL DEFAULT 0 COMMENT '匹配置信度 0-100',
    match_reason       VARCHAR(255)  NULL COMMENT '匹配依据说明',
    reconciled_at      DATETIME      NULL COMMENT '核销时间',
    operator_name      VARCHAR(64)   NULL COMMENT '核销操作人',
    create_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted         TINYINT       NOT NULL DEFAULT 0,
    INDEX idx_bank_stmt_status (match_status, tx_date),
    INDEX idx_bank_stmt_serial (bank_serial)
) COMMENT='银行流水自动匹配与一键核销台账(V132)';

-- 预置 10 条核心自动化规则（一期 + 二期）
INSERT IGNORE INTO yc_rent_automation_rule
    (rule_key, rule_name, category, schedule_desc, enabled, lead_days, threshold_amount, target_role, description)
VALUES
    ('boss_morning_brief', '老板每日经营晨报', '推送简报', '每日 08:30', 1, 7, 50000.00, '老板',
     '汇总昨日实收、当前逾期、未来7天采购应付、超期未开3%票及6个月最低资金水位，推送至老板通道'),
    ('finance_daily_todo', '财务/业务每日待办清单', '推送简报', '每日 09:00', 1, 7, NULL, '财务',
     '汇总今日应收租金、待核销银行流水、待开3%销项票、近7日应付节点及临期合同，推送至财务/业务通道'),
    ('invoice_overdue_scan', '收租后3%销项票超期/临期扫描', '定时扫描', '每日 08:00', 1, 2, NULL, '财务',
     '扫描已收款但尚未开3%销项专票的账单，T-2天提醒、超10个工作日红灯派单并外推'),
    ('payable_upcoming_scan', '采购月结应付节点到期预警', '定时扫描', '每日 08:15', 1, 7, NULL, '财务',
     '扫描采购合同未付月结节点(40/30/30)，到期前7天内自动派单并推送提醒，防止供应商断供或违约'),
    ('warranty_expiry_scan', '货架/设备5年质保到期扫描', '定时扫描', '每日 08:20', 1, 60, NULL, '业务',
     '扫描距质保到期60天内的项目与工程套装资产，提前安排质保终检与客户续保沟通'),
    ('cash_waterline_alert', '银行资金水位安全垫预警', '定时扫描', '每日 08:25', 1, 180, 50000.00, '老板',
     '基于当前银行余额与未来6个月真实收付计划，若预测最低水位低于安全阈值自动红灯告警'),
    ('overdue_escalation_scan', '逾期阶梯自动升级与催收话术', '定时扫描', '每日 09:30', 1, 3, NULL, '财务',
     'T-3预提醒、T+1温和催收、T+7自动算滞纳金升级罚息、T+15锁机/收回预警并生成标准催款通知函'),
    ('auto_nominal_buyout', '期末1元留购自动过户结项', '事件联动', '每日 10:00 + 收款触发', 1, 0, 100.00, '财务',
     '当合同全部期次租金结清且约定留购价≤100元时，自动走免审过户并推进项目至「留购结项」'),
    ('data_self_heal_scan', '数据自愈与异常巡检', '自愈巡检', '每日 06:30', 1, 0, 3.00, '老板',
     '自动清理已结清单据遗留的幽灵待办，并巡检未绑主合同的采购合同与过磅偏差超±3%项目'),
    ('event_lifecycle_cascade', '业务事件全自动级联总开关', '事件联动', '实时事件触发', 1, 0, NULL, '全员',
     '开启后：签约自动建项目、三方验收自动推工程套装为「在租」、银行流水核销自动记凭证并启开票倒计时');
