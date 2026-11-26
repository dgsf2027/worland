-- V130: 工作台银行可用余额配置（资金水位线预测）+ 清理历史残留的孤儿系统任务（消除假红灯）

-- 1. 银行可用余额规则项（老板/财务可在经营驾驶舱随时更新，驱动未来 6/12 个月资金水位线预测）
INSERT INTO yc_rent_rule_config (rule_key, scope_key, rule_value, value_type, version, effective_from, remark)
SELECT 'bank_cash_balance', '', 0.00, 'money', 1, '2025-01-01', '当前银行可用余额(元·用于经营驾驶舱资金水位线与缺口预警)'
WHERE NOT EXISTS (
    SELECT 1 FROM yc_rent_rule_config WHERE rule_key = 'bank_cash_balance' AND scope_key = '' AND is_deleted = 0
);

-- 2. 清理历史测试残留的孤儿系统任务（来源逾期案/合同/收租单已删除、已关闭、已作废或已结清的旧系统任务）
UPDATE yc_rent_task t
SET t.status = '已作废',
    t.remark = CONCAT(COALESCE(t.remark, ''), ' [V130清理:来源逾期案已关闭或不存在]')
WHERE t.is_deleted = 0
  AND t.source = '系统'
  AND t.status NOT IN ('已完成', '已作废')
  AND (t.biz_type = 'overdue_case' OR t.type = '催收')
  AND (
      t.biz_id IS NULL
      OR NOT EXISTS (
          SELECT 1
          FROM yc_rent_overdue_case oc
          JOIN yc_rent_rent_bill rb ON rb.id = oc.rent_bill_id AND rb.is_deleted = 0
          JOIN yc_rent_contract c ON c.id = rb.contract_id AND c.is_deleted = 0 AND c.status <> '已作废'
          WHERE oc.id = t.biz_id
            AND oc.is_deleted = 0
            AND oc.status = '开启'
            AND rb.status IN ('逾期', '待收', '部分已收')
      )
  );

UPDATE yc_rent_task t
SET t.status = '已作废',
    t.remark = CONCAT(COALESCE(t.remark, ''), ' [V130清理:来源合同已关闭/作废或不存在]')
WHERE t.is_deleted = 0
  AND t.source = '系统'
  AND t.status NOT IN ('已完成', '已作废')
  AND t.biz_type = 'contract'
  AND (
      t.biz_id IS NULL
      OR NOT EXISTS (
          SELECT 1
          FROM yc_rent_contract c
          WHERE c.id = t.biz_id
            AND c.is_deleted = 0
            AND c.status = '生效'
      )
  );
