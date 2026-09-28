-- =====================================================================
-- 合同付款方式(V118):付款条件从「设备」搬到「合同」,并与设备租赁台账打通
--
-- 口径变化(用户确认的路线):
--   1) 合同是付款方式的唯一真相源。一份设备租赁合同定义一套多段付款方式,
--      该合同下的台账设备与采购单全部继承,不再逐台维护。
--      设备的预计付款金额 = 该设备合同价 × 段比例(算出来不存)。
--   2) 任一付款条件挂上即计入累计应付与未付 —— 采购下单时一次生成全部阶段的应付,
--      不再等「入库」触发。未触发阶段的到期日按采购单的「预计入库日」推算,
--      并标 due_provisional=1;实际入库时改写为真实入库日 + 账期并清标记。
--
-- yc_rent_asset_payment_term 停止写入,保留读取(只为映射不到合同段的历史应付服务)。
--
-- 本迁移做四件事:
--   A. 建表 yc_rent_contract_payment_term + 加 purchase_in.expect_receive_date
--      + 加 payable.due_provisional + 新增 rule_config[purchase_lead_days]
--   B. 从每份合同名下设备现有的付款条件反推出合同付款方式
--   C. payable.term_id 按阶段名重映射到新的合同段
--   D. 为已有采购单补齐未触发阶段的应付(带统一备注便于财务识别)
--
-- ⚠️ D 会让累计应付与未付变大 —— 这是修正原先的低估(未触发阶段被漏掉),
--    补出来的行统一带备注「合同付款方式补齐」。
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- A. 结构
-- ---------------------------------------------------------------------
CREATE TABLE yc_rent_contract_payment_term (
  id            bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  contract_id   bigint        NOT NULL COMMENT '合同(yc_rent_contract.id)',
  seq           int           NOT NULL COMMENT '段序号(1 起)',
  stage_name    varchar(16)   NOT NULL COMMENT '阶段名:首付/验收/尾款…(同合同内不重复,「退款红字」为系统保留)',
  ratio         decimal(18,8) NOT NULL COMMENT '付款比例(0-1),同合同各段合计=1',
  trigger_point varchar(8)    NOT NULL COMMENT '触发时点:下单/入库',
  due_days      int           NOT NULL DEFAULT 0 COMMENT '到期天数(触发日 + N 天)',
  create_time   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  is_deleted    tinyint(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除:0否 1是',
  PRIMARY KEY (id),
  KEY idx_cpt_contract (contract_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合同付款方式:唯一真相源,设备与采购单继承(M1-13/V118)';

ALTER TABLE yc_rent_purchase_in
  ADD COLUMN expect_receive_date date DEFAULT NULL
    COMMENT '预计入库日(未入库时按此推算「入库」阶段应付的到期日)' AFTER order_date;

ALTER TABLE yc_rent_payable
  ADD COLUMN due_provisional tinyint(1) NOT NULL DEFAULT 0
    COMMENT '到期日是否为预估(按预计入库日推算;实际入库后改写真实日期并置 0)' AFTER due_date;

INSERT INTO yc_rent_rule_config
 (rule_key, scope_key, rule_value, value_type, version, effective_from, effective_to, remark) VALUES
 ('purchase_lead_days', '', 30, 'months', 1, '2023-01-01', NULL,
  '采购提前期(单位:天)。下单时未填预计入库日则取 下单日 + 本值,用于推算「入库」阶段应付的到期日');

-- ---------------------------------------------------------------------
-- B. 反推合同付款方式
--    同一合同下各设备的条件可能不一致 → 取「覆盖设备数最多」的那一套,
--    并在合同备注里留痕待人工复核。一台设备都没有条件的合同走默认三段。
-- ---------------------------------------------------------------------

-- B1. 每台设备的条件签名(按 seq 拼串;同签名视为同一套条件)
CREATE TEMPORARY TABLE tmp_asset_term_sig (
  asset_id    bigint       NOT NULL PRIMARY KEY,
  contract_id bigint       NOT NULL,
  sig         varchar(500) NOT NULL
);

INSERT INTO tmp_asset_term_sig (asset_id, contract_id, sig)
SELECT t.asset_id, a.contract_id,
       GROUP_CONCAT(CONCAT(t.stage_name, '|', t.ratio, '|', t.trigger_point, '|', t.due_days)
                    ORDER BY t.seq, t.id SEPARATOR ';')
FROM yc_rent_asset_payment_term t
JOIN yc_rent_asset a ON a.id = t.asset_id AND a.is_deleted = 0
WHERE t.is_deleted = 0 AND a.contract_id IS NOT NULL
GROUP BY t.asset_id, a.contract_id;

-- B2. 每份合同里覆盖设备最多的那个签名(并列时取签名字典序最小的,保证可重复执行)
CREATE TEMPORARY TABLE tmp_contract_sig (
  contract_id bigint       NOT NULL PRIMARY KEY,
  sig         varchar(500) NOT NULL,
  asset_cnt   int          NOT NULL,
  sig_kinds   int          NOT NULL
);

INSERT INTO tmp_contract_sig (contract_id, sig, asset_cnt, sig_kinds)
SELECT contract_id, sig, asset_cnt, sig_kinds FROM (
  SELECT s.contract_id, s.sig, s.asset_cnt,
         COUNT(*) OVER (PARTITION BY s.contract_id) AS sig_kinds,
         ROW_NUMBER() OVER (PARTITION BY s.contract_id ORDER BY s.asset_cnt DESC, s.sig ASC) AS rn
  FROM (SELECT contract_id, sig, COUNT(*) AS asset_cnt
        FROM tmp_asset_term_sig GROUP BY contract_id, sig) s
) x WHERE x.rn = 1;

-- B3. 代表设备:签名命中的设备里 id 最小的那台,拿它的条件行直接搬到合同上
CREATE TEMPORARY TABLE tmp_contract_rep (
  contract_id bigint NOT NULL PRIMARY KEY,
  asset_id    bigint NOT NULL
);

INSERT INTO tmp_contract_rep (contract_id, asset_id)
SELECT c.contract_id, MIN(s.asset_id)
FROM tmp_contract_sig c
JOIN tmp_asset_term_sig s ON s.contract_id = c.contract_id AND s.sig = c.sig
GROUP BY c.contract_id;

INSERT INTO yc_rent_contract_payment_term
 (contract_id, seq, stage_name, ratio, trigger_point, due_days)
SELECT r.contract_id,
       ROW_NUMBER() OVER (PARTITION BY r.contract_id ORDER BY t.seq, t.id),
       t.stage_name, t.ratio, t.trigger_point, t.due_days
FROM tmp_contract_rep r
JOIN yc_rent_asset_payment_term t ON t.asset_id = r.asset_id AND t.is_deleted = 0;

-- B4. 条件不一致的合同:备注留痕待复核
UPDATE yc_rent_contract c
JOIN tmp_contract_sig s ON s.contract_id = c.id
SET c.remark = CONCAT(IFNULL(c.remark, ''),
      CASE WHEN IFNULL(c.remark, '') = '' THEN '' ELSE ' | ' END,
      'V118 付款方式按覆盖设备最多的一套迁移(该合同下原有 ', s.sig_kinds, ' 种不同条件),待人工复核')
WHERE c.is_deleted = 0 AND s.sig_kinds > 1;

-- B5. 存续合同但一台设备都没有条件的 → 默认三段(比例取 rule_config[payable_stage_ratio])
CREATE TEMPORARY TABLE tmp_default_contract (id bigint NOT NULL PRIMARY KEY);

INSERT INTO tmp_default_contract (id)
SELECT c.id FROM yc_rent_contract c
WHERE c.is_deleted = 0
  AND NOT EXISTS (SELECT 1 FROM yc_rent_contract_payment_term p
                  WHERE p.contract_id = c.id AND p.is_deleted = 0);

INSERT INTO yc_rent_contract_payment_term
 (contract_id, seq, stage_name, ratio, trigger_point, due_days)
SELECT d.id, 1, '首付',
       IFNULL((SELECT rule_value FROM yc_rent_rule_config
               WHERE rule_key = 'payable_stage_ratio' AND scope_key = '首付'
                 AND is_deleted = 0 ORDER BY version DESC LIMIT 1), 0.3),
       '下单', 0
FROM tmp_default_contract d;

INSERT INTO yc_rent_contract_payment_term
 (contract_id, seq, stage_name, ratio, trigger_point, due_days)
SELECT d.id, 2, '验收',
       IFNULL((SELECT rule_value FROM yc_rent_rule_config
               WHERE rule_key = 'payable_stage_ratio' AND scope_key = '验收'
                 AND is_deleted = 0 ORDER BY version DESC LIMIT 1), 0.6),
       '入库', 0
FROM tmp_default_contract d;

INSERT INTO yc_rent_contract_payment_term
 (contract_id, seq, stage_name, ratio, trigger_point, due_days)
SELECT d.id, 3, '尾款',
       1 - IFNULL((SELECT rule_value FROM yc_rent_rule_config
                   WHERE rule_key = 'payable_stage_ratio' AND scope_key = '首付'
                     AND is_deleted = 0 ORDER BY version DESC LIMIT 1), 0.3)
         - IFNULL((SELECT rule_value FROM yc_rent_rule_config
                   WHERE rule_key = 'payable_stage_ratio' AND scope_key = '验收'
                     AND is_deleted = 0 ORDER BY version DESC LIMIT 1), 0.6),
       '入库',
       IFNULL((SELECT rule_value FROM yc_rent_rule_config
               WHERE rule_key = 'payable_tail_days' AND scope_key = ''
                 AND is_deleted = 0 ORDER BY version DESC LIMIT 1), 90)
FROM tmp_default_contract d;

-- 比例为 0 或负的尾款段删掉(首付+验收已经占满 100%)
DELETE FROM yc_rent_contract_payment_term WHERE stage_name = '尾款' AND ratio <= 0;

-- ---------------------------------------------------------------------
-- C. payable.term_id 重映射:按「合同 + 阶段名」对上新的合同段
--    映射不上的(改过阶段名的历史数据)保留原值并在备注里标注
-- ---------------------------------------------------------------------
UPDATE yc_rent_payable p
JOIN yc_rent_purchase_in pi ON pi.id = p.purchase_in_id
JOIN yc_rent_contract_payment_term cpt
     ON cpt.contract_id = pi.contract_id AND cpt.stage_name = p.stage AND cpt.is_deleted = 0
SET p.term_id = cpt.id
WHERE p.is_deleted = 0 AND p.stage <> '退款红字';

UPDATE yc_rent_payable p
JOIN yc_rent_purchase_in pi ON pi.id = p.purchase_in_id
SET p.remark = CONCAT(IFNULL(p.remark, ''),
      CASE WHEN IFNULL(p.remark, '') = '' THEN '' ELSE ' | ' END, '(历史条件)')
WHERE p.is_deleted = 0 AND p.stage <> '退款红字'
  AND NOT EXISTS (SELECT 1 FROM yc_rent_contract_payment_term cpt
                  WHERE cpt.contract_id = pi.contract_id AND cpt.stage_name = p.stage
                    AND cpt.is_deleted = 0);

-- ---------------------------------------------------------------------
-- D. 预计入库日回填 + 补齐未触发阶段的应付
-- ---------------------------------------------------------------------
UPDATE yc_rent_purchase_in
SET expect_receive_date = CASE
      WHEN receive_date IS NOT NULL THEN receive_date
      WHEN order_date   IS NOT NULL THEN DATE_ADD(order_date, INTERVAL 30 DAY)
      ELSE NULL END
WHERE is_deleted = 0 AND expect_receive_date IS NULL;

-- 已生成应付的行原样保留;缺的阶段按 (采购明细 × 合同段) 补。
-- 只补:采购单未红冲、明细挂了设备、设备有合同价、该(设备,阶段)还没有非红冲应付。
INSERT INTO yc_rent_payable
 (purchase_in_id, asset_id, purchase_item_id, term_id, stage, due_date, due_provisional,
  amount, status, remark)
SELECT pi.id, a.id, it.id, cpt.id, cpt.stage_name,
       CASE WHEN cpt.trigger_point = '下单'
                 THEN DATE_ADD(IFNULL(pi.order_date, CURDATE()), INTERVAL cpt.due_days DAY)
            WHEN pi.receive_date IS NOT NULL
                 THEN DATE_ADD(pi.receive_date, INTERVAL cpt.due_days DAY)
            ELSE DATE_ADD(IFNULL(pi.expect_receive_date, IFNULL(pi.order_date, CURDATE())),
                          INTERVAL cpt.due_days DAY) END,
       CASE WHEN cpt.trigger_point = '入库' AND pi.receive_date IS NULL THEN 1 ELSE 0 END,
       ROUND(a.purchase_price * cpt.ratio, 2),
       '待付',
       '合同付款方式补齐'
FROM yc_rent_purchase_in pi
JOIN yc_rent_purchase_item it ON it.purchase_in_id = pi.id AND it.is_deleted = 0
JOIN yc_rent_asset a ON a.id = it.asset_id AND a.is_deleted = 0
JOIN yc_rent_contract_payment_term cpt ON cpt.contract_id = pi.contract_id AND cpt.is_deleted = 0
WHERE pi.is_deleted = 0
  AND pi.status <> '已红冲'
  AND a.purchase_price IS NOT NULL
  AND NOT EXISTS (
        SELECT 1 FROM yc_rent_payable ex
        WHERE ex.is_deleted = 0 AND ex.asset_id = a.id
          AND ex.stage = cpt.stage_name AND ex.status <> '红冲');

DROP TEMPORARY TABLE tmp_default_contract;
DROP TEMPORARY TABLE tmp_contract_rep;
DROP TEMPORARY TABLE tmp_contract_sig;
DROP TEMPORARY TABLE tmp_asset_term_sig;
